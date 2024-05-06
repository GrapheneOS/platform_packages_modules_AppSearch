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

import static com.android.server.appsearch.appsindexer.TestUtils.createFakeMobileApplication;
import static com.android.server.appsearch.appsindexer.TestUtils.createFakePackageInfos;
import static com.android.server.appsearch.appsindexer.TestUtils.createFakeResolveInfos;
import static com.android.server.appsearch.appsindexer.TestUtils.createMockPackageIdentifiers;
import static com.android.server.appsearch.appsindexer.TestUtils.removeFakePackageDocuments;
import static com.android.server.appsearch.appsindexer.TestUtils.setupMockPackageManager;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;

import androidx.test.core.app.ApplicationProvider;

import com.android.server.appsearch.appsindexer.appsearchtypes.MobileApplication;

import com.google.common.collect.ImmutableList;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AppsIndexerImplTest {
    private AppSearchHelper mAppSearchHelper;
    private Context mContext;
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();
    private final ExecutorService mSingleThreadedExecutor = Executors.newSingleThreadExecutor();

    @Before
    public void setUp() throws Exception {
        mContext = ApplicationProvider.getApplicationContext();
        mAppSearchHelper = AppSearchHelper.createAppSearchHelper(mContext);
    }

    @After
    public void tearDown() throws Exception {
        removeFakePackageDocuments(mContext, mSingleThreadedExecutor);
        mAppSearchHelper.close();
    }

    @Test
    public void testAppsIndexerImpl_removeApps() throws Exception {
        // Add some apps
        MobileApplication app1 = createFakeMobileApplication(0);
        MobileApplication app2 = createFakeMobileApplication(1);

        mAppSearchHelper.setSchemasForPackages(createMockPackageIdentifiers(2));
        mAppSearchHelper.indexApps(ImmutableList.of(app1, app2));
        Map<String, Long> appTimestampMap = mAppSearchHelper.getAppsFromAppSearch();

        List<String> packageIds = new ArrayList<>(appTimestampMap.keySet());
        assertThat(packageIds).containsExactly("com.fake.package0", "com.fake.package1");

        // Set up mock so that just 1 document is returned, as if we deleted a doc
        PackageManager pm = Mockito.mock(PackageManager.class);
        setupMockPackageManager(pm, createFakePackageInfos(1), createFakeResolveInfos(1));
        Context context =
                new ContextWrapper(mContext) {
                    @Override
                    public PackageManager getPackageManager() {
                        return pm;
                    }
                };
        try (AppsIndexerImpl appsIndexerImpl = new AppsIndexerImpl(context)) {
            appsIndexerImpl.doUpdate(new AppsIndexerSettings(temporaryFolder.newFolder("temp")));

            assertThat(mAppSearchHelper.getAppsFromAppSearch().keySet())
                    .containsExactly("com.fake.package0");
        }
    }

    @Test
    public void testAppsIndexerImpl_updateAppsThrowsError_shouldContinueOnError() throws Exception {
        PackageManager pm = Mockito.mock(PackageManager.class);
        when(pm.getInstalledPackages(any())).thenThrow(new RuntimeException("fake"));
        Context context =
                new ContextWrapper(mContext) {
                    @Override
                    public PackageManager getPackageManager() {
                        return pm;
                    }
                };
        try (AppsIndexerImpl appsIndexerImpl = new AppsIndexerImpl(context)) {
            appsIndexerImpl.doUpdate(new AppsIndexerSettings(temporaryFolder.newFolder("tmp")));

            // Shouldn't throw, but no apps indexed
            assertThat(mAppSearchHelper.getAppsFromAppSearch()).isEmpty();
        }
    }
}
