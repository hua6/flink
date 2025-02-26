/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.api.internal;

import org.apache.flink.annotation.Internal;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.JobExecutionResult;
import org.apache.flink.api.dag.Pipeline;
import org.apache.flink.api.dag.Transformation;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.execution.JobClient;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.ExplainDetail;
import org.apache.flink.table.api.ResultKind;
import org.apache.flink.table.api.Schema;
import org.apache.flink.table.api.SqlParserException;
import org.apache.flink.table.api.StatementSet;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.TableConfig;
import org.apache.flink.table.api.TableDescriptor;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.TableException;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.api.TableSchema;
import org.apache.flink.table.api.ValidationException;
import org.apache.flink.table.catalog.Catalog;
import org.apache.flink.table.catalog.CatalogBaseTable;
import org.apache.flink.table.catalog.CatalogFunction;
import org.apache.flink.table.catalog.CatalogManager;
import org.apache.flink.table.catalog.CatalogPartition;
import org.apache.flink.table.catalog.CatalogPartitionSpec;
import org.apache.flink.table.catalog.CatalogTable;
import org.apache.flink.table.catalog.CatalogTableImpl;
import org.apache.flink.table.catalog.Column;
import org.apache.flink.table.catalog.ConnectorCatalogTable;
import org.apache.flink.table.catalog.FunctionCatalog;
import org.apache.flink.table.catalog.GenericInMemoryCatalog;
import org.apache.flink.table.catalog.ObjectIdentifier;
import org.apache.flink.table.catalog.ObjectPath;
import org.apache.flink.table.catalog.QueryOperationCatalogView;
import org.apache.flink.table.catalog.ResolvedCatalogBaseTable;
import org.apache.flink.table.catalog.ResolvedCatalogTable;
import org.apache.flink.table.catalog.ResolvedSchema;
import org.apache.flink.table.catalog.UnresolvedIdentifier;
import org.apache.flink.table.catalog.WatermarkSpec;
import org.apache.flink.table.catalog.exceptions.CatalogException;
import org.apache.flink.table.catalog.exceptions.DatabaseAlreadyExistException;
import org.apache.flink.table.catalog.exceptions.DatabaseNotEmptyException;
import org.apache.flink.table.catalog.exceptions.DatabaseNotExistException;
import org.apache.flink.table.catalog.exceptions.FunctionAlreadyExistException;
import org.apache.flink.table.catalog.exceptions.FunctionNotExistException;
import org.apache.flink.table.catalog.exceptions.TableAlreadyExistException;
import org.apache.flink.table.catalog.exceptions.TableNotExistException;
import org.apache.flink.table.delegation.Executor;
import org.apache.flink.table.delegation.ExecutorFactory;
import org.apache.flink.table.delegation.Parser;
import org.apache.flink.table.delegation.Planner;
import org.apache.flink.table.delegation.PlannerFactory;
import org.apache.flink.table.expressions.ApiExpressionUtils;
import org.apache.flink.table.expressions.Expression;
import org.apache.flink.table.factories.ComponentFactoryService;
import org.apache.flink.table.factories.FactoryUtil;
import org.apache.flink.table.functions.ScalarFunction;
import org.apache.flink.table.functions.UserDefinedFunction;
import org.apache.flink.table.functions.UserDefinedFunctionHelper;
import org.apache.flink.table.module.Module;
import org.apache.flink.table.module.ModuleEntry;
import org.apache.flink.table.module.ModuleManager;
import org.apache.flink.table.operations.CatalogQueryOperation;
import org.apache.flink.table.operations.CatalogSinkModifyOperation;
import org.apache.flink.table.operations.CollectModifyOperation;
import org.apache.flink.table.operations.DescribeTableOperation;
import org.apache.flink.table.operations.ExplainOperation;
import org.apache.flink.table.operations.LoadModuleOperation;
import org.apache.flink.table.operations.ModifyOperation;
import org.apache.flink.table.operations.NopOperation;
import org.apache.flink.table.operations.Operation;
import org.apache.flink.table.operations.QueryOperation;
import org.apache.flink.table.operations.ShowCatalogsOperation;
import org.apache.flink.table.operations.ShowCreateTableOperation;
import org.apache.flink.table.operations.ShowCurrentCatalogOperation;
import org.apache.flink.table.operations.ShowCurrentDatabaseOperation;
import org.apache.flink.table.operations.ShowDatabasesOperation;
import org.apache.flink.table.operations.ShowFunctionsOperation;
import org.apache.flink.table.operations.ShowModulesOperation;
import org.apache.flink.table.operations.ShowPartitionsOperation;
import org.apache.flink.table.operations.ShowTablesOperation;
import org.apache.flink.table.operations.ShowViewsOperation;
import org.apache.flink.table.operations.TableSourceQueryOperation;
import org.apache.flink.table.operations.UnloadModuleOperation;
import org.apache.flink.table.operations.UseCatalogOperation;
import org.apache.flink.table.operations.UseDatabaseOperation;
import org.apache.flink.table.operations.UseModulesOperation;
import org.apache.flink.table.operations.ddl.AddPartitionsOperation;
import org.apache.flink.table.operations.ddl.AlterCatalogFunctionOperation;
import org.apache.flink.table.operations.ddl.AlterDatabaseOperation;
import org.apache.flink.table.operations.ddl.AlterPartitionPropertiesOperation;
import org.apache.flink.table.operations.ddl.AlterTableAddConstraintOperation;
import org.apache.flink.table.operations.ddl.AlterTableDropConstraintOperation;
import org.apache.flink.table.operations.ddl.AlterTableOperation;
import org.apache.flink.table.operations.ddl.AlterTableOptionsOperation;
import org.apache.flink.table.operations.ddl.AlterTableRenameOperation;
import org.apache.flink.table.operations.ddl.AlterTableSchemaOperation;
import org.apache.flink.table.operations.ddl.AlterViewAsOperation;
import org.apache.flink.table.operations.ddl.AlterViewOperation;
import org.apache.flink.table.operations.ddl.AlterViewPropertiesOperation;
import org.apache.flink.table.operations.ddl.AlterViewRenameOperation;
import org.apache.flink.table.operations.ddl.CreateCatalogFunctionOperation;
import org.apache.flink.table.operations.ddl.CreateCatalogOperation;
import org.apache.flink.table.operations.ddl.CreateDatabaseOperation;
import org.apache.flink.table.operations.ddl.CreateTableASOperation;
import org.apache.flink.table.operations.ddl.CreateTableOperation;
import org.apache.flink.table.operations.ddl.CreateTempSystemFunctionOperation;
import org.apache.flink.table.operations.ddl.CreateViewOperation;
import org.apache.flink.table.operations.ddl.DropCatalogFunctionOperation;
import org.apache.flink.table.operations.ddl.DropCatalogOperation;
import org.apache.flink.table.operations.ddl.DropDatabaseOperation;
import org.apache.flink.table.operations.ddl.DropPartitionsOperation;
import org.apache.flink.table.operations.ddl.DropTableOperation;
import org.apache.flink.table.operations.ddl.DropTempSystemFunctionOperation;
import org.apache.flink.table.operations.ddl.DropViewOperation;
import org.apache.flink.table.operations.utils.OperationTreeBuilder;
import org.apache.flink.table.sinks.TableSink;
import org.apache.flink.table.sources.TableSource;
import org.apache.flink.table.sources.TableSourceValidation;
import org.apache.flink.table.types.AbstractDataType;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.logical.LogicalType;
import org.apache.flink.table.utils.EncodingUtils;
import org.apache.flink.table.utils.PrintUtils;
import org.apache.flink.table.utils.TableSchemaUtils;
import org.apache.flink.types.Row;
import org.apache.flink.util.Preconditions;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.apache.flink.table.api.config.TableConfigOptions.TABLE_DML_SYNC;

/**
 * Implementation of {@link TableEnvironment} that works exclusively with Table API interfaces. Only
 * {@link TableSource} is supported as an input and {@link TableSink} as an output. It also does not
 * bind to any particular {@code StreamExecutionEnvironment}.
 */
@Internal
public class TableEnvironmentImpl implements TableEnvironmentInternal {
    // Flag that tells if the TableSource/TableSink used in this environment is stream table
    // source/sink,
    // and this should always be true. This avoids too many hard code.
    private static final boolean IS_STREAM_TABLE = true;
    private final CatalogManager catalogManager;
    private final ModuleManager moduleManager;
    private final OperationTreeBuilder operationTreeBuilder;
    private final List<ModifyOperation> bufferedModifyOperations = new ArrayList<>();

    protected final TableConfig tableConfig;
    protected final Executor execEnv;
    protected final FunctionCatalog functionCatalog;
    protected final Planner planner;
    private final boolean isStreamingMode;
    private final ClassLoader userClassLoader;
    private static final String UNSUPPORTED_QUERY_IN_SQL_UPDATE_MSG =
            "Unsupported SQL query! sqlUpdate() only accepts a single SQL statement of type "
                    + "INSERT, CREATE TABLE, DROP TABLE, ALTER TABLE, USE CATALOG, USE [CATALOG.]DATABASE, "
                    + "CREATE DATABASE, DROP DATABASE, ALTER DATABASE, CREATE FUNCTION, DROP FUNCTION, ALTER FUNCTION, "
                    + "CREATE CATALOG, DROP CATALOG, CREATE VIEW, DROP VIEW, LOAD MODULE, UNLOAD "
                    + "MODULE, USE MODULES.";
    private static final String UNSUPPORTED_QUERY_IN_EXECUTE_SQL_MSG =
            "Unsupported SQL query! executeSql() only accepts a single SQL statement of type "
                    + "CREATE TABLE, DROP TABLE, ALTER TABLE, CREATE DATABASE, DROP DATABASE, ALTER DATABASE, "
                    + "CREATE FUNCTION, DROP FUNCTION, ALTER FUNCTION, CREATE CATALOG, DROP CATALOG, "
                    + "USE CATALOG, USE [CATALOG.]DATABASE, SHOW CATALOGS, SHOW DATABASES, SHOW TABLES, SHOW [USER] FUNCTIONS, SHOW PARTITIONS"
                    + "CREATE VIEW, DROP VIEW, SHOW VIEWS, INSERT, DESCRIBE, LOAD MODULE, UNLOAD "
                    + "MODULE, USE MODULES, SHOW [FULL] MODULES.";

    protected TableEnvironmentImpl(
            CatalogManager catalogManager,
            ModuleManager moduleManager,
            TableConfig tableConfig,
            Executor executor,
            FunctionCatalog functionCatalog,
            Planner planner,
            boolean isStreamingMode,
            ClassLoader userClassLoader) {
        this.catalogManager = catalogManager;
        this.moduleManager = moduleManager;
        this.execEnv = executor;

        this.tableConfig = tableConfig;

        this.functionCatalog = functionCatalog;
        this.planner = planner;
        this.isStreamingMode = isStreamingMode;
        this.userClassLoader = userClassLoader;
        this.operationTreeBuilder =
                OperationTreeBuilder.create(
                        tableConfig,
                        functionCatalog.asLookup(getParser()::parseIdentifier),
                        catalogManager.getDataTypeFactory(),
                        path -> {
                            try {
                                UnresolvedIdentifier unresolvedIdentifier =
                                        getParser().parseIdentifier(path);
                                Optional<CatalogQueryOperation> catalogQueryOperation =
                                        scanInternal(unresolvedIdentifier);
                                return catalogQueryOperation.map(
                                        t -> ApiExpressionUtils.tableRef(path, t));
                            } catch (SqlParserException ex) {
                                // The TableLookup is used during resolution of expressions and it
                                // actually might not be an
                                // identifier of a table. It might be a reference to some other
                                // object such as column, local
                                // reference etc. This method should return empty optional in such
                                // cases to fallback for other
                                // identifiers resolution.
                                return Optional.empty();
                            }
                        },
                        (sqlExpression, inputRowType, outputType) -> {
                            try {
                                return getParser()
                                        .parseSqlExpression(
                                                sqlExpression, inputRowType, outputType);
                            } catch (Throwable t) {
                                throw new ValidationException(
                                        String.format("Invalid SQL expression: %s", sqlExpression),
                                        t);
                            }
                        },
                        isStreamingMode);

        catalogManager.initSchemaResolver(
                isStreamingMode, operationTreeBuilder.getResolverBuilder());
    }

    public static TableEnvironmentImpl create(Configuration configuration) {
        return create(EnvironmentSettings.fromConfiguration(configuration), configuration);
    }

    public static TableEnvironmentImpl create(EnvironmentSettings settings) {
        return create(settings, settings.toConfiguration());
    }

    private static TableEnvironmentImpl create(
            EnvironmentSettings settings, Configuration configuration) {
        // temporary solution until FLINK-15635 is fixed
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

        // use configuration to init table config
        TableConfig tableConfig = new TableConfig();
        tableConfig.addConfiguration(configuration);

        ModuleManager moduleManager = new ModuleManager();

        CatalogManager catalogManager =
                CatalogManager.newBuilder()
                        .classLoader(classLoader)
                        .config(tableConfig.getConfiguration())
                        .defaultCatalog(
                                settings.getBuiltInCatalogName(),
                                new GenericInMemoryCatalog(
                                        settings.getBuiltInCatalogName(),
                                        settings.getBuiltInDatabaseName()))
                        .build();

        FunctionCatalog functionCatalog =
                new FunctionCatalog(tableConfig, catalogManager, moduleManager);

        final ExecutorFactory executorFactory =
                FactoryUtil.discoverFactory(
                        classLoader, ExecutorFactory.class, settings.getExecutor());
        final Executor executor = executorFactory.create(configuration);

        Map<String, String> plannerProperties = settings.toPlannerProperties();
        Planner planner =
                ComponentFactoryService.find(PlannerFactory.class, plannerProperties)
                        .create(
                                plannerProperties,
                                executor,
                                tableConfig,
                                functionCatalog,
                                catalogManager);

        return new TableEnvironmentImpl(
                catalogManager,
                moduleManager,
                tableConfig,
                executor,
                functionCatalog,
                planner,
                settings.isStreamingMode(),
                classLoader);
    }

    @Override
    public Table fromValues(Object... values) {
        return fromValues(Arrays.asList(values));
    }

    @Override
    public Table fromValues(AbstractDataType<?> rowType, Object... values) {
        return fromValues(rowType, Arrays.asList(values));
    }

    @Override
    public Table fromValues(Expression... values) {
        return createTable(operationTreeBuilder.values(values));
    }

    @Override
    public Table fromValues(AbstractDataType<?> rowType, Expression... values) {
        final DataType resolvedDataType =
                catalogManager.getDataTypeFactory().createDataType(rowType);
        return createTable(operationTreeBuilder.values(resolvedDataType, values));
    }

    @Override
    public Table fromValues(Iterable<?> values) {
        Expression[] exprs =
                StreamSupport.stream(values.spliterator(), false)
                        .map(ApiExpressionUtils::objectToExpression)
                        .toArray(Expression[]::new);
        return fromValues(exprs);
    }

    @Override
    public Table fromValues(AbstractDataType<?> rowType, Iterable<?> values) {
        Expression[] exprs =
                StreamSupport.stream(values.spliterator(), false)
                        .map(ApiExpressionUtils::objectToExpression)
                        .toArray(Expression[]::new);
        return fromValues(rowType, exprs);
    }

    @VisibleForTesting
    public Planner getPlanner() {
        return planner;
    }

    @Override
    public Table fromTableSource(TableSource<?> source) {
        // only accept StreamTableSource and LookupableTableSource here
        // TODO should add a validation, while StreamTableSource is in flink-table-api-java-bridge
        // module now
        return createTable(new TableSourceQueryOperation<>(source, !IS_STREAM_TABLE));
    }

    @Override
    public void registerCatalog(String catalogName, Catalog catalog) {
        catalogManager.registerCatalog(catalogName, catalog);
    }

    @Override
    public Optional<Catalog> getCatalog(String catalogName) {
        return catalogManager.getCatalog(catalogName);
    }

    @Override
    public void loadModule(String moduleName, Module module) {
        moduleManager.loadModule(moduleName, module);
    }

    @Override
    public void useModules(String... moduleNames) {
        moduleManager.useModules(moduleNames);
    }

    @Override
    public void unloadModule(String moduleName) {
        moduleManager.unloadModule(moduleName);
    }

    @Override
    public void registerFunction(String name, ScalarFunction function) {
        functionCatalog.registerTempSystemScalarFunction(name, function);
    }

    @Override
    public void createTemporarySystemFunction(
            String name, Class<? extends UserDefinedFunction> functionClass) {
        final UserDefinedFunction functionInstance =
                UserDefinedFunctionHelper.instantiateFunction(functionClass);
        createTemporarySystemFunction(name, functionInstance);
    }

    @Override
    public void createTemporarySystemFunction(String name, UserDefinedFunction functionInstance) {
        functionCatalog.registerTemporarySystemFunction(name, functionInstance, false);
    }

    @Override
    public boolean dropTemporarySystemFunction(String name) {
        return functionCatalog.dropTemporarySystemFunction(name, true);
    }

    @Override
    public void createFunction(String path, Class<? extends UserDefinedFunction> functionClass) {
        createFunction(path, functionClass, false);
    }

    @Override
    public void createFunction(
            String path,
            Class<? extends UserDefinedFunction> functionClass,
            boolean ignoreIfExists) {
        final UnresolvedIdentifier unresolvedIdentifier = getParser().parseIdentifier(path);
        functionCatalog.registerCatalogFunction(
                unresolvedIdentifier, functionClass, ignoreIfExists);
    }

    @Override
    public boolean dropFunction(String path) {
        final UnresolvedIdentifier unresolvedIdentifier = getParser().parseIdentifier(path);
        return functionCatalog.dropCatalogFunction(unresolvedIdentifier, true);
    }

    @Override
    public void createTemporaryFunction(
            String path, Class<? extends UserDefinedFunction> functionClass) {
        final UserDefinedFunction functionInstance =
                UserDefinedFunctionHelper.instantiateFunction(functionClass);
        createTemporaryFunction(path, functionInstance);
    }

    @Override
    public void createTemporaryFunction(String path, UserDefinedFunction functionInstance) {
        final UnresolvedIdentifier unresolvedIdentifier = getParser().parseIdentifier(path);
        functionCatalog.registerTemporaryCatalogFunction(
                unresolvedIdentifier, functionInstance, false);
    }

    @Override
    public boolean dropTemporaryFunction(String path) {
        final UnresolvedIdentifier unresolvedIdentifier = getParser().parseIdentifier(path);
        return functionCatalog.dropTemporaryCatalogFunction(unresolvedIdentifier, true);
    }

    @Override
    public void createTemporaryTable(String path, TableDescriptor descriptor) {
        Preconditions.checkNotNull(path, "Path must not be null.");
        Preconditions.checkNotNull(descriptor, "Table descriptor must not be null.");

        createTemporaryTableInternal(getParser().parseIdentifier(path), descriptor);
    }

    private void createTemporaryTableInternal(
            UnresolvedIdentifier path, TableDescriptor descriptor) {
        final ObjectIdentifier tableIdentifier = catalogManager.qualifyIdentifier(path);
        final CatalogTable catalogTable = convertTableDescriptor(descriptor);
        catalogManager.createTemporaryTable(catalogTable, tableIdentifier, false);
    }

    @Override
    public void createTable(String path, TableDescriptor descriptor) {
        Preconditions.checkNotNull(path, "Path must not be null.");
        Preconditions.checkNotNull(descriptor, "Table descriptor must not be null.");

        final ObjectIdentifier tableIdentifier =
                catalogManager.qualifyIdentifier(getParser().parseIdentifier(path));
        final CatalogTable catalogTable = convertTableDescriptor(descriptor);
        catalogManager.createTable(catalogTable, tableIdentifier, false);
    }

    private CatalogTable convertTableDescriptor(TableDescriptor descriptor) {
        final Schema schema =
                descriptor
                        .getSchema()
                        .orElseThrow(
                                () ->
                                        new ValidationException(
                                                "Missing schema in TableDescriptor. "
                                                        + "A schema is typically required. "
                                                        + "It can only be omitted at certain "
                                                        + "documented locations."));

        return CatalogTable.of(
                schema,
                descriptor.getComment().orElse(null),
                descriptor.getPartitionKeys(),
                descriptor.getOptions());
    }

    @Override
    public void registerTable(String name, Table table) {
        UnresolvedIdentifier identifier = UnresolvedIdentifier.of(name);
        createTemporaryView(identifier, table);
    }

    @Override
    public void createTemporaryView(String path, Table view) {
        Preconditions.checkNotNull(path, "Path must not be null.");
        Preconditions.checkNotNull(view, "Table view must not be null.");
        UnresolvedIdentifier identifier = getParser().parseIdentifier(path);
        createTemporaryView(identifier, view);
    }

    private void createTemporaryView(UnresolvedIdentifier identifier, Table view) {
        if (((TableImpl) view).getTableEnvironment() != this) {
            throw new TableException(
                    "Only table API objects that belong to this TableEnvironment can be registered.");
        }

        ObjectIdentifier tableIdentifier = catalogManager.qualifyIdentifier(identifier);
        QueryOperation queryOperation =
                qualifyQueryOperation(tableIdentifier, view.getQueryOperation());
        CatalogBaseTable tableTable = new QueryOperationCatalogView(queryOperation);

        catalogManager.createTemporaryTable(tableTable, tableIdentifier, false);
    }

    @Override
    public Table scan(String... tablePath) {
        UnresolvedIdentifier unresolvedIdentifier = UnresolvedIdentifier.of(tablePath);
        return scanInternal(unresolvedIdentifier)
                .map(this::createTable)
                .orElseThrow(
                        () ->
                                new ValidationException(
                                        String.format(
                                                "Table %s was not found.", unresolvedIdentifier)));
    }

    @Override
    public Table from(String path) {
        UnresolvedIdentifier unresolvedIdentifier = getParser().parseIdentifier(path);
        return scanInternal(unresolvedIdentifier)
                .map(this::createTable)
                .orElseThrow(
                        () ->
                                new ValidationException(
                                        String.format(
                                                "Table %s was not found.", unresolvedIdentifier)));
    }

    @Override
    public Table from(TableDescriptor descriptor) {
        Preconditions.checkNotNull(descriptor, "Table descriptor must not be null.");

        final String path = TableDescriptorUtil.getUniqueAnonymousPath();
        createTemporaryTableInternal(UnresolvedIdentifier.of(path), descriptor);
        return from(path);
    }

    @Override
    public void insertInto(String targetPath, Table table) {
        UnresolvedIdentifier unresolvedIdentifier = getParser().parseIdentifier(targetPath);
        insertIntoInternal(unresolvedIdentifier, table);
    }

    @Override
    public void insertInto(Table table, String sinkPath, String... sinkPathContinued) {
        List<String> fullPath = new ArrayList<>(Arrays.asList(sinkPathContinued));
        fullPath.add(0, sinkPath);
        UnresolvedIdentifier unresolvedIdentifier = UnresolvedIdentifier.of(fullPath);

        insertIntoInternal(unresolvedIdentifier, table);
    }

    private void insertIntoInternal(UnresolvedIdentifier unresolvedIdentifier, Table table) {
        ObjectIdentifier objectIdentifier = catalogManager.qualifyIdentifier(unresolvedIdentifier);

        List<ModifyOperation> modifyOperations =
                Collections.singletonList(
                        new CatalogSinkModifyOperation(
                                objectIdentifier, table.getQueryOperation()));

        buffer(modifyOperations);
    }

    private Optional<CatalogQueryOperation> scanInternal(UnresolvedIdentifier identifier) {
        ObjectIdentifier tableIdentifier = catalogManager.qualifyIdentifier(identifier);

        return catalogManager
                .getTable(tableIdentifier)
                .map(t -> new CatalogQueryOperation(tableIdentifier, t.getResolvedSchema()));
    }

    @Override
    public String[] listCatalogs() {
        return catalogManager.listCatalogs().stream().sorted().toArray(String[]::new);
    }

    @Override
    public String[] listModules() {
        return moduleManager.listModules().toArray(new String[0]);
    }

    @Override
    public ModuleEntry[] listFullModules() {
        return moduleManager.listFullModules().toArray(new ModuleEntry[0]);
    }

    @Override
    public String[] listDatabases() {
        return catalogManager
                .getCatalog(catalogManager.getCurrentCatalog())
                .get()
                .listDatabases()
                .toArray(new String[0]);
    }

    @Override
    public String[] listTables() {
        return catalogManager.listTables().stream().sorted().toArray(String[]::new);
    }

    @Override
    public String[] listViews() {
        return catalogManager.listViews().stream().sorted().toArray(String[]::new);
    }

    @Override
    public String[] listTemporaryTables() {
        return catalogManager.listTemporaryTables().stream().sorted().toArray(String[]::new);
    }

    @Override
    public String[] listTemporaryViews() {
        return catalogManager.listTemporaryViews().stream().sorted().toArray(String[]::new);
    }

    @Override
    public boolean dropTemporaryTable(String path) {
        UnresolvedIdentifier unresolvedIdentifier = getParser().parseIdentifier(path);
        ObjectIdentifier identifier = catalogManager.qualifyIdentifier(unresolvedIdentifier);
        try {
            catalogManager.dropTemporaryTable(identifier, false);
            return true;
        } catch (ValidationException e) {
            return false;
        }
    }

    @Override
    public boolean dropTemporaryView(String path) {
        UnresolvedIdentifier unresolvedIdentifier = getParser().parseIdentifier(path);
        ObjectIdentifier identifier = catalogManager.qualifyIdentifier(unresolvedIdentifier);
        try {
            catalogManager.dropTemporaryView(identifier, false);
            return true;
        } catch (ValidationException e) {
            return false;
        }
    }

    @Override
    public String[] listUserDefinedFunctions() {
        String[] functions = functionCatalog.getUserDefinedFunctions();
        Arrays.sort(functions);
        return functions;
    }

    @Override
    public String[] listFunctions() {
        String[] functions = functionCatalog.getFunctions();
        Arrays.sort(functions);
        return functions;
    }

    @Override
    public String explain(Table table) {
        return explain(table, false);
    }

    @Override
    public String explain(Table table, boolean extended) {
        return planner.explain(
                Collections.singletonList(table.getQueryOperation()), getExplainDetails(extended));
    }

    @Override
    public String explain(boolean extended) {
        List<Operation> operations =
                bufferedModifyOperations.stream()
                        .map(o -> (Operation) o)
                        .collect(Collectors.toList());
        return planner.explain(operations, getExplainDetails(extended));
    }

    @Override
    public String explainSql(String statement, ExplainDetail... extraDetails) {
        List<Operation> operations = getParser().parse(statement);

        if (operations.size() != 1) {
            throw new TableException(
                    "Unsupported SQL query! explainSql() only accepts a single SQL query.");
        }

        return explainInternal(operations, extraDetails);
    }

    @Override
    public String explainInternal(List<Operation> operations, ExplainDetail... extraDetails) {
        operations =
                operations.stream()
                        .filter(o -> !(o instanceof NopOperation))
                        .collect(Collectors.toList());
        // hive parser may generate an NopOperation, in which case we just return an
        // empty string as the plan
        if (operations.isEmpty()) {
            return "";
        } else {
            return planner.explain(operations, extraDetails);
        }
    }

    @Override
    public String[] getCompletionHints(String statement, int position) {
        return planner.getParser().getCompletionHints(statement, position);
    }

    @Override
    public Table sqlQuery(String query) {
        List<Operation> operations = getParser().parse(query);

        if (operations.size() != 1) {
            throw new ValidationException(
                    "Unsupported SQL query! sqlQuery() only accepts a single SQL query.");
        }

        Operation operation = operations.get(0);

        if (operation instanceof QueryOperation && !(operation instanceof ModifyOperation)) {
            return createTable((QueryOperation) operation);
        } else {
            throw new ValidationException(
                    "Unsupported SQL query! sqlQuery() only accepts a single SQL query of type "
                            + "SELECT, UNION, INTERSECT, EXCEPT, VALUES, and ORDER_BY.");
        }
    }

    @Override
    public TableResult executeSql(String statement) {
        List<Operation> operations = getParser().parse(statement);

        if (operations.size() != 1) {
            throw new TableException(UNSUPPORTED_QUERY_IN_EXECUTE_SQL_MSG);
        }

        return executeInternal(operations.get(0));
    }

    @Override
    public StatementSet createStatementSet() {
        return new StatementSetImpl(this);
    }

    @Override
    public TableResult executeInternal(List<ModifyOperation> operations) {
        List<Transformation<?>> transformations = translate(operations);
        List<String> sinkIdentifierNames = extractSinkIdentifierNames(operations);
        TableResult result = executeInternal(transformations, sinkIdentifierNames);
        if (tableConfig.getConfiguration().get(TABLE_DML_SYNC)) {
            try {
                result.await();
            } catch (InterruptedException | ExecutionException e) {
                result.getJobClient().ifPresent(JobClient::cancel);
                throw new TableException("Fail to wait execution finish.", e);
            }
        }
        return result;
    }

    private TableResult executeInternal(
            List<Transformation<?>> transformations, List<String> sinkIdentifierNames) {
        final String defaultJobName = "insert-into_" + String.join(",", sinkIdentifierNames);
        Pipeline pipeline =
                execEnv.createPipeline(
                        transformations, tableConfig.getConfiguration(), defaultJobName);
        try {
            JobClient jobClient = execEnv.executeAsync(pipeline);
            final List<Column> columns = new ArrayList<>();
            Object[] affectedRowCounts = new Long[transformations.size()];
            for (int i = 0; i < transformations.size(); ++i) {
                // use sink identifier name as field name
                columns.add(Column.physical(sinkIdentifierNames.get(i), DataTypes.BIGINT()));
                affectedRowCounts[i] = -1L;
            }

            return TableResultImpl.builder()
                    .jobClient(jobClient)
                    .resultKind(ResultKind.SUCCESS_WITH_CONTENT)
                    .schema(ResolvedSchema.of(columns))
                    .data(
                            new InsertResultIterator(
                                    jobClient, Row.of(affectedRowCounts), userClassLoader))
                    .build();
        } catch (Exception e) {
            throw new TableException("Failed to execute sql", e);
        }
    }

    private TableResult executeQueryOperation(QueryOperation operation) {
        final UnresolvedIdentifier unresolvedIdentifier =
                UnresolvedIdentifier.of(
                        "Unregistered_Collect_Sink_" + CollectModifyOperation.getUniqueId());
        final ObjectIdentifier objectIdentifier =
                catalogManager.qualifyIdentifier(unresolvedIdentifier);

        CollectModifyOperation sinkOperation =
                new CollectModifyOperation(objectIdentifier, operation);
        List<Transformation<?>> transformations =
                translate(Collections.singletonList(sinkOperation));
        final String defaultJobName = "collect";
        Pipeline pipeline =
                execEnv.createPipeline(
                        transformations, tableConfig.getConfiguration(), defaultJobName);
        try {
            JobClient jobClient = execEnv.executeAsync(pipeline);
            CollectResultProvider resultProvider = sinkOperation.getSelectResultProvider();
            resultProvider.setJobClient(jobClient);
            return TableResultImpl.builder()
                    .jobClient(jobClient)
                    .resultKind(ResultKind.SUCCESS_WITH_CONTENT)
                    .schema(operation.getResolvedSchema())
                    .data(resultProvider.getResultIterator())
                    .setPrintStyle(
                            TableResultImpl.PrintStyle.tableau(
                                    PrintUtils.MAX_COLUMN_WIDTH,
                                    PrintUtils.NULL_COLUMN,
                                    true,
                                    isStreamingMode))
                    .setSessionTimeZone(getConfig().getLocalTimeZone())
                    .build();
        } catch (Exception e) {
            throw new TableException("Failed to execute sql", e);
        }
    }

    @Override
    public void sqlUpdate(String stmt) {
        List<Operation> operations = getParser().parse(stmt);

        if (operations.size() != 1) {
            throw new TableException(UNSUPPORTED_QUERY_IN_SQL_UPDATE_MSG);
        }

        Operation operation = operations.get(0);
        if (operation instanceof ModifyOperation) {
            buffer(Collections.singletonList((ModifyOperation) operation));
        } else if (operation instanceof CreateTableOperation
                || operation instanceof DropTableOperation
                || operation instanceof AlterTableOperation
                || operation instanceof CreateViewOperation
                || operation instanceof DropViewOperation
                || operation instanceof CreateDatabaseOperation
                || operation instanceof DropDatabaseOperation
                || operation instanceof AlterDatabaseOperation
                || operation instanceof CreateCatalogFunctionOperation
                || operation instanceof CreateTempSystemFunctionOperation
                || operation instanceof DropCatalogFunctionOperation
                || operation instanceof DropTempSystemFunctionOperation
                || operation instanceof AlterCatalogFunctionOperation
                || operation instanceof CreateCatalogOperation
                || operation instanceof DropCatalogOperation
                || operation instanceof UseCatalogOperation
                || operation instanceof UseDatabaseOperation
                || operation instanceof LoadModuleOperation
                || operation instanceof UnloadModuleOperation
                || operation instanceof NopOperation) {
            executeInternal(operation);
        } else {
            throw new TableException(UNSUPPORTED_QUERY_IN_SQL_UPDATE_MSG);
        }
    }

    @Override
    public TableResult executeInternal(Operation operation) {
        if (operation instanceof ModifyOperation) {
            return executeInternal(Collections.singletonList((ModifyOperation) operation));
        } else if (operation instanceof CreateTableOperation) {
            CreateTableOperation createTableOperation = (CreateTableOperation) operation;
            if (createTableOperation.isTemporary()) {
                catalogManager.createTemporaryTable(
                        createTableOperation.getCatalogTable(),
                        createTableOperation.getTableIdentifier(),
                        createTableOperation.isIgnoreIfExists());
            } else {
                catalogManager.createTable(
                        createTableOperation.getCatalogTable(),
                        createTableOperation.getTableIdentifier(),
                        createTableOperation.isIgnoreIfExists());
            }
            return TableResultImpl.TABLE_RESULT_OK;
        } else if (operation instanceof DropTableOperation) {
            DropTableOperation dropTableOperation = (DropTableOperation) operation;
            if (dropTableOperation.isTemporary()) {
                catalogManager.dropTemporaryTable(
                        dropTableOperation.getTableIdentifier(), dropTableOperation.isIfExists());
            } else {
                catalogManager.dropTable(
                        dropTableOperation.getTableIdentifier(), dropTableOperation.isIfExists());
            }
            return TableResultImpl.TABLE_RESULT_OK;
        } else if (operation instanceof AlterTableOperation) {
            AlterTableOperation alterTableOperation = (AlterTableOperation) operation;
            Catalog catalog =
                    getCatalogOrThrowException(
                            alterTableOperation.getTableIdentifier().getCatalogName());
            String exMsg = getDDLOpExecuteErrorMsg(alterTableOperation.asSummaryString());
            try {
                if (alterTableOperation instanceof AlterTableRenameOperation) {
                    AlterTableRenameOperation alterTableRenameOp =
                            (AlterTableRenameOperation) operation;
                    catalog.renameTable(
                            alterTableRenameOp.getTableIdentifier().toObjectPath(),
                            alterTableRenameOp.getNewTableIdentifier().getObjectName(),
                            false);
                } else if (alterTableOperation instanceof AlterTableOptionsOperation) {
                    AlterTableOptionsOperation alterTablePropertiesOp =
                            (AlterTableOptionsOperation) operation;
                    catalogManager.alterTable(
                            alterTablePropertiesOp.getCatalogTable(),
                            alterTablePropertiesOp.getTableIdentifier(),
                            false);
                } else if (alterTableOperation instanceof AlterTableAddConstraintOperation) {
                    AlterTableAddConstraintOperation addConstraintOP =
                            (AlterTableAddConstraintOperation) operation;
                    CatalogTable oriTable =
                            (CatalogTable)
                                    catalogManager
                                            .getTable(addConstraintOP.getTableIdentifier())
                                            .get()
                                            .getTable();
                    TableSchema.Builder builder =
                            TableSchemaUtils.builderWithGivenSchema(oriTable.getSchema());
                    if (addConstraintOP.getConstraintName().isPresent()) {
                        builder.primaryKey(
                                addConstraintOP.getConstraintName().get(),
                                addConstraintOP.getColumnNames());
                    } else {
                        builder.primaryKey(addConstraintOP.getColumnNames());
                    }
                    CatalogTable newTable =
                            new CatalogTableImpl(
                                    builder.build(),
                                    oriTable.getPartitionKeys(),
                                    oriTable.getOptions(),
                                    oriTable.getComment());
                    catalogManager.alterTable(
                            newTable, addConstraintOP.getTableIdentifier(), false);
                } else if (alterTableOperation instanceof AlterTableDropConstraintOperation) {
                    AlterTableDropConstraintOperation dropConstraintOperation =
                            (AlterTableDropConstraintOperation) operation;
                    CatalogTable oriTable =
                            (CatalogTable)
                                    catalogManager
                                            .getTable(dropConstraintOperation.getTableIdentifier())
                                            .get()
                                            .getTable();
                    CatalogTable newTable =
                            new CatalogTableImpl(
                                    TableSchemaUtils.dropConstraint(
                                            oriTable.getSchema(),
                                            dropConstraintOperation.getConstraintName()),
                                    oriTable.getPartitionKeys(),
                                    oriTable.getOptions(),
                                    oriTable.getComment());
                    catalogManager.alterTable(
                            newTable, dropConstraintOperation.getTableIdentifier(), false);
                } else if (alterTableOperation instanceof AlterPartitionPropertiesOperation) {
                    AlterPartitionPropertiesOperation alterPartPropsOp =
                            (AlterPartitionPropertiesOperation) operation;
                    catalog.alterPartition(
                            alterPartPropsOp.getTableIdentifier().toObjectPath(),
                            alterPartPropsOp.getPartitionSpec(),
                            alterPartPropsOp.getCatalogPartition(),
                            false);
                } else if (alterTableOperation instanceof AlterTableSchemaOperation) {
                    AlterTableSchemaOperation alterTableSchemaOperation =
                            (AlterTableSchemaOperation) alterTableOperation;
                    catalogManager.alterTable(
                            alterTableSchemaOperation.getCatalogTable(),
                            alterTableSchemaOperation.getTableIdentifier(),
                            false);
                } else if (alterTableOperation instanceof AddPartitionsOperation) {
                    AddPartitionsOperation addPartitionsOperation =
                            (AddPartitionsOperation) alterTableOperation;
                    List<CatalogPartitionSpec> specs = addPartitionsOperation.getPartitionSpecs();
                    List<CatalogPartition> partitions =
                            addPartitionsOperation.getCatalogPartitions();
                    boolean ifNotExists = addPartitionsOperation.ifNotExists();
                    ObjectPath tablePath =
                            addPartitionsOperation.getTableIdentifier().toObjectPath();
                    for (int i = 0; i < specs.size(); i++) {
                        catalog.createPartition(
                                tablePath, specs.get(i), partitions.get(i), ifNotExists);
                    }
                } else if (alterTableOperation instanceof DropPartitionsOperation) {
                    DropPartitionsOperation dropPartitionsOperation =
                            (DropPartitionsOperation) alterTableOperation;
                    ObjectPath tablePath =
                            dropPartitionsOperation.getTableIdentifier().toObjectPath();
                    boolean ifExists = dropPartitionsOperation.ifExists();
                    for (CatalogPartitionSpec spec : dropPartitionsOperation.getPartitionSpecs()) {
                        catalog.dropPartition(tablePath, spec, ifExists);
                    }
                }
                return TableResultImpl.TABLE_RESULT_OK;
            } catch (TableAlreadyExistException | TableNotExistException e) {
                throw new ValidationException(exMsg, e);
            } catch (Exception e) {
                throw new TableException(exMsg, e);
            }
        } else if (operation instanceof CreateViewOperation) {
            CreateViewOperation createViewOperation = (CreateViewOperation) operation;
            if (createViewOperation.isTemporary()) {
                catalogManager.createTemporaryTable(
                        createViewOperation.getCatalogView(),
                        createViewOperation.getViewIdentifier(),
                        createViewOperation.isIgnoreIfExists());
            } else {
                catalogManager.createTable(
                        createViewOperation.getCatalogView(),
                        createViewOperation.getViewIdentifier(),
                        createViewOperation.isIgnoreIfExists());
            }
            return TableResultImpl.TABLE_RESULT_OK;
        } else if (operation instanceof DropViewOperation) {
            DropViewOperation dropViewOperation = (DropViewOperation) operation;
            if (dropViewOperation.isTemporary()) {
                catalogManager.dropTemporaryView(
                        dropViewOperation.getViewIdentifier(), dropViewOperation.isIfExists());
            } else {
                catalogManager.dropView(
                        dropViewOperation.getViewIdentifier(), dropViewOperation.isIfExists());
            }
            return TableResultImpl.TABLE_RESULT_OK;
        } else if (operation instanceof AlterViewOperation) {
            AlterViewOperation alterViewOperation = (AlterViewOperation) operation;
            Catalog catalog =
                    getCatalogOrThrowException(
                            alterViewOperation.getViewIdentifier().getCatalogName());
            String exMsg = getDDLOpExecuteErrorMsg(alterViewOperation.asSummaryString());
            try {
                if (alterViewOperation instanceof AlterViewRenameOperation) {
                    AlterViewRenameOperation alterTableRenameOp =
                            (AlterViewRenameOperation) operation;
                    catalog.renameTable(
                            alterTableRenameOp.getViewIdentifier().toObjectPath(),
                            alterTableRenameOp.getNewViewIdentifier().getObjectName(),
                            false);
                } else if (alterViewOperation instanceof AlterViewPropertiesOperation) {
                    AlterViewPropertiesOperation alterTablePropertiesOp =
                            (AlterViewPropertiesOperation) operation;
                    catalogManager.alterTable(
                            alterTablePropertiesOp.getCatalogView(),
                            alterTablePropertiesOp.getViewIdentifier(),
                            false);
                } else if (alterViewOperation instanceof AlterViewAsOperation) {
                    AlterViewAsOperation alterViewAsOperation =
                            (AlterViewAsOperation) alterViewOperation;
                    catalogManager.alterTable(
                            alterViewAsOperation.getNewView(),
                            alterViewAsOperation.getViewIdentifier(),
                            false);
                }
                return TableResultImpl.TABLE_RESULT_OK;
            } catch (TableAlreadyExistException | TableNotExistException e) {
                throw new ValidationException(exMsg, e);
            } catch (Exception e) {
                throw new TableException(exMsg, e);
            }
        } else if (operation instanceof CreateDatabaseOperation) {
            CreateDatabaseOperation createDatabaseOperation = (CreateDatabaseOperation) operation;
            Catalog catalog = getCatalogOrThrowException(createDatabaseOperation.getCatalogName());
            String exMsg = getDDLOpExecuteErrorMsg(createDatabaseOperation.asSummaryString());
            try {
                catalog.createDatabase(
                        createDatabaseOperation.getDatabaseName(),
                        createDatabaseOperation.getCatalogDatabase(),
                        createDatabaseOperation.isIgnoreIfExists());
                return TableResultImpl.TABLE_RESULT_OK;
            } catch (DatabaseAlreadyExistException e) {
                throw new ValidationException(exMsg, e);
            } catch (Exception e) {
                throw new TableException(exMsg, e);
            }
        } else if (operation instanceof DropDatabaseOperation) {
            DropDatabaseOperation dropDatabaseOperation = (DropDatabaseOperation) operation;
            Catalog catalog = getCatalogOrThrowException(dropDatabaseOperation.getCatalogName());
            String exMsg = getDDLOpExecuteErrorMsg(dropDatabaseOperation.asSummaryString());
            try {
                catalog.dropDatabase(
                        dropDatabaseOperation.getDatabaseName(),
                        dropDatabaseOperation.isIfExists(),
                        dropDatabaseOperation.isCascade());
                return TableResultImpl.TABLE_RESULT_OK;
            } catch (DatabaseNotExistException | DatabaseNotEmptyException e) {
                throw new ValidationException(exMsg, e);
            } catch (Exception e) {
                throw new TableException(exMsg, e);
            }
        } else if (operation instanceof AlterDatabaseOperation) {
            AlterDatabaseOperation alterDatabaseOperation = (AlterDatabaseOperation) operation;
            Catalog catalog = getCatalogOrThrowException(alterDatabaseOperation.getCatalogName());
            String exMsg = getDDLOpExecuteErrorMsg(alterDatabaseOperation.asSummaryString());
            try {
                catalog.alterDatabase(
                        alterDatabaseOperation.getDatabaseName(),
                        alterDatabaseOperation.getCatalogDatabase(),
                        false);
                return TableResultImpl.TABLE_RESULT_OK;
            } catch (DatabaseNotExistException e) {
                throw new ValidationException(exMsg, e);
            } catch (Exception e) {
                throw new TableException(exMsg, e);
            }
        } else if (operation instanceof CreateCatalogFunctionOperation) {
            return createCatalogFunction((CreateCatalogFunctionOperation) operation);
        } else if (operation instanceof CreateTempSystemFunctionOperation) {
            return createSystemFunction((CreateTempSystemFunctionOperation) operation);
        } else if (operation instanceof DropCatalogFunctionOperation) {
            return dropCatalogFunction((DropCatalogFunctionOperation) operation);
        } else if (operation instanceof DropTempSystemFunctionOperation) {
            return dropSystemFunction((DropTempSystemFunctionOperation) operation);
        } else if (operation instanceof AlterCatalogFunctionOperation) {
            return alterCatalogFunction((AlterCatalogFunctionOperation) operation);
        } else if (operation instanceof CreateCatalogOperation) {
            return createCatalog((CreateCatalogOperation) operation);
        } else if (operation instanceof DropCatalogOperation) {
            DropCatalogOperation dropCatalogOperation = (DropCatalogOperation) operation;
            String exMsg = getDDLOpExecuteErrorMsg(dropCatalogOperation.asSummaryString());
            try {
                catalogManager.unregisterCatalog(
                        dropCatalogOperation.getCatalogName(), dropCatalogOperation.isIfExists());
                return TableResultImpl.TABLE_RESULT_OK;
            } catch (CatalogException e) {
                throw new ValidationException(exMsg, e);
            }
        } else if (operation instanceof LoadModuleOperation) {
            return loadModule((LoadModuleOperation) operation);
        } else if (operation instanceof UnloadModuleOperation) {
            return unloadModule((UnloadModuleOperation) operation);
        } else if (operation instanceof UseModulesOperation) {
            return useModules((UseModulesOperation) operation);
        } else if (operation instanceof UseCatalogOperation) {
            UseCatalogOperation useCatalogOperation = (UseCatalogOperation) operation;
            catalogManager.setCurrentCatalog(useCatalogOperation.getCatalogName());
            return TableResultImpl.TABLE_RESULT_OK;
        } else if (operation instanceof UseDatabaseOperation) {
            UseDatabaseOperation useDatabaseOperation = (UseDatabaseOperation) operation;
            catalogManager.setCurrentCatalog(useDatabaseOperation.getCatalogName());
            catalogManager.setCurrentDatabase(useDatabaseOperation.getDatabaseName());
            return TableResultImpl.TABLE_RESULT_OK;
        } else if (operation instanceof ShowCatalogsOperation) {
            return buildShowResult("catalog name", listCatalogs());
        } else if (operation instanceof ShowCreateTableOperation) {
            ShowCreateTableOperation showCreateTableOperation =
                    (ShowCreateTableOperation) operation;
            Optional<CatalogManager.TableLookupResult> result =
                    catalogManager.getTable(showCreateTableOperation.getTableIdentifier());
            if (result.isPresent()) {
                return TableResultImpl.builder()
                        .resultKind(ResultKind.SUCCESS_WITH_CONTENT)
                        .schema(ResolvedSchema.of(Column.physical("result", DataTypes.STRING())))
                        .data(
                                Collections.singletonList(
                                        Row.of(
                                                buildShowCreateTableRow(
                                                        result.get().getResolvedTable(),
                                                        showCreateTableOperation
                                                                .getTableIdentifier(),
                                                        result.get().isTemporary()))))
                        .setPrintStyle(TableResultImpl.PrintStyle.rawContent())
                        .build();
            } else {
                throw new ValidationException(
                        String.format(
                                "Could not execute SHOW CREATE TABLE. Table with identifier %s does not exist.",
                                showCreateTableOperation
                                        .getTableIdentifier()
                                        .asSerializableString()));
            }
        } else if (operation instanceof ShowCurrentCatalogOperation) {
            return buildShowResult(
                    "current catalog name", new String[] {catalogManager.getCurrentCatalog()});
        } else if (operation instanceof ShowDatabasesOperation) {
            return buildShowResult("database name", listDatabases());
        } else if (operation instanceof ShowCurrentDatabaseOperation) {
            return buildShowResult(
                    "current database name", new String[] {catalogManager.getCurrentDatabase()});
        } else if (operation instanceof ShowModulesOperation) {
            ShowModulesOperation showModulesOperation = (ShowModulesOperation) operation;
            if (showModulesOperation.requireFull()) {
                return buildShowFullModulesResult(listFullModules());
            } else {
                return buildShowResult("module name", listModules());
            }
        } else if (operation instanceof ShowTablesOperation) {
            return buildShowResult("table name", listTables());
        } else if (operation instanceof ShowFunctionsOperation) {
            ShowFunctionsOperation showFunctionsOperation = (ShowFunctionsOperation) operation;
            String[] functionNames = null;
            ShowFunctionsOperation.FunctionScope functionScope =
                    showFunctionsOperation.getFunctionScope();
            switch (functionScope) {
                case USER:
                    functionNames = listUserDefinedFunctions();
                    break;
                case ALL:
                    functionNames = listFunctions();
                    break;
                default:
                    throw new UnsupportedOperationException(
                            String.format(
                                    "SHOW FUNCTIONS with %s scope is not supported.",
                                    functionScope));
            }
            return buildShowResult("function name", functionNames);
        } else if (operation instanceof ShowViewsOperation) {
            return buildShowResult("view name", listViews());
        } else if (operation instanceof ShowPartitionsOperation) {
            String exMsg = getDDLOpExecuteErrorMsg(operation.asSummaryString());
            try {
                ShowPartitionsOperation showPartitionsOperation =
                        (ShowPartitionsOperation) operation;
                Catalog catalog =
                        getCatalogOrThrowException(
                                showPartitionsOperation.getTableIdentifier().getCatalogName());
                ObjectPath tablePath = showPartitionsOperation.getTableIdentifier().toObjectPath();
                CatalogPartitionSpec partitionSpec = showPartitionsOperation.getPartitionSpec();
                List<CatalogPartitionSpec> partitionSpecs =
                        partitionSpec == null
                                ? catalog.listPartitions(tablePath)
                                : catalog.listPartitions(tablePath, partitionSpec);
                List<String> partitionNames = new ArrayList<>(partitionSpecs.size());
                for (CatalogPartitionSpec spec : partitionSpecs) {
                    List<String> partitionKVs = new ArrayList<>(spec.getPartitionSpec().size());
                    for (Map.Entry<String, String> partitionKV :
                            spec.getPartitionSpec().entrySet()) {
                        partitionKVs.add(partitionKV.getKey() + "=" + partitionKV.getValue());
                    }
                    partitionNames.add(String.join("/", partitionKVs));
                }
                return buildShowResult("partition name", partitionNames.toArray(new String[0]));
            } catch (TableNotExistException e) {
                throw new ValidationException(exMsg, e);
            } catch (Exception e) {
                throw new TableException(exMsg, e);
            }
        } else if (operation instanceof ExplainOperation) {
            ExplainOperation explainOperation = (ExplainOperation) operation;
            ExplainDetail[] explainDetails =
                    explainOperation.getExplainDetails().stream()
                            .map(ExplainDetail::valueOf)
                            .toArray(ExplainDetail[]::new);
            String explanation =
                    explainInternal(
                            Collections.singletonList(((ExplainOperation) operation).getChild()),
                            explainDetails);
            return TableResultImpl.builder()
                    .resultKind(ResultKind.SUCCESS_WITH_CONTENT)
                    .schema(ResolvedSchema.of(Column.physical("result", DataTypes.STRING())))
                    .data(Collections.singletonList(Row.of(explanation)))
                    .setPrintStyle(TableResultImpl.PrintStyle.rawContent())
                    .setSessionTimeZone(getConfig().getLocalTimeZone())
                    .build();
        } else if (operation instanceof DescribeTableOperation) {
            DescribeTableOperation describeTableOperation = (DescribeTableOperation) operation;
            Optional<CatalogManager.TableLookupResult> result =
                    catalogManager.getTable(describeTableOperation.getSqlIdentifier());
            if (result.isPresent()) {
                return buildDescribeResult(result.get().getResolvedSchema());
            } else {
                throw new ValidationException(
                        String.format(
                                "Tables or views with the identifier '%s' doesn't exist",
                                describeTableOperation.getSqlIdentifier().asSummaryString()));
            }
        } else if (operation instanceof QueryOperation) {
            return executeQueryOperation((QueryOperation) operation);
        } else if (operation instanceof CreateTableASOperation) {
            executeInternal(((CreateTableASOperation) operation).getCreateTableOperation());
            return executeInternal(((CreateTableASOperation) operation).getInsertOperation());
        } else if (operation instanceof NopOperation) {
            return TableResultImpl.TABLE_RESULT_OK;
        } else {
            throw new TableException(UNSUPPORTED_QUERY_IN_EXECUTE_SQL_MSG);
        }
    }

    private TableResult createCatalog(CreateCatalogOperation operation) {
        String exMsg = getDDLOpExecuteErrorMsg(operation.asSummaryString());
        try {
            String catalogName = operation.getCatalogName();
            Map<String, String> properties = operation.getProperties();

            Catalog catalog =
                    FactoryUtil.createCatalog(
                            catalogName,
                            properties,
                            tableConfig.getConfiguration(),
                            userClassLoader);
            catalogManager.registerCatalog(catalogName, catalog);

            return TableResultImpl.TABLE_RESULT_OK;
        } catch (CatalogException e) {
            throw new ValidationException(exMsg, e);
        }
    }

    private TableResult loadModule(LoadModuleOperation operation) {
        final String exMsg = getDDLOpExecuteErrorMsg(operation.asSummaryString());
        try {
            final Module module =
                    FactoryUtil.createModule(
                            operation.getModuleName(),
                            operation.getOptions(),
                            tableConfig.getConfiguration(),
                            userClassLoader);
            moduleManager.loadModule(operation.getModuleName(), module);
            return TableResultImpl.TABLE_RESULT_OK;
        } catch (ValidationException e) {
            throw new ValidationException(String.format("%s. %s", exMsg, e.getMessage()), e);
        } catch (Exception e) {
            throw new TableException(String.format("%s. %s", exMsg, e.getMessage()), e);
        }
    }

    private TableResult unloadModule(UnloadModuleOperation operation) {
        String exMsg = getDDLOpExecuteErrorMsg(operation.asSummaryString());
        try {
            moduleManager.unloadModule(operation.getModuleName());
            return TableResultImpl.TABLE_RESULT_OK;
        } catch (ValidationException e) {
            throw new ValidationException(String.format("%s. %s", exMsg, e.getMessage()), e);
        }
    }

    private TableResult useModules(UseModulesOperation operation) {
        String exMsg = getDDLOpExecuteErrorMsg(operation.asSummaryString());
        try {
            moduleManager.useModules(operation.getModuleNames().toArray(new String[0]));
            return TableResultImpl.TABLE_RESULT_OK;
        } catch (ValidationException e) {
            throw new ValidationException(String.format("%s. %s", exMsg, e.getMessage()), e);
        }
    }

    private TableResult buildShowResult(String columnName, String[] objects) {
        return buildResult(
                new String[] {columnName},
                new DataType[] {DataTypes.STRING()},
                Arrays.stream(objects).map((c) -> new String[] {c}).toArray(String[][]::new));
    }

    private String buildShowCreateTableRow(
            ResolvedCatalogBaseTable<?> table,
            ObjectIdentifier tableIdentifier,
            boolean isTemporary) {
        final String printIndent = "  ";
        CatalogBaseTable.TableKind kind = table.getTableKind();
        if (kind == CatalogBaseTable.TableKind.VIEW) {
            throw new TableException(
                    String.format(
                            "SHOW CREATE TABLE does not support showing CREATE VIEW statement with identifier %s.",
                            tableIdentifier.asSerializableString()));
        }
        StringBuilder sb =
                new StringBuilder(
                        String.format(
                                "CREATE %sTABLE %s (\n",
                                isTemporary ? "TEMPORARY " : "",
                                tableIdentifier.asSerializableString()));
        ResolvedSchema schema = table.getResolvedSchema();
        // append columns
        sb.append(
                schema.getColumns().stream()
                        .map(column -> String.format("%s%s", printIndent, getColumnString(column)))
                        .collect(Collectors.joining(",\n")));
        // append watermark spec
        if (!schema.getWatermarkSpecs().isEmpty()) {
            sb.append(",\n");
            sb.append(
                    schema.getWatermarkSpecs().stream()
                            .map(
                                    watermarkSpec ->
                                            String.format(
                                                    "%sWATERMARK FOR %s AS %s",
                                                    printIndent,
                                                    EncodingUtils.escapeIdentifier(
                                                            watermarkSpec.getRowtimeAttribute()),
                                                    watermarkSpec
                                                            .getWatermarkExpression()
                                                            .asSerializableString()))
                            .collect(Collectors.joining("\n")));
        }
        // append constraint
        if (schema.getPrimaryKey().isPresent()) {
            sb.append(",\n");
            sb.append(String.format("%s%s", printIndent, schema.getPrimaryKey().get()));
        }
        sb.append("\n) ");
        // append comment
        String comment = table.getComment();
        if (StringUtils.isNotEmpty(comment)) {
            sb.append(String.format("COMMENT '%s'\n", comment));
        }
        // append partitions
        ResolvedCatalogTable catalogTable = (ResolvedCatalogTable) table;
        if (catalogTable.isPartitioned()) {
            sb.append("PARTITIONED BY (")
                    .append(
                            catalogTable.getPartitionKeys().stream()
                                    .map(EncodingUtils::escapeIdentifier)
                                    .collect(Collectors.joining(", ")))
                    .append(")\n");
        }
        // append `with` properties
        Map<String, String> options = table.getOptions();
        sb.append("WITH (\n")
                .append(
                        options.entrySet().stream()
                                .map(
                                        entry ->
                                                String.format(
                                                        "%s'%s' = '%s'",
                                                        printIndent,
                                                        entry.getKey(),
                                                        entry.getValue()))
                                .collect(Collectors.joining(",\n")))
                .append("\n)\n");
        return sb.toString();
    }

    private String getColumnString(Column column) {
        final StringBuilder sb = new StringBuilder();
        sb.append(EncodingUtils.escapeIdentifier(column.getName()));
        sb.append(" ");
        // skip data type for computed column
        if (column instanceof Column.ComputedColumn) {
            sb.append(
                    column.explainExtras()
                            .orElseThrow(
                                    () ->
                                            new TableException(
                                                    String.format(
                                                            "Column expression can not be null for computed column '%s'",
                                                            column.getName()))));
        } else {
            sb.append(column.getDataType().getLogicalType().asSerializableString());
            column.explainExtras()
                    .ifPresent(
                            e -> {
                                sb.append(" ");
                                sb.append(e);
                            });
        }
        // TODO: Print the column comment until FLINK-18958 is fixed
        return sb.toString();
    }

    private TableResult buildShowFullModulesResult(ModuleEntry[] moduleEntries) {
        Object[][] rows =
                Arrays.stream(moduleEntries)
                        .map(entry -> new Object[] {entry.name(), entry.used()})
                        .toArray(Object[][]::new);
        return buildResult(
                new String[] {"module name", "used"},
                new DataType[] {DataTypes.STRING(), DataTypes.BOOLEAN()},
                rows);
    }

    private TableResult buildDescribeResult(ResolvedSchema schema) {
        Map<String, String> fieldToWatermark =
                schema.getWatermarkSpecs().stream()
                        .collect(
                                Collectors.toMap(
                                        WatermarkSpec::getRowtimeAttribute,
                                        spec -> spec.getWatermarkExpression().asSummaryString()));

        Map<String, String> fieldToPrimaryKey = new HashMap<>();
        schema.getPrimaryKey()
                .ifPresent(
                        (p) -> {
                            List<String> columns = p.getColumns();
                            columns.forEach(
                                    (c) ->
                                            fieldToPrimaryKey.put(
                                                    c,
                                                    String.format(
                                                            "PRI(%s)",
                                                            String.join(", ", columns))));
                        });

        Object[][] rows =
                schema.getColumns().stream()
                        .map(
                                (c) -> {
                                    final LogicalType logicalType =
                                            c.getDataType().getLogicalType();
                                    return new Object[] {
                                        c.getName(),
                                        logicalType.copy(true).asSummaryString(),
                                        logicalType.isNullable(),
                                        fieldToPrimaryKey.getOrDefault(c.getName(), null),
                                        c.explainExtras().orElse(null),
                                        fieldToWatermark.getOrDefault(c.getName(), null)
                                    };
                                })
                        .toArray(Object[][]::new);

        return buildResult(
                new String[] {"name", "type", "null", "key", "extras", "watermark"},
                new DataType[] {
                    DataTypes.STRING(),
                    DataTypes.STRING(),
                    DataTypes.BOOLEAN(),
                    DataTypes.STRING(),
                    DataTypes.STRING(),
                    DataTypes.STRING()
                },
                rows);
    }

    private TableResult buildResult(String[] headers, DataType[] types, Object[][] rows) {
        return TableResultImpl.builder()
                .resultKind(ResultKind.SUCCESS_WITH_CONTENT)
                .schema(ResolvedSchema.physical(headers, types))
                .data(Arrays.stream(rows).map(Row::of).collect(Collectors.toList()))
                .setPrintStyle(
                        TableResultImpl.PrintStyle.tableau(Integer.MAX_VALUE, "", false, false))
                .setSessionTimeZone(getConfig().getLocalTimeZone())
                .build();
    }

    /**
     * extract sink identifier names from {@link ModifyOperation}s.
     *
     * <p>If there are multiple ModifyOperations have same name, an index suffix will be added at
     * the end of the name to ensure each name is unique.
     */
    private List<String> extractSinkIdentifierNames(List<ModifyOperation> operations) {
        List<String> tableNames = new ArrayList<>(operations.size());
        Map<String, Integer> tableNameToCount = new HashMap<>();
        for (ModifyOperation operation : operations) {
            if (operation instanceof CatalogSinkModifyOperation) {
                ObjectIdentifier identifier =
                        ((CatalogSinkModifyOperation) operation).getTableIdentifier();
                String fullName = identifier.asSummaryString();
                tableNames.add(fullName);
                tableNameToCount.put(fullName, tableNameToCount.getOrDefault(fullName, 0) + 1);
            } else {
                throw new UnsupportedOperationException("Unsupported operation: " + operation);
            }
        }
        Map<String, Integer> tableNameToIndex = new HashMap<>();
        return tableNames.stream()
                .map(
                        tableName -> {
                            if (tableNameToCount.get(tableName) == 1) {
                                return tableName;
                            } else {
                                Integer index = tableNameToIndex.getOrDefault(tableName, 0) + 1;
                                tableNameToIndex.put(tableName, index);
                                return tableName + "_" + index;
                            }
                        })
                .collect(Collectors.toList());
    }

    /** Get catalog from catalogName or throw a ValidationException if the catalog not exists. */
    private Catalog getCatalogOrThrowException(String catalogName) {
        return getCatalog(catalogName)
                .orElseThrow(
                        () ->
                                new ValidationException(
                                        String.format("Catalog %s does not exist", catalogName)));
    }

    private String getDDLOpExecuteErrorMsg(String action) {
        return String.format("Could not execute %s", action);
    }

    @Override
    public String getCurrentCatalog() {
        return catalogManager.getCurrentCatalog();
    }

    @Override
    public void useCatalog(String catalogName) {
        catalogManager.setCurrentCatalog(catalogName);
    }

    @Override
    public String getCurrentDatabase() {
        return catalogManager.getCurrentDatabase();
    }

    @Override
    public void useDatabase(String databaseName) {
        catalogManager.setCurrentDatabase(databaseName);
    }

    @Override
    public TableConfig getConfig() {
        return tableConfig;
    }

    @Override
    public JobExecutionResult execute(String jobName) throws Exception {
        Pipeline pipeline =
                execEnv.createPipeline(
                        translateAndClearBuffer(), tableConfig.getConfiguration(), jobName);
        return execEnv.execute(pipeline);
    }

    @Override
    public Parser getParser() {
        return getPlanner().getParser();
    }

    @Override
    public CatalogManager getCatalogManager() {
        return catalogManager;
    }

    @Override
    public OperationTreeBuilder getOperationTreeBuilder() {
        return operationTreeBuilder;
    }

    /**
     * Subclasses can override this method to transform the given QueryOperation to a new one with
     * the qualified object identifier. This is needed for some QueryOperations, e.g.
     * JavaDataStreamQueryOperation, which doesn't know the registered identifier when created
     * ({@code fromDataStream(DataStream)}. But the identifier is required when converting this
     * QueryOperation to RelNode.
     */
    protected QueryOperation qualifyQueryOperation(
            ObjectIdentifier identifier, QueryOperation queryOperation) {
        return queryOperation;
    }

    /**
     * Subclasses can override this method to add additional checks.
     *
     * @param tableSource tableSource to validate
     */
    protected void validateTableSource(TableSource<?> tableSource) {
        TableSourceValidation.validateTableSource(tableSource, tableSource.getTableSchema());
    }

    /**
     * Translate the buffered operations to Transformations, and clear the buffer.
     *
     * <p>The buffer will be clear even if the `translate` fails. In most cases, the failure is not
     * retryable (e.g. type mismatch, can't generate physical plan). If the buffer is not clear
     * after failure, the following `translate` will also fail.
     */
    protected List<Transformation<?>> translateAndClearBuffer() {
        List<Transformation<?>> transformations;
        try {
            transformations = translate(bufferedModifyOperations);
        } finally {
            bufferedModifyOperations.clear();
        }
        return transformations;
    }

    protected List<Transformation<?>> translate(List<ModifyOperation> modifyOperations) {
        return planner.translate(modifyOperations);
    }

    private void buffer(List<ModifyOperation> modifyOperations) {
        bufferedModifyOperations.addAll(modifyOperations);
    }

    @VisibleForTesting
    protected ExplainDetail[] getExplainDetails(boolean extended) {
        if (extended) {
            if (isStreamingMode) {
                return new ExplainDetail[] {
                    ExplainDetail.ESTIMATED_COST, ExplainDetail.CHANGELOG_MODE
                };
            } else {
                return new ExplainDetail[] {ExplainDetail.ESTIMATED_COST};
            }
        } else {
            return new ExplainDetail[0];
        }
    }

    @Override
    public void registerTableSourceInternal(String name, TableSource<?> tableSource) {
        validateTableSource(tableSource);
        ObjectIdentifier objectIdentifier =
                catalogManager.qualifyIdentifier(UnresolvedIdentifier.of(name));
        Optional<CatalogBaseTable> table = getTemporaryTable(objectIdentifier);

        if (table.isPresent()) {
            if (table.get() instanceof ConnectorCatalogTable<?, ?>) {
                ConnectorCatalogTable<?, ?> sourceSinkTable =
                        (ConnectorCatalogTable<?, ?>) table.get();
                if (sourceSinkTable.getTableSource().isPresent()) {
                    throw new ValidationException(
                            String.format(
                                    "Table '%s' already exists. Please choose a different name.",
                                    name));
                } else {
                    // wrapper contains only sink (not source)
                    ConnectorCatalogTable sourceAndSink =
                            ConnectorCatalogTable.sourceAndSink(
                                    tableSource,
                                    sourceSinkTable.getTableSink().get(),
                                    !IS_STREAM_TABLE);
                    catalogManager.dropTemporaryTable(objectIdentifier, false);
                    catalogManager.createTemporaryTable(sourceAndSink, objectIdentifier, false);
                }
            } else {
                throw new ValidationException(
                        String.format(
                                "Table '%s' already exists. Please choose a different name.",
                                name));
            }
        } else {
            ConnectorCatalogTable source =
                    ConnectorCatalogTable.source(tableSource, !IS_STREAM_TABLE);
            catalogManager.createTemporaryTable(source, objectIdentifier, false);
        }
    }

    @Override
    public void registerTableSinkInternal(String name, TableSink<?> tableSink) {
        ObjectIdentifier objectIdentifier =
                catalogManager.qualifyIdentifier(UnresolvedIdentifier.of(name));
        Optional<CatalogBaseTable> table = getTemporaryTable(objectIdentifier);

        if (table.isPresent()) {
            if (table.get() instanceof ConnectorCatalogTable<?, ?>) {
                ConnectorCatalogTable<?, ?> sourceSinkTable =
                        (ConnectorCatalogTable<?, ?>) table.get();
                if (sourceSinkTable.getTableSink().isPresent()) {
                    throw new ValidationException(
                            String.format(
                                    "Table '%s' already exists. Please choose a different name.",
                                    name));
                } else {
                    // wrapper contains only sink (not source)
                    ConnectorCatalogTable sourceAndSink =
                            ConnectorCatalogTable.sourceAndSink(
                                    sourceSinkTable.getTableSource().get(),
                                    tableSink,
                                    !IS_STREAM_TABLE);
                    catalogManager.dropTemporaryTable(objectIdentifier, false);
                    catalogManager.createTemporaryTable(sourceAndSink, objectIdentifier, false);
                }
            } else {
                throw new ValidationException(
                        String.format(
                                "Table '%s' already exists. Please choose a different name.",
                                name));
            }
        } else {
            ConnectorCatalogTable sink = ConnectorCatalogTable.sink(tableSink, !IS_STREAM_TABLE);
            catalogManager.createTemporaryTable(sink, objectIdentifier, false);
        }
    }

    private Optional<CatalogBaseTable> getTemporaryTable(ObjectIdentifier identifier) {
        return catalogManager
                .getTable(identifier)
                .filter(CatalogManager.TableLookupResult::isTemporary)
                .map(CatalogManager.TableLookupResult::getTable);
    }

    private TableResult createCatalogFunction(
            CreateCatalogFunctionOperation createCatalogFunctionOperation) {
        String exMsg = getDDLOpExecuteErrorMsg(createCatalogFunctionOperation.asSummaryString());
        try {
            if (createCatalogFunctionOperation.isTemporary()) {
                functionCatalog.registerTemporaryCatalogFunction(
                        UnresolvedIdentifier.of(
                                createCatalogFunctionOperation.getFunctionIdentifier().toList()),
                        createCatalogFunctionOperation.getCatalogFunction(),
                        createCatalogFunctionOperation.isIgnoreIfExists());
            } else {
                Catalog catalog =
                        getCatalogOrThrowException(
                                createCatalogFunctionOperation
                                        .getFunctionIdentifier()
                                        .getCatalogName());
                catalog.createFunction(
                        createCatalogFunctionOperation.getFunctionIdentifier().toObjectPath(),
                        createCatalogFunctionOperation.getCatalogFunction(),
                        createCatalogFunctionOperation.isIgnoreIfExists());
            }
            return TableResultImpl.TABLE_RESULT_OK;
        } catch (ValidationException e) {
            throw e;
        } catch (FunctionAlreadyExistException e) {
            throw new ValidationException(e.getMessage(), e);
        } catch (Exception e) {
            throw new TableException(exMsg, e);
        }
    }

    private TableResult alterCatalogFunction(
            AlterCatalogFunctionOperation alterCatalogFunctionOperation) {
        String exMsg = getDDLOpExecuteErrorMsg(alterCatalogFunctionOperation.asSummaryString());
        try {
            CatalogFunction function = alterCatalogFunctionOperation.getCatalogFunction();
            if (alterCatalogFunctionOperation.isTemporary()) {
                throw new ValidationException("Alter temporary catalog function is not supported");
            } else {
                Catalog catalog =
                        getCatalogOrThrowException(
                                alterCatalogFunctionOperation
                                        .getFunctionIdentifier()
                                        .getCatalogName());
                catalog.alterFunction(
                        alterCatalogFunctionOperation.getFunctionIdentifier().toObjectPath(),
                        function,
                        alterCatalogFunctionOperation.isIfExists());
            }
            return TableResultImpl.TABLE_RESULT_OK;
        } catch (ValidationException e) {
            throw e;
        } catch (FunctionNotExistException e) {
            throw new ValidationException(e.getMessage(), e);
        } catch (Exception e) {
            throw new TableException(exMsg, e);
        }
    }

    private TableResult dropCatalogFunction(
            DropCatalogFunctionOperation dropCatalogFunctionOperation) {
        String exMsg = getDDLOpExecuteErrorMsg(dropCatalogFunctionOperation.asSummaryString());
        try {
            if (dropCatalogFunctionOperation.isTemporary()) {
                functionCatalog.dropTempCatalogFunction(
                        dropCatalogFunctionOperation.getFunctionIdentifier(),
                        dropCatalogFunctionOperation.isIfExists());
            } else {
                Catalog catalog =
                        getCatalogOrThrowException(
                                dropCatalogFunctionOperation
                                        .getFunctionIdentifier()
                                        .getCatalogName());

                catalog.dropFunction(
                        dropCatalogFunctionOperation.getFunctionIdentifier().toObjectPath(),
                        dropCatalogFunctionOperation.isIfExists());
            }
            return TableResultImpl.TABLE_RESULT_OK;
        } catch (ValidationException e) {
            throw e;
        } catch (FunctionNotExistException e) {
            throw new ValidationException(e.getMessage(), e);
        } catch (Exception e) {
            throw new TableException(exMsg, e);
        }
    }

    private TableResult createSystemFunction(CreateTempSystemFunctionOperation operation) {
        String exMsg = getDDLOpExecuteErrorMsg(operation.asSummaryString());
        try {
            functionCatalog.registerTemporarySystemFunction(
                    operation.getFunctionName(),
                    operation.getCatalogFunction(),
                    operation.isIgnoreIfExists());
            return TableResultImpl.TABLE_RESULT_OK;
        } catch (ValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new TableException(exMsg, e);
        }
    }

    private TableResult dropSystemFunction(DropTempSystemFunctionOperation operation) {
        try {
            functionCatalog.dropTemporarySystemFunction(
                    operation.getFunctionName(), operation.isIfExists());
            return TableResultImpl.TABLE_RESULT_OK;
        } catch (ValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new TableException(getDDLOpExecuteErrorMsg(operation.asSummaryString()), e);
        }
    }

    protected TableImpl createTable(QueryOperation tableOperation) {
        return TableImpl.createTable(
                this,
                tableOperation,
                operationTreeBuilder,
                functionCatalog.asLookup(getParser()::parseIdentifier));
    }

    @Override
    public String getJsonPlan(String stmt) {
        List<Operation> operations = getParser().parse(stmt);
        if (operations.size() != 1) {
            throw new TableException(
                    "Unsupported SQL query! getJsonPlan() only accepts a single INSERT statement.");
        }
        Operation operation = operations.get(0);
        List<ModifyOperation> modifyOperations = new ArrayList<>(1);
        if (operation instanceof ModifyOperation) {
            modifyOperations.add((ModifyOperation) operation);
        } else {
            throw new TableException("Only INSERT is supported now.");
        }
        return getJsonPlan(modifyOperations);
    }

    @Override
    public String getJsonPlan(List<ModifyOperation> operations) {
        return planner.getJsonPlan(operations);
    }

    @Override
    public String explainJsonPlan(String jsonPlan, ExplainDetail... extraDetails) {
        return planner.explainJsonPlan(jsonPlan, extraDetails);
    }

    @Override
    public TableResult executeJsonPlan(String jsonPlan) {
        List<Transformation<?>> transformations = planner.translateJsonPlan(jsonPlan);
        List<String> sinkIdentifierNames = new ArrayList<>();
        for (int i = 0; i < transformations.size(); ++i) {
            // TODO serialize the sink table names to json plan ?
            sinkIdentifierNames.add("sink" + i);
        }
        return executeInternal(transformations, sinkIdentifierNames);
    }
}
