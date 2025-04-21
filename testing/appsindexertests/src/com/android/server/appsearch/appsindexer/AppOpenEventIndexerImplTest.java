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

package com.android.server.appsearch.appsindexer;

import static com.android.server.appsearch.appsindexer.TestUtils.createIndividualUsageEvent;
import static com.android.server.appsearch.appsindexer.TestUtils.removeFakeAppOpenEventDocuments;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import android.app.appsearch.exceptions.AppSearchException;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.ContextWrapper;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AppOpenEventIndexerImplTest {
    private AppSearchHelper mAppSearchHelper;
    private final ExecutorService mSingleThreadedExecutor = Executors.newSingleThreadExecutor();
    private Context mContext;
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Before
    public void setUp() throws Exception {
        mContext = ApplicationProvider.getApplicationContext();
        mAppSearchHelper = new AppSearchHelper(mContext);
        removeFakeAppOpenEventDocuments(mContext, mSingleThreadedExecutor);
    }

    @After
    public void tearDown() throws Exception {
        mAppSearchHelper.close();
    }

    @Test
    public void testAppOpenEventIndexerImpl_updateAppsThrowsError_shouldContinueOnError()
            throws Exception {
        long currentTimeMillis = System.currentTimeMillis();
        AppOpenEventIndexerSettings settings =
                new AppOpenEventIndexerSettings(temporaryFolder.newFolder("tmp"));
        settings.setLastUpdateTimestampMillis(currentTimeMillis + 100L);

        UsageStatsManager usm = Mockito.mock(UsageStatsManager.class);
        when(usm.queryEvents(anyLong(), anyLong()))
                .thenThrow(new RuntimeException("AppSearchException"));

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

        AppOpenEventIndexerImpl appOpenEventIndexerImpl = new AppOpenEventIndexerImpl(context);
        assertThrows(Exception.class, () -> appOpenEventIndexerImpl.doUpdate(settings));
        // Indexing did not succeed, so we should not be able to get any app open events
        assertThrows(
                AppSearchException.class,
                () ->
                        mAppSearchHelper.getSubsequentAppOpenEventAfterThreshold(
                                currentTimeMillis + 100L));

        // Settings unchanged on failed indexing
        assertThat(settings.getLastUpdateTimestampMillis()).isEqualTo(currentTimeMillis + 100L);
    }

    @Test
    public void testAppOpenEventIndexerImpl_updateApps_worksEndToEnd() throws Exception {
        long currentTimeMillis = System.currentTimeMillis();
        AppOpenEventIndexerSettings settings =
                new AppOpenEventIndexerSettings(temporaryFolder.newFolder("tmp"));
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

        AppOpenEventIndexerImpl appOpenEventIndexerImpl = new AppOpenEventIndexerImpl(context);
        appOpenEventIndexerImpl.doUpdate(settings);

        assertThat(
                        mAppSearchHelper
                                .getSubsequentAppOpenEventAfterThreshold(currentTimeMillis)
                                .getId())
                .isEqualTo("com.example.package" + (currentTimeMillis + 1L));

        // Settings updated on successful indexing
        assertThat(settings.getLastUpdateTimestampMillis()).isGreaterThan(currentTimeMillis);
    }
}
