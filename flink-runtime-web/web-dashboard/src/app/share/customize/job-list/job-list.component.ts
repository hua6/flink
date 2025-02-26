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

import { ChangeDetectionStrategy, ChangeDetectorRef, Component, Input, OnDestroy, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { JobsItemInterface } from 'interfaces';
import { Observable, Subject } from 'rxjs';
import { flatMap, takeUntil } from 'rxjs/operators';
import { JobService, StatusService } from 'services';
import { isNil } from 'utils';
import { NzMessageService } from 'ng-zorro-antd/message';

@Component({
  selector: 'flink-job-list',
  templateUrl: './job-list.component.html',
  styleUrls: ['./job-list.component.less'],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class JobListComponent implements OnInit, OnDestroy {
  listOfJob: JobsItemInterface[] = [];
  isLoading = true;
  destroy$ = new Subject();
  sortName = 'start-time';
  sortValue = 'descend';
  pageSize = Infinity;
  @Input() completed = false;
  @Input() title: string;
  @Input() jobData$: Observable<JobsItemInterface[]>;

  sortStartTimeFn = (pre: JobsItemInterface, next: JobsItemInterface) => pre['start-time'] - next['start-time'];
  sortDurationFn = (pre: JobsItemInterface, next: JobsItemInterface) => pre.duration - next.duration;
  sortEndTimeFn = (pre: JobsItemInterface, next: JobsItemInterface) => pre['end-time'] - next['end-time'];
  sortStateFn = (pre: JobsItemInterface, next: JobsItemInterface) => pre.state.localeCompare(next.state);

  trackJobBy(_: number, node: JobsItemInterface) {
    return node.jid;
  }

  navigateToJob(job: JobsItemInterface) {
    if (job.state === 'INITIALIZING') {
      this.nzMessageService.info('Job detail page is not available while it is in state INITIALIZING.');
    } else {
      this.router.navigate(['job', job.jid]).then();
    }
  }

  constructor(
    private statusService: StatusService,
    private jobService: JobService,
    private nzMessageService: NzMessageService,
    private activatedRoute: ActivatedRoute,
    private cdr: ChangeDetectorRef,
    private router: Router
  ) {}

  ngOnInit() {
    if (this.activatedRoute.snapshot.data) {
      this.completed = isNil(this.activatedRoute.snapshot.data.completed)
        ? this.completed
        : this.activatedRoute.snapshot.data.completed;
      this.title = isNil(this.activatedRoute.snapshot.data.title)
        ? this.title
        : this.activatedRoute.snapshot.data.title;
    }
    this.jobData$ =
      this.jobData$ ||
      this.statusService.refresh$.pipe(
        takeUntil(this.destroy$),
        flatMap(() => this.jobService.loadJobs())
      );
    this.jobData$.subscribe(data => {
      this.isLoading = false;
      this.listOfJob = data.filter(item => item.completed === this.completed);
      this.cdr.markForCheck();
    });
  }

  ngOnDestroy() {
    this.destroy$.next();
    this.destroy$.complete();
  }
}
