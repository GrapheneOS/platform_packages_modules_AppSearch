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

import static android.Manifest.permission.OBSERVE_APP_USAGE;
import static android.Manifest.permission.PACKAGE_USAGE_STATS;

import static com.android.server.appsearch.appsindexer.TestUtils.createFakeAppOpenEventsIndexerSession;
import static com.android.server.appsearch.appsindexer.TestUtils.removeFakeAppOpenEventDocuments;

import static com.google.common.truth.Truth.assertThat;

import android.app.UiAutomation;
import android.app.appsearch.AppSearchEnvironmentFactory;
import android.app.appsearch.AppSearchManager;
import android.app.appsearch.AppSearchSessionShim;
import android.app.appsearch.SearchResult;
import android.app.appsearch.SearchSpec;
import android.app.appsearch.SetSchemaRequest;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.UserInfo;
import android.os.CancellationSignal;
import android.os.UserHandle;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.SystemService;
import com.android.server.appsearch.appsindexer.appsearchtypes.AppOpenEvent;

import junit.framework.Assert;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

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

    protected Context mContext;
    protected UserInfo mUserInfo;
    protected UserHandle mUserHandle;
    protected Context mUserContext;
    protected UiAutomation mUiAutomation;

    @Before
    public void setUp() throws Exception {
        mContext = new ContextWrapper(ApplicationProvider.getApplicationContext());
        mUserInfo =
                new UserInfo(
                        mContext.getUser().getIdentifier(), /* name= */ "default", /* flags= */ 0);
        mUserHandle = new SystemService.TargetUser(mUserInfo).getUserHandle();
        mUserContext =
                AppSearchEnvironmentFactory.getEnvironmentInstance()
                        .createContextAsUser(mContext, mUserHandle);
        AppOpenEventIndexerSettings appOpenEventIndexerSettings =
                new AppOpenEventIndexerSettings(
                        AppSearchEnvironmentFactory.getEnvironmentInstance()
                                .getAppSearchDir(mUserContext, mUserHandle));
        appOpenEventIndexerSettings.setLastUpdateTimestampMillis(System.currentTimeMillis());
        removeFakeAppOpenEventDocuments(mContext, Executors.newSingleThreadExecutor());

        mUiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        mUiAutomation.adoptShellPermissionIdentity(PACKAGE_USAGE_STATS, OBSERVE_APP_USAGE);
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
    public void testRealDocuments_check() throws Exception {
        Intent launchIntent =
                mContext.getPackageManager().getLaunchIntentForPackage(mContext.getPackageName());
        Assert.assertNotNull(launchIntent);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT);
        mContext.startActivity(launchIntent);

        UsageStatsManager usageStatsManager = mContext.getSystemService(UsageStatsManager.class);

        CountDownLatch latch = new CountDownLatch(1);
        AppOpenEventIndexerManagerService appOpenEventIndexerManagerService =
                new AppOpenEventIndexerManagerService(
                        mContext, new TestAppOpenEventIndexerConfig(), latch::countDown);
        appOpenEventIndexerManagerService.mLocalService.doUpdateForUser(
                new SystemService.TargetUser(mUserInfo).getUserHandle(), new CancellationSignal());
        assertThat(latch.await(10, TimeUnit.SECONDS)).isEqualTo(true);

        // Search for most recently opened app open event
        SearchSpec searchSpec =
                new SearchSpec.Builder()
                        .addFilterNamespaces(AppOpenEvent.APP_OPEN_EVENT_NAMESPACE)
                        .setOrder(SearchSpec.ORDER_DESCENDING)
                        .setRankingStrategy(SearchSpec.RANKING_STRATEGY_CREATION_TIMESTAMP)
                        .build();
        AppSearchManager manager =
                ApplicationProvider.getApplicationContext()
                        .getSystemService(AppSearchManager.class);
        Executor executor =
                AppSearchEnvironmentFactory.getEnvironmentInstance().createSingleThreadExecutor();
        SyncGlobalSearchSession globalSearchSession =
                new SyncGlobalSearchSessionImpl(manager, executor);
        SyncSearchResults searchResults = globalSearchSession.search("", searchSpec);
        List<SearchResult> results = searchResults.getNextPage();

        for (int i = 0; i < results.size(); i++) {
            assertThat(results.get(i).getGenericDocument().getSchemaType())
                    .startsWith(AppOpenEvent.SCHEMA_TYPE);
        }

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
                    && (currentTimeMillis - timestampMillis) <= TimeUnit.SECONDS.toMillis(30)) {
                hasMatchingResult = true;
                break;
            }
        }

        // Assert that the matching result exists
        assertThat(hasMatchingResult).isTrue();
    }
}
