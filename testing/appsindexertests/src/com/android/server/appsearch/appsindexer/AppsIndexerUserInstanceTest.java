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

import static com.android.server.appsearch.appsindexer.TestUtils.createFakePackageInfos;
import static com.android.server.appsearch.appsindexer.TestUtils.createFakeResolveInfos;
import static com.android.server.appsearch.appsindexer.TestUtils.setupMockPackageManager;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.PersistableBundle;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class AppsIndexerUserInstanceTest extends AppsIndexerTestBase {
    private TestContext mContext;
    private final PackageManager mMockPackageManager = mock(PackageManager.class);

    @Rule public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    private ThreadPoolExecutor mSingleThreadedExecutor;
    private File mAppsDir;
    private File mSettingsFile;
    private AppsIndexerUserInstance mInstance;
    private final AppsIndexerConfig mAppsIndexerConfig = new TestAppsIndexerConfig();

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        Context context = ApplicationProvider.getApplicationContext();
        mContext = new TestContext(context);

        mSingleThreadedExecutor =
                new ThreadPoolExecutor(
                        /* corePoolSize= */ 1,
                        /* maximumPoolSize= */ 1,
                        /* KeepAliveTime= */ 0L,
                        TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<>());

        // Setup the file path to the persisted data
        mAppsDir = new File(mTemporaryFolder.newFolder(), "appsearch/apps");
        mSettingsFile = new File(mAppsDir, AppsIndexerSettings.SETTINGS_FILE_NAME);
        mInstance =
                AppsIndexerUserInstance.createInstance(
                        mContext, mAppsDir, mAppsIndexerConfig, mSingleThreadedExecutor);
    }

    @After
    @Override
    public void tearDown() throws Exception {
        TestUtils.removeFakePackageDocuments(mContext, Executors.newSingleThreadExecutor());
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
                        mContext, mAppsDir, mAppsIndexerConfig, mSingleThreadedExecutor);

        // Pretend there's one package on device
        setupMockPackageManager(
                mMockPackageManager, createFakePackageInfos(1), createFakeResolveInfos(1));

        // Wait for file setup, as file setup uses the same ExecutorService.
        semaphore.acquire();

        long beforeFirstRun = mSingleThreadedExecutor.getCompletedTaskCount();

        mInstance.updateAsync(true);
        semaphore.acquire();

        while (mSingleThreadedExecutor.getCompletedTaskCount() != beforeFirstRun + 1) {
            continue;
        }

        assertThat(mSingleThreadedExecutor.getCompletedTaskCount()).isEqualTo(beforeFirstRun + 1);
        try (AppSearchHelper searchHelper = AppSearchHelper.createAppSearchHelper(mContext)) {
            Map<String, Long> appsTimestampMap = searchHelper.getAppsFromAppSearch();
            assertThat(appsTimestampMap).hasSize(1);
            assertThat(appsTimestampMap.keySet()).containsExactly("com.fake.package0");
        }
    }

    @Test
    public void testFirstRun_updateAlreadyRan_doesNotUpdate() throws Exception {
        // Pretend we already ran
        AppsIndexerSettings settings = new AppsIndexerSettings(mAppsDir);
        settings.setLastUpdateTimestampMillis(1000);
        settings.persist();

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
                        mContext, mAppsDir, mAppsIndexerConfig, mSingleThreadedExecutor);

        // Pretend there's one package on device
        setupMockPackageManager(
                mMockPackageManager, createFakePackageInfos(1), createFakeResolveInfos(1));

        // Wait for file setup, as file setup uses the same ExecutorService.
        semaphore.acquire();

        long beforeFirstRun = mSingleThreadedExecutor.getCompletedTaskCount();

        mInstance.updateAsync(true);
        // Wait for the task to finish
        semaphore.acquire();

        while (mSingleThreadedExecutor.getCompletedTaskCount() != beforeFirstRun + 1) {
            continue;
        }
        // One more task should've ran, checked settings, and exited
        assertThat(mSingleThreadedExecutor.getActiveCount()).isEqualTo(0);
        assertThat(mSingleThreadedExecutor.getTaskCount()).isEqualTo(beforeFirstRun + 1);
        assertThat(mSingleThreadedExecutor.getCompletedTaskCount()).isEqualTo(beforeFirstRun + 1);

        // Even though a task ran and we got 1 app ready, we requested a "firstRun" but the
        // timestamp was not 0, so nothing should've been indexed
        try (AppSearchHelper searchHelper = AppSearchHelper.createAppSearchHelper(mContext)) {
            assertThat(searchHelper.getAppsFromAppSearch()).isEmpty();
        }
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
                        pauseContext, mAppsDir, mAppsIndexerConfig, mSingleThreadedExecutor);
        // Wait for file setup, as file setup uses the same ExecutorService.
        afterSemaphore.acquire();

        int numOfNotifications = 20;
        setupMockPackageManager(
                mMockPackageManager,
                createFakePackageInfos(numOfNotifications / 10),
                createFakeResolveInfos(numOfNotifications / 10));

        // Schedule a bunch of tasks. However, only one will run, and one other will be scheduled
        for (int i = 0; i < numOfNotifications / 2; i++) {
            // This will pretend to add apps repeatedly
            mInstance.updateAsync(/* firstRun= */ false);
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
                createFakeResolveInfos(numOfNotifications));
        for (int i = numOfNotifications / 2; i < numOfNotifications; i++) {
            mInstance.updateAsync(/* firstRun= */ false);
        }

        // Now we allow syncing to continue
        midSyncSemaphoreB.release();

        // Wait for the first sync to finish
        afterSemaphore.acquire();

        // The call to getCompletedTaskCount can be flaky due to the fact that getCompletedTaskCount
        // relies on a count that is updated a little bit AFTER afterExecute is called, which is
        // where the semaphore is released. See ThreadPoolExecutor#runWorker
        while (mSingleThreadedExecutor.getCompletedTaskCount() != 2) {
            continue;
        }

        assertThat(mSingleThreadedExecutor.getCompletedTaskCount()).isEqualTo(2);

        // Wait for the second sync to finish
        midSyncSemaphoreB.release();
        afterSemaphore.acquire();

        // Only two updates ran even though many were scheduled
        while (mSingleThreadedExecutor.getCompletedTaskCount() != 3) {
            continue;
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
                                                    mContext,
                                                    dataDir,
                                                    mAppsIndexerConfig,
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
                createFakeResolveInfos(docCount));
        CountDownLatch latch = setupLatch(docCount);

        mInstance.doUpdate(/* firstRun= */ false);
        latch.await(10, TimeUnit.SECONDS);

        AppSearchHelper searchHelper = AppSearchHelper.createAppSearchHelper(mContext);
        Map<String, Long> appIds = searchHelper.getAppsFromAppSearch();
        assertThat(appIds.size()).isEqualTo(docCount);
    }

    @Test
    public void testUpdate_setsLastAppUpdatedTimestamp() throws Exception {
        int docCount = 10;
        setupMockPackageManager(
                mMockPackageManager,
                createFakePackageInfos(docCount),
                createFakeResolveInfos(docCount));
        mInstance.doUpdate(/* firstRun= */ false);

        AppsIndexerSettings settings = new AppsIndexerSettings(mAppsDir);
        settings.load();
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
                mMockPackageManager, createFakePackageInfos(10), createFakeResolveInfos(10));

        mInstance.doUpdate(/* firstRun= */ false);

        AppSearchHelper searchHelper = AppSearchHelper.createAppSearchHelper(mContext);
        Map<String, Long> appIds = searchHelper.getAppsFromAppSearch();
        assertThat(appIds.size()).isEqualTo(10);

        setupMockPackageManager(
                mMockPackageManager, createFakePackageInfos(6), createFakeResolveInfos(6));

        mInstance.doUpdate(/* firstRun= */ false);

        searchHelper = AppSearchHelper.createAppSearchHelper(mContext);
        appIds = searchHelper.getAppsFromAppSearch();
        assertThat(appIds.size()).isEqualTo(6);
        assertThat(appIds.keySet())
                .containsNoneOf(
                        TestUtils.FAKE_PACKAGE_PREFIX + "6",
                        TestUtils.FAKE_PACKAGE_PREFIX + "7",
                        TestUtils.FAKE_PACKAGE_PREFIX + "8",
                        TestUtils.FAKE_PACKAGE_PREFIX + "9");

        PersistableBundle settingsBundle = AppsIndexerSettings.readBundle(mSettingsFile);
        assertThat(settingsBundle.getLong(AppsIndexerSettings.LAST_UPDATE_TIMESTAMP_KEY))
                .isAtLeast(timeBeforeChangeNotification);

        // The last updated app was still the "9" app
        assertThat(settingsBundle.getLong(AppsIndexerSettings.LAST_APP_UPDATE_TIMESTAMP_KEY))
                .isEqualTo(9);
    }

    class TestContext extends ContextWrapper {
        TestContext(Context base) {
            super(base);
        }

        @Override
        public PackageManager getPackageManager() {
            return mMockPackageManager;
        }
    }
}
