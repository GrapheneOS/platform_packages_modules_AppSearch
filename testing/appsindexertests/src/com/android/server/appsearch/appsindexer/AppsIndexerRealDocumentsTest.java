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

import static android.Manifest.permission.INTERACT_ACROSS_USERS_FULL;
import static android.Manifest.permission.READ_DEVICE_CONFIG;
import static android.Manifest.permission.RECEIVE_BOOT_COMPLETED;

import static com.android.server.appsearch.appsindexer.TestUtils.createFakeAppIndexerSession;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertTrue;

import static org.junit.Assume.assumeTrue;

import android.app.UiAutomation;
import android.app.appsearch.AppSearchEnvironmentFactory;
import android.app.appsearch.AppSearchManager;
import android.app.appsearch.AppSearchSessionShim;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.SearchResult;
import android.app.appsearch.SearchResultsShim;
import android.app.appsearch.SearchSpec;
import android.app.appsearch.SetSchemaRequest;
import android.app.appsearch.exceptions.AppSearchException;
import android.app.appsearch.testutil.AppSearchTestUtils;
import android.content.Intent;
import android.content.pm.UserInfo;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.SystemService;
import com.android.server.appsearch.appsindexer.appsearchtypes.MobileApplication;
import com.android.server.appsearch.flags.Flags;

import org.junit.After;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class AppsIndexerRealDocumentsTest extends AppsIndexerTestBase {
    @After
    @Override
    public void tearDown() throws Exception {
        AppSearchSessionShim db =
                createFakeAppIndexerSession(
                        ApplicationProvider.getApplicationContext(),
                        Executors.newSingleThreadExecutor());
        db.setSchemaAsync(new SetSchemaRequest.Builder().setForceOverride(true).build()).get();
        db.close();
        super.tearDown();
    }

    @Test
    public void testRealDocuments_check() throws AppSearchException {
        UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        uiAutomation.adoptShellPermissionIdentity(READ_DEVICE_CONFIG);
        assumeTrue(new FrameworkAppsIndexerConfig().isAppsIndexerEnabled());
        assumeTrue(Flags.appsIndexerEnabled());
        // Ensure that all documents in the android package and with the "apps" namespace are
        // MobileApplication documents. Read-only test as we are dealing with real apps
        SearchSpec searchSpec =
                new SearchSpec.Builder()
                        .addFilterPackageNames("android")
                        .addFilterNamespaces(MobileApplication.APPS_NAMESPACE)
                        .setResultCountPerPage(100)
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

        // There should be at least settings and other AOSP apps
        assertThat(results.size()).isGreaterThan(0);

        while (!results.isEmpty()) {
            for (int i = 0; i < results.size(); i++) {
                SearchResult result = results.get(i);
                assertThat(result.getGenericDocument().getSchemaType())
                        .startsWith(MobileApplication.SCHEMA_TYPE);
            }
            results = searchResults.getNextPage();
        }
    }

    // Created for system health trace, as close to real as we can get in a test
    @Test
    public void testRealIndexing() throws Exception {
        // Create a real manager service for the test package, no mocking. Use the captured
        // receiver to simulate package events
        UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        String testPackage = mContext.getPackageName();
        UserInfo userInfo =
                new UserInfo(
                        mContext.getUser().getIdentifier(), /* name= */ "default", /* flags= */ 0);

        android.os.Trace.beginSection("appIndexer");
        AppsIndexerManagerService appsIndexerManagerService =
                new AppsIndexerManagerService(mContext, new TestAppsIndexerConfig());

        uiAutomation.adoptShellPermissionIdentity(INTERACT_ACROSS_USERS_FULL);
        // This throws an error because the LocalService is already registered. That is fine, we
        // just need to register the receivers
        try {
            appsIndexerManagerService.onStart();
        } catch (Exception e) {
        }

        uiAutomation.adoptShellPermissionIdentity(RECEIVE_BOOT_COMPLETED);
        appsIndexerManagerService.onUserUnlocking(new SystemService.TargetUser(userInfo));

        int userId = new SystemService.TargetUser(userInfo).getUserHandle().getIdentifier();
        Intent intent = new Intent(Intent.ACTION_PACKAGE_ADDED);
        intent.setData(Uri.parse("package:" + mContext.getPackageName()));
        intent.putExtra(Intent.EXTRA_UID, userId);
        mCapturedReceiver.onReceive(mContext, intent);

        // As the apps get indexed at the same time, we just need to wait for one change.
        CountDownLatch latch = setupLatch(1, false);
        assertTrue(latch.await(10L, TimeUnit.SECONDS));
        mShim.unregisterObserverCallback(testPackage, mCallback);

        SearchResultsShim results =
                mShim.search(
                        "",
                        new SearchSpec.Builder()
                                .addFilterNamespaces(MobileApplication.APPS_NAMESPACE)
                                .setResultCountPerPage(50)
                                .addFilterPackageNames(testPackage, mContext.getPackageName())
                                .build());
        List<GenericDocument> documents =
                AppSearchTestUtils.convertSearchResultsToDocuments(results);
        assertThat(documents).isNotEmpty();
        assertThat(documents.get(0).getSchemaType()).startsWith(MobileApplication.SCHEMA_TYPE);

        appsIndexerManagerService.onUserStopping(new SystemService.TargetUser(userInfo));

        android.os.Trace.endSection();

        // Clear it out
        uiAutomation.dropShellPermissionIdentity();
        AppSearchSessionShim db =
                createFakeAppIndexerSession(mContext, Executors.newSingleThreadExecutor());
        db.setSchemaAsync(new SetSchemaRequest.Builder().setForceOverride(true).build()).get();
        db.close();
    }
}
