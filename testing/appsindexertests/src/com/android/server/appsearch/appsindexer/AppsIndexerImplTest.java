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

import static com.android.server.appsearch.appsindexer.TestUtils.createFakeAppFunctionResolveInfo;
import static com.android.server.appsearch.appsindexer.TestUtils.createFakeLaunchResolveInfo;
import static com.android.server.appsearch.appsindexer.TestUtils.createFakeMobileApplication;
import static com.android.server.appsearch.appsindexer.TestUtils.createFakePackageInfo;
import static com.android.server.appsearch.appsindexer.TestUtils.createFakePackageInfos;
import static com.android.server.appsearch.appsindexer.TestUtils.createFakeResolveInfos;
import static com.android.server.appsearch.appsindexer.TestUtils.createMockPackageIdentifiers;
import static com.android.server.appsearch.appsindexer.TestUtils.removeFakePackageDocuments;
import static com.android.server.appsearch.appsindexer.TestUtils.setupMockPackageManager;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import android.app.appsearch.GenericDocument;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;

import androidx.test.core.app.ApplicationProvider;

import com.android.server.appsearch.appsindexer.appsearchtypes.MobileApplication;

import com.google.common.collect.ImmutableList;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;
import org.mockito.MockitoSession;

import java.io.ByteArrayInputStream;
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
    private final AppsIndexerConfig mAppsIndexerConfig = new TestAppsIndexerConfig();
    private MockitoSession mMockitoSession;

    @Before
    public void setUp() throws Exception {
        mContext = ApplicationProvider.getApplicationContext();
        mAppSearchHelper = new AppSearchHelper(mContext);
    }

    @After
    public void tearDown() throws Exception {
        removeFakePackageDocuments(mContext, mSingleThreadedExecutor);
        mAppSearchHelper.close();
        if (mMockitoSession != null) {
            mMockitoSession.finishMocking();
        }
    }

    @Test
    public void testAppsIndexerImpl_removeApps() throws Exception {
        // Add some apps
        MobileApplication app1 = createFakeMobileApplication(0);
        MobileApplication app2 = createFakeMobileApplication(1);

        mAppSearchHelper.setSchemasForPackages(createMockPackageIdentifiers(2), new ArrayList<>());
        mAppSearchHelper.indexApps(
                ImmutableList.of(app1, app2),
                /* appFunctions= */ ImmutableList.of(),
                /* existingAppFunctions= */ ImmutableList.of(),
                new AppsUpdateStats());
        Map<String, Long> appTimestampMap = mAppSearchHelper.getAppsFromAppSearch();

        List<String> packageIds = new ArrayList<>(appTimestampMap.keySet());
        assertThat(packageIds).containsExactly("com.fake.package0", "com.fake.package1");

        // Set up mock so that just 1 document is returned, as if we deleted a doc
        PackageManager pm = Mockito.mock(PackageManager.class);
        setupMockPackageManager(
                pm,
                createFakePackageInfos(1),
                createFakeResolveInfos(1),
                /* appFunctionServices= */ ImmutableList.of());
        Context context =
                new ContextWrapper(mContext) {
                    @Override
                    public PackageManager getPackageManager() {
                        return pm;
                    }
                };
        try (AppsIndexerImpl appsIndexerImpl = new AppsIndexerImpl(context, mAppsIndexerConfig)) {
            appsIndexerImpl.doUpdate(
                    new AppsIndexerSettings(temporaryFolder.newFolder("temp")),
                    new AppsUpdateStats());

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
        try (AppsIndexerImpl appsIndexerImpl = new AppsIndexerImpl(context, mAppsIndexerConfig)) {
            appsIndexerImpl.doUpdate(
                    new AppsIndexerSettings(temporaryFolder.newFolder("tmp")),
                    new AppsUpdateStats());

            // Shouldn't throw, but no apps indexed
            assertThat(mAppSearchHelper.getAppsFromAppSearch()).isEmpty();
        }
    }

    @Test
    public void testAppsIndexerImpl_statsSet() throws Exception {
        // Simulate the first update: no changes, just adding initial apps
        PackageManager pm1 = Mockito.mock(PackageManager.class);
        setupMockPackageManager(
                pm1,
                createFakePackageInfos(3),
                createFakeResolveInfos(3),
                /* appFunctionServices= */ ImmutableList.of());
        Context context1 =
                new ContextWrapper(mContext) {
                    @Override
                    public PackageManager getPackageManager() {
                        return pm1;
                    }
                };

        // Perform the first update
        try (AppsIndexerImpl appsIndexerImpl = new AppsIndexerImpl(context1, mAppsIndexerConfig)) {
            AppsUpdateStats stats1 = new AppsUpdateStats();
            appsIndexerImpl.doUpdate(
                    new AppsIndexerSettings(temporaryFolder.newFolder("temp1")), stats1);

            // Check the stats object after the first update
            assertThat(stats1.mNumberOfAppsAdded).isEqualTo(3); // Three new apps added
            assertThat(stats1.mNumberOfAppsRemoved).isEqualTo(0); // No apps deleted
            assertThat(stats1.mNumberOfAppsUnchanged).isEqualTo(0); // No apps unchanged
            assertThat(stats1.mNumberOfAppsUpdated).isEqualTo(0); // No apps updated

            // Verify the state of the indexed apps after the first update
            assertThat(mAppSearchHelper.getAppsFromAppSearch().keySet())
                    .containsExactly("com.fake.package0", "com.fake.package1", "com.fake.package2");
        }

        PackageManager pm2 = Mockito.mock(PackageManager.class);
        // Simulate the second update: one app updated, one unchanged, one deleted, and one new
        // added. We'll remove package0, update package1, leave package2 unchanged, and add
        // package3.
        List<PackageInfo> fakePackages = new ArrayList<>(createFakePackageInfos(4));
        List<ResolveInfo> fakeActivities = new ArrayList<>(createFakeResolveInfos(4));
        int updateIndex = 1;
        fakePackages.get(updateIndex).lastUpdateTime = 1000;
        fakePackages.remove(0);
        fakeActivities.remove(0);

        setupMockPackageManager(
                pm2, fakePackages, fakeActivities, /* appFunctionServices= */ ImmutableList.of());
        Context context2 =
                new ContextWrapper(mContext) {
                    @Override
                    public PackageManager getPackageManager() {
                        return pm2;
                    }
                };

        // Perform the second update
        try (AppsIndexerImpl appsIndexerImpl = new AppsIndexerImpl(context2, mAppsIndexerConfig)) {
            AppsUpdateStats stats2 = new AppsUpdateStats();
            appsIndexerImpl.doUpdate(
                    new AppsIndexerSettings(temporaryFolder.newFolder("temp2")), stats2);

            // Check the stats object after the second update
            assertThat(stats2.mNumberOfAppsAdded).isEqualTo(1); // One new app added
            assertThat(stats2.mNumberOfAppsRemoved).isEqualTo(1); // One app deleted
            assertThat(stats2.mNumberOfAppsUnchanged).isEqualTo(1); // One app unchanged
            assertThat(stats2.mNumberOfAppsUpdated).isEqualTo(1); // One app updated

            // Verify the state of the indexed apps after the second update
            assertThat(mAppSearchHelper.getAppsFromAppSearch().keySet())
                    .containsExactly("com.fake.package1", "com.fake.package2", "com.fake.package3");
        }
    }

    @Test
    public void testAppsIndexerImpl_statsSet_functionsIndexed() throws Exception {
        // Simulate the first update: no changes, just adding initial apps
        PackageManager pm1 = Mockito.mock(PackageManager.class);
        List<PackageInfo> fakePackages = new ArrayList<>();
        List<ResolveInfo> fakeActivities = new ArrayList<>();
        List<ResolveInfo> fakeAppFunctionServices = new ArrayList<>();

        for (int i = 0; i < 5; i++) {
            fakePackages.add(createFakePackageInfo(i));
            fakeActivities.add(createFakeLaunchResolveInfo(i));
            fakeAppFunctionServices.add(createFakeAppFunctionResolveInfo(i));
        }

        when(pm1.getProperty(any(String.class), any(ComponentName.class)))
                .thenReturn(new PackageManager.Property("", "", "", ""));
        AssetManager assetManager = Mockito.mock(AssetManager.class);

        // Three functions initially. One will be deleted, another updated, the third left alone,
        // then a fourth added.
        String xml =
                "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n"
                        + "<version>1</version>\n"
                        + "<appfunctions>\n"
                        + "  <appfunction>\n"
                        + "    <function_id>com.example.utils#print</function_id>\n"
                        + "  </appfunction>\n"
                        + "  <appfunction>\n"
                        + "    <function_id>com.example.utils#search</function_id>\n"
                        + "  </appfunction>\n"
                        + "  <appfunction>\n"
                        + "    <function_id>com.example.utils#pay</function_id>\n"
                        + "  </appfunction>\n"
                        + "</appfunctions>";

        when(assetManager.open(any(String.class)))
                .thenReturn(
                        new ByteArrayInputStream(xml.getBytes()),
                        new ByteArrayInputStream(xml.getBytes()),
                        new ByteArrayInputStream(xml.getBytes()),
                        new ByteArrayInputStream(xml.getBytes()),
                        new ByteArrayInputStream(xml.getBytes()));

        Resources resources = Mockito.mock(Resources.class);
        when(resources.getAssets()).thenReturn(assetManager);
        when(pm1.getResourcesForApplication(any(String.class))).thenReturn(resources);
        setupMockPackageManager(pm1, fakePackages, fakeActivities, fakeAppFunctionServices);

        Context context1 =
                new ContextWrapper(mContext) {
                    @Override
                    public PackageManager getPackageManager() {
                        return pm1;
                    }
                };

        List<String> packages = new ArrayList<>();
        packages.add("com.fake.package0");
        packages.add("com.fake.package1");
        packages.add("com.fake.package2");
        packages.add("com.fake.package3");
        packages.add("com.fake.package4");

        List<String> expectedFunctionIds = new ArrayList<>();
        for (int i = 0; i < packages.size(); i++) {
            expectedFunctionIds.add(packages.get(i) + "/com.example.utils#pay");
            expectedFunctionIds.add(packages.get(i) + "/com.example.utils#search");
            expectedFunctionIds.add(packages.get(i) + "/com.example.utils#print");
        }

        // Perform the first update
        try (AppsIndexerImpl appsIndexerImpl = new AppsIndexerImpl(context1, mAppsIndexerConfig)) {
            AppsUpdateStats stats1 = new AppsUpdateStats();
            appsIndexerImpl.doUpdate(
                    new AppsIndexerSettings(temporaryFolder.newFolder("temp1")), stats1);

            // Check the stats object after the first update
            assertThat(stats1.mNumberOfAppsAdded).isEqualTo(5);
            assertThat(stats1.mNumberOfAppsRemoved).isEqualTo(0); // No apps deleted
            assertThat(stats1.mNumberOfAppsUnchanged).isEqualTo(0); // No apps unchanged
            assertThat(stats1.mNumberOfAppsUpdated).isEqualTo(0); // No apps updated

            // Currently we are logging added and updated and unchanged all as updated
            assertThat(stats1.mNumberOfFunctionsUpdated).isEqualTo(15); // One app updated
            assertThat(stats1.mNumberOfFunctionsAdded).isEqualTo(0);
            assertThat(stats1.mApproximateNumberOfFunctionsRemoved).isEqualTo(0);
            assertThat(stats1.mApproximateNumberOfFunctionsUnchanged).isEqualTo(0);

            List<GenericDocument> indexedFunctions =
                    mAppSearchHelper.getAppFunctionsFromAppSearch();
            List<String> indexedFunctionIds = new ArrayList<>();
            for (int i = 0; i < indexedFunctions.size(); i++) {
                indexedFunctionIds.add(indexedFunctions.get(i).getId());
            }
            // Verify the state of the indexed apps after the first update
            assertThat(indexedFunctionIds).containsExactlyElementsIn(expectedFunctionIds);
        }

        // Simulate an update
        for (int i = 0; i < 5; i++) {
            fakePackages.get(i).lastUpdateTime = 1000;
        }

        expectedFunctionIds = new ArrayList<>();
        for (int i = 0; i < packages.size(); i++) {
            expectedFunctionIds.add(packages.get(i) + "/com.example.utils#pay");
            expectedFunctionIds.add(packages.get(i) + "/com.example.utils#search");
            expectedFunctionIds.add(packages.get(i) + "/com.example.utils#scan_doc");
        }

        // Remove print, update search (with category), don't change pay, add scan doc
        String xml2 =
                "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n"
                        + "<version>1</version>\n"
                        + "<appfunctions>\n"
                        + "  <appfunction>\n"
                        + "    <function_id>com.example.utils#search</function_id>\n"
                        + "    <schema_category>utils</schema_category>\n"
                        + "  </appfunction>\n"
                        + "  <appfunction>\n"
                        + "    <function_id>com.example.utils#pay</function_id>\n"
                        + "  </appfunction>\n"
                        + "  <appfunction>\n"
                        + "    <function_id>com.example.utils#scan_doc</function_id>\n"
                        + "  </appfunction>\n"
                        + "</appfunctions>";

        // This is flaky, out of order
        when(assetManager.open(any(String.class)))
                .thenReturn(
                        new ByteArrayInputStream(xml2.getBytes()),
                        new ByteArrayInputStream(xml2.getBytes()),
                        new ByteArrayInputStream(xml2.getBytes()),
                        new ByteArrayInputStream(xml2.getBytes()),
                        new ByteArrayInputStream(xml2.getBytes()));

        // xml + xmlUpdatedFunction both seem to be treated as updates
        // or it's no functions + two functions

        try (AppsIndexerImpl appsIndexerImpl = new AppsIndexerImpl(context1, mAppsIndexerConfig)) {
            AppsUpdateStats stats1 = new AppsUpdateStats();
            appsIndexerImpl.doUpdate(
                    new AppsIndexerSettings(temporaryFolder.newFolder("temp2")), stats1);

            // Check the stats object after the first update
            assertThat(stats1.mNumberOfAppsAdded).isEqualTo(0);
            assertThat(stats1.mNumberOfAppsRemoved).isEqualTo(0);
            assertThat(stats1.mNumberOfAppsUnchanged).isEqualTo(0);
            assertThat(stats1.mNumberOfAppsUpdated).isEqualTo(5);

            // Currently we are logging added and updated and unchanged all as updated
            assertThat(stats1.mNumberOfFunctionsUpdated).isEqualTo(15);
            assertThat(stats1.mApproximateNumberOfFunctionsRemoved).isEqualTo(5);
            assertThat(stats1.mNumberOfFunctionsAdded).isEqualTo(0);
            assertThat(stats1.mApproximateNumberOfFunctionsUnchanged).isEqualTo(0);

            List<GenericDocument> indexedFunctions =
                    mAppSearchHelper.getAppFunctionsFromAppSearch();
            List<String> indexedFunctionIds = new ArrayList<>();
            for (int i = 0; i < indexedFunctions.size(); i++) {
                indexedFunctionIds.add(indexedFunctions.get(i).getId());
            }
            // Verify the state of the indexed apps after the first update
            assertThat(indexedFunctionIds).containsExactlyElementsIn(expectedFunctionIds);
        }
    }
}
