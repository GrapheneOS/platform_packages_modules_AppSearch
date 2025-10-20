/*
 * Copyright (C) 2024 The Android Open Source Project
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
// TODO: b/413737868 - Remove annotation and move JobInfo-related tests to a Platform-specific test.
// @exportToGMSCore:skipFile()
package com.android.server.appsearch.appsindexer;

import static com.android.server.appsearch.appsindexer.AppIndexerVersions.APP_INDEXER_VERSION_UNKNOWN;
import static com.android.server.appsearch.appsindexer.AppIndexerVersions.CURR_APP_INDEXER_VERSION;
import static com.android.server.appsearch.appsindexer.FrameworkAppsIndexerForceUpdateConfig.KEY_APPS_INDEXER_FORCE_UPDATE_EMERGENCY_COUNTER;
import static com.android.server.appsearch.appsindexer.FrameworkAppsIndexerForceUpdateConfig.KEY_APPS_INDEXER_FORCE_UPDATE_ENABLED;
import static com.android.server.appsearch.appsindexer.TestUtils.createFakePackageInfos;
import static com.android.server.appsearch.appsindexer.TestUtils.createFakeResolveInfos;
import static com.android.server.appsearch.appsindexer.TestUtils.removeFakePackageDocuments;
import static com.android.server.appsearch.appsindexer.TestUtils.setupMockPackageManager;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.appsearch.AppSearchManager;
import android.app.appsearch.AppSearchSessionShim;
import android.app.appsearch.SearchResult;
import android.app.appsearch.SearchResultsShim;
import android.app.appsearch.SearchSpec;
import android.app.appsearch.SetSchemaRequest;
import android.app.appsearch.testutil.AppSearchSessionShimImpl;
import android.app.appsearch.testutil.AppSearchTestUtils;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.provider.DeviceConfig;

import androidx.test.core.app.ApplicationProvider;

import com.android.appsearch.flags.Flags;
import com.android.modules.utils.testing.TestableDeviceConfig;
import com.android.server.appsearch.appsindexer.appsearchtypes.MobileApplication;
import com.android.server.appsearch.indexer.FrameworkIndexerMaintenanceService;
import com.android.server.appsearch.indexer.IndexerForceUpdateConfig;
import com.android.server.appsearch.indexer.IndexerJobHandler;
import com.android.server.appsearch.indexer.PersistableBundleSettingsStore;
import com.android.server.appsearch.indexer.SettingsStore;

import com.google.common.collect.ImmutableList;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class AppsIndexerUserInstanceTest extends AppsIndexerTestBase {

    private static final Duration UPDATE_ASYNC_TIMEOUT = Duration.ofSeconds(2);

    private TestContext mTestContext;
    private final PackageManager mMockPackageManager = mock(PackageManager.class);

    @Rule public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    @Rule
    public final RuleChain mRuleChain =
            AppSearchTestUtils.createCommonTestRules()
                    .around(new TestableDeviceConfig.TestableDeviceConfigRule());

    private ThreadPoolExecutor mSingleThreadedExecutor;
    private File mAppsDir;
    private SettingsStore mSettingsStore;
    private AppsIndexerUserInstance mInstance;
    private final AppsIndexerConfig mAppsIndexerConfig = new TestAppsIndexerConfig();
    private final IndexerForceUpdateConfig mIndexerForceUpdateConfig =
            new TestAppsIndexerForceUpdateConfig();

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        Context context = ApplicationProvider.getApplicationContext();
        mTestContext = new TestContext(context);

        mSingleThreadedExecutor =
                new ThreadPoolExecutor(
                        /* corePoolSize= */ 1,
                        /* maximumPoolSize= */ 1,
                        /* KeepAliveTime= */ 0L,
                        TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<>());

        // Setup the file path to the persisted data
        mAppsDir = new File(mTemporaryFolder.newFolder(), "appsearch/apps");
        mAppsDir.mkdirs();
        mSettingsStore = new PersistableBundleSettingsStore(mAppsDir);
        mInstance =
                AppsIndexerUserInstance.createInstance(
                        mTestContext,
                        mTestContext.getTestUser(),
                        mAppsDir,
                        mAppsIndexerConfig,
                        mIndexerForceUpdateConfig,
                        mSingleThreadedExecutor);
    }

    @After
    @Override
    public void tearDown() throws Exception {
        removeFakePackageDocuments(mTestContext, Executors.newSingleThreadExecutor());
        mSingleThreadedExecutor.shutdownNow();
        mInstance.shutdown();
        super.tearDown();
    }

    @Test
    public void testFirstRun_schedulesUpdate() throws Exception {
        // This semaphore allows us to pause test execution until we're sure the tasks in
        // AppsIndexerUserInstance are finished.
        final Semaphore semaphore = new Semaphore(0);
        mSingleThreadedExecutor =
                new ThreadPoolExecutor(
                        /* corePoolSize= */ 1,
                        /* maximumPoolSize= */ 1,
                        /* KeepAliveTime= */ 0L,
                        TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<>()) {
                    @Override
                    protected void afterExecute(Runnable r, Throwable t) {
                        super.afterExecute(r, t);
                        semaphore.release();
                    }
                };
        mInstance =
                AppsIndexerUserInstance.createInstance(
                        mTestContext,
                        mTestContext.getTestUser(),
                        mAppsDir,
                        mAppsIndexerConfig,
                        mIndexerForceUpdateConfig,
                        mSingleThreadedExecutor);

        // Pretend there's one package on device
        setupMockPackageManager(
                mMockPackageManager,
                createFakePackageInfos(1),
                createFakeResolveInfos(1),
                /* appFunctionServices= */ ImmutableList.of());

        // Wait for file setup, as file setup uses the same ExecutorService.
        assertThat(semaphore.tryAcquire(UPDATE_ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .isTrue();

        mInstance.updateAsync(/* firstRun= */ true, /* isForceUpdateTriggered= */ false);
        // Wait for the task to finish
        assertThat(semaphore.tryAcquire(UPDATE_ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .isTrue();

        try (AppSearchHelper searchHelper = new AppSearchHelper(mTestContext)) {
            Map<String, MobileApplication> appsTimestampMap =
                    searchHelper.getAppsLastUpdatedTimeAndAppFunctionServiceEnabledFromAppSearch();
            assertThat(appsTimestampMap).hasSize(1);
            assertThat(appsTimestampMap.keySet()).containsExactly("com.fake.package0");
        }
    }

    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_INDEXER_FORCE_UPDATE)
    @Test
    public void testForceUpdate_schedulesJob() throws Exception {
        JobScheduler mockJobScheduler = mock(JobScheduler.class);
        mTestContext.setJobScheduler(mockJobScheduler);
        // This semaphore allows us to pause test execution until we're sure the tasks in
        // AppsIndexerUserInstance are finished.
        final Semaphore semaphore = new Semaphore(0);
        mSingleThreadedExecutor =
                new ThreadPoolExecutor(
                        /* corePoolSize= */ 1,
                        /* maximumPoolSize= */ 1,
                        /* KeepAliveTime= */ 0L,
                        TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<>()) {
                    @Override
                    protected void afterExecute(Runnable r, Throwable t) {
                        super.afterExecute(r, t);
                        semaphore.release();
                    }
                };
        IndexerForceUpdateConfig indexerForceUpdateConfig =
                new FrameworkAppsIndexerForceUpdateConfig();
        mInstance =
                AppsIndexerUserInstance.createInstance(
                        mTestContext,
                        mTestContext.getTestUser(),
                        mAppsDir,
                        mAppsIndexerConfig,
                        indexerForceUpdateConfig,
                        mSingleThreadedExecutor);

        // Pretend there's one package on device
        setupMockPackageManager(
                mMockPackageManager,
                createFakePackageInfos(1),
                createFakeResolveInfos(1),
                /* appFunctionServices= */ ImmutableList.of());

        // Wait for file setup, as file setup uses the same ExecutorService.
        assertThat(semaphore.tryAcquire(UPDATE_ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .isTrue();

        verify(mockJobScheduler, never()).schedule(any());

        CountDownLatch latch = new CountDownLatch(1);

        assertThat(mInstance.getSettings().getIndexerForceUpdateEmergencyCounter()).isEqualTo(0);

        mInstance.startAsync(latch::countDown);

        // Modify Settings and Device Configuration values to force an update
        mInstance.getSettings().setIndexerForceUpdateEmergencyCounter(0);

        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_APPSEARCH,
                KEY_APPS_INDEXER_FORCE_UPDATE_ENABLED,
                Boolean.toString(true),
                false);
        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_APPSEARCH,
                KEY_APPS_INDEXER_FORCE_UPDATE_EMERGENCY_COUNTER,
                Integer.toString(1),
                false);

        assertThat(latch.await(/* timeout */ 1, TimeUnit.SECONDS)).isTrue();

        // The executor is responsible for releasing semaphore permits. It's invoked repeatedly
        // during listener configuration: once for updates and twice for every configuration change.
        assertThat(
                        semaphore.tryAcquire(
                                /* permits */ 3,
                                UPDATE_ASYNC_TIMEOUT.toSeconds(),
                                TimeUnit.SECONDS))
                .isTrue();

        assertThat(mInstance.getSettings().getIndexerForceUpdateEmergencyCounter()).isEqualTo(1);
        ArgumentCaptor<JobInfo> jobInfoArgumentCaptor = ArgumentCaptor.forClass(JobInfo.class);
        verify(mockJobScheduler, times(1)).schedule(jobInfoArgumentCaptor.capture());

        // Assert that force update doesn't schedule a job after persisting to settings
        Mockito.clearInvocations(mockJobScheduler);
        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_APPSEARCH,
                KEY_APPS_INDEXER_FORCE_UPDATE_EMERGENCY_COUNTER,
                Integer.toString(1),
                false);
        verify(mockJobScheduler, never()).schedule(any());
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_ENABLE_ALL_PACKAGE_INDEXING_ON_INDEXER_UPDATE)
    public void testFirstRun_updateAlreadyRan_doesNotUpdate() throws Exception {
        // Pretend we already ran
        AppsIndexerSettings settings = new AppsIndexerSettings();
        mAppsDir.mkdirs();
        settings.setLastUpdateTimestampMillis(1000);
        List<Build.Partition> sortedFingerprintedPartitions =
                new ArrayList<>(Build.getFingerprintedPartitions());
        sortedFingerprintedPartitions.sort(Comparator.comparing(Build.Partition::getName));
        settings.setLastPartitionFingerprintsSortedByPartitionName(sortedFingerprintedPartitions);
        mSettingsStore.persist(settings);

        // This semaphore allows us to pause test execution until we're sure the tasks in
        // AppsIndexerUserInstance are finished.
        final Semaphore semaphore = new Semaphore(0);
        mSingleThreadedExecutor =
                new ThreadPoolExecutor(
                        /* corePoolSize= */ 1,
                        /* maximumPoolSize= */ 1,
                        /* KeepAliveTime= */ 0L,
                        TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<>()) {
                    @Override
                    protected void afterExecute(Runnable r, Throwable t) {
                        super.afterExecute(r, t);
                        semaphore.release();
                    }
                };
        mInstance =
                AppsIndexerUserInstance.createInstance(
                        mTestContext,
                        mTestContext.getTestUser(),
                        mAppsDir,
                        mAppsIndexerConfig,
                        mIndexerForceUpdateConfig,
                        mSingleThreadedExecutor);

        // Pretend there's one package on device
        setupMockPackageManager(
                mMockPackageManager,
                createFakePackageInfos(1),
                createFakeResolveInfos(1),
                /* appFunctionServices= */ ImmutableList.of());

        // Wait for file setup, as file setup uses the same ExecutorService.
        assertThat(semaphore.tryAcquire(UPDATE_ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .isTrue();

        mInstance.updateAsync(/* firstRun= */ true, /* isForceUpdateTriggered= */ false);

        // Wait for the task to finish
        assertThat(semaphore.tryAcquire(UPDATE_ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .isTrue();

        // Even though a task ran and we got 1 app ready, we requested a "firstRun" but the
        // timestamp was not 0, so nothing should've been indexed
        try (AppSearchHelper searchHelper = new AppSearchHelper(mTestContext)) {
            Map<String, MobileApplication> apps =
                    searchHelper.getAppsLastUpdatedTimeAndAppFunctionServiceEnabledFromAppSearch();
            assertThat(apps).isEmpty();
        }
    }

    @RequiresFlagsDisabled(Flags.FLAG_ENABLE_APPS_INDEXER_CHECK_PRIOR_ATTEMPT)
    @Test
    public void testFirstRun_withoutCheckPriorAttempt_doesNotWrite() throws Exception {
        // This semaphore allows us to pause test execution until we're sure the tasks in
        // AppsIndexerUserInstance are finished.
        final Semaphore semaphore = new Semaphore(0);
        mSingleThreadedExecutor =
                new ThreadPoolExecutor(
                        /* corePoolSize= */ 1,
                        /* maximumPoolSize= */ 1,
                        /* KeepAliveTime= */ 0L,
                        TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<>()) {
                    @Override
                    protected void afterExecute(Runnable r, Throwable t) {
                        super.afterExecute(r, t);
                        semaphore.release();
                    }
                };
        mInstance =
                AppsIndexerUserInstance.createInstance(
                        mTestContext,
                        mTestContext.getTestUser(),
                        mAppsDir,
                        mAppsIndexerConfig,
                        mIndexerForceUpdateConfig,
                        mSingleThreadedExecutor);

        // Pretend there's one package on device
        setupMockPackageManager(
                mMockPackageManager,
                createFakePackageInfos(1),
                createFakeResolveInfos(1),
                /* appFunctionServices= */ ImmutableList.of());

        // Wait for file setup, as file setup uses the same ExecutorService.
        assertThat(semaphore.tryAcquire(UPDATE_ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .isTrue();

        mInstance.updateAsync(/* firstRun= */ true, /* isForceUpdateTriggered= */ false);

        // Wait for the task to finish
        assertThat(semaphore.tryAcquire(UPDATE_ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .isTrue();

        AppsIndexerSettings settings = new AppsIndexerSettings();
        mSettingsStore.loadInto(settings);
        long lastAttemptedUpdatedTimestampMillis = settings.getLastAttemptedUpdateTimestampMillis();
        assertThat(lastAttemptedUpdatedTimestampMillis).isEqualTo(0);
    }

    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_APPS_INDEXER_CHECK_PRIOR_ATTEMPT)
    @Test
    public void testFirstRun_lastRunInFuture_runsSync() throws Exception {
        AppsIndexerSettings settings = new AppsIndexerSettings();
        settings.setLastAttemptedUpdateTimestampMillis(Long.MAX_VALUE);
        mSettingsStore.persist(settings);

        // This semaphore allows us to pause test execution until we're sure the tasks in
        // AppsIndexerUserInstance are finished.
        final Semaphore semaphore = new Semaphore(0);
        mSingleThreadedExecutor =
                new ThreadPoolExecutor(
                        /* corePoolSize= */ 1,
                        /* maximumPoolSize= */ 1,
                        /* KeepAliveTime= */ 0L,
                        TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<>()) {
                    @Override
                    protected void afterExecute(Runnable r, Throwable t) {
                        super.afterExecute(r, t);
                        semaphore.release();
                    }
                };
        mInstance =
                AppsIndexerUserInstance.createInstance(
                        mTestContext,
                        mTestContext.getTestUser(),
                        mAppsDir,
                        mAppsIndexerConfig,
                        mIndexerForceUpdateConfig,
                        mSingleThreadedExecutor);

        // Pretend there's one package on device
        setupMockPackageManager(
                mMockPackageManager,
                createFakePackageInfos(1),
                createFakeResolveInfos(1),
                /* appFunctionServices= */ ImmutableList.of());

        // Wait for file setup, as file setup uses the same ExecutorService.
        assertThat(semaphore.tryAcquire(UPDATE_ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .isTrue();

        mInstance.updateAsync(/* firstRun= */ true, /* isForceUpdateTriggered= */ false);
        // Wait for the task to finish
        assertThat(semaphore.tryAcquire(UPDATE_ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .isTrue();

        settings = new AppsIndexerSettings();
        mSettingsStore.loadInto(settings);
        long lastAttemptedUpdatedTimestampMillis = settings.getLastAttemptedUpdateTimestampMillis();
        // Timestamp should be set to more current value
        assertThat(lastAttemptedUpdatedTimestampMillis).isAtMost(System.currentTimeMillis());
    }

    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_APPS_INDEXER_CHECK_PRIOR_ATTEMPT)
    @Test
    public void testFirstRun_persistsAttemptTimestamp() throws Exception {
        // This semaphore allows us to pause test execution until we're sure the tasks in
        // AppsIndexerUserInstance are finished.
        final Semaphore semaphore = new Semaphore(0);
        mSingleThreadedExecutor =
                new ThreadPoolExecutor(
                        /* corePoolSize= */ 1,
                        /* maximumPoolSize= */ 1,
                        /* KeepAliveTime= */ 0L,
                        TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<>()) {
                    @Override
                    protected void afterExecute(Runnable r, Throwable t) {
                        super.afterExecute(r, t);
                        semaphore.release();
                    }
                };
        mInstance =
                AppsIndexerUserInstance.createInstance(
                        mTestContext,
                        mTestContext.getTestUser(),
                        mAppsDir,
                        mAppsIndexerConfig,
                        mIndexerForceUpdateConfig,
                        mSingleThreadedExecutor);

        // Pretend there's one package on device
        setupMockPackageManager(
                mMockPackageManager,
                createFakePackageInfos(1),
                createFakeResolveInfos(1),
                /* appFunctionServices= */ ImmutableList.of());

        // Wait for file setup, as file setup uses the same ExecutorService.
        assertThat(semaphore.tryAcquire(UPDATE_ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .isTrue();

        mInstance.updateAsync(/* firstRun= */ true, /* isForceUpdateTriggered= */ false);

        // Wait for the task to finish
        assertThat(semaphore.tryAcquire(UPDATE_ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .isTrue();

        AppsIndexerSettings settings = new AppsIndexerSettings();
        mSettingsStore.loadInto(settings);
        long lastAttemptedUpdatedTimestampMillis = settings.getLastAttemptedUpdateTimestampMillis();
        assertThat(lastAttemptedUpdatedTimestampMillis).isGreaterThan(0);
    }

    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_APPS_INDEXER_CHECK_PRIOR_ATTEMPT)
    @Test
    public void testFirstRun_waitsForMinTime() throws Exception {
        // This semaphore allows us to pause test execution until we're sure the tasks in
        // AppsIndexerUserInstance are finished.
        final Semaphore semaphore = new Semaphore(0);
        mSingleThreadedExecutor =
                new ThreadPoolExecutor(
                        /* corePoolSize= */ 1,
                        /* maximumPoolSize= */ 1,
                        /* KeepAliveTime= */ 0L,
                        TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<>()) {
                    @Override
                    protected void afterExecute(Runnable r, Throwable t) {
                        super.afterExecute(r, t);
                        semaphore.release();
                    }
                };
        mInstance =
                AppsIndexerUserInstance.createInstance(
                        mTestContext,
                        mTestContext.getTestUser(),
                        mAppsDir,
                        mAppsIndexerConfig,
                        mIndexerForceUpdateConfig,
                        mSingleThreadedExecutor);

        // Pretend there's one package on device
        setupMockPackageManager(
                mMockPackageManager,
                createFakePackageInfos(1),
                createFakeResolveInfos(1),
                /* appFunctionServices= */ ImmutableList.of());

        // Wait for file setup, as file setup uses the same ExecutorService.
        assertThat(semaphore.tryAcquire(UPDATE_ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .isTrue();

        mInstance.updateAsync(/* firstRun= */ true, /* isForceUpdateTriggered= */ false);
        // Wait for the task to finish
        assertThat(semaphore.tryAcquire(UPDATE_ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .isTrue();

        AppsIndexerSettings settings = new AppsIndexerSettings();
        mSettingsStore.loadInto(settings);
        long firstAttemptedUpdateTimestampMillis = settings.getLastAttemptedUpdateTimestampMillis();

        // Reset the last run timestamp to 0 to simulate what would happen if the sync fails
        settings.setLastAppUpdateTimestampMillis(0);
        mSettingsStore.persist(settings);

        long secondAttemptedUpdateTimestampMillis = firstAttemptedUpdateTimestampMillis;

        // Request a bunch of updates and check timestamp after each
        while (secondAttemptedUpdateTimestampMillis == firstAttemptedUpdateTimestampMillis) {
            mInstance.updateAsync(/* firstRun= */ true, /* isForceUpdateTriggered= */ false);
            assertTrue(semaphore.tryAcquire(100L, TimeUnit.MILLISECONDS));
            mSettingsStore.loadInto(settings);
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
                                + new TestAppsIndexerConfig().getMinTimeBetweenFirstSyncsMillis());
    }

    @Test
    public void testFirstRun_withOtaUpdate_updateAlreadyRan_indexesApp() throws Exception {
        // Pretend we already ran with no fingerprints set.
        AppsIndexerSettings settings = new AppsIndexerSettings();
        mAppsDir.mkdirs();
        settings.setLastUpdateTimestampMillis(1000);
        settings.setPreviousIndexerVersionCode(CURR_APP_INDEXER_VERSION);
        mSettingsStore.persist(settings);

        // This semaphore allows us to pause test execution until we're sure the tasks in
        // AppsIndexerUserInstance are finished.
        final Semaphore semaphore = new Semaphore(0);
        mSingleThreadedExecutor =
                new ThreadPoolExecutor(
                        /* corePoolSize= */ 1,
                        /* maximumPoolSize= */ 1,
                        /* KeepAliveTime= */ 0L,
                        TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<>()) {
                    @Override
                    protected void afterExecute(Runnable r, Throwable t) {
                        super.afterExecute(r, t);
                        semaphore.release();
                    }
                };
        mInstance =
                AppsIndexerUserInstance.createInstance(
                        mTestContext,
                        mTestContext.getTestUser(),
                        mAppsDir,
                        mAppsIndexerConfig,
                        mIndexerForceUpdateConfig,
                        mSingleThreadedExecutor);

        // Pretend there's one package on device
        setupMockPackageManager(
                mMockPackageManager,
                createFakePackageInfos(1),
                createFakeResolveInfos(1),
                /* appFunctionServices= */ ImmutableList.of());

        // Wait for settings file initialization as it uses the same executor service.
        assertThat(semaphore.tryAcquire(UPDATE_ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .isTrue();

        mInstance.updateAsync(/* firstRun= */ true, /* isForceUpdateTriggered= */ false);
        // Wait for the task to finish
        assertThat(semaphore.tryAcquire(UPDATE_ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .isTrue();

        try (AppSearchHelper searchHelper = new AppSearchHelper(mTestContext)) {
            Map<String, MobileApplication> appsTimestampMap =
                    searchHelper.getAppsLastUpdatedTimeAndAppFunctionServiceEnabledFromAppSearch();
            assertThat(appsTimestampMap.keySet()).containsExactly("com.fake.package0");
        }
        // Last joined partition fingerprint is updated in settings.
        AppsIndexerSettings currSettings = new AppsIndexerSettings();
        mSettingsStore.loadInto(currSettings);
        assertThat(Arrays.asList(currSettings.getLastPartitionFingerprints()))
                .containsExactlyElementsIn(
                        Build.getFingerprintedPartitions().stream()
                                .map(partition -> partition.getFingerprint())
                                .toArray());
    }

    @Test
    public void testFirstRun_noOtaUpdate_updateAlreadyRan_doesNotIndex() throws Exception {
        // Pretend we already ran with the current partition fingerprints.
        AppsIndexerSettings settings = new AppsIndexerSettings();
        mAppsDir.mkdirs();
        settings.setLastUpdateTimestampMillis(1000);
        List<Build.Partition> sortedFingerprintedPartitions =
                new ArrayList<>(Build.getFingerprintedPartitions());
        sortedFingerprintedPartitions.sort(Comparator.comparing(Build.Partition::getName));
        settings.setLastPartitionFingerprintsSortedByPartitionName(sortedFingerprintedPartitions);
        settings.setPreviousIndexerVersionCode(CURR_APP_INDEXER_VERSION);
        mSettingsStore.persist(settings);

        // This semaphore allows us to pause test execution until we're sure the tasks in
        // AppsIndexerUserInstance are finished.
        final Semaphore semaphore = new Semaphore(0);
        mSingleThreadedExecutor =
                new ThreadPoolExecutor(
                        /* corePoolSize= */ 1,
                        /* maximumPoolSize= */ 1,
                        /* KeepAliveTime= */ 0L,
                        TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<>()) {
                    @Override
                    protected void afterExecute(Runnable r, Throwable t) {
                        super.afterExecute(r, t);
                        semaphore.release();
                    }
                };
        mInstance =
                AppsIndexerUserInstance.createInstance(
                        mTestContext,
                        mTestContext.getTestUser(),
                        mAppsDir,
                        mAppsIndexerConfig,
                        mIndexerForceUpdateConfig,
                        mSingleThreadedExecutor);

        // Pretend there's one package on device
        setupMockPackageManager(
                mMockPackageManager,
                createFakePackageInfos(1),
                createFakeResolveInfos(1),
                /* appFunctionServices= */ ImmutableList.of());

        // Wait for settings file initialization as it uses the same executor service.
        assertThat(semaphore.tryAcquire(UPDATE_ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .isTrue();

        mInstance.updateAsync(/* firstRun= */ true, /* isForceUpdateTriggered= */ false);

        // Wait for the task to finish
        assertThat(semaphore.tryAcquire(UPDATE_ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .isTrue();

        // Even though a task ran and we got 1 app ready, we requested a "firstRun" but the
        // fingerprint string didn't change, so nothing should've been indexed
        try (AppSearchHelper searchHelper = new AppSearchHelper(mTestContext)) {
            Map<String, MobileApplication> apps =
                    searchHelper.getAppsLastUpdatedTimeAndAppFunctionServiceEnabledFromAppSearch();
            assertThat(apps).isEmpty();
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_ALL_PACKAGE_INDEXING_ON_INDEXER_UPDATE)
    public void testFirstRun_withIndexerUpdate_updateAlreadyRan_indexesApp() throws Exception {
        // Pretend we already ran with a old indexer version.
        AppsIndexerSettings settings = new AppsIndexerSettings();
        mAppsDir.mkdirs();
        settings.setLastUpdateTimestampMillis(1000);
        settings.setPreviousIndexerVersionCode(APP_INDEXER_VERSION_UNKNOWN);
        mSettingsStore.persist(settings);

        // This semaphore allows us to pause test execution until we're sure the tasks in
        // AppsIndexerUserInstance are finished.
        final Semaphore semaphore = new Semaphore(0);
        mSingleThreadedExecutor =
                new ThreadPoolExecutor(
                        /* corePoolSize= */ 1,
                        /* maximumPoolSize= */ 1,
                        /* KeepAliveTime= */ 0L,
                        TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<>()) {
                    @Override
                    protected void afterExecute(Runnable r, Throwable t) {
                        super.afterExecute(r, t);
                        semaphore.release();
                    }
                };
        mInstance =
                AppsIndexerUserInstance.createInstance(
                        mTestContext,
                        mTestContext.getTestUser(),
                        mAppsDir,
                        mAppsIndexerConfig,
                        mIndexerForceUpdateConfig,
                        mSingleThreadedExecutor);

        // Pretend there's one package on device
        setupMockPackageManager(
                mMockPackageManager,
                createFakePackageInfos(1),
                createFakeResolveInfos(1),
                /* appFunctionServices= */ ImmutableList.of());

        // Wait for file setup, as file setup uses the same ExecutorService.
        assertThat(semaphore.tryAcquire(UPDATE_ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .isTrue();

        mInstance.updateAsync(/* firstRun= */ true, /* isForceUpdateTriggered= */ false);

        // Wait for the task to finish
        assertThat(semaphore.tryAcquire(UPDATE_ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .isTrue();

        try (AppSearchHelper searchHelper = new AppSearchHelper(mTestContext)) {
            Map<String, MobileApplication> appsTimestampMap =
                    searchHelper.getAppsLastUpdatedTimeAndAppFunctionServiceEnabledFromAppSearch();
            assertThat(appsTimestampMap).hasSize(1);
            assertThat(appsTimestampMap.keySet()).containsExactly("com.fake.package0");
        }
        // Previous indexer version is updated in settings.
        AppsIndexerSettings currSettings = new AppsIndexerSettings();
        mSettingsStore.loadInto(currSettings);
        assertThat(currSettings.getPreviousIndexerVersionCode())
                .isEqualTo((long) CURR_APP_INDEXER_VERSION);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_ALL_PACKAGE_INDEXING_ON_INDEXER_UPDATE)
    public void testFirstRun_noIndexerUpdate_updateAlreadyRan_doesNotUpdate() throws Exception {
        // Pretend we already ran
        AppsIndexerSettings settings = new AppsIndexerSettings();
        mAppsDir.mkdirs();
        settings.setLastUpdateTimestampMillis(1000);
        List<Build.Partition> sortedFingerprintedPartitions =
                new ArrayList<>(Build.getFingerprintedPartitions());
        sortedFingerprintedPartitions.sort(Comparator.comparing(Build.Partition::getName));
        settings.setLastPartitionFingerprintsSortedByPartitionName(sortedFingerprintedPartitions);
        settings.setPreviousIndexerVersionCode(CURR_APP_INDEXER_VERSION);
        mSettingsStore.persist(settings);

        // This semaphore allows us to pause test execution until we're sure the tasks in
        // AppsIndexerUserInstance are finished.
        final Semaphore semaphore = new Semaphore(0);
        mSingleThreadedExecutor =
                new ThreadPoolExecutor(
                        /* corePoolSize= */ 1,
                        /* maximumPoolSize= */ 1,
                        /* KeepAliveTime= */ 0L,
                        TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<>()) {
                    @Override
                    protected void afterExecute(Runnable r, Throwable t) {
                        super.afterExecute(r, t);
                        semaphore.release();
                    }
                };
        mInstance =
                AppsIndexerUserInstance.createInstance(
                        mTestContext,
                        mTestContext.getTestUser(),
                        mAppsDir,
                        mAppsIndexerConfig,
                        mIndexerForceUpdateConfig,
                        mSingleThreadedExecutor);

        // Pretend there's one package on device
        setupMockPackageManager(
                mMockPackageManager,
                createFakePackageInfos(1),
                createFakeResolveInfos(1),
                /* appFunctionServices= */ ImmutableList.of());

        // Wait for file setup, as file setup uses the same ExecutorService.
        assertThat(semaphore.tryAcquire(UPDATE_ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .isTrue();

        mInstance.updateAsync(/* firstRun= */ true, /* isForceUpdateTriggered= */ false);

        // Wait for the task to finish
        assertThat(semaphore.tryAcquire(UPDATE_ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .isTrue();

        // Even though a task ran and we got 1 app ready, we requested a "firstRun" but the
        // timestamp was not 0, so nothing should've been indexed
        try (AppSearchHelper searchHelper = new AppSearchHelper(mTestContext)) {
            Map<String, MobileApplication> apps =
                    searchHelper.getAppsLastUpdatedTimeAndAppFunctionServiceEnabledFromAppSearch();
            assertThat(apps).isEmpty();
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_APPS_INDEXER_LOCALE_CHANGE_FULL_UPDATE)
    public void testSync_localeChange_triggersFullUpdate() throws Exception {
        final Semaphore semaphore = new Semaphore(0);
        mSingleThreadedExecutor =
                new ThreadPoolExecutor(
                        /* corePoolSize= */ 1,
                        /* maximumPoolSize= */ 1,
                        /* KeepAliveTime= */ 0L,
                        TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<>()) {
                    @Override
                    protected void afterExecute(Runnable r, Throwable t) {
                        super.afterExecute(r, t);
                        semaphore.release();
                    }
                };

        // Set initial locale in settings
        AppsIndexerSettings settings = new AppsIndexerSettings();
        settings.setPreviousLocaleCode("en");
        mSettingsStore.persist(settings);

        mInstance =
                AppsIndexerUserInstance.createInstance(
                        mTestContext,
                        mTestContext.getTestUser(),
                        mAppsDir,
                        mAppsIndexerConfig,
                        mIndexerForceUpdateConfig,
                        mSingleThreadedExecutor);

        // Wait for file setup
        assertThat(semaphore.tryAcquire(UPDATE_ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .isTrue();

        // Setup package manager with 1 app
        setupMockPackageManager(
                mMockPackageManager,
                createFakePackageInfos(1),
                createFakeResolveInfos(1),
                /* appFunctionServices= */ ImmutableList.of());

        // Initial index run
        mInstance.updateAsync(/* firstRun= */ false, /* isForceUpdateTriggered= */ false);
        assertThat(semaphore.tryAcquire(UPDATE_ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .isTrue();

        AppSearchManager.SearchContext searchContext =
                new AppSearchManager.SearchContext.Builder(AppSearchHelper.APP_DATABASE).build();
        AppSearchSessionShim db =
                AppSearchSessionShimImpl.createSearchSessionAsync(searchContext).get();

        SearchResultsShim sr = db.search("", new SearchSpec.Builder().build());
        List<SearchResult> results = sr.getNextPageAsync().get();
        assertThat(results.size()).isEqualTo(1);

        String originalDisplayName =
                results.get(0)
                        .getGenericDocument()
                        .getPropertyString(MobileApplication.APP_PROPERTY_ALTERNATE_NAMES);

        // Update the display name of the package
        String updatedDisplayName = "Le label";
        when(mMockPackageManager.getApplicationLabel(any())).thenReturn(updatedDisplayName);

        // Run updateAsync again. This should not re-index the app.
        mInstance.updateAsync(false, false);
        assertThat(semaphore.tryAcquire(UPDATE_ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .isTrue();
        sr = db.search("", new SearchSpec.Builder().build());
        results = sr.getNextPageAsync().get();
        assertThat(results.size()).isEqualTo(1);

        // Display name did not change
        assertThat(
                        results.get(0)
                                .getGenericDocument()
                                .getPropertyString(MobileApplication.APP_PROPERTY_ALTERNATE_NAMES))
                .isEqualTo(originalDisplayName);

        // Simulate Locale Change to fr-FR
        mTestContext.setLocale(new Locale("fr", "FR"));

        // Now it will update if we run updateAsync
        mInstance.updateAsync(false, false);

        // Wait for the update
        assertThat(semaphore.tryAcquire(UPDATE_ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .isTrue();

        // Verify the app is re-indexed due to locale change forced update
        sr = db.search("", new SearchSpec.Builder().build());
        results = sr.getNextPageAsync().get();
        assertThat(results.size()).isEqualTo(1);
        assertThat(
                        results.get(0)
                                .getGenericDocument()
                                .getPropertyString(MobileApplication.APP_PROPERTY_ALTERNATE_NAMES))
                .isEqualTo(updatedDisplayName);

        // Verify settings are updated with the new locale
        mSettingsStore.loadInto(settings);
        assertThat(settings.getPreviousLocaleCode()).isEqualTo("fr");
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_APPS_INDEXER_LOCALE_CHANGE_FULL_UPDATE)
    public void testSync_previousLocaleNull_noFullUpdate() throws Exception {
        final Semaphore semaphore = new Semaphore(0);
        mSingleThreadedExecutor =
                new ThreadPoolExecutor(
                        /* corePoolSize= */ 1,
                        /* maximumPoolSize= */ 1,
                        /* KeepAliveTime= */ 0L,
                        TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<>()) {
                    @Override
                    protected void afterExecute(Runnable r, Throwable t) {
                        super.afterExecute(r, t);
                        semaphore.release();
                    }
                };

        // Do not set initial locale in settings, only in the test context
        mTestContext.setLocale(new Locale("en", "US"));

        mInstance =
                AppsIndexerUserInstance.createInstance(
                        mTestContext,
                        mTestContext.getTestUser(),
                        mAppsDir,
                        mAppsIndexerConfig,
                        mIndexerForceUpdateConfig,
                        mSingleThreadedExecutor);

        // Wait for file setup
        assertThat(semaphore.tryAcquire(UPDATE_ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .isTrue();

        // Setup package manager with 1 app
        setupMockPackageManager(
                mMockPackageManager,
                createFakePackageInfos(1),
                createFakeResolveInfos(1),
                /* appFunctionServices= */ ImmutableList.of());

        // Initial index run
        mInstance.updateAsync(/* firstRun= */ false, /* isForceUpdateTriggered= */ false);
        assertThat(semaphore.tryAcquire(UPDATE_ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .isTrue();

        // Locale settings should be now set
        AppsIndexerSettings settings = new AppsIndexerSettings();
        mSettingsStore.loadInto(settings);
        assertThat(settings.getPreviousLocaleCode()).isEqualTo("en");

        AppSearchManager.SearchContext searchContext =
                new AppSearchManager.SearchContext.Builder(AppSearchHelper.APP_DATABASE).build();
        AppSearchSessionShim db =
                AppSearchSessionShimImpl.createSearchSessionAsync(searchContext).get();

        SearchResultsShim sr = db.search("", new SearchSpec.Builder().build());
        List<SearchResult> results = sr.getNextPageAsync().get();
        assertThat(results.size()).isEqualTo(1);

        String originalDisplayName =
                results.get(0)
                        .getGenericDocument()
                        .getPropertyString(MobileApplication.APP_PROPERTY_ALTERNATE_NAMES);

        // Update the display name of the package
        String updatedDisplayName = "Le label";
        when(mMockPackageManager.getApplicationLabel(any())).thenReturn(updatedDisplayName);

        // Clear settings. The next run should not re-index because the last locale is null
        settings.reset();
        // Set the indexer version mode to prevent full update for that reason.
        List<Build.Partition> sortedFingerprintedPartitions =
                new ArrayList<>(Build.getFingerprintedPartitions());
        sortedFingerprintedPartitions.sort(Comparator.comparing(Build.Partition::getName));
        settings.setLastPartitionFingerprintsSortedByPartitionName(sortedFingerprintedPartitions);
        settings.setPreviousIndexerVersionCode(CURR_APP_INDEXER_VERSION);
        mSettingsStore.persist(settings);
        // Recreate the instance to pick up settings change
        mInstance =
                AppsIndexerUserInstance.createInstance(
                        mTestContext,
                        mTestContext.getTestUser(),
                        mAppsDir,
                        mAppsIndexerConfig,
                        mIndexerForceUpdateConfig,
                        mSingleThreadedExecutor);

        // Wait for file setup
        assertThat(semaphore.tryAcquire(UPDATE_ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .isTrue();

        mInstance.updateAsync(false, false);
        assertThat(semaphore.tryAcquire(UPDATE_ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .isTrue();
        sr = db.search("", new SearchSpec.Builder().build());
        results = sr.getNextPageAsync().get();
        assertThat(results.size()).isEqualTo(1);

        // Display name did not change
        assertThat(
                        results.get(0)
                                .getGenericDocument()
                                .getPropertyString(MobileApplication.APP_PROPERTY_ALTERNATE_NAMES))
                .isEqualTo(originalDisplayName);

        // Simulate Locale Change to fr-FR
        mTestContext.setLocale(new Locale("fr", "FR"));

        // Now it will update if we run updateAsync
        mInstance.updateAsync(false, false);

        // Wait for the update
        assertThat(semaphore.tryAcquire(UPDATE_ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .isTrue();

        // Verify the app is re-indexed due to locale change forced update
        sr = db.search("", new SearchSpec.Builder().build());
        results = sr.getNextPageAsync().get();
        assertThat(results.size()).isEqualTo(1);
        assertThat(
                        results.get(0)
                                .getGenericDocument()
                                .getPropertyString(MobileApplication.APP_PROPERTY_ALTERNATE_NAMES))
                .isEqualTo(updatedDisplayName);

        // Verify settings are updated with the new locale
        mSettingsStore.loadInto(settings);
        assertThat(settings.getPreviousLocaleCode()).isEqualTo("fr");
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_ALL_PACKAGE_INDEXING_ON_INDEXER_UPDATE)
    public void testSubsequentRun_withIndexerUpdate_previouslyIndexedAppIsReIndexed()
            throws Exception {
        mInstance =
                AppsIndexerUserInstance.createInstance(
                        mTestContext,
                        mTestContext.getTestUser(),
                        mAppsDir,
                        mAppsIndexerConfig,
                        mIndexerForceUpdateConfig,
                        mSingleThreadedExecutor);
        // Pretend there's one package on device
        setupMockPackageManager(
                mMockPackageManager,
                createFakePackageInfos(1),
                createFakeResolveInfos(1),
                /* appFunctionServices= */ ImmutableList.of());
        AppsUpdateStats stats = new AppsUpdateStats();
        mInstance.doUpdate(/* firstRun= */ true, stats);
        assertThat(stats.mNumberOfAppsAdded).isEqualTo(1);

        // Pretend indexer version is updated
        AppsIndexerSettings settings = new AppsIndexerSettings();
        settings.setPreviousIndexerVersionCode(APP_INDEXER_VERSION_UNKNOWN);
        mSettingsStore.persist(settings);
        // Create new instance that uses the updated settings.
        mInstance =
                AppsIndexerUserInstance.createInstance(
                        mTestContext,
                        mTestContext.getTestUser(),
                        mAppsDir,
                        mAppsIndexerConfig,
                        mIndexerForceUpdateConfig,
                        mSingleThreadedExecutor);

        // Run indexer again
        AppsUpdateStats stats1 = new AppsUpdateStats();
        mInstance.doUpdate(/* firstRun= */ false, stats1);

        // App is re-indexed.
        assertThat(stats1.mNumberOfAppsUpdated).isEqualTo(1);
    }

    @Test
    public void testHandleMultipleNotifications_onlyOneUpdateCanBeScheduledAndRun()
            throws Exception {
        // This semaphore allows us to make sure that a sync has finished running before performing
        // checks.
        final Semaphore afterSemaphore = new Semaphore(0);
        // This semaphore is released when the modified context calls getPackageManager, which is
        // part of the sync. By waiting to acquire this in the test thread, we can ensure that we
        // end up in the middle of the sync operation
        final Semaphore midSyncSemaphoreA = new Semaphore(0);
        // This semaphore blocks getPackageManager in the modified context, and continues when the
        // test thread releases this semaphore. In the test thread, by waiting for
        // midSyncSemaphoreA, running test code, then releasing midSyncSemaphoreB, we can guarantee
        // that the test code runs in the middle of a sync, no timing required.
        final Semaphore midSyncSemaphoreB = new Semaphore(0);
        mSingleThreadedExecutor =
                new ThreadPoolExecutor(
                        /* corePoolSize= */ 1,
                        /* maximumPoolSize= */ 1,
                        /* KeepAliveTime= */ 0L,
                        TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<>()) {
                    @Override
                    protected void afterExecute(Runnable r, Throwable t) {
                        super.afterExecute(r, t);
                        afterSemaphore.release();
                    }
                };

        // We need to pause this mid-update so that we can schedule updates mid-update. We can do so
        // by using a semaphore when we get package manager
        Context pauseContext =
                new TestContext(ApplicationProvider.getApplicationContext()) {
                    @Override
                    public PackageManager getPackageManager() {
                        // Pause here with semaphore
                        try {
                            midSyncSemaphoreA.release();
                            midSyncSemaphoreB.acquire();
                        } catch (InterruptedException ignored) {
                        }
                        return mMockPackageManager;
                    }
                };

        mInstance =
                AppsIndexerUserInstance.createInstance(
                        pauseContext,
                        ((TestContext) pauseContext).getTestUser(),
                        mAppsDir,
                        mAppsIndexerConfig,
                        mIndexerForceUpdateConfig,
                        mSingleThreadedExecutor);
        // Wait for file setup, as file setup uses the same ExecutorService.
        afterSemaphore.acquire();

        int numOfNotifications = 20;
        setupMockPackageManager(
                mMockPackageManager,
                createFakePackageInfos(numOfNotifications / 10),
                createFakeResolveInfos(numOfNotifications / 10),
                /* appFunctionServices= */ ImmutableList.of());

        // Schedule a bunch of tasks. However, only one will run, and one other will be scheduled
        for (int i = 0; i < numOfNotifications / 2; i++) {
            // This will pretend to add apps repeatedly
            mInstance.updateAsync(/* firstRun= */ false, /* isForceUpdateTriggered= */ false);
        }

        // Now, we wait for getPackageManager to be called
        midSyncSemaphoreA.acquire();

        // We are now in the middle of the sync. The thread should be currently handling one sync.
        // And the other (we allow two) should be scheduled.

        // Settings task + current sync + scheduled second sync = 3
        assertThat(mSingleThreadedExecutor.getTaskCount()).isEqualTo(3);
        assertThat(mSingleThreadedExecutor.getActiveCount()).isEqualTo(1);
        // Settings task
        assertThat(mSingleThreadedExecutor.getCompletedTaskCount()).isEqualTo(1);

        // Schedule even more sync
        setupMockPackageManager(
                mMockPackageManager,
                createFakePackageInfos(numOfNotifications),
                createFakeResolveInfos(numOfNotifications),
                /* appFunctionServices= */ ImmutableList.of());
        for (int i = numOfNotifications / 2; i < numOfNotifications; i++) {
            mInstance.updateAsync(/* firstRun= */ false, /* isForceUpdateTriggered= */ false);
        }

        // Now we allow syncing to continue
        midSyncSemaphoreB.release();

        // Wait for the first sync to finish
        afterSemaphore.acquire();

        // The call to getCompletedTaskCount can be flaky due to the fact that getCompletedTaskCount
        // relies on a count that is updated a little bit AFTER afterExecute is called, which is
        // where the semaphore is released. See ThreadPoolExecutor#runWorker
        while (mSingleThreadedExecutor.getCompletedTaskCount() != 2) {
            Thread.sleep(100);
        }

        assertThat(mSingleThreadedExecutor.getCompletedTaskCount()).isEqualTo(2);

        // Wait for the second sync to finish
        midSyncSemaphoreB.release();
        afterSemaphore.acquire();

        // Only two updates ran even though many were scheduled
        while (mSingleThreadedExecutor.getCompletedTaskCount() != 3) {
            Thread.sleep(100);
        }
        assertThat(mSingleThreadedExecutor.getCompletedTaskCount()).isEqualTo(3);
        assertThat(mSingleThreadedExecutor.getActiveCount()).isEqualTo(0);

        // Just to be sure
        midSyncSemaphoreB.release(numOfNotifications);
        afterSemaphore.release(numOfNotifications);

        assertThat(mSingleThreadedExecutor.getActiveCount()).isEqualTo(0);
        assertThat(mSingleThreadedExecutor.getCompletedTaskCount()).isEqualTo(3);
    }

    @Test
    public void testCreateInstance_dataDirectoryCreatedAsynchronously() throws Exception {
        File dataDir = new File(mTemporaryFolder.newFolder(), "apps");
        boolean isDataDirectoryCreatedSynchronously =
                mSingleThreadedExecutor
                        .submit(
                                () -> {
                                    AppsIndexerUserInstance unused =
                                            AppsIndexerUserInstance.createInstance(
                                                    mTestContext,
                                                    mTestContext.getTestUser(),
                                                    dataDir,
                                                    mAppsIndexerConfig,
                                                    mIndexerForceUpdateConfig,
                                                    mSingleThreadedExecutor);
                                    // Data directory shouldn't have been created synchronously in
                                    // createInstance()
                                    return dataDir.exists();
                                })
                        .get();
        assertThat(isDataDirectoryCreatedSynchronously).isFalse();
        boolean isDataDirectoryCreatedAsynchronously =
                mSingleThreadedExecutor.submit(dataDir::exists).get();
        assertThat(isDataDirectoryCreatedAsynchronously).isTrue();
    }

    @Test
    public void testUpdate() throws Exception {
        int docCount = 500;
        setupMockPackageManager(
                mMockPackageManager,
                createFakePackageInfos(docCount),
                createFakeResolveInfos(docCount),
                /* appFunctionServices= */ ImmutableList.of());
        CountDownLatch latch = setupLatch(docCount);

        mInstance.doUpdate(/* firstRun= */ false, new AppsUpdateStats());
        latch.await(10, TimeUnit.SECONDS);

        AppSearchHelper searchHelper = new AppSearchHelper(mTestContext);
        Map<String, MobileApplication> appIds =
                searchHelper.getAppsLastUpdatedTimeAndAppFunctionServiceEnabledFromAppSearch();
        assertThat(appIds.size()).isEqualTo(docCount);
    }

    @Test
    public void testUpdate_setsLastAppUpdatedTimestamp() throws Exception {
        int docCount = 10;
        setupMockPackageManager(
                mMockPackageManager,
                createFakePackageInfos(docCount),
                createFakeResolveInfos(docCount),
                /* appFunctionServices= */ ImmutableList.of());
        mInstance.doUpdate(/* firstRun= */ false, new AppsUpdateStats());

        AppsIndexerSettings settings = new AppsIndexerSettings();
        mSettingsStore.loadInto(settings);
        // The tenth document will have a timestamp of 9 as it is 0-indexed
        assertThat(settings.getLastAppUpdateTimestampMillis()).isEqualTo(9);
    }

    @Test
    public void testUpdate_insertedAndDeletedApps() throws Exception {
        long timeBeforeChangeNotification = System.currentTimeMillis();
        // Don't want to get this confused with real indexed packages.

        // We can't actually install 10 apps here, then delete four them. So what we do is pretend
        // to install 10 apps, run the indexer, then pretend there's only 6 apps, and run the
        // indexer again. The indexer should create 10 MobileApplication documents, then remove four
        // of them when we "remove" four apps.

        setupMockPackageManager(
                mMockPackageManager,
                createFakePackageInfos(10),
                createFakeResolveInfos(10),
                /* appFunctionServices= */ ImmutableList.of());

        mInstance.doUpdate(/* firstRun= */ false, new AppsUpdateStats());

        AppSearchHelper searchHelper = new AppSearchHelper(mTestContext);
        Map<String, MobileApplication> appIds =
                searchHelper.getAppsLastUpdatedTimeAndAppFunctionServiceEnabledFromAppSearch();
        assertThat(appIds.size()).isEqualTo(10);

        setupMockPackageManager(
                mMockPackageManager,
                createFakePackageInfos(6),
                createFakeResolveInfos(6),
                /* appFunctionServices= */ ImmutableList.of());

        mInstance.doUpdate(/* firstRun= */ false, new AppsUpdateStats());

        searchHelper = new AppSearchHelper(mTestContext);
        appIds = searchHelper.getAppsLastUpdatedTimeAndAppFunctionServiceEnabledFromAppSearch();
        assertThat(appIds.size()).isEqualTo(6);
        assertThat(appIds.keySet())
                .containsNoneOf(
                        TestUtils.FAKE_PACKAGE_PREFIX + "6",
                        TestUtils.FAKE_PACKAGE_PREFIX + "7",
                        TestUtils.FAKE_PACKAGE_PREFIX + "8",
                        TestUtils.FAKE_PACKAGE_PREFIX + "9");

        AppsIndexerSettings settings = mInstance.getSettings();
        assertThat(settings.getLastUpdateTimestampMillis()).isAtLeast(timeBeforeChangeNotification);

        // The last updated app was still the "9" app
        assertThat(settings.getLastAppUpdateTimestampMillis()).isEqualTo(9);
    }

    @Test
    public void testStart_initialRun_schedulesUpdateJob() throws Exception {
        JobScheduler mockJobScheduler = mock(JobScheduler.class);
        mTestContext.setJobScheduler(mockJobScheduler);
        // This semaphore allows us to make sure that a sync has finished running before performing
        // checks.
        final Semaphore afterSemaphore = new Semaphore(0);
        mSingleThreadedExecutor =
                new ThreadPoolExecutor(
                        /* corePoolSize= */ 1,
                        /* maximumPoolSize= */ 1,
                        /* KeepAliveTime= */ 0L,
                        TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<>()) {
                    @Override
                    protected void afterExecute(Runnable r, Throwable t) {
                        super.afterExecute(r, t);
                        afterSemaphore.release();
                    }
                };
        mInstance =
                AppsIndexerUserInstance.createInstance(
                        mTestContext,
                        mTestContext.getTestUser(),
                        mAppsDir,
                        mAppsIndexerConfig,
                        mIndexerForceUpdateConfig,
                        mSingleThreadedExecutor);
        // Wait for settings initialization
        afterSemaphore.acquire();

        int docCount = 100;
        // Set up package manager
        setupMockPackageManager(
                mMockPackageManager,
                createFakePackageInfos(docCount),
                createFakeResolveInfos(docCount),
                /* appFunctionServices= */ ImmutableList.of());

        mInstance.updateAsync(/* firstRun= */ false, /* isForceUpdateTriggered= */ false);

        // Wait for all async tasks to complete
        afterSemaphore.acquire();

        ArgumentCaptor<JobInfo> jobInfoArgumentCaptor = ArgumentCaptor.forClass(JobInfo.class);
        verify(mockJobScheduler).schedule(jobInfoArgumentCaptor.capture());
        JobInfo updateJob = jobInfoArgumentCaptor.getValue();
        assertThat(updateJob.isRequireBatteryNotLow()).isTrue();
        assertThat(updateJob.isRequireDeviceIdle()).isTrue();
        assertThat(updateJob.isPersisted()).isTrue();
        assertThat(updateJob.isPeriodic()).isTrue();
    }

    @Test
    public void testStart_subsequentRunWithNoScheduledJob_schedulesUpdateJob() throws Exception {
        // Trigger an initial update.
        mInstance.doUpdate(/* firstRun= */ false, new AppsUpdateStats());

        // This semaphore allows us to pause test execution until we're sure the tasks in
        // AppsIndexerUserInstance (scheduling the maintenance job) are finished.
        final Semaphore semaphore = new Semaphore(0);
        mSingleThreadedExecutor =
                new ThreadPoolExecutor(
                        /* corePoolSize= */ 1,
                        /* maximumPoolSize= */ 1,
                        /* KeepAliveTime= */ 0L,
                        TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<>()) {
                    @Override
                    protected void afterExecute(Runnable r, Throwable t) {
                        super.afterExecute(r, t);
                        semaphore.release();
                    }
                };

        // By default mockJobScheduler.getPendingJob() would return null. This simulates the
        // scenario where the scheduled update job after the initial run is cancelled
        // due to some reason.
        JobScheduler mockJobScheduler = mock(JobScheduler.class);
        mTestContext.setJobScheduler(mockJobScheduler);
        // the update should be zero, and if not it's because of mAppsDir
        mInstance =
                AppsIndexerUserInstance.createInstance(
                        mTestContext,
                        mTestContext.getTestUser(),
                        mAppsDir,
                        mAppsIndexerConfig,
                        mIndexerForceUpdateConfig,
                        mSingleThreadedExecutor);

        // Wait for file setup, as file setup uses the same ExecutorService.
        semaphore.acquire();

        int docCount = 100;
        // Set up package manager
        setupMockPackageManager(
                mMockPackageManager,
                createFakePackageInfos(docCount),
                createFakeResolveInfos(docCount),
                /* appFunctionServices= */ ImmutableList.of());

        mInstance.updateAsync(/* firstRun= */ true, /* isForceUpdateTriggered= */ false);

        // Wait for all async tasks to complete
        semaphore.acquire();

        ArgumentCaptor<JobInfo> jobInfoArgumentCaptor = ArgumentCaptor.forClass(JobInfo.class);
        verify(mockJobScheduler).schedule(jobInfoArgumentCaptor.capture());
        JobInfo updateJob = jobInfoArgumentCaptor.getValue();
        assertThat(updateJob.isRequireBatteryNotLow()).isTrue();
        assertThat(updateJob.isRequireDeviceIdle()).isTrue();
        assertThat(updateJob.isPersisted()).isTrue();
        assertThat(updateJob.isPeriodic()).isTrue();
    }

    @Test
    public void testUpdate_triggered_afterCompatibleSchemaChange() throws Exception {
        // Preset a compatible schema.
        AppSearchManager.SearchContext searchContext =
                new AppSearchManager.SearchContext.Builder(AppSearchHelper.APP_DATABASE).build();
        AppSearchSessionShim db =
                AppSearchSessionShimImpl.createSearchSessionAsync(searchContext).get();
        SetSchemaRequest setSchemaRequest =
                new SetSchemaRequest.Builder()
                        .addSchemas(TestUtils.COMPATIBLE_APP_SCHEMA)
                        .setForceOverride(true)
                        .build();
        db.setSchemaAsync(setSchemaRequest).get();
        db.close();

        // The current schema is compatible, and an update will be triggered
        JobScheduler mockJobScheduler = mock(JobScheduler.class);
        mTestContext.setJobScheduler(mockJobScheduler);
        // This semaphore allows us to make sure that a sync has finished running before performing
        // checks.
        final Semaphore afterSemaphore = new Semaphore(0);
        mSingleThreadedExecutor =
                new ThreadPoolExecutor(
                        /* corePoolSize= */ 1,
                        /* maximumPoolSize= */ 1,
                        /* KeepAliveTime= */ 0L,
                        TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<>()) {
                    @Override
                    protected void afterExecute(Runnable r, Throwable t) {
                        super.afterExecute(r, t);
                        afterSemaphore.release();
                    }
                };
        mInstance =
                AppsIndexerUserInstance.createInstance(
                        mTestContext,
                        mTestContext.getTestUser(),
                        mAppsDir,
                        mAppsIndexerConfig,
                        mIndexerForceUpdateConfig,
                        mSingleThreadedExecutor);
        // Wait for settings initialization
        afterSemaphore.acquire();

        mInstance.updateAsync(/* firstRun= */ true, /* isForceUpdateTriggered= */ false);
        afterSemaphore.acquire();

        ArgumentCaptor<JobInfo> jobInfoArgumentCaptor = ArgumentCaptor.forClass(JobInfo.class);
        verify(mockJobScheduler).schedule(jobInfoArgumentCaptor.capture());
        JobInfo updateJob = jobInfoArgumentCaptor.getValue();
        assertThat(updateJob.isRequireBatteryNotLow()).isTrue();
        assertThat(updateJob.isRequireDeviceIdle()).isTrue();
        assertThat(updateJob.isPersisted()).isTrue();
        assertThat(updateJob.isPeriodic()).isTrue();
    }

    @Test
    public void testUpdate_triggered_afterIncompatibleSchemaChange() throws Exception {
        int docCount = 250;

        // This semaphore allows us to pause test execution until we're sure the tasks in
        // AppsIndexerUserInstance (scheduling the maintenance job) are finished.
        final Semaphore semaphore = new Semaphore(0);
        mSingleThreadedExecutor =
                new ThreadPoolExecutor(
                        /* corePoolSize= */ 1,
                        /* maximumPoolSize= */ 1,
                        /* KeepAliveTime= */ 0L,
                        TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<>()) {
                    @Override
                    protected void afterExecute(Runnable r, Throwable t) {
                        super.afterExecute(r, t);
                        semaphore.release();
                    }
                };

        // Preset an incompatible schema.
        AppSearchManager.SearchContext searchContext =
                new AppSearchManager.SearchContext.Builder(AppSearchHelper.APP_DATABASE).build();
        AppSearchSessionShim db =
                AppSearchSessionShimImpl.createSearchSessionAsync(searchContext).get();

        SetSchemaRequest setSchemaRequest =
                new SetSchemaRequest.Builder()
                        .addSchemas(TestUtils.INCOMPATIBLE_APP_SCHEMA)
                        .setForceOverride(true)
                        .build();
        db.setSchemaAsync(setSchemaRequest).get();

        // Since the current schema is incompatible, it will overwrite it
        JobScheduler mockJobScheduler = mock(JobScheduler.class);
        mTestContext.setJobScheduler(mockJobScheduler);
        mInstance =
                AppsIndexerUserInstance.createInstance(
                        mTestContext,
                        mTestContext.getTestUser(),
                        mAppsDir,
                        mAppsIndexerConfig,
                        mIndexerForceUpdateConfig,
                        mSingleThreadedExecutor);
        // Wait for file setup, as file setup uses the same ExecutorService.
        semaphore.acquire();

        setupMockPackageManager(
                mMockPackageManager,
                createFakePackageInfos(docCount),
                createFakeResolveInfos(docCount),
                /* appFunctionServices= */ ImmutableList.of());

        mInstance.updateAsync(/* firstRun= */ true, /* isForceUpdateTriggered= */ false);
        // Wait for all async tasks to complete
        semaphore.acquire();

        ArgumentCaptor<JobInfo> jobInfoArgumentCaptor = ArgumentCaptor.forClass(JobInfo.class);
        verify(mockJobScheduler).schedule(jobInfoArgumentCaptor.capture());
        JobInfo updateJob = jobInfoArgumentCaptor.getValue();
        assertThat(updateJob.isRequireBatteryNotLow()).isTrue();
        assertThat(updateJob.isRequireDeviceIdle()).isTrue();
        assertThat(updateJob.isPersisted()).isTrue();
        assertThat(updateJob.isPeriodic()).isTrue();
    }

    @Test
    public void testConcurrentUpdates_updatesDoNotInterfereWithEachOther() throws Exception {
        long timeBeforeChangeNotification = System.currentTimeMillis();
        setupMockPackageManager(
                mMockPackageManager,
                createFakePackageInfos(250),
                createFakeResolveInfos(250),
                /* appFunctionServices= */ ImmutableList.of());
        // This semaphore allows us to make sure that a sync has finished running before performing
        // checks.
        final Semaphore afterSemaphore = new Semaphore(0);
        mSingleThreadedExecutor =
                new ThreadPoolExecutor(
                        /* corePoolSize= */ 1,
                        /* maximumPoolSize= */ 1,
                        /* KeepAliveTime= */ 0L,
                        TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<>()) {
                    @Override
                    protected void afterExecute(Runnable r, Throwable t) {
                        super.afterExecute(r, t);
                        afterSemaphore.release();
                    }
                };
        mInstance =
                AppsIndexerUserInstance.createInstance(
                        mTestContext,
                        mTestContext.getTestUser(),
                        mAppsDir,
                        mAppsIndexerConfig,
                        mIndexerForceUpdateConfig,
                        mSingleThreadedExecutor);
        // Wait for settings initialization
        afterSemaphore.acquire();

        // As there is nothing else in the executor queue, it should run soon.
        Future<?> unused =
                mSingleThreadedExecutor.submit(
                        () -> mInstance.doUpdate(/* firstRun= */ false, new AppsUpdateStats()));

        // On the current thread, this update will run at the same time as the task on the executor.
        mInstance.doUpdate(/* firstRun= */ false, new AppsUpdateStats());

        // By waiting for the single threaded executor to finish after calling doUpdate, both
        // updates are guaranteed to be finished.
        afterSemaphore.acquire();

        AppSearchHelper searchHelper = new AppSearchHelper(mTestContext);
        Map<String, MobileApplication> appIds =
                searchHelper.getAppsLastUpdatedTimeAndAppFunctionServiceEnabledFromAppSearch();
        assertThat(appIds.size()).isEqualTo(250);

        AppsIndexerSettings settings = mInstance.getSettings();
        assertThat(settings.getLastUpdateTimestampMillis()).isAtLeast(timeBeforeChangeNotification);
    }

    @Test
    public void testStart_subsequentRunWithScheduledJob_doesNotScheduleUpdateJob()
            throws Exception {
        // Trigger an initial update.
        mInstance.doUpdate(/* firstRun= */ false, new AppsUpdateStats());

        JobScheduler mockJobScheduler = mock(JobScheduler.class);

        // The JobInfo has to match exactly
        JobInfo scheduled =
                FrameworkIndexerMaintenanceService.createJobInfo(
                        mTestContext,
                        mTestContext.getUser(),
                        IndexerJobHandler.APPS_INDEXER,
                        /* isPeriodic= */ true,
                        mAppsIndexerConfig.getAppsMaintenanceUpdateIntervalMillis());

        // getPendingJob() should return a non-null value to simulate the scenario where a
        // background job is already scheduled.
        doReturn(scheduled)
                .when(mockJobScheduler)
                .getPendingJob(
                        FrameworkAppsIndexerMaintenanceConfig.MIN_APPS_INDEXER_JOB_ID
                                + mTestContext.getUser().getIdentifier());
        mTestContext.setJobScheduler(mockJobScheduler);
        mInstance =
                AppsIndexerUserInstance.createInstance(
                        mTestContext,
                        mTestContext.getTestUser(),
                        mAppsDir,
                        mAppsIndexerConfig,
                        mIndexerForceUpdateConfig,
                        mSingleThreadedExecutor);

        int docCount = 10;
        setupMockPackageManager(
                mMockPackageManager,
                createFakePackageInfos(docCount),
                createFakeResolveInfos(docCount),
                /* appFunctionServices= */ ImmutableList.of());

        CountDownLatch latch = setupLatch(docCount);
        mInstance.updateAsync(/* firstRun= */ false, /* isForceUpdateTriggered= */ false);
        // Wait for all async tasks to complete
        latch.await(10L, TimeUnit.SECONDS);

        verify(mockJobScheduler, never()).schedule(any());
    }

    class TestContext extends ContextWrapper {
        @Nullable JobScheduler mJobScheduler;
        private Locale mLocale = null;

        TestContext(Context base) {
            super(base);
        }

        @Override
        @Nullable
        public Object getSystemService(String name) {
            if (mJobScheduler != null && Context.JOB_SCHEDULER_SERVICE.equals(name)) {
                return mJobScheduler;
            }
            return getBaseContext().getSystemService(name);
        }

        @Override
        public PackageManager getPackageManager() {
            return mMockPackageManager;
        }

        public void setJobScheduler(@Nullable JobScheduler jobScheduler) {
            mJobScheduler = jobScheduler;
        }

        public void setLocale(Locale locale) {
            mLocale = locale;
        }

        @Override
        public Resources getResources() {
            if (mLocale == null) {
                return super.getResources();
            }
            Configuration overrideConfig =
                    new Configuration(super.getResources().getConfiguration());
            overrideConfig.setLocale(mLocale);
            Context configContext = createConfigurationContext(overrideConfig);
            return configContext.getResources();
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        @NonNull
        public Context createContextAsUser(UserHandle user, int flags) {
            return this;
        }

        @NonNull
        public UserHandle getTestUser() {
            return Process.myUserHandle();
        }
    }
}
