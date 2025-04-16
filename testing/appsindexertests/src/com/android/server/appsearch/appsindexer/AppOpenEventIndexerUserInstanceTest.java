/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.appsearch.appsindexer;

import static com.android.server.appsearch.appsindexer.TestUtils.createIndividualUsageEvent;
import static com.android.server.appsearch.appsindexer.TestUtils.createUsageEvents;
import static com.android.server.appsearch.appsindexer.TestUtils.setupMockUsageStatsManager;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.Nullable;
import android.app.appsearch.exceptions.AppSearchException;
import android.app.appsearch.testutil.AppSearchTestUtils;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.app.usage.UsageEvents;
import android.app.usage.UsageEvents.Event;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;

import androidx.test.core.app.ApplicationProvider;

import com.android.appsearch.flags.Flags;
import com.android.server.appsearch.appsindexer.appsearchtypes.AppOpenEvent;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class AppOpenEventIndexerUserInstanceTest {
    private TestContext mContext;
    private final UsageStatsManager mMockUsageStatsManager = mock(UsageStatsManager.class);

    @Rule public TemporaryFolder mTemporaryFolder = new TemporaryFolder();
    @Rule public final RuleChain mRuleChain = AppSearchTestUtils.createCommonTestRules();

    private ExecutorService mSingleThreadedExecutor;
    private File mAppsDir;
    private AppOpenEventIndexerUserInstance mInstance;
    private AppOpenEventIndexerConfig mAppOpenEventIndexerConfig =
            new TestAppOpenEventIndexerConfig();

    class TestContext extends ContextWrapper {
        @Nullable JobScheduler mJobScheduler;

        TestContext(Context base) {
            super(base);
        }

        public void setJobScheduler(@Nullable JobScheduler jobScheduler) {
            mJobScheduler = jobScheduler;
        }

        @Override
        public Object getSystemService(String name) {
            if (name.equals(Context.USAGE_STATS_SERVICE)) {
                return mMockUsageStatsManager;
            }
            if (mJobScheduler != null && Context.JOB_SCHEDULER_SERVICE.equals(name)) {
                return mJobScheduler;
            }
            return super.getSystemService(name);
        }
    }

    @Before
    public void setUp() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();

        mContext = new TestContext(context);

        mSingleThreadedExecutor = Executors.newSingleThreadExecutor();

        // Setup the file path to the persisted data
        mAppsDir = new File(mTemporaryFolder.newFolder(), "app-open-events");
        mInstance =
                AppOpenEventIndexerUserInstance.createInstance(
                        mContext, mAppsDir, mAppOpenEventIndexerConfig, mSingleThreadedExecutor);
        TestUtils.removeFakeAppOpenEventDocuments(mContext, mSingleThreadedExecutor);
    }

    @After
    public void tearDown() throws Exception {
        mSingleThreadedExecutor.shutdown();
        mInstance.shutdown();
    }

    @Test
    public void testFirstRun_schedulesUpdate() throws Exception {
        long currentTimeMillis = System.currentTimeMillis();

        mInstance =
                AppOpenEventIndexerUserInstance.createInstance(
                        mContext, mAppsDir, mAppOpenEventIndexerConfig, mSingleThreadedExecutor);

        Event event =
                createIndividualUsageEvent(
                        UsageEvents.Event.MOVE_TO_FOREGROUND,
                        currentTimeMillis + 1000L,
                        "com.fake.package");
        UsageEvents events = createUsageEvents(event);
        CountDownLatch latch = new CountDownLatch(1);
        setupMockUsageStatsManager(mMockUsageStatsManager, events);

        mInstance.updateAsync(latch::countDown);
        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();

        AppSearchHelper appSearchHelper = new AppSearchHelper(mContext);
        AppOpenEvent appOpenEvent =
                appSearchHelper.getSubsequentAppOpenEventAfterThreshold(currentTimeMillis + 100);
        assertThat(appOpenEvent.getId())
                .isEqualTo("com.fake.package" + (currentTimeMillis + 1000L));
    }

    @Test
    public void testSecondRun_noOpOnSecondUpdate() throws Exception {
        long currentTimeMillis = System.currentTimeMillis();
        mInstance =
                AppOpenEventIndexerUserInstance.createInstance(
                        mContext, mAppsDir, mAppOpenEventIndexerConfig, mSingleThreadedExecutor);

        Event event =
                createIndividualUsageEvent(
                        UsageEvents.Event.MOVE_TO_FOREGROUND,
                        currentTimeMillis + 1000L,
                        "com.fake.package");

        UsageEvents events = createUsageEvents(event);
        CountDownLatch latch1 = new CountDownLatch(1);
        CountDownLatch latch2 = new CountDownLatch(1);

        setupMockUsageStatsManager(mMockUsageStatsManager, events);

        mInstance.updateAsync(latch1::countDown);
        assertThat(latch1.await(1, TimeUnit.SECONDS)).isTrue();

        AppSearchHelper appSearchHelper = new AppSearchHelper(mContext);
        AppOpenEvent appOpenEvent =
                appSearchHelper.getSubsequentAppOpenEventAfterThreshold(currentTimeMillis + 100);
        assertThat(appOpenEvent.getId())
                .isEqualTo("com.fake.package" + (currentTimeMillis + 1000L));

        mInstance.updateAsync(latch2::countDown);
        assertThat(latch2.await(1, TimeUnit.SECONDS)).isTrue();

        event =
                createIndividualUsageEvent(
                        UsageEvents.Event.MOVE_TO_FOREGROUND,
                        currentTimeMillis + 2000L,
                        "com.fake.package");

        events = createUsageEvents(event);
        setupMockUsageStatsManager(mMockUsageStatsManager, events);

        assertThrows(
                AppSearchException.class,
                () ->
                        appSearchHelper.getSubsequentAppOpenEventAfterThreshold(
                                currentTimeMillis + 1500L));
        AppOpenEvent appOpenEvent2 =
                appSearchHelper.getSubsequentAppOpenEventAfterThreshold(currentTimeMillis + 100L);
        assertThat(appOpenEvent2.getId())
                .isEqualTo("com.fake.package" + (currentTimeMillis + 1000L)); // Unchanged
    }

    @Test
    public void testStart_initialRun_schedulesUpdateJob() throws Exception {
        long currentTimeMillis = System.currentTimeMillis();
        JobScheduler mockJobScheduler = mock(JobScheduler.class);
        mContext.setJobScheduler(mockJobScheduler);

        mInstance =
                AppOpenEventIndexerUserInstance.createInstance(
                        mContext, mAppsDir, mAppOpenEventIndexerConfig, mSingleThreadedExecutor);

        Event event =
                createIndividualUsageEvent(
                        UsageEvents.Event.MOVE_TO_FOREGROUND,
                        currentTimeMillis + 1000L,
                        "com.fake.package");
        UsageEvents events = createUsageEvents(event);
        setupMockUsageStatsManager(mMockUsageStatsManager, events);

        CountDownLatch latch = new CountDownLatch(1);
        mInstance.updateAsync(latch::countDown);

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();

        ArgumentCaptor<JobInfo> jobInfoArgumentCaptor = ArgumentCaptor.forClass(JobInfo.class);
        verify(mockJobScheduler).schedule(jobInfoArgumentCaptor.capture());
        JobInfo updateJob = jobInfoArgumentCaptor.getValue();
        assertThat(updateJob.isRequireBatteryNotLow()).isTrue();
        assertThat(updateJob.isRequireDeviceIdle()).isTrue();
        assertThat(updateJob.isPersisted()).isTrue();
        assertThat(updateJob.isPeriodic()).isTrue();
    }

    @Test
    public void testStart_onShutdown_cancelsUpdateJob() throws Exception {
        JobScheduler mockJobScheduler = mock(JobScheduler.class);
        mContext.setJobScheduler(mockJobScheduler);

        mInstance =
                AppOpenEventIndexerUserInstance.createInstance(
                        mContext, mAppsDir, mAppOpenEventIndexerConfig, mSingleThreadedExecutor);

        mInstance.schedulePeriodicUpdate();
        ArgumentCaptor<JobInfo> jobInfoArgumentCaptor = ArgumentCaptor.forClass(JobInfo.class);
        verify(mockJobScheduler).schedule(jobInfoArgumentCaptor.capture());
        JobInfo updateJob = jobInfoArgumentCaptor.getValue();
        assertThat(updateJob.isRequireBatteryNotLow()).isTrue();
        assertThat(updateJob.isRequireDeviceIdle()).isTrue();
        assertThat(updateJob.isPersisted()).isTrue();
        assertThat(updateJob.isPeriodic()).isTrue();

        // Mock the pending job to return the job we just scheduled
        when(mockJobScheduler.getPendingJob(updateJob.getId())).thenReturn(updateJob);
        mInstance.shutdown();
        verify(mockJobScheduler).cancel(jobInfoArgumentCaptor.getValue().getId());
    }

    @RequiresFlagsDisabled(Flags.FLAG_ENABLE_APP_OPEN_EVENTS_INDEXER_CHECK_PRIOR_ATTEMPT)
    @Test
    public void testFirstRun_withoutCheckPriorAttempt_doesNotWrite() throws Exception {
        long currentTimeMillis = System.currentTimeMillis();

        mInstance =
                AppOpenEventIndexerUserInstance.createInstance(
                        mContext, mAppsDir, mAppOpenEventIndexerConfig, mSingleThreadedExecutor);

        Event event =
                createIndividualUsageEvent(
                        UsageEvents.Event.MOVE_TO_FOREGROUND,
                        currentTimeMillis + 1000L,
                        "com.fake.package");
        UsageEvents events = createUsageEvents(event);
        setupMockUsageStatsManager(mMockUsageStatsManager, events);

        CountDownLatch latch = new CountDownLatch(1);
        mInstance.updateAsync(latch::countDown);

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();

        AppOpenEventIndexerSettings settings = new AppOpenEventIndexerSettings(mAppsDir);
        settings.load();
        long lastAttemptedUpdatedTimestampMillis = settings.getLastAttemptedUpdateTimestampMillis();
        assertThat(lastAttemptedUpdatedTimestampMillis).isEqualTo(0);
    }

    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_APP_OPEN_EVENTS_INDEXER_CHECK_PRIOR_ATTEMPT)
    @Test
    public void testFirstRun_lastRunInFuture_runsSync() throws Exception {
        long currentTimeMillis = System.currentTimeMillis();
        AppOpenEventIndexerSettings settings = new AppOpenEventIndexerSettings(mAppsDir);
        settings.setLastAttemptedUpdateTimestampMillis(Long.MAX_VALUE);
        settings.persist();

        mInstance =
                AppOpenEventIndexerUserInstance.createInstance(
                        mContext, mAppsDir, mAppOpenEventIndexerConfig, mSingleThreadedExecutor);

        Event event =
                createIndividualUsageEvent(
                        UsageEvents.Event.MOVE_TO_FOREGROUND,
                        currentTimeMillis + 1000L,
                        "com.fake.package");
        UsageEvents events = createUsageEvents(event);
        setupMockUsageStatsManager(mMockUsageStatsManager, events);

        CountDownLatch latch = new CountDownLatch(1);
        mInstance.updateAsync(latch::countDown);

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();

        settings = new AppOpenEventIndexerSettings(mAppsDir);
        settings.load();
        long lastAttemptedUpdatedTimestampMillis = settings.getLastAttemptedUpdateTimestampMillis();
        // Timestamp should be set to more current value
        assertThat(lastAttemptedUpdatedTimestampMillis).isAtMost(System.currentTimeMillis());
    }

    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_APP_OPEN_EVENTS_INDEXER_CHECK_PRIOR_ATTEMPT)
    @Test
    public void testFirstRun_persistsAttemptTimestamp() throws Exception {
        long currentTimeMillis = System.currentTimeMillis();
        mInstance =
                AppOpenEventIndexerUserInstance.createInstance(
                        mContext, mAppsDir, mAppOpenEventIndexerConfig, mSingleThreadedExecutor);

        Event event =
                createIndividualUsageEvent(
                        UsageEvents.Event.MOVE_TO_FOREGROUND,
                        currentTimeMillis + 1000L,
                        "com.fake.package");
        UsageEvents events = createUsageEvents(event);
        setupMockUsageStatsManager(mMockUsageStatsManager, events);

        CountDownLatch latch = new CountDownLatch(1);
        mInstance.updateAsync(latch::countDown);

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();

        AppOpenEventIndexerSettings settings = new AppOpenEventIndexerSettings(mAppsDir);
        settings.load();
        long lastAttemptedUpdatedTimestampMillis = settings.getLastAttemptedUpdateTimestampMillis();
        assertThat(lastAttemptedUpdatedTimestampMillis).isGreaterThan(0);
    }

    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_APP_OPEN_EVENTS_INDEXER_CHECK_PRIOR_ATTEMPT)
    @Test
    public void testFirstRun_waitsForMinTime() throws Exception {
        long currentTimeMillis = System.currentTimeMillis();
        mInstance =
                AppOpenEventIndexerUserInstance.createInstance(
                        mContext, mAppsDir, mAppOpenEventIndexerConfig, mSingleThreadedExecutor);

        Event event =
                createIndividualUsageEvent(
                        UsageEvents.Event.MOVE_TO_FOREGROUND,
                        currentTimeMillis + 1000L,
                        "com.fake.package");
        UsageEvents events = createUsageEvents(event);
        setupMockUsageStatsManager(mMockUsageStatsManager, events);

        CountDownLatch latch = new CountDownLatch(1);
        mInstance.updateAsync(latch::countDown);

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();

        AppOpenEventIndexerSettings settings = new AppOpenEventIndexerSettings(mAppsDir);
        settings.load();
        long firstAttemptedUpdateTimestampMillis = settings.getLastAttemptedUpdateTimestampMillis();

        // Reset the last run timestamp to 0 to simulate what would happen if the sync fails
        settings.setLastAttemptedUpdateTimestampMillis(0);
        settings.persist();

        long secondAttemptedUpdateTimestampMillis = 0;

        // Request a bunch of updates and check timestamp after each. This will stay 0 until another
        // update runs
        while (secondAttemptedUpdateTimestampMillis == 0) {
            latch = new CountDownLatch(1);
            mInstance.updateAsync(latch::countDown);

            assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();

            settings.load();
            secondAttemptedUpdateTimestampMillis = settings.getLastAttemptedUpdateTimestampMillis();
        }

        // At this point, one of the requested firstRun updates has completed
        mSingleThreadedExecutor.shutdown();

        // Check timestamp, it should've persisted a new time that is at least
        // TestAppsIndexerConfig.getMinTimeBetweenFirstSyncsMillis greater than the first attempt
        // timestamp
        assertThat(secondAttemptedUpdateTimestampMillis)
                .isAtLeast(
                        firstAttemptedUpdateTimestampMillis
                                + new TestAppOpenEventIndexerConfig()
                                        .getMinTimeBetweenSyncsMillis());
    }
}
