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

package org.apache.flink.connector.base.source.reader;

import org.apache.flink.api.common.accumulators.ListAccumulator;
import org.apache.flink.api.common.eventtime.BoundedOutOfOrdernessWatermarks;
import org.apache.flink.api.common.eventtime.SerializableTimestampAssigner;
import org.apache.flink.api.common.eventtime.WatermarkOutput;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.connector.source.Boundedness;
import org.apache.flink.api.connector.source.Source;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.connector.base.source.reader.mocks.MockBaseSource;
import org.apache.flink.connector.base.source.reader.mocks.MockRecordEmitter;
import org.apache.flink.core.execution.JobClient;
import org.apache.flink.metrics.Gauge;
import org.apache.flink.metrics.Metric;
import org.apache.flink.metrics.groups.OperatorMetricGroup;
import org.apache.flink.runtime.metrics.MetricNames;
import org.apache.flink.runtime.testutils.InMemoryReporterRule;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.DiscardingSink;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.apache.flink.test.util.AbstractTestBase;
import org.apache.flink.testutils.junit.SharedObjects;
import org.apache.flink.testutils.junit.SharedReference;

import org.hamcrest.Matcher;
import org.junit.Rule;
import org.junit.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;

import static org.apache.flink.metrics.testutils.MetricMatchers.isCounter;
import static org.apache.flink.metrics.testutils.MetricMatchers.isGauge;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

/** IT case for the {@link Source} with a coordinator. */
public class CoordinatedSourceITCase extends AbstractTestBase {
    // since integration tests depend on wall clock time, use huge lags
    private static final long EVENTTIME_LAG = Duration.ofDays(100).toMillis();
    private static final long WATERMARK_LAG = Duration.ofDays(1).toMillis();
    private static final long EVENTTIME_EPSILON = Duration.ofDays(20).toMillis();
    // this basically is the time a build is allowed to be frozen before the test fails
    private static final long WATERMARK_EPSILON = Duration.ofHours(6).toMillis();
    @Rule public final SharedObjects sharedObjects = SharedObjects.create();
    @Rule public final InMemoryReporterRule inMemoryReporter = InMemoryReporterRule.create();

    @Test
    public void testMetrics() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        int numSplits = Math.max(1, env.getParallelism() - 2);
        env.getConfig().setAutoWatermarkInterval(1L);

        int numRecordsPerSplit = 10;
        MockBaseSource source =
                new MockBaseSource(numSplits, numRecordsPerSplit, Boundedness.BOUNDED);

        long baseTime = System.currentTimeMillis() - EVENTTIME_LAG;
        WatermarkStrategy<Integer> strategy =
                WatermarkStrategy.forGenerator(
                                context -> new EagerBoundedOutOfOrdernessWatermarks())
                        .withTimestampAssigner(new LaggingTimestampAssigner(baseTime));

        // make sure all parallel instances have processed the same amount of records before
        // validating metrics
        SharedReference<CyclicBarrier> beforeBarrier =
                sharedObjects.add(new CyclicBarrier(numSplits + 1));
        SharedReference<CyclicBarrier> afterBarrier =
                sharedObjects.add(new CyclicBarrier(numSplits + 1));
        int stopAtRecord1 = 3;
        int stopAtRecord2 = numRecordsPerSplit - 1;
        DataStream<Integer> stream =
                env.fromSource(source, strategy, "MetricTestingSource")
                        .map(
                                i -> {
                                    if (i % numRecordsPerSplit == stopAtRecord1
                                            || i % numRecordsPerSplit == stopAtRecord2) {
                                        beforeBarrier.get().await();
                                        afterBarrier.get().await();
                                    }
                                    return i;
                                });
        stream.addSink(new DiscardingSink<>());
        JobClient jobClient = env.executeAsync();

        beforeBarrier.get().await();
        assertSourceMetrics(stopAtRecord1 + 1, numRecordsPerSplit, env.getParallelism(), numSplits);
        afterBarrier.get().await();

        beforeBarrier.get().await();
        assertSourceMetrics(stopAtRecord2 + 1, numRecordsPerSplit, env.getParallelism(), numSplits);
        afterBarrier.get().await();

        jobClient.getJobExecutionResult().get();
    }

    private void assertSourceMetrics(
            long processedRecordsPerSubtask,
            long numTotalPerSubtask,
            int parallelism,
            int numSplits) {
        List<OperatorMetricGroup> groups =
                inMemoryReporter.getReporter().findOperatorMetricGroups("MetricTestingSource");
        assertThat(groups, hasSize(parallelism));

        int subtaskWithMetrics = 0;
        for (OperatorMetricGroup group : groups) {
            Map<String, Metric> metrics = inMemoryReporter.getReporter().getMetricsByGroup(group);
            // there are only 2 splits assigned; so two groups will not update metrics
            if (group.getIOMetricGroup().getNumRecordsInCounter().getCount() == 0) {
                // assert that optional metrics are not initialized when no split assigned
                assertThat(metrics.get(MetricNames.CURRENT_EMIT_EVENT_TIME_LAG), nullValue());
                assertThat(metrics.get(MetricNames.WATERMARK_LAG), nullValue());
                continue;
            }
            subtaskWithMetrics++;
            // I/O metrics
            assertThat(
                    group.getIOMetricGroup().getNumRecordsInCounter(),
                    isCounter(equalTo(processedRecordsPerSubtask)));
            assertThat(
                    group.getIOMetricGroup().getNumBytesInCounter(),
                    isCounter(
                            equalTo(
                                    processedRecordsPerSubtask
                                            * MockRecordEmitter.RECORD_SIZE_IN_BYTES)));
            // MockRecordEmitter is just incrementing errors every even record
            assertThat(
                    metrics.get(MetricNames.NUM_RECORDS_IN_ERRORS),
                    isCounter(equalTo(processedRecordsPerSubtask / 2)));
            // Timestamp assigner subtracting EVENTTIME_LAG from wall clock
            assertThat(
                    metrics.get(MetricNames.CURRENT_EMIT_EVENT_TIME_LAG),
                    isGauge(isCloseTo(EVENTTIME_LAG, EVENTTIME_EPSILON)));
            // Watermark is derived from timestamp, so it has to be in the same order of magnitude
            assertThat(
                    metrics.get(MetricNames.WATERMARK_LAG),
                    isGauge(isCloseTo(EVENTTIME_LAG, EVENTTIME_EPSILON)));
            // Calculate the additional watermark lag (on top of event time lag)
            Long watermarkLag =
                    ((Gauge<Long>) metrics.get(MetricNames.WATERMARK_LAG)).getValue()
                            - ((Gauge<Long>) metrics.get(MetricNames.CURRENT_EMIT_EVENT_TIME_LAG))
                                    .getValue();
            // That should correspond to the out-of-order boundedness
            assertThat(watermarkLag, isCloseTo(WATERMARK_LAG, WATERMARK_EPSILON));

            long pendingRecords = numTotalPerSubtask - processedRecordsPerSubtask;
            assertThat(metrics.get(MetricNames.PENDING_RECORDS), isGauge(equalTo(pendingRecords)));
            assertThat(
                    metrics.get(MetricNames.PENDING_BYTES),
                    isGauge(equalTo(pendingRecords * MockRecordEmitter.RECORD_SIZE_IN_BYTES)));
            // test is keeping source idle time metric busy with the barrier
            assertThat(metrics.get(MetricNames.SOURCE_IDLE_TIME), isGauge(equalTo(0L)));
        }
        assertThat(subtaskWithMetrics, equalTo(numSplits));
    }

    private Matcher<Long> isCloseTo(long value, long epsilon) {
        return both(greaterThan(value - epsilon)).and(lessThan(value + epsilon));
    }

    @Test
    public void testEnumeratorReaderCommunication() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        MockBaseSource source = new MockBaseSource(2, 10, Boundedness.BOUNDED);
        DataStream<Integer> stream =
                env.fromSource(source, WatermarkStrategy.noWatermarks(), "TestingSource");
        executeAndVerify(env, stream, 20);
    }

    @Test
    public void testMultipleSources() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        MockBaseSource source1 = new MockBaseSource(2, 10, Boundedness.BOUNDED);
        MockBaseSource source2 = new MockBaseSource(2, 10, 20, Boundedness.BOUNDED);
        DataStream<Integer> stream1 =
                env.fromSource(source1, WatermarkStrategy.noWatermarks(), "TestingSource1");
        DataStream<Integer> stream2 =
                env.fromSource(source2, WatermarkStrategy.noWatermarks(), "TestingSource2");
        executeAndVerify(env, stream1.union(stream2), 40);
    }

    @SuppressWarnings("serial")
    private void executeAndVerify(
            StreamExecutionEnvironment env, DataStream<Integer> stream, int numRecords)
            throws Exception {
        stream.addSink(
                new RichSinkFunction<Integer>() {
                    @Override
                    public void open(Configuration parameters) throws Exception {
                        getRuntimeContext()
                                .addAccumulator("result", new ListAccumulator<Integer>());
                    }

                    @Override
                    public void invoke(Integer value, Context context) throws Exception {
                        getRuntimeContext().getAccumulator("result").add(value);
                    }
                });
        List<Integer> result = env.execute().getAccumulatorResult("result");
        Collections.sort(result);
        assertEquals(numRecords, result.size());
        assertEquals(0, (int) result.get(0));
        assertEquals(numRecords - 1, (int) result.get(result.size() - 1));
    }

    private static class LaggingTimestampAssigner
            implements SerializableTimestampAssigner<Integer> {
        private final long baseTime;

        public LaggingTimestampAssigner(long baseTime) {
            this.baseTime = baseTime;
        }

        @Override
        public long extractTimestamp(Integer i, long ts) {
            return baseTime + i;
        }
    }

    /** Emits watermarks on each record. */
    private static class EagerBoundedOutOfOrdernessWatermarks
            extends BoundedOutOfOrdernessWatermarks<Integer> {
        public EagerBoundedOutOfOrdernessWatermarks() {
            super(Duration.ofMillis(CoordinatedSourceITCase.WATERMARK_LAG));
        }

        @Override
        public void onEvent(Integer event, long eventTimestamp, WatermarkOutput output) {
            super.onEvent(event, eventTimestamp, output);
            onPeriodicEmit(output);
        }
    }
}
