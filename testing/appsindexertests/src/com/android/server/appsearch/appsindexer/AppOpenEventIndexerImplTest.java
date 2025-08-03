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

import static com.android.server.appsearch.appsindexer.TestUtils.createIndividualUsageEvent;
import static com.android.server.appsearch.appsindexer.TestUtils.removeFakeAppOpenEventDocuments;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.appsearch.AppSearchResult;
import android.app.appsearch.exceptions.AppSearchException;
import android.app.appsearch.testutil.AppSearchTestUtils;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.ContextWrapper;
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
import org.mockito.Mockito;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class AppOpenEventIndexerImplTest {
    private AppSearchHelper mAppSearchHelper;
    private final ExecutorService mSingleThreadedExecutor = Executors.newSingleThreadExecutor();
    private Context mContext;
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Rule public final RuleChain mRuleChain = AppSearchTestUtils.createCommonTestRules();

    @Before
    public void setUp() throws Exception {
        mContext = ApplicationProvider.getApplicationContext();
        mAppSearchHelper = new AppSearchHelper(mContext);
        removeFakeAppOpenEventDocuments(mContext, mSingleThreadedExecutor);
    }

    @After
    public void tearDown() throws Exception {
        if (mAppSearchHelper != null) {
            mAppSearchHelper.close();
        }
        if (mSingleThreadedExecutor != null) {
            mSingleThreadedExecutor.shutdownNow();
        }
        temporaryFolder.delete();
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_APP_OPEN_EVENT_INDEXER_ENABLED_V2)
    public void testAppOpenEventIndexerImpl_updateAppsThrowsError_shouldContinueOnError()
            throws Exception {
        long firstRunTimeBase = System.currentTimeMillis();
        AppOpenEventIndexerSettings settings =
                new AppOpenEventIndexerSettings(temporaryFolder.newFolder("tmp"));

        long initialLastUpdateMillis = firstRunTimeBase - TimeUnit.MINUTES.toMillis(5);
        settings.setLastUpdateTimestampMillis(initialLastUpdateMillis);

        UsageStatsManager usm = Mockito.mock(UsageStatsManager.class);
        RuntimeException simulatedError = new RuntimeException("AppSearchException");
        when(usm.queryEvents(anyLong(), anyLong())).thenThrow(simulatedError);

        Context context =
                new ContextWrapper(mContext) {
                    @Override
                    public Object getSystemService(String name) {
                        if (name.equals(Context.USAGE_STATS_SERVICE)) {
                            return usm;
                        }
                        return super.getSystemService(name);
                    }
                };

        AppOpenEventIndexerImpl appOpenEventIndexerImpl =
                new AppOpenEventIndexerImpl(context, new TestAppOpenEventIndexerConfig());

        AppSearchException thrownAppSearchException =
                assertThrows(
                        AppSearchException.class,
                () -> appOpenEventIndexerImpl.doUpdate(settings, new AppOpenEventStats.Builder()));
        assertThat(thrownAppSearchException.getCause()).isSameInstanceAs(simulatedError);

        assertThrows(
                AppSearchException.class,
                () ->
                        mAppSearchHelper.getSubsequentAppOpenEventAfterThreshold(
                                initialLastUpdateMillis));

        assertThat(settings.getLastUpdateTimestampMillis()).isEqualTo(initialLastUpdateMillis);
        Mockito.reset(usm);

        long secondRunEffectiveCurrentTime = System.currentTimeMillis();
        String successfulPackageName = "com.example.success";
        long successfulEventTimestamp = secondRunEffectiveCurrentTime + 1;

        UsageEvents.Event[] successfulEventsArray =
                new UsageEvents.Event[] {
                    createIndividualUsageEvent(
                            UsageEvents.Event.MOVE_TO_FOREGROUND,
                            successfulEventTimestamp,
                            successfulPackageName)
                };
        UsageEvents successfulUsageEvents = TestUtils.createUsageEvents(successfulEventsArray);

        when(usm.queryEvents(eq(initialLastUpdateMillis), anyLong()))
                .thenReturn(successfulUsageEvents);
        appOpenEventIndexerImpl.doUpdate(settings, new AppOpenEventStats.Builder());
        AppOpenEvent indexedEvent = mAppSearchHelper.getSubsequentAppOpenEventAfterThreshold(0L);

        assertThat(indexedEvent).isNotNull();
        assertThat(indexedEvent.getPackageName()).isEqualTo(successfulPackageName);
        assertThat(indexedEvent.getAppOpenEventTimestampMillis())
                .isEqualTo(successfulEventTimestamp);
        assertThat(settings.getLastUpdateTimestampMillis())
                .isAtLeast(secondRunEffectiveCurrentTime);
    }

    @Test
    public void testAppOpenEventIndexerImpl_updateApps_worksEndToEnd() throws Exception {
        long currentTimeMillis = System.currentTimeMillis();
        AppOpenEventIndexerSettings settings =
                new AppOpenEventIndexerSettings(temporaryFolder.newFolder("tmpEndToEnd"));
        settings.setLastUpdateTimestampMillis(currentTimeMillis);
        UsageStatsManager usm = Mockito.mock(UsageStatsManager.class);

        UsageEvents.Event[] events =
                new UsageEvents.Event[] {
                    createIndividualUsageEvent(
                            UsageEvents.Event.MOVE_TO_FOREGROUND,
                            currentTimeMillis + 1L,
                            "com.example.package"),
                };

        UsageEvents mockUsageEvents = TestUtils.createUsageEvents(events);
        when(usm.queryEvents(anyLong(), anyLong())).thenReturn(mockUsageEvents);

        Context context =
                new ContextWrapper(mContext) {
                    @Override
                    public Object getSystemService(String name) {
                        if (name.equals(Context.USAGE_STATS_SERVICE)) {
                            return usm;
                        }
                        return super.getSystemService(name);
                    }
                };

        AppOpenEventIndexerImpl appOpenEventIndexerImpl =
                new AppOpenEventIndexerImpl(context, new TestAppOpenEventIndexerConfig());
        AppOpenEventStats.Builder appOpenEventStatsBuilder = new AppOpenEventStats.Builder();
        appOpenEventIndexerImpl.doUpdate(settings, appOpenEventStatsBuilder);
        AppOpenEventStats appOpenEventStats = appOpenEventStatsBuilder.build();

        assertThat(
                        mAppSearchHelper
                                .getSubsequentAppOpenEventAfterThreshold(currentTimeMillis)
                                .getId())
                .isEqualTo("com.example.package" + (currentTimeMillis + 1L));
        assertThat(appOpenEventStats.getNumberOfAppOpenEventsAdded()).isEqualTo(1);
        assertThat(appOpenEventStats.getUpdateStatusCodes())
                .containsExactly(AppSearchResult.RESULT_OK);
        // Settings updated on successful indexing
        assertThat(settings.getLastUpdateTimestampMillis()).isGreaterThan(currentTimeMillis);
    }

    @Test
    public void testAppOpenEventIndexerImpl_updateApps_statsAreCorrect() throws Exception {
        long currentTimeMillis = System.currentTimeMillis();
        AppOpenEventIndexerSettings settings =
                new AppOpenEventIndexerSettings(temporaryFolder.newFolder("tmp"));
        settings.setLastUpdateTimestampMillis(currentTimeMillis);

        UsageStatsManager usm = Mockito.mock(UsageStatsManager.class);

        UsageEvents.Event[] oldEvents =
                new UsageEvents.Event[] {
                    createIndividualUsageEvent(
                            UsageEvents.Event.MOVE_TO_FOREGROUND,
                            currentTimeMillis,
                            "com.example.package"),
                    createIndividualUsageEvent(
                            UsageEvents.Event.MOVE_TO_FOREGROUND,
                            currentTimeMillis + 1L,
                            "com.example.package"),
                };
        UsageEvents mockUsageEvents = TestUtils.createUsageEvents(oldEvents);

        when(usm.queryEvents(anyLong(), anyLong())).thenReturn(mockUsageEvents);

        Context context =
                new ContextWrapper(mContext) {
                    @Override
                    public Object getSystemService(String name) {
                        if (name.equals(Context.USAGE_STATS_SERVICE)) {
                            return usm;
                        }
                        return super.getSystemService(name);
                    }
                };

        AppOpenEventIndexerImpl appOpenEventIndexerImpl = new AppOpenEventIndexerImpl(context, new TestAppOpenEventIndexerConfig());
        AppOpenEventStats.Builder appOpenEventStatsBuilder = new AppOpenEventStats.Builder();
        appOpenEventIndexerImpl.doUpdate(settings, appOpenEventStatsBuilder);

        AppOpenEventStats appOpenEventStats = appOpenEventStatsBuilder.build();

        assertThat(appOpenEventStats.getNumberOfAppOpenEventsAdded()).isEqualTo(2);
        assertThat(appOpenEventStats.getUpdateStatusCodes()).hasSize(1);
        assertThat(appOpenEventStats.getUpdateStatusCodes()).contains(AppSearchResult.RESULT_OK);

        assertThat(appOpenEventStats.getLastAppUpdateTimestampMillis()).isEqualTo(currentTimeMillis);
        assertThat(appOpenEventStats.getUpdateStartTimestampMillis()).isAtLeast(currentTimeMillis);

        assertThat(appOpenEventStats.getUsageStatsManagerReadLatencyMillis()).isGreaterThan(0L);
        assertThat(appOpenEventStats.getUsageStatsManagerReadLatencyMillis()).isLessThan(1_000L); //

        assertThat(appOpenEventStats.getAppSearchSetSchemaLatencyMillis()).isGreaterThan(0L);
        assertThat(appOpenEventStats.getAppSearchSetSchemaLatencyMillis()).isLessThan(1_000L);

        assertThat(appOpenEventStats.getAppSearchPutLatencyMillis()).isGreaterThan(0L);
        assertThat(appOpenEventStats.getAppSearchPutLatencyMillis()).isLessThan(1_000L);

        assertThat(appOpenEventStats.getTotalLatencyMillis()).isGreaterThan(0L);
        assertThat(appOpenEventStats.getTotalLatencyMillis()).isLessThan(1_000L);

        assertThat(appOpenEventStats.getForceUpdateTriggered()).isFalse();

        assertThat(settings.getLastUpdateTimestampMillis()).isAtLeast(currentTimeMillis);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_APP_OPEN_EVENT_INDEXER_ENABLED_V2)
    public void testAppOpenEventIndexerImpl_updateApps_recentQueryStartTime() throws Exception {
        long effectiveCurrentTimeMillis = System.currentTimeMillis();
        AppOpenEventIndexerSettings settings =
                new AppOpenEventIndexerSettings(temporaryFolder.newFolder("tmpRecentQuery"));

        long lastUpdateTimeMillis = effectiveCurrentTimeMillis - TimeUnit.MINUTES.toMillis(30);
        settings.setLastUpdateTimestampMillis(lastUpdateTimeMillis);

        UsageStatsManager usm = Mockito.mock(UsageStatsManager.class);
        String packageName = "com.example.recent";
        long eventTimestamp = lastUpdateTimeMillis + TimeUnit.MINUTES.toMillis(5);

        UsageEvents.Event[] eventsArray =
                new UsageEvents.Event[] {
                    createIndividualUsageEvent(
                            UsageEvents.Event.MOVE_TO_FOREGROUND, eventTimestamp, packageName)
                };
        UsageEvents mockUsageEvents = TestUtils.createUsageEvents(eventsArray);

        when(usm.queryEvents(eq(lastUpdateTimeMillis), anyLong())).thenReturn(mockUsageEvents);

        Context context =
                new ContextWrapper(mContext) {
                    @Override
                    public Object getSystemService(String name) {
                        if (name.equals(Context.USAGE_STATS_SERVICE)) {
                            return usm;
                        }
                        return super.getSystemService(name);
                    }
                };

        AppOpenEventIndexerConfig config = new TestAppOpenEventIndexerConfig();
        AppOpenEventIndexerImpl indexer = new AppOpenEventIndexerImpl(context, config);
        indexer.doUpdate(settings, new AppOpenEventStats.Builder());
        Mockito.verify(usm, times(1)).queryEvents(eq(lastUpdateTimeMillis), anyLong());

        AppOpenEvent indexedEvent =
                mAppSearchHelper.getSubsequentAppOpenEventAfterThreshold(lastUpdateTimeMillis);
        assertThat(indexedEvent).isNotNull();
        assertThat(indexedEvent.getPackageName()).isEqualTo(packageName);
        assertThat(indexedEvent.getAppOpenEventTimestampMillis()).isEqualTo(eventTimestamp);

        AppOpenEvent nextEvent =
                mAppSearchHelper.getSubsequentAppOpenEventAfterThreshold(eventTimestamp - 1);
        assertThat(nextEvent).isNotNull();
        assertThat(nextEvent.getPackageName()).isEqualTo(packageName);
        assertThat(nextEvent.getAppOpenEventTimestampMillis()).isEqualTo(eventTimestamp);
        assertThat(settings.getLastUpdateTimestampMillis()).isAtLeast(effectiveCurrentTimeMillis);
    }

    @Test
    public void paginationMathCheck() throws Exception {
        long effectiveCurrentTimeMillis = System.currentTimeMillis();
        AppOpenEventIndexerConfig config = new TestAppOpenEventIndexerConfig();

        long lastUpdateTimestampMillis = effectiveCurrentTimeMillis - TimeUnit.MINUTES.toMillis(90);

        AppOpenEventIndexerSettings settings =
                new AppOpenEventIndexerSettings(temporaryFolder.newFolder("paginatedReadCheck"));
        settings.setLastUpdateTimestampMillis(lastUpdateTimestampMillis);

        UsageStatsManager mockUsm = Mockito.mock(UsageStatsManager.class);

        String testPackageName = mContext.getPackageName();
        long testEventTimestamp = effectiveCurrentTimeMillis - TimeUnit.HOURS.toMillis(1);

        // Return the event if it's in the query range, otherwise empty.
        when(mockUsm.queryEvents(anyLong(), anyLong()))
                .thenAnswer(
                        invocation -> {
                            long queryStartTime = invocation.getArgument(0);
                            long queryEndTime = invocation.getArgument(1);
                            UsageEvents.Event eventToReturn =
                                    createIndividualUsageEvent(
                                            UsageEvents.Event.ACTIVITY_RESUMED,
                                            testEventTimestamp,
                                            testPackageName);

                            if (testEventTimestamp >= queryStartTime
                                    && testEventTimestamp < queryEndTime) {
                                return TestUtils.createUsageEvents(
                                        new UsageEvents.Event[] {eventToReturn});
                            }
                            return TestUtils.createUsageEvents(new UsageEvents.Event[] {});
                        });

        Context contextWrapper =
                new ContextWrapper(mContext) {
                    @Override
                    public Object getSystemService(String name) {
                        if (name.equals(Context.USAGE_STATS_SERVICE)) {
                            return mockUsm;
                        }
                        return super.getSystemService(name);
                    }
                };

        AppOpenEventIndexerImpl indexer = new AppOpenEventIndexerImpl(contextWrapper, config);
        indexer.doUpdate(settings, new AppOpenEventStats.Builder());

        ArgumentCaptor<Long> startTimeCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> endTimeCaptor = ArgumentCaptor.forClass(Long.class);

        long queryWindowMillis = effectiveCurrentTimeMillis - lastUpdateTimestampMillis; // 90 mins

        long intervalMillis = TimeUnit.HOURS.toMillis(1); // Default pagination interval
        int expectedNumCalls =
                (int)
                        Math.ceil(
                                (double) queryWindowMillis
                                        / intervalMillis); // 2 calls, 1 full hour and 1 partial
        // hour

        verify(mockUsm, times(expectedNumCalls))
                .queryEvents(startTimeCaptor.capture(), endTimeCaptor.capture());

        List<Long> startTimes = startTimeCaptor.getAllValues();
        List<Long> endTimes = endTimeCaptor.getAllValues();

        assertThat(startTimes).hasSize(expectedNumCalls);
        assertThat(endTimes).hasSize(expectedNumCalls);

        assertThat(expectedNumCalls).isEqualTo(2);

        // First call
        long firstCallStartTime = startTimes.get(0);
        long firstCallEndTime = endTimes.get(0);

        assertThat(firstCallEndTime - firstCallStartTime).isEqualTo(intervalMillis);
        assertThat(firstCallStartTime).isEqualTo(lastUpdateTimestampMillis);

        // Second call
        long secondCallStartTime = startTimes.get(1);
        long secondCallEndTime = endTimes.get(1);

        assertThat(secondCallEndTime - secondCallStartTime)
                .isEqualTo((secondCallEndTime - lastUpdateTimestampMillis) - intervalMillis);

        assertThat(secondCallEndTime).isAtMost(System.currentTimeMillis());
        assertThat(secondCallEndTime).isAtLeast(effectiveCurrentTimeMillis);

        assertThat(firstCallEndTime).isEqualTo(secondCallStartTime);

        AppOpenEvent foundEvent =
                mAppSearchHelper.getSubsequentAppOpenEventAfterThreshold(testEventTimestamp - 1);
        boolean hasMatchingIndexedResult = false;
        if (foundEvent != null
                && testPackageName.equals(foundEvent.getPackageName())
                && testEventTimestamp == foundEvent.getAppOpenEventTimestampMillis()) {
            hasMatchingIndexedResult = true;
        }
        assertTrue(hasMatchingIndexedResult);
        assertThat(settings.getLastUpdateTimestampMillis()).isAtLeast(effectiveCurrentTimeMillis);
    }
}
