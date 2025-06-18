/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.server.appsearch.appsindexer;

import static android.Manifest.permission.OBSERVE_APP_USAGE;
import static android.Manifest.permission.PACKAGE_USAGE_STATS;
import static android.Manifest.permission.READ_DEVICE_CONFIG;
import static android.Manifest.permission.RECEIVE_BOOT_COMPLETED;

import static com.android.server.appsearch.appsindexer.TestUtils.createFakeAppOpenEventsIndexerSession;
import static com.android.server.appsearch.appsindexer.TestUtils.removeFakeAppOpenEventDocuments;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.UiAutomation;
import android.app.appsearch.AppSearchEnvironmentFactory;
import android.app.appsearch.AppSearchManager;
import android.app.appsearch.AppSearchSessionShim;
import android.app.appsearch.FrameworkAppSearchEnvironment;
import android.app.appsearch.SearchResult;
import android.app.appsearch.SearchSpec;
import android.app.appsearch.SetSchemaRequest;
import android.app.appsearch.testutil.AppSearchFrameworkTestUtils;
import android.app.appsearch.testutil.AppSearchTestUtils;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.UserInfo;
import android.os.UserHandle;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;

import androidx.annotation.NonNull;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.appsearch.flags.Flags;
import com.android.server.SystemService;
import com.android.server.appsearch.appsindexer.appsearchtypes.AppOpenEvent;

import junit.framework.Assert;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Integration tests for the AppOpenEventIndexer service to verify indexing and retrieval of app
 * open events from the AppSearch service in a near-prod environment.
 *
 * <p>This test class performs the following:
 *
 * <ul>
 *   <li>Sets up the testing environment with necessary permissions and mock AppSearch sessions.
 *   <li>Opens an app to trigger the indexing of an app open event, performs search operations, and
 *       verifies the indexing of recent app open events.
 * </ul>
 */
public class AppOpenEventIndexerRealDocumentsTest {

    private static final String TAG = "AppOpenEventIndexerRealDocumentsTest";
    private static final int HOURS_PER_WEEK = 24 * 7;

    private Context mContext;
    private UserInfo mUserInfo;
    private UserHandle mUserHandle;
    private UiAutomation mUiAutomation;

    private UsageStatsManager mSpiedUsageStatsManager;
    private Context mContextForService;

    @Rule public final TemporaryFolder mTemporaryFolder = new TemporaryFolder();
    @Rule public final RuleChain mRuleChain = AppSearchTestUtils.createCommonTestRules();

    @Before
    public void setUp() throws Exception {
        mContext = new ContextWrapper(ApplicationProvider.getApplicationContext());
        mUserInfo =
                new UserInfo(
                        mContext.getUser().getIdentifier(), /* name= */ "default", /* flags= */ 0);
        mUserHandle = new SystemService.TargetUser(mUserInfo).getUserHandle();
        removeFakeAppOpenEventDocuments(mContext, Executors.newSingleThreadExecutor());

        mUiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        mUiAutomation.adoptShellPermissionIdentity(
                PACKAGE_USAGE_STATS, OBSERVE_APP_USAGE, RECEIVE_BOOT_COMPLETED, READ_DEVICE_CONFIG);

        File mAppSearchDir = mTemporaryFolder.newFolder();
        AppSearchEnvironmentFactory.setEnvironmentInstanceForTest(
                new FrameworkAppSearchEnvironment() {
                    @Override
                    public File getAppSearchDir(
                            @NonNull Context unused, @NonNull UserHandle userHandle) {
                        return mAppSearchDir;
                    }
                });

        final Context baseApplicationContext = ApplicationProvider.getApplicationContext();
        UsageStatsManager realUsageStatsManagerInstance =
                (UsageStatsManager)
                        baseApplicationContext.getSystemService(Context.USAGE_STATS_SERVICE);
        if (realUsageStatsManagerInstance == null) {
            throw new IllegalStateException(
                    "Failed to get UsageStatsManager system service for spying.");
        }
        mSpiedUsageStatsManager = spy(realUsageStatsManagerInstance);

        mContextForService =
                new ContextWrapper(baseApplicationContext) {
                    final ContextWrapper thisWrapperInstance = this;

                    @Override
                    public Object getSystemService(@NonNull String name) {
                        if (Context.USAGE_STATS_SERVICE.equals(name))
                            return mSpiedUsageStatsManager;
                        return getBaseContext().getSystemService(name);
                    }

                    @Override
                    public Context getApplicationContext() {
                        return thisWrapperInstance;
                    }

                    @Override
                    public Context createContextAsUser(@NonNull UserHandle user, int flags) {
                        Context userSpecificBaseContext =
                                getBaseContext().createContextAsUser(user, flags);
                        return new ContextWrapper(userSpecificBaseContext) {
                            final ContextWrapper thisInnerWrapperInstance = this;

                            @Override
                            public Object getSystemService(@NonNull String name) {
                                if (Context.USAGE_STATS_SERVICE.equals(name)) {
                                    return AppOpenEventIndexerRealDocumentsTest.this
                                            .mSpiedUsageStatsManager;
                                }
                                return getBaseContext().getSystemService(name);
                            }

                            @Override
                            public Context getApplicationContext() {
                                return thisInnerWrapperInstance;
                            }
                        };
                    }
                };
    }

    @After
    public void tearDown() throws Exception {
        try (AppSearchSessionShim db =
                createFakeAppOpenEventsIndexerSession(
                        ApplicationProvider.getApplicationContext(),
                        Executors.newSingleThreadExecutor())) {
            removeFakeAppOpenEventDocuments(mContext, Executors.newSingleThreadExecutor());
            db.setSchemaAsync(new SetSchemaRequest.Builder().setForceOverride(true).build()).get();
        } finally {
            mUiAutomation.dropShellPermissionIdentity();
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_APP_OPEN_EVENT_INDEXER_PAGINATED_READ_ENABLED)
    public void testRealDocuments_check() throws Exception {
        long testStartTimeMillis = System.currentTimeMillis();

        Intent launchIntent =
                mContext.getPackageManager().getLaunchIntentForPackage(mContext.getPackageName());
        Assert.assertNotNull(launchIntent);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT);
        mContext.startActivity(launchIntent);

        UsageStatsManager pollingUsageStatsManager =
                (UsageStatsManager)
                        ApplicationProvider.getApplicationContext()
                                .getSystemService(Context.USAGE_STATS_SERVICE);
        assertThat(pollingUsageStatsManager).isNotNull();

        boolean foundMatchingEventInSystem = false;
        long matchingEventTimestamp = 0;
        final long maxWaitMillis = TimeUnit.SECONDS.toMillis(10);
        final long waitStartTime = System.currentTimeMillis();
        final long sleepMillis = 100;

        while (!foundMatchingEventInSystem
                && (System.currentTimeMillis() - waitStartTime) < maxWaitMillis) {
            UsageEvents events =
                    pollingUsageStatsManager.queryEvents(
                            testStartTimeMillis, System.currentTimeMillis());
            UsageEvents.Event event = new UsageEvents.Event();

            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                if (event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED
                        && event.getPackageName().equals(mContext.getPackageName())
                        && event.getTimeStamp() >= testStartTimeMillis) {
                    foundMatchingEventInSystem = true;
                    matchingEventTimestamp = event.getTimeStamp();
                    break;
                }
            }
            Thread.sleep(sleepMillis);
        }
        // Usage stats manager does not have an observer/callback API, so we just have to wait for
        // the event to appear.  If the test is flaky, this is likely the culprit.  Can increase the
        // spin loop time if needed (may improve flakiness).
        assertThat(foundMatchingEventInSystem).isTrue();

        CountDownLatch latch = new CountDownLatch(1);
        AppOpenEventIndexerManagerService appOpenEventIndexerManagerService =
                new AppOpenEventIndexerManagerService(
                        mContextForService, new TestAppOpenEventIndexerConfig(), latch::countDown);
        SystemService.TargetUser targetUser = new SystemService.TargetUser(mUserInfo);
        appOpenEventIndexerManagerService.onUserUnlocking(targetUser);

        appOpenEventIndexerManagerService.mLocalService.doUpdateForUser(
                targetUser.getUserHandle(), null);
        assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue();

        ArgumentCaptor<Long> startTimeCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> endTimeCaptor = ArgumentCaptor.forClass(Long.class);
        // The maximum lookback is 2 weeks, so we expect 2 * HOURS_PER_WEEK calls to queryEvents.
        verify(mSpiedUsageStatsManager, times(2 * HOURS_PER_WEEK))
                .queryEvents(startTimeCaptor.capture(), endTimeCaptor.capture());

        List<Long> startTimes = startTimeCaptor.getAllValues();
        List<Long> endTimes = endTimeCaptor.getAllValues();

        for (int i = 0; i < startTimes.size(); i++) {
            long currentStartTime = startTimes.get(i);
            long currentEndTime = endTimes.get(i);

            assertThat(currentEndTime - currentStartTime).isEqualTo(TimeUnit.HOURS.toMillis(1));

            if (i < startTimes.size() - 1) {
                long nextCallStartTime = startTimes.get(i + 1);
                assertThat(currentEndTime).isEqualTo(nextCallStartTime);
            }
        }

        SearchSpec searchSpec =
                new SearchSpec.Builder()
                        .addFilterNamespaces(AppOpenEvent.APP_OPEN_EVENT_NAMESPACE)
                        .setOrder(SearchSpec.ORDER_DESCENDING)
                        .setRankingStrategy(SearchSpec.RANKING_STRATEGY_CREATION_TIMESTAMP)
                        .addFilterPackageNames(mContext.getPackageName())
                        .build();
        AppSearchManager manager =
                ApplicationProvider.getApplicationContext()
                        .getSystemService(AppSearchManager.class);
        Executor executor =
                AppSearchEnvironmentFactory.getEnvironmentInstance().createSingleThreadExecutor();
        SyncGlobalSearchSession globalSearchSession =
                new SyncGlobalSearchSessionImpl(manager, executor);
        SyncSearchResults searchResults = globalSearchSession.search("", searchSpec);
        List<SearchResult> results =
                AppSearchFrameworkTestUtils.retrieveAllSearchResults(searchResults);
        boolean hasMatchingIndexedResult = false;

        for (SearchResult result : results) {
            String packageNameResult =
                    result.getGenericDocument()
                            .getPropertyString(AppOpenEvent.APP_OPEN_EVENT_PROPERTY_PACKAGE_NAME);
            Long timestampMillisResult =
                    result.getGenericDocument()
                            .getPropertyLong(
                                    AppOpenEvent.APP_OPEN_EVENT_PROPERTY_APP_OPEN_TIMESTAMP_MILLIS);
            if (packageNameResult != null
                    && timestampMillisResult != null
                    && mContext.getPackageName().equals(packageNameResult)
                    && timestampMillisResult == matchingEventTimestamp) {
                hasMatchingIndexedResult = true;
                break;
            }
        }
        Assert.assertTrue(
                "Matching indexed app open event was not found in AppSearch.",
                hasMatchingIndexedResult);
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_APP_OPEN_EVENT_INDEXER_PAGINATED_READ_ENABLED)
    public void testRealDocuments_check_withoutPaginatedRead() throws Exception {
        long testStartTimeMillis = System.currentTimeMillis();

        Intent launchIntent =
                mContext.getPackageManager().getLaunchIntentForPackage(mContext.getPackageName());
        assertThat(launchIntent).isNotNull();
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT);
        mContext.startActivity(launchIntent);

        UsageStatsManager usageStatsManager = mContext.getSystemService(UsageStatsManager.class);

        boolean foundMatchingEvent = false;
        long matchingEventTimestamp = 0;
        long maxWaitMillis = TimeUnit.SECONDS.toMillis(10);
        long waitStartTime = System.currentTimeMillis();
        long sleepMillis = 100;

        while (!foundMatchingEvent
                && (System.currentTimeMillis() - waitStartTime) < maxWaitMillis) {
            UsageEvents events =
                    usageStatsManager.queryEvents(testStartTimeMillis, System.currentTimeMillis());
            UsageEvents.Event event = new UsageEvents.Event();

            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                if (event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED
                        && event.getPackageName().equals(mContext.getPackageName())
                        && event.getTimeStamp() >= testStartTimeMillis) {
                    foundMatchingEvent = true;
                    matchingEventTimestamp = event.getTimeStamp();
                    break;
                }
            }
            Thread.sleep(sleepMillis);
        }
        // Usage stats manager does not have an observer/callback API, so we just have to wait for
        // the event to appear.  If the test is flaky, this is likely the culprit.  Can increase the
        // spin loop time if needed (may improve flakiness).
        assumeTrue(foundMatchingEvent);

        CountDownLatch latch = new CountDownLatch(1);
        AppOpenEventIndexerManagerService appOpenEventIndexerManagerService =
                new AppOpenEventIndexerManagerService(
                        mContext, new TestAppOpenEventIndexerConfig(), latch::countDown);
        SystemService.TargetUser targetUser = new SystemService.TargetUser(mUserInfo);
        appOpenEventIndexerManagerService.onUserUnlocking(targetUser);
        appOpenEventIndexerManagerService.mLocalService.doUpdateForUser(
                targetUser.getUserHandle(), null);
        assertThat(latch.await(10, TimeUnit.SECONDS)).isEqualTo(true);

        // Search for all app open events for the package opened earlier
        SearchSpec searchSpec =
                new SearchSpec.Builder()
                        .addFilterNamespaces(AppOpenEvent.APP_OPEN_EVENT_NAMESPACE)
                        .setOrder(SearchSpec.ORDER_DESCENDING)
                        .setRankingStrategy(SearchSpec.RANKING_STRATEGY_CREATION_TIMESTAMP)
                        .addFilterPackageNames(mContext.getPackageName())
                        .build();
        AppSearchManager manager =
                ApplicationProvider.getApplicationContext()
                        .getSystemService(AppSearchManager.class);
        Executor executor =
                AppSearchEnvironmentFactory.getEnvironmentInstance().createSingleThreadExecutor();
        SyncGlobalSearchSession globalSearchSession =
                new SyncGlobalSearchSessionImpl(manager, executor);
        String query =
                AppOpenEvent.APP_OPEN_EVENT_PROPERTY_PACKAGE_NAME
                        + ":("
                        + mContext.getPackageName()
                        + ")";
        SyncSearchResults searchResults = globalSearchSession.search(query, searchSpec);

        List<SearchResult> results =
                AppSearchFrameworkTestUtils.retrieveAllSearchResults(searchResults);

        long currentTimeMillis = System.currentTimeMillis();
        boolean hasMatchingResult = false;

        // Validate that a very recent app open event is for the package we opened. It appears
        // the emulator has other app open events on the query events API that aren't from the test,
        // so ignore those.
        for (SearchResult result : results) {
            String packageName =
                    result.getGenericDocument()
                            .getPropertyString(AppOpenEvent.APP_OPEN_EVENT_PROPERTY_PACKAGE_NAME);

            Long timestampMillis =
                    result.getGenericDocument()
                            .getPropertyLong(
                                    AppOpenEvent.APP_OPEN_EVENT_PROPERTY_APP_OPEN_TIMESTAMP_MILLIS);

            if (packageName != null
                    && timestampMillis != null
                    && mContext.getPackageName().equals(packageName)
                    && (currentTimeMillis - timestampMillis) <= TimeUnit.SECONDS.toMillis(30)
                    && timestampMillis == matchingEventTimestamp) {
                hasMatchingResult = true;
                break;
            }
        }

        assertThat(hasMatchingResult).isTrue();
    }
}
