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

import static com.android.compatibility.common.util.ApiLevelUtil.isAtLeast;
import static com.android.server.appsearch.appsindexer.TestUtils.APP_FUNCTION_STATIC_METADATA_PARENT_PROPERTIES;
import static com.android.server.appsearch.appsindexer.TestUtils.APP_FUNCTION_STATIC_METADATA_PARENT_SCHEMA_XSD;
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

import static junit.framework.Assert.assertEquals;

import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.testutil.AppSearchTestUtils;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Build;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.util.ArraySet;

import androidx.test.core.app.ApplicationProvider;

import com.android.appsearch.flags.Flags;
import com.android.server.appsearch.appsindexer.appsearchtypes.AppFunctionDocument;
import com.android.server.appsearch.appsindexer.appsearchtypes.MobileApplication;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;
import org.mockito.MockitoSession;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AppsIndexerImplTest {

    private AppSearchHelper mAppSearchHelper;
    private Context mContext;
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Rule public final RuleChain mRuleChain = AppSearchTestUtils.createCommonTestRules();

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
                ImmutableList.of(app1, app2), /* appFunctions= */ ImmutableList.of());
        Map<String, MobileApplication> appTimestampMap =
                mAppSearchHelper.getAppsLastUpdatedTimeAndAppFunctionServiceEnabledFromAppSearch();

        List<String> packageIds = new ArrayList<>(appTimestampMap.keySet());
        assertThat(packageIds).containsExactly("com.fake.package0", "com.fake.package1");

        // Set up mock so that just 1 document is returned, as if we deleted a doc
        PackageManager pm = Mockito.mock(PackageManager.class);
        setupMockPackageManager(
                pm,
                createFakePackageInfos(1),
                createFakeResolveInfos(1),
                /* appFunctionServices= */ ImmutableList.of());
        Context context = createContextWithPackageManager(pm);
        try (AppsIndexerImpl appsIndexerImpl = new AppsIndexerImpl(context, mAppsIndexerConfig)) {
            appsIndexerImpl.doUpdateIncrementalPut(
                    new AppsIndexerSettings(),
                    new AppsUpdateStats(),
                    /* isFullUpdateRequired= */ false);

            Set<String> ids =
                    mAppSearchHelper
                            .getAppsLastUpdatedTimeAndAppFunctionServiceEnabledFromAppSearch()
                            .keySet();
            assertThat(ids).containsExactly("com.fake.package0");
        }
    }

    @Test
    public void testAppsIndexerImpl_updateAppsThrowsError_shouldContinueOnError() throws Exception {
        PackageManager pm = Mockito.mock(PackageManager.class);
        when(pm.getInstalledPackages(any())).thenThrow(new RuntimeException("fake"));
        Context context = createContextWithPackageManager(pm);
        try (AppsIndexerImpl appsIndexerImpl = new AppsIndexerImpl(context, mAppsIndexerConfig)) {
            appsIndexerImpl.doUpdateIncrementalPut(
                    new AppsIndexerSettings(),
                    new AppsUpdateStats(),
                    /* isFullUpdateRequired= */ false);

            // Shouldn't throw, but no apps indexed
            Set<String> ids =
                    mAppSearchHelper
                            .getAppsLastUpdatedTimeAndAppFunctionServiceEnabledFromAppSearch()
                            .keySet();
            assertThat(ids).isEmpty();
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
        Context context1 = createContextWithPackageManager(pm1);

        // Perform the first update
        try (AppsIndexerImpl appsIndexerImpl = new AppsIndexerImpl(context1, mAppsIndexerConfig)) {
            AppsUpdateStats stats1 = new AppsUpdateStats();
            appsIndexerImpl.doUpdateIncrementalPut(
                    new AppsIndexerSettings(), stats1, /* isFullUpdateRequired= */ false);

            // Check the stats object after the first update
            assertThat(stats1.mNumberOfAppsAdded).isEqualTo(3); // Three new apps added
            assertThat(stats1.mNumberOfAppsRemoved).isEqualTo(0); // No apps deleted
            assertThat(stats1.mNumberOfAppsUnchanged).isEqualTo(0); // No apps unchanged
            assertThat(stats1.mNumberOfAppsUpdated).isEqualTo(0); // No apps updated

            // Verify the state of the indexed apps after the first update
            Set<String> ids =
                    mAppSearchHelper
                            .getAppsLastUpdatedTimeAndAppFunctionServiceEnabledFromAppSearch()
                            .keySet();
            assertThat(ids)
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
        Context context2 = createContextWithPackageManager(pm2);

        // Perform the second update
        try (AppsIndexerImpl appsIndexerImpl = new AppsIndexerImpl(context2, mAppsIndexerConfig)) {
            AppsUpdateStats stats2 = new AppsUpdateStats();
            appsIndexerImpl.doUpdateIncrementalPut(
                    new AppsIndexerSettings(), stats2, /* isFullUpdateRequired= */ false);

            // Check the stats object after the second update
            assertThat(stats2.mNumberOfAppsAdded).isEqualTo(1); // One new app added
            assertThat(stats2.mNumberOfAppsRemoved).isEqualTo(1); // One app deleted
            assertThat(stats2.mNumberOfAppsUnchanged).isEqualTo(1); // One app unchanged
            assertThat(stats2.mNumberOfAppsUpdated).isEqualTo(1); // One app updated

            // Verify the state of the indexed apps after the second update
            Set<String> ids =
                    mAppSearchHelper
                            .getAppsLastUpdatedTimeAndAppFunctionServiceEnabledFromAppSearch()
                            .keySet();
            assertThat(ids)
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

        when(pm1.getProperty(eq("android.app.appfunctions"), any(ComponentName.class)))
                .thenReturn(new PackageManager.Property("", "app_functions.xml", "", ""));
        when(pm1.getProperty(eq("android.app.appfunctions.schema"), any(String.class)))
                .thenThrow(PackageManager.NameNotFoundException.class);
        when(pm1.getProperty(eq("android.app.appfunctions.schema"), any(ComponentName.class)))
                .thenThrow(PackageManager.NameNotFoundException.class);
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
        when(assetManager.open(eq("app_functions.xml")))
                .thenReturn(
                        new ByteArrayInputStream(xml.getBytes()),
                        new ByteArrayInputStream(xml.getBytes()),
                        new ByteArrayInputStream(xml.getBytes()),
                        new ByteArrayInputStream(xml.getBytes()),
                        new ByteArrayInputStream(xml.getBytes()));
        setUpResourcesForApp(assetManager, pm1, fakePackages.get(0).packageName);
        setUpResourcesForApp(assetManager, pm1, fakePackages.get(1).packageName);
        setUpResourcesForApp(assetManager, pm1, fakePackages.get(2).packageName);
        setUpResourcesForApp(assetManager, pm1, fakePackages.get(3).packageName);
        setUpResourcesForApp(assetManager, pm1, fakePackages.get(4).packageName);
        setupMockPackageManager(pm1, fakePackages, fakeActivities, fakeAppFunctionServices);
        Context context1 = createContextWithPackageManager(pm1);

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
            appsIndexerImpl.doUpdateIncrementalPut(
                    new AppsIndexerSettings(), stats1, /* isFullUpdateRequired= */ true);

            // Check the stats object after the first update
            assertThat(stats1.mNumberOfAppsAdded).isEqualTo(5);
            assertThat(stats1.mNumberOfAppsRemoved).isEqualTo(0); // No apps deleted
            assertThat(stats1.mNumberOfAppsUnchanged).isEqualTo(0); // No apps unchanged
            assertThat(stats1.mNumberOfAppsUpdated).isEqualTo(0); // No apps updated

            assertThat(stats1.mNumberOfFunctionsUpdated).isEqualTo(0);
            assertThat(stats1.mNumberOfFunctionsAdded).isEqualTo(15);
            assertThat(stats1.mApproximateNumberOfFunctionsRemoved).isEqualTo(0);

            List<String> indexedFunctionIds = new ArrayList<>();
            Map<String, Map<String, AppFunctionDocument>> indexedFunctions =
                    mAppSearchHelper.getAppFunctionDocumentsFromAppSearch(new ArraySet<>(packages));
            for (Map<String, AppFunctionDocument> functions : indexedFunctions.values()) {
                for (AppFunctionDocument function : functions.values()) {
                    indexedFunctionIds.add(function.getId());
                }
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
            appsIndexerImpl.doUpdateIncrementalPut(
                    new AppsIndexerSettings(), stats1, /* isFullUpdateRequired= */ false);

            // Check the stats object after the first update
            assertThat(stats1.mNumberOfAppsAdded).isEqualTo(0);
            assertThat(stats1.mNumberOfAppsRemoved).isEqualTo(0);
            assertThat(stats1.mNumberOfAppsUnchanged).isEqualTo(0);
            assertThat(stats1.mNumberOfAppsUpdated).isEqualTo(5);

            assertThat(stats1.mNumberOfFunctionsUpdated).isEqualTo(5);
            assertThat(stats1.mApproximateNumberOfFunctionsRemoved).isEqualTo(5);
            assertThat(stats1.mNumberOfFunctionsAdded).isEqualTo(5);

            List<String> indexedFunctionIds = new ArrayList<>();
            Map<String, Map<String, AppFunctionDocument>> indexedFunctions =
                    mAppSearchHelper.getAppFunctionDocumentsFromAppSearch(new ArraySet<>(packages));
            for (Map<String, AppFunctionDocument> functions : indexedFunctions.values()) {
                for (AppFunctionDocument function : functions.values()) {
                    indexedFunctionIds.add(function.getId());
                }
            }
            // Verify the state of the indexed apps after the first update
            assertThat(indexedFunctionIds).containsExactlyElementsIn(expectedFunctionIds);
        }
    }

    @Test
    public void testAppsIndexerImpl_fullUpdateRequired_reIndexAllApps() throws Exception {
        PackageManager pm1 = Mockito.mock(PackageManager.class);
        List<PackageInfo> fakePackageInfos = createFakePackageInfos(3);
        List<ResolveInfo> fakeResolveInfos = createFakeResolveInfos(3);
        setupMockPackageManager(
                pm1,
                fakePackageInfos,
                fakeResolveInfos,
                /* appFunctionServices= */ ImmutableList.of());
        Context context1 = createContextWithPackageManager(pm1);
        // Perform the first update
        try (AppsIndexerImpl appsIndexerImpl = new AppsIndexerImpl(context1, mAppsIndexerConfig)) {
            AppsUpdateStats stats = new AppsUpdateStats();
            appsIndexerImpl.doUpdateIncrementalPut(
                    new AppsIndexerSettings(), stats, /* isFullUpdateRequired= */ true);

            // Check the stats object after the first update
            assertThat(stats.mNumberOfAppsAdded).isEqualTo(3); // Three new apps added
            assertThat(stats.mNumberOfAppsRemoved).isEqualTo(0); // No apps deleted
            assertThat(stats.mNumberOfAppsUnchanged).isEqualTo(0); // No apps unchanged
            assertThat(stats.mNumberOfAppsUpdated).isEqualTo(0); // No apps updated
        }

        // Update only 1 app and run the indexer with isFullUpdateRequired=true
        fakePackageInfos.get(1).lastUpdateTime = 1000;

        try (AppsIndexerImpl appsIndexerImpl = new AppsIndexerImpl(context1, mAppsIndexerConfig)) {
            AppsUpdateStats stats = new AppsUpdateStats();
            appsIndexerImpl.doUpdateIncrementalPut(
                    new AppsIndexerSettings(), stats, /* isFullUpdateRequired= */ true);

            // Check the stats object after the first update
            assertThat(stats.mNumberOfAppsAdded).isEqualTo(0);
            assertThat(stats.mNumberOfAppsRemoved).isEqualTo(0);
            assertThat(stats.mNumberOfAppsUnchanged).isEqualTo(0);
            assertThat(stats.mNumberOfAppsUpdated).isEqualTo(3); // All 3 apps updated.
        }
    }

    // This does not have the @RequiresFlagEnabled annotation as it directly calls the "incremental
    // update" path.
    @Test
    public void testAppsIndexerImpl_incrementalPut_doesNotPutAllDocs() throws Exception {
        // Index a package with 1 function, modify the function document timestamp, re-index the app
        // with an additional function, ensure the modification to the original document hasn't
        // changed.

        // Simulate the first update: no changes, just adding initial apps
        PackageManager pm1 = Mockito.mock(PackageManager.class);
        List<PackageInfo> fakePackages = ImmutableList.of(createFakePackageInfo(0));
        List<ResolveInfo> fakeActivities = ImmutableList.of(createFakeLaunchResolveInfo(0));
        List<ResolveInfo> fakeAppFunctionServices =
                ImmutableList.of(createFakeAppFunctionResolveInfo(0));
        when(pm1.getProperty(any(String.class), any(String.class)))
                .thenThrow(PackageManager.NameNotFoundException.class);
        when(pm1.getProperty(any(String.class), any(ComponentName.class)))
                .thenThrow(PackageManager.NameNotFoundException.class);
        when(pm1.getProperty(eq("android.app.appfunctions"), any(ComponentName.class)))
                .thenReturn(new PackageManager.Property("", "app_functions.xml", "", ""));

        AssetManager assetManager = Mockito.mock(AssetManager.class);

        // One functions initially
        String xml =
                "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n"
                        + "<version>1</version>\n"
                        + "<appfunctions>\n"
                        + "  <appfunction>\n"
                        + "    <function_id>com.example.utils#print</function_id>\n"
                        + "  </appfunction>\n"
                        + "</appfunctions>";

        when(assetManager.open(eq("app_functions.xml")))
                .thenReturn(new ByteArrayInputStream(xml.getBytes()));

        Resources resources = Mockito.mock(Resources.class);
        when(resources.getAssets()).thenReturn(assetManager);
        when(pm1.getResourcesForApplication(any(String.class))).thenReturn(resources);
        setupMockPackageManager(pm1, fakePackages, fakeActivities, fakeAppFunctionServices);

        Context context1 = createContextWithPackageManager(pm1);

        Set<String> packages = ImmutableSet.of("com.fake.package0");

        // Perform the first update
        try (AppsIndexerImpl appsIndexerImpl = new AppsIndexerImpl(context1, mAppsIndexerConfig)) {
            AppsUpdateStats stats1 = new AppsUpdateStats();
            appsIndexerImpl.doUpdateIncrementalPut(
                    new AppsIndexerSettings(), stats1, /* isFullUpdateRequired= */ false);

            // Check the stats object after the first update
            assertThat(stats1.mNumberOfAppsAdded).isEqualTo(1);
            assertThat(stats1.mNumberOfAppsRemoved).isEqualTo(0);
            assertThat(stats1.mNumberOfAppsUnchanged).isEqualTo(0);
            assertThat(stats1.mNumberOfAppsUpdated).isEqualTo(0);

            assertThat(stats1.mNumberOfFunctionsUpdated).isEqualTo(0);
            assertThat(stats1.mApproximateNumberOfFunctionsRemoved).isEqualTo(0);
            assertThat(stats1.mNumberOfFunctionsAdded).isEqualTo(1);

            // Verify the state of the indexed apps after the first update
            assertThat(mAppSearchHelper.getAppFunctionDocumentsFromAppSearch(packages).keySet())
                    .containsExactlyElementsIn(packages);
        }

        // Manually modify the AppSearch function document timestamp
        Map<String, Map<String, AppFunctionDocument>> indexedFunctions =
                mAppSearchHelper.getAppFunctionDocumentsFromAppSearch(packages);
        GenericDocument original =
                indexedFunctions
                        .get("com.fake.package0")
                        .get("com.fake.package0/com.example.utils#print");
        long firstPutTimestamp = original.getCreationTimestampMillis();

        // Simulate an update
        fakePackages.get(0).lastUpdateTime = 1000;

        // Add a function without modifying the first
        String xml2 =
                "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n"
                        + "<version>1</version>\n"
                        + "<appfunctions>\n"
                        + "  <appfunction>\n"
                        + "    <function_id>com.example.utils#print</function_id>\n"
                        + "  </appfunction>\n"
                        + "  <appfunction>\n"
                        + "    <function_id>com.example.utils#search</function_id>\n"
                        + "    <schema_category>utils</schema_category>\n"
                        + "  </appfunction>\n"
                        + "</appfunctions>";

        when(assetManager.open(any(String.class)))
                .thenReturn(new ByteArrayInputStream(xml2.getBytes()));

        try (AppsIndexerImpl appsIndexerImpl = new AppsIndexerImpl(context1, mAppsIndexerConfig)) {
            AppsUpdateStats stats1 = new AppsUpdateStats();
            appsIndexerImpl.doUpdateIncrementalPut(
                    new AppsIndexerSettings(), stats1, /* isFullUpdateRequired= */ false);

            // Check the stats object after the first update
            assertThat(stats1.mNumberOfAppsAdded).isEqualTo(0);
            assertThat(stats1.mNumberOfAppsRemoved).isEqualTo(0);
            assertThat(stats1.mNumberOfAppsUnchanged).isEqualTo(0);
            assertThat(stats1.mNumberOfAppsUpdated).isEqualTo(1);

            assertThat(stats1.mNumberOfFunctionsUpdated).isEqualTo(0);
            assertThat(stats1.mApproximateNumberOfFunctionsRemoved).isEqualTo(0);
            assertThat(stats1.mNumberOfFunctionsAdded).isEqualTo(1);

            assertThat(mAppSearchHelper.getAppFunctionDocumentsFromAppSearch(packages).keySet())
                    .containsExactlyElementsIn(packages);
        }
        indexedFunctions = mAppSearchHelper.getAppFunctionDocumentsFromAppSearch(packages);
        GenericDocument unchangedFunctions =
                indexedFunctions
                        .get("com.fake.package0")
                        .get("com.fake.package0/com.example.utils#print");
        GenericDocument addedFunction =
                indexedFunctions
                        .get("com.fake.package0")
                        .get("com.fake.package0/com.example.utils#search");

        assertEquals(unchangedFunctions.getCreationTimestampMillis(), firstPutTimestamp);
        assertThat(addedFunction.getCreationTimestampMillis()).isGreaterThan(firstPutTimestamp);
    }

    @Test
    public void testAppsIndexerImpl_incrementalPut_allFunctionsRemovedButAppFunctionServicePresent()
            throws Exception {
        PackageManager pm1 = Mockito.mock(PackageManager.class);
        List<PackageInfo> fakePackages = ImmutableList.of(createFakePackageInfo(0));
        List<ResolveInfo> fakeActivities = ImmutableList.of(createFakeLaunchResolveInfo(0));
        List<ResolveInfo> fakeAppFunctionServices =
                ImmutableList.of(createFakeAppFunctionResolveInfo(0));
        when(pm1.getProperty(eq("android.app.appfunctions.schema"), any(String.class)))
                .thenThrow(PackageManager.NameNotFoundException.class);
        when(pm1.getProperty(eq("android.app.appfunctions.schema"), any(ComponentName.class)))
                .thenThrow(PackageManager.NameNotFoundException.class);
        when(pm1.getProperty(eq("android.app.appfunctions"), any(ComponentName.class)))
                .thenReturn(new PackageManager.Property("", "app_functions.xml", "", ""));
        AssetManager assetManager = Mockito.mock(AssetManager.class);
        // One functions initially
        String xml =
                "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n"
                        + "<version>1</version>\n"
                        + "<appfunctions>\n"
                        + "  <appfunction>\n"
                        + "    <function_id>com.example.utils#print</function_id>\n"
                        + "  </appfunction>\n"
                        + "</appfunctions>";
        when(assetManager.open(eq("app_functions.xml")))
                .thenReturn(new ByteArrayInputStream(xml.getBytes()));
        setUpResourcesForApp(assetManager, pm1, fakePackages.get(0).packageName);
        setupMockPackageManager(pm1, fakePackages, fakeActivities, fakeAppFunctionServices);
        Context context1 = createContextWithPackageManager(pm1);
        Set<String> packages = ImmutableSet.of("com.fake.package0");
        // Perform the first update
        try (AppsIndexerImpl appsIndexerImpl = new AppsIndexerImpl(context1, mAppsIndexerConfig)) {
            AppsUpdateStats stats1 = new AppsUpdateStats();
            appsIndexerImpl.doUpdateIncrementalPut(
                    new AppsIndexerSettings(), stats1, /* isFullUpdateRequired= */ false);

            assertThat(stats1.mNumberOfFunctionsUpdated).isEqualTo(0);
            assertThat(stats1.mApproximateNumberOfFunctionsRemoved).isEqualTo(0);
            assertThat(stats1.mNumberOfFunctionsAdded).isEqualTo(1);
        }

        // Simulate an update
        fakePackages.get(0).lastUpdateTime = 1000;
        // Remove the function
        String xmlWithNoFunctions =
                "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n"
                        + "<version>1</version>\n"
                        + "<appfunctions>\n"
                        + "</appfunctions>";
        when(assetManager.open(eq("app_functions.xml")))
                .thenReturn(new ByteArrayInputStream(xmlWithNoFunctions.getBytes()));
        try (AppsIndexerImpl appsIndexerImpl = new AppsIndexerImpl(context1, mAppsIndexerConfig)) {
            AppsUpdateStats stats1 = new AppsUpdateStats();
            appsIndexerImpl.doUpdateIncrementalPut(
                    new AppsIndexerSettings(), stats1, /* isFullUpdateRequired= */ false);
            assertThat(stats1.mNumberOfFunctionsUpdated).isEqualTo(0);
            assertThat(stats1.mApproximateNumberOfFunctionsRemoved).isEqualTo(1);
            assertThat(stats1.mNumberOfFunctionsAdded).isEqualTo(0);
        }

        assertThat(mAppSearchHelper.getAppFunctionDocumentsFromAppSearch(packages).keySet())
                .isEmpty();
    }

    @Test
    @RequiresFlagsEnabled({
        Flags.FLAG_ENABLE_APP_FUNCTIONS_SCHEMA_PARSER,
        android.app.appfunctions.flags.Flags.FLAG_ENABLE_DYNAMIC_APP_FUNCTIONS
    })
    public void testAppsIndexerImpl_withApplicationLevelSchema_indexesAppFunctions()
            throws Exception {
        assumeTrue(isAtLeast(Build.VERSION_CODES.BAKLAVA));
        PackageManager pm1 = Mockito.mock(PackageManager.class);
        PackageInfo app = createFakePackageInfo(0);
        ResolveInfo appResolveInfo = createFakeLaunchResolveInfo(0);
        ResolveInfo appFunctionResolveInfo = createFakeAppFunctionResolveInfo(0);
        AssetManager assetManager = Mockito.mock(AssetManager.class);
        when(assetManager.open(eq("app_function_schema.xml")))
                .thenReturn(
                        new ByteArrayInputStream(
                                APP_FUNCTION_STATIC_METADATA_PARENT_SCHEMA_XSD.getBytes()));
        String appFunctionsXml =
                "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n"
                        + "<appfunctions>\n"
                        + "  <AppFunctionStaticMetadata>\n"
                        + "    <id>com.appLevelSchema.utils#print</id>\n"
                        + "    <functionId>com.appLevelSchema.utils#print</functionId>\n"
                        + "  </AppFunctionStaticMetadata>\n"
                        + "</appfunctions>";
        when(assetManager.open(eq("app_functions.xml")))
                .thenReturn(new ByteArrayInputStream(appFunctionsXml.getBytes()));
        setUpResourcesForApp(assetManager, pm1, app.packageName);
        setupMockPackageManager(
                pm1,
                ImmutableList.of(app),
                ImmutableList.of(appResolveInfo),
                ImmutableList.of(appFunctionResolveInfo));
        Context context1 = createContextWithPackageManager(pm1);
        when(pm1.getProperty(eq("android.app.appfunctions.schema"), eq(app.packageName)))
                .thenReturn(new PackageManager.Property("", "app_function_schema.xml", "", ""));
        when(pm1.getProperty(
                        eq("android.app.appfunctions.v2"),
                        eq(
                                new ComponentName(
                                        appFunctionResolveInfo.serviceInfo.packageName,
                                        appFunctionResolveInfo.serviceInfo.name))))
                .thenReturn(new PackageManager.Property("", "app_functions.xml", "", ""));

        try (AppsIndexerImpl appsIndexerImpl = new AppsIndexerImpl(context1, mAppsIndexerConfig)) {
            appsIndexerImpl.doUpdateIncrementalPut(
                    new AppsIndexerSettings(),
                    new AppsUpdateStats(),
                    /* isFullUpdateRequired= */ false);

            Map<String, Map<String, AppFunctionDocument>> indexedFunctions =
                    mAppSearchHelper.getAppFunctionDocumentsFromAppSearch(
                            ImmutableSet.of(app.packageName));
            assertThat(indexedFunctions.keySet()).containsExactly(app.packageName);
            assertThat(indexedFunctions.get(app.packageName).keySet())
                    .containsExactly("com.fake.package0/com.appLevelSchema.utils#print");
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_APP_FUNCTIONS_SCHEMA_PARSER)
    public void testAppsIndexerImpl_withDynamicAndNoSchemasDefinedInApp_indexesAppFunctions()
            throws Exception {
        // Dynamic schema defined in the app1.
        PackageManager pm1 = Mockito.mock(PackageManager.class);
        PackageInfo dynamicSchemaApp = createFakePackageInfo(0);
        ResolveInfo dynamicSchemaAppResolveInfo = createFakeLaunchResolveInfo(0);
        ResolveInfo dynamicSchemaAppFunctionResolveInfo = createFakeAppFunctionResolveInfo(0);
        setUpAppFunctionProperties(pm1, dynamicSchemaAppFunctionResolveInfo);
        AssetManager assetManager = Mockito.mock(AssetManager.class);
        when(assetManager.open(eq("app_function_schema.xml")))
                .thenReturn(
                        new ByteArrayInputStream(
                                APP_FUNCTION_STATIC_METADATA_PARENT_SCHEMA_XSD.getBytes()));
        String appFunctionsXml =
                "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n"
                        + "<appfunctions>\n"
                        + "  <AppFunctionStaticMetadata>\n"
                        + "    <id>com.dynamicSchemaApp.utils#print</id>\n"
                        + "    <functionId>com.dynamicSchemaApp.utils#print</functionId>\n"
                        + "  </AppFunctionStaticMetadata>\n"
                        + "</appfunctions>";
        when(assetManager.open(eq("app_functions.xml")))
                .thenReturn(new ByteArrayInputStream(appFunctionsXml.getBytes()));
        setUpResourcesForApp(assetManager, pm1, dynamicSchemaApp.packageName);
        // No schema defined in the app2.
        PackageInfo noSchemaApp = createFakePackageInfo(1);
        ResolveInfo noSchemaAppResolveInfo = createFakeLaunchResolveInfo(1);
        ResolveInfo noSchemaAppFunctionResolveInfo = createFakeAppFunctionResolveInfo(1);
        when(pm1.getProperty(
                        eq("android.app.appfunctions.schema"),
                        eq(
                                new ComponentName(
                                        noSchemaAppFunctionResolveInfo.serviceInfo.packageName,
                                        noSchemaAppFunctionResolveInfo.serviceInfo.name))))
                .thenThrow(new PackageManager.NameNotFoundException());
        when(pm1.getProperty(
                        eq("android.app.appfunctions"),
                        eq(
                                new ComponentName(
                                        noSchemaAppFunctionResolveInfo.serviceInfo.packageName,
                                        noSchemaAppFunctionResolveInfo.serviceInfo.name))))
                .thenReturn(new PackageManager.Property("", "app_functions.xml", "", ""));
        AssetManager assetManager2 = Mockito.mock(AssetManager.class);
        String appFunctionsXml2 =
                "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n"
                        + "<appfunctions>\n"
                        + "  <appfunction>\n"
                        + "    <function_id>com.noSchemaApp.utils#print</function_id>\n"
                        + "  </appfunction>\n"
                        + "</appfunctions>";
        when(assetManager2.open(eq("app_functions.xml")))
                .thenReturn(new ByteArrayInputStream(appFunctionsXml2.getBytes()));
        setUpResourcesForApp(assetManager2, pm1, noSchemaApp.packageName);
        setupMockPackageManager(
                pm1,
                ImmutableList.of(dynamicSchemaApp, noSchemaApp),
                ImmutableList.of(dynamicSchemaAppResolveInfo, noSchemaAppResolveInfo),
                ImmutableList.of(
                        dynamicSchemaAppFunctionResolveInfo, noSchemaAppFunctionResolveInfo));
        Context context1 = createContextWithPackageManager(pm1);

        try (AppsIndexerImpl appsIndexerImpl = new AppsIndexerImpl(context1, mAppsIndexerConfig)) {
            appsIndexerImpl.doUpdateIncrementalPut(
                    new AppsIndexerSettings(),
                    new AppsUpdateStats(),
                    /* isFullUpdateRequired= */ false);

            Map<String, Map<String, AppFunctionDocument>> indexedFunctions =
                    mAppSearchHelper.getAppFunctionDocumentsFromAppSearch(
                            ImmutableSet.of(dynamicSchemaApp.packageName, noSchemaApp.packageName));
            assertThat(indexedFunctions.keySet())
                    .containsExactly(dynamicSchemaApp.packageName, noSchemaApp.packageName);
            assertThat(indexedFunctions.get(dynamicSchemaApp.packageName).keySet())
                    .containsExactly("com.fake.package0/com.dynamicSchemaApp.utils#print");
            assertThat(indexedFunctions.get(noSchemaApp.packageName).keySet())
                    .containsExactly("com.fake.package1/com.noSchemaApp.utils#print");
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_APP_FUNCTIONS_SCHEMA_PARSER)
    public void testAppsIndexerImpl_withValidAndInvalidSchemas_indexesOnlyValidSchemaApp()
            throws Exception {
        // Valid schema
        PackageManager pm1 = Mockito.mock(PackageManager.class);
        PackageInfo validSchemaApp = createFakePackageInfo(0);
        ResolveInfo validSchemaAppResolveInfo = createFakeLaunchResolveInfo(0);
        ResolveInfo validSchemaAppFunctionResolveInfo = createFakeAppFunctionResolveInfo(0);
        setUpAppFunctionProperties(pm1, validSchemaAppFunctionResolveInfo);
        AssetManager assetManager = Mockito.mock(AssetManager.class);
        when(assetManager.open(eq("app_function_schema.xml")))
                .thenReturn(
                        new ByteArrayInputStream(
                                APP_FUNCTION_STATIC_METADATA_PARENT_SCHEMA_XSD.getBytes()));
        String appFunctionsXml =
                "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n"
                        + "<appfunctions>\n"
                        + "  <AppFunctionStaticMetadata>\n"
                        + "    <id>com.validSchemaApp.utils#print</id>\n"
                        + "    <functionId>com.validSchemaApp.utils#print</functionId>\n"
                        + "  </AppFunctionStaticMetadata>\n"
                        + "</appfunctions>";
        when(assetManager.open(eq("app_functions.xml")))
                .thenReturn(new ByteArrayInputStream(appFunctionsXml.getBytes()));
        setUpResourcesForApp(assetManager, pm1, validSchemaApp.packageName);
        // Invalid schema with more than 64 indexable properties.
        PackageInfo invalidSchemaApp = createFakePackageInfo(1);
        ResolveInfo invalidSchemaAppResolveInfo = createFakeLaunchResolveInfo(1);
        ResolveInfo invalidSchemaAppFunctionResolveInfo = createFakeAppFunctionResolveInfo(1);
        setUpAppFunctionProperties(pm1, invalidSchemaAppFunctionResolveInfo);
        AssetManager assetManager2 = Mockito.mock(AssetManager.class);
        StringBuilder invalidSchemaXml =
                new StringBuilder(
                        "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">"
                                + "    <xs:documentType name=\"AppFunctionStaticMetadata\">"
                                + APP_FUNCTION_STATIC_METADATA_PARENT_PROPERTIES);
        for (int i = 0; i < 100; i++) {
            invalidSchemaXml
                    .append("<xs:element name=\"randomProperty")
                    .append(i)
                    .append("\" type=\"xs:long\" indexingType=\"")
                    .append(AppSearchSchema.LongPropertyConfig.INDEXING_TYPE_RANGE)
                    .append("\" />");
        }
        invalidSchemaXml.append("    </xs:documentType>").append("</xs:schema>");
        when(assetManager2.open(eq("app_function_schema.xml")))
                .thenReturn(new ByteArrayInputStream(invalidSchemaXml.toString().getBytes()));
        String appFunctionsXml2 =
                "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n"
                        + "<appfunctions>\n"
                        + "  <AppFunctionStaticMetadata>\n"
                        + "    <id>com.invalidSchemaApp.utils#print</id>\n"
                        + "    <functionId>com.invalidSchemaApp.utils#print</functionId>\n"
                        + "  </AppFunctionStaticMetadata>\n"
                        + "</appfunctions>";
        when(assetManager2.open(eq("app_functions.xml")))
                .thenReturn(new ByteArrayInputStream(appFunctionsXml2.getBytes()));
        setUpResourcesForApp(assetManager2, pm1, invalidSchemaApp.packageName);
        setupMockPackageManager(
                pm1,
                ImmutableList.of(validSchemaApp, invalidSchemaApp),
                ImmutableList.of(validSchemaAppResolveInfo, invalidSchemaAppResolveInfo),
                ImmutableList.of(
                        validSchemaAppFunctionResolveInfo, invalidSchemaAppFunctionResolveInfo));
        Context context1 = createContextWithPackageManager(pm1);

        try (AppsIndexerImpl appsIndexerImpl = new AppsIndexerImpl(context1, mAppsIndexerConfig)) {
            appsIndexerImpl.doUpdateIncrementalPut(
                    new AppsIndexerSettings(),
                    new AppsUpdateStats(),
                    /* isFullUpdateRequired= */ false);

            Map<String, Map<String, AppFunctionDocument>> indexedFunctions =
                    mAppSearchHelper.getAppFunctionDocumentsFromAppSearch(
                            ImmutableSet.of(
                                    validSchemaApp.packageName, invalidSchemaApp.packageName));
            assertThat(indexedFunctions.keySet()).containsExactly(validSchemaApp.packageName);
            assertThat(indexedFunctions.get(validSchemaApp.packageName).keySet())
                    .containsExactly("com.fake.package0/com.validSchemaApp.utils#print");
            assertThat(indexedFunctions.keySet()).doesNotContain(invalidSchemaApp.packageName);
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_APP_FUNCTIONS_SCHEMA_PARSER)
    public void testAppsIndexerImpl_indexesMultipleAppsWithDynamicSchema() throws Exception {
        PackageManager pm1 = Mockito.mock(PackageManager.class);
        PackageInfo schemaApp1 = createFakePackageInfo(0);
        ResolveInfo schemaApp1ResolveInfo = createFakeLaunchResolveInfo(0);
        ResolveInfo schemaApp1FunctionResolveInfo = createFakeAppFunctionResolveInfo(0);
        setUpAppFunctionProperties(pm1, schemaApp1FunctionResolveInfo);
        AssetManager assetManager = Mockito.mock(AssetManager.class);
        String xsd =
                "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">"
                        + "    <xs:documentType name=\"AppFunctionStaticMetadata\">"
                        + APP_FUNCTION_STATIC_METADATA_PARENT_PROPERTIES
                        + "        <xs:element name=\"inner\" type=\"appfn:InnerType\" />"
                        + "    </xs:documentType>"
                        + "    <xs:documentType name=\"InnerType\">"
                        + "        <xs:element name=\"value\" type=\"xs:string\" />"
                        + "    </xs:documentType>"
                        + "</xs:schema>";
        when(assetManager.open(eq("app_function_schema.xml")))
                .thenReturn(new ByteArrayInputStream(xsd.getBytes()));
        String appFunctionsXml =
                "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n"
                        + "<appfunctions>\n"
                        + "  <AppFunctionStaticMetadata>\n"
                        + "    <id>com.schemaApp1.utils#print</id>\n"
                        + "    <functionId>com.schemaApp1.utils#print</functionId>\n"
                        + "    <inner>\n"
                        + "      <id>com.schemaApp1/com.schemaApp1.utils#print/inner</id>\n"
                        + "      <value>test</value>\n"
                        + "    </inner>\n"
                        + "  </AppFunctionStaticMetadata>\n"
                        + "</appfunctions>";
        when(assetManager.open(eq("app_functions.xml")))
                .thenReturn(new ByteArrayInputStream(appFunctionsXml.getBytes()));
        setUpResourcesForApp(assetManager, pm1, schemaApp1.packageName);
        PackageInfo schemaApp2 = createFakePackageInfo(1);
        ResolveInfo schemaApp2ResolveInfo = createFakeLaunchResolveInfo(1);
        ResolveInfo schemaApp2FunctionResolveInfo = createFakeAppFunctionResolveInfo(1);
        setUpAppFunctionProperties(pm1, schemaApp2FunctionResolveInfo);
        AssetManager assetManager2 = Mockito.mock(AssetManager.class);
        when(assetManager2.open(eq("app_function_schema.xml")))
                .thenReturn(new ByteArrayInputStream(xsd.getBytes()));
        String appFunctionsXml2 =
                "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n"
                        + "<appfunctions>\n"
                        + "  <AppFunctionStaticMetadata>\n"
                        + "    <id>com.schemaApp2.utils#print</id>\n"
                        + "    <functionId>com.schemaApp2.utils#print</functionId>\n"
                        + "    <inner>\n"
                        + "      <id>com.schemaApp2/com.schemaApp1.utils#print/inner</id>\n"
                        + "      <value>test</value>\n"
                        + "    </inner>\n"
                        + "  </AppFunctionStaticMetadata>\n"
                        + "</appfunctions>";
        when(assetManager2.open(eq("app_functions.xml")))
                .thenReturn(new ByteArrayInputStream(appFunctionsXml2.getBytes()));
        setUpResourcesForApp(assetManager2, pm1, schemaApp2.packageName);
        setupMockPackageManager(
                pm1,
                ImmutableList.of(schemaApp1, schemaApp2),
                ImmutableList.of(schemaApp1ResolveInfo, schemaApp2ResolveInfo),
                ImmutableList.of(schemaApp1FunctionResolveInfo, schemaApp2FunctionResolveInfo));
        Context context1 = createContextWithPackageManager(pm1);

        try (AppsIndexerImpl appsIndexerImpl = new AppsIndexerImpl(context1, mAppsIndexerConfig)) {
            appsIndexerImpl.doUpdateIncrementalPut(
                    new AppsIndexerSettings(),
                    new AppsUpdateStats(),
                    /* isFullUpdateRequired= */ false);

            Map<String, Map<String, AppFunctionDocument>> indexedFunctions =
                    mAppSearchHelper.getAppFunctionDocumentsFromAppSearch(
                            ImmutableSet.of(schemaApp1.packageName, schemaApp2.packageName));
            // Verify functions from both apps are indexed successfully.
            assertThat(indexedFunctions.keySet())
                    .containsExactly(schemaApp1.packageName, schemaApp2.packageName);
            assertThat(indexedFunctions.get(schemaApp1.packageName).keySet())
                    .containsExactly("com.fake.package0/com.schemaApp1.utils#print");
            assertThat(indexedFunctions.get(schemaApp2.packageName).keySet())
                    .containsExactly("com.fake.package1/com.schemaApp2.utils#print");
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_APP_FUNCTIONS_SCHEMA_PARSER)
    public void testAppsIndexerImpl_incrementalPut_withDynamicSchema_doesNotPutAllDocsOnUpdate()
            throws Exception {
        PackageManager pm1 = Mockito.mock(PackageManager.class);
        PackageInfo dynamicSchemaApp = createFakePackageInfo(0);
        ResolveInfo dynamicSchemaAppResolveInfo = createFakeLaunchResolveInfo(0);
        ResolveInfo dynamicSchemaAppFunctionResolveInfo = createFakeAppFunctionResolveInfo(0);
        List<PackageInfo> fakePackages = ImmutableList.of(dynamicSchemaApp);
        setUpAppFunctionProperties(pm1, dynamicSchemaAppFunctionResolveInfo);
        AssetManager assetManager = Mockito.mock(AssetManager.class);
        String xsdWithNestedTypes =
                "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">"
                        + "    <xs:documentType name=\"AppFunctionStaticMetadata\">\n"
                        + APP_FUNCTION_STATIC_METADATA_PARENT_PROPERTIES
                        + "        <xs:element name=\"nested\" type=\"appfn:NestedType\" />\n"
                        + "    </xs:documentType>\n"
                        + "    <xs:documentType name=\"NestedType\">\n"
                        + "        <xs:element name=\"value\" type=\"xs:string\" />\n"
                        + "    </xs:documentType>\n"
                        + "</xs:schema>";
        when(assetManager.open(eq("app_function_schema.xml")))
                .thenAnswer(inv -> new ByteArrayInputStream(xsdWithNestedTypes.getBytes()));
        String appFunctionsXml =
                "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n"
                        + "<appfunctions>\n"
                        + "  <AppFunctionStaticMetadata>\n"
                        + "    <id>com.dynamicSchemaApp.utils#print</id>\n"
                        + "    <functionId>com.dynamicSchemaApp.utils#print</functionId>\n"
                        + "    <nested>\n"
                        + "     <id>com.dynamicSchemaApp.utils#print/nested0</id>\n"
                        + "     <value>innerProperty</value>\n"
                        + "    </nested>\n"
                        + "  </AppFunctionStaticMetadata>\n"
                        + "</appfunctions>";
        when(assetManager.open(eq("app_functions.xml")))
                .thenReturn(new ByteArrayInputStream(appFunctionsXml.getBytes()));
        setUpResourcesForApp(assetManager, pm1, dynamicSchemaApp.packageName);
        setupMockPackageManager(
                pm1,
                fakePackages,
                ImmutableList.of(dynamicSchemaAppResolveInfo),
                ImmutableList.of(dynamicSchemaAppFunctionResolveInfo));
        Context context1 = createContextWithPackageManager(pm1);
        Set<String> packageNames = ImmutableSet.of(dynamicSchemaApp.packageName);
        // Perform the first update
        try (AppsIndexerImpl appsIndexerImpl = new AppsIndexerImpl(context1, mAppsIndexerConfig)) {
            AppsUpdateStats stats1 = new AppsUpdateStats();
            appsIndexerImpl.doUpdateIncrementalPut(
                    new AppsIndexerSettings(), stats1, /* isFullUpdateRequired= */ false);
        }
        // Find first put timestamp of the AppSearch function document
        Map<String, Map<String, AppFunctionDocument>> indexedFunctions =
                mAppSearchHelper.getAppFunctionDocumentsFromAppSearch(packageNames);
        GenericDocument original =
                indexedFunctions
                        .get("com.fake.package0")
                        .get("com.fake.package0/com.dynamicSchemaApp.utils#print");
        long firstPutTimestamp = original.getCreationTimestampMillis();
        // Simulate an update
        fakePackages.get(0).lastUpdateTime = 1000;
        String appFunctionsXml2 =
                "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n"
                        + "<appfunctions>\n"
                        + "  <AppFunctionStaticMetadata>\n"
                        + "    <id>com.dynamicSchemaApp.utils#print</id>\n"
                        + "    <functionId>com.dynamicSchemaApp.utils#print</functionId>\n"
                        + "    <nested>\n"
                        + "     <id>com.dynamicSchemaApp.utils#print/nested0</id>\n"
                        + "     <value>innerProperty</value>\n"
                        + "    </nested>\n"
                        + "  </AppFunctionStaticMetadata>\n"
                        + "  <AppFunctionStaticMetadata>\n"
                        + "    <id>com.dynamicSchemaApp.utils#search</id>\n"
                        + "    <functionId>com.dynamicSchemaApp.utils#search</functionId>\n"
                        + "    <nested>\n"
                        + "     <id>com.dynamicSchemaApp.utils#search/nested0</id>\n"
                        + "     <value>innerProperty</value>\n"
                        + "    </nested>\n"
                        + "  </AppFunctionStaticMetadata>\n"
                        + "</appfunctions>";
        when(assetManager.open(eq("app_functions.xml")))
                .thenReturn(new ByteArrayInputStream(appFunctionsXml2.getBytes()));

        try (AppsIndexerImpl appsIndexerImpl = new AppsIndexerImpl(context1, mAppsIndexerConfig)) {
            AppsUpdateStats stats1 = new AppsUpdateStats();
            appsIndexerImpl.doUpdateIncrementalPut(
                    new AppsIndexerSettings(), stats1, /* isFullUpdateRequired= */ false);

            // Check the stats object after the first update
            assertThat(stats1.mNumberOfAppsAdded).isEqualTo(0);
            assertThat(stats1.mNumberOfAppsRemoved).isEqualTo(0);
            assertThat(stats1.mNumberOfAppsUnchanged).isEqualTo(0);
            assertThat(stats1.mNumberOfAppsUpdated).isEqualTo(1);

            assertThat(mAppSearchHelper.getAppFunctionDocumentsFromAppSearch(packageNames).keySet())
                    .containsExactlyElementsIn(packageNames);
        }
        indexedFunctions = mAppSearchHelper.getAppFunctionDocumentsFromAppSearch(packageNames);
        GenericDocument unchangedFunction =
                indexedFunctions
                        .get("com.fake.package0")
                        .get("com.fake.package0/com.dynamicSchemaApp.utils#print");
        GenericDocument addedFunction =
                indexedFunctions
                        .get("com.fake.package0")
                        .get("com.fake.package0/com.dynamicSchemaApp.utils#search");
        assertThat(unchangedFunction.getCreationTimestampMillis()).isEqualTo(firstPutTimestamp);
        assertThat(addedFunction.getCreationTimestampMillis()).isGreaterThan(firstPutTimestamp);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_APP_FUNCTIONS_SCHEMA_PARSER)
    public void
            testAppsIndexerImpl_incrementalPut_withDynamicSchema_multipleRootSchemas_indexesDocs()
                    throws Exception {
        PackageManager pm1 = Mockito.mock(PackageManager.class);
        PackageInfo dynamicSchemaApp = createFakePackageInfo(0);
        ResolveInfo dynamicSchemaAppResolveInfo = createFakeLaunchResolveInfo(0);
        ResolveInfo dynamicSchemaAppFunctionResolveInfo = createFakeAppFunctionResolveInfo(0);
        List<PackageInfo> fakePackages = ImmutableList.of(dynamicSchemaApp);
        setUpAppFunctionProperties(pm1, dynamicSchemaAppFunctionResolveInfo);
        AssetManager assetManager = Mockito.mock(AssetManager.class);
        String xsdWithNestedTypes =
                "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">"
                        + "    <xs:documentType name=\"AppFunctionStaticMetadata\">\n"
                        + APP_FUNCTION_STATIC_METADATA_PARENT_PROPERTIES
                        + "    </xs:documentType>\n"
                        + "    <xs:documentType name=\"AnotherTopLevelType\">\n"
                        + "        <xs:element name=\"value\" type=\"xs:string\" />\n"
                        + "        <xs:element name=\"packageName\" type=\"xs:string\" cardinality="
                        + "\""
                        + AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL
                        + "\" indexingType=\""
                        + AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_EXACT_TERMS
                        + "\" tokenizerType=\""
                        + AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_VERBATIM
                        + "\" />\n"
                        + "        <xs:element name=\"mobileApplicationQualifiedId\" type=\"xs:"
                        + "string\" cardinality=\""
                        + AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL
                        + "\" joinableValueType=\""
                        + AppSearchSchema.StringPropertyConfig.JOINABLE_VALUE_TYPE_QUALIFIED_ID
                        + "\" />\n"
                        + "    </xs:documentType>\n"
                        + "</xs:schema>";
        when(assetManager.open(eq("app_function_schema.xml")))
                .thenAnswer(inv -> new ByteArrayInputStream(xsdWithNestedTypes.getBytes()));
        String appFunctionsXml =
                "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n"
                        + "<appfunctions>\n"
                        + "  <AppFunctionStaticMetadata>\n"
                        + "    <id>com.dynamicSchemaApp.utils#print</id>\n"
                        + "    <functionId>com.dynamicSchemaApp.utils#print</functionId>\n"
                        + "  </AppFunctionStaticMetadata>\n"
                        + "  <AnotherTopLevelType>\n"
                        + "    <id>com.dynamicSchemaApp.utils#anotherTopLevelType</id>\n"
                        + "    <value>anotherTopLevelTypeValue</value>\n"
                        + "  </AnotherTopLevelType>\n"
                        + "</appfunctions>";
        when(assetManager.open(eq("app_functions.xml")))
                .thenReturn(new ByteArrayInputStream(appFunctionsXml.getBytes()));
        setUpResourcesForApp(assetManager, pm1, dynamicSchemaApp.packageName);
        setupMockPackageManager(
                pm1,
                fakePackages,
                ImmutableList.of(dynamicSchemaAppResolveInfo),
                ImmutableList.of(dynamicSchemaAppFunctionResolveInfo));
        Context context1 = createContextWithPackageManager(pm1);
        Set<String> packageNames = ImmutableSet.of(dynamicSchemaApp.packageName);
        // Perform the first update
        try (AppsIndexerImpl appsIndexerImpl = new AppsIndexerImpl(context1, mAppsIndexerConfig)) {
            AppsUpdateStats stats1 = new AppsUpdateStats();
            appsIndexerImpl.doUpdateIncrementalPut(
                    new AppsIndexerSettings(), stats1, /* isFullUpdateRequired= */ false);
        }
        // Find first put timestamp of the AppSearch function document
        Map<String, Map<String, AppFunctionDocument>> indexedFunctionDocuments =
                mAppSearchHelper.getAppFunctionDocumentsFromAppSearch(packageNames);
        assertThat(indexedFunctionDocuments.get("com.fake.package0").keySet()).hasSize(2);
        assertThat(indexedFunctionDocuments.get("com.fake.package0").keySet())
                .containsExactly(
                        "com.fake.package0/com.dynamicSchemaApp.utils#print",
                        "com.fake.package0/com.dynamicSchemaApp.utils#anotherTopLevelType");
        GenericDocument fnMetadata =
                indexedFunctionDocuments
                        .get("com.fake.package0")
                        .get("com.fake.package0/com.dynamicSchemaApp.utils#print");
        assertThat(fnMetadata.getPropertyString("functionId"))
                .isEqualTo("com.dynamicSchemaApp.utils#print");
        GenericDocument anotherTopLevelDocument =
                indexedFunctionDocuments
                        .get("com.fake.package0")
                        .get("com.fake.package0/com.dynamicSchemaApp.utils#anotherTopLevelType");
        assertThat(anotherTopLevelDocument.getPropertyString("value"))
                .isEqualTo("anotherTopLevelTypeValue");
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_APP_FUNCTIONS_SCHEMA_PARSER)
    public void testAppsIndexerImpl_incrementalPut_newSchemaProperty_indexesTheNewProperty()
            throws Exception {
        PackageManager pm1 = Mockito.mock(PackageManager.class);
        PackageInfo dynamicSchemaApp = createFakePackageInfo(0);
        ResolveInfo dynamicSchemaAppResolveInfo = createFakeLaunchResolveInfo(0);
        ResolveInfo dynamicSchemaAppFunctionResolveInfo = createFakeAppFunctionResolveInfo(0);
        List<PackageInfo> fakePackages = ImmutableList.of(dynamicSchemaApp);
        setUpAppFunctionProperties(pm1, dynamicSchemaAppFunctionResolveInfo);
        AssetManager assetManager = Mockito.mock(AssetManager.class);
        when(assetManager.open(eq("app_function_schema.xml")))
                .thenAnswer(
                        inv ->
                                new ByteArrayInputStream(
                                        APP_FUNCTION_STATIC_METADATA_PARENT_SCHEMA_XSD.getBytes()));
        String appFunctionsXml =
                "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n"
                        + "<appfunctions>\n"
                        + "  <AppFunctionStaticMetadata>\n"
                        + "    <id>com.dynamicSchemaApp.utils#print</id>\n"
                        + "    <functionId>com.dynamicSchemaApp.utils#print</functionId>\n"
                        + "  </AppFunctionStaticMetadata>\n"
                        + "</appfunctions>";
        when(assetManager.open(eq("app_functions.xml")))
                .thenReturn(new ByteArrayInputStream(appFunctionsXml.getBytes()));
        setUpResourcesForApp(assetManager, pm1, dynamicSchemaApp.packageName);
        setupMockPackageManager(
                pm1,
                fakePackages,
                ImmutableList.of(dynamicSchemaAppResolveInfo),
                ImmutableList.of(dynamicSchemaAppFunctionResolveInfo));
        Context context1 = createContextWithPackageManager(pm1);
        Set<String> packageNames = ImmutableSet.of(dynamicSchemaApp.packageName);
        // Perform the first update
        try (AppsIndexerImpl appsIndexerImpl = new AppsIndexerImpl(context1, mAppsIndexerConfig)) {
            AppsUpdateStats stats1 = new AppsUpdateStats();
            appsIndexerImpl.doUpdateIncrementalPut(
                    new AppsIndexerSettings(), stats1, /* isFullUpdateRequired= */ false);
        }
        // Simulate an update with new schema.
        fakePackages.get(0).lastUpdateTime = 1000;
        String schemaXmlWithNewProperty =
                "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">"
                        + "    <xs:documentType name=\"AppFunctionStaticMetadata\">"
                        + APP_FUNCTION_STATIC_METADATA_PARENT_PROPERTIES
                        + "        <xs:element name=\"newProperty\" type=\"xs:string\" />"
                        + "    </xs:documentType>"
                        + "</xs:schema>";
        String appFunctionsXml2 =
                "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n"
                        + "<appfunctions>\n"
                        + "  <AppFunctionStaticMetadata>\n"
                        + "    <id>com.dynamicSchemaApp.utils#print</id>\n"
                        + "    <functionId>com.dynamicSchemaApp.utils#print</functionId>\n"
                        + "    <newProperty>test_new_property</newProperty>\n"
                        + "  </AppFunctionStaticMetadata>\n"
                        + "</appfunctions>";
        when(assetManager.open(eq("app_function_schema.xml")))
                .thenReturn(new ByteArrayInputStream(schemaXmlWithNewProperty.getBytes()));
        when(assetManager.open(eq("app_functions.xml")))
                .thenReturn(new ByteArrayInputStream(appFunctionsXml2.getBytes()));

        try (AppsIndexerImpl appsIndexerImpl = new AppsIndexerImpl(context1, mAppsIndexerConfig)) {
            AppsUpdateStats stats1 = new AppsUpdateStats();
            appsIndexerImpl.doUpdateIncrementalPut(
                    new AppsIndexerSettings(), stats1, /* isFullUpdateRequired= */ false);

            // Check the stats object after the first update
            assertThat(stats1.mNumberOfAppsAdded).isEqualTo(0);
            assertThat(stats1.mNumberOfAppsRemoved).isEqualTo(0);
            assertThat(stats1.mNumberOfAppsUnchanged).isEqualTo(0);
            assertThat(stats1.mNumberOfAppsUpdated).isEqualTo(1);
        }
        Map<String, Map<String, AppFunctionDocument>> indexedFunctions =
                mAppSearchHelper.getAppFunctionDocumentsFromAppSearch(packageNames);
        GenericDocument updatedFunction =
                indexedFunctions
                        .get("com.fake.package0")
                        .get("com.fake.package0/com.dynamicSchemaApp.utils#print");
        assertThat(updatedFunction.getPropertyString("newProperty")).isEqualTo("test_new_property");
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_APPS_INDEXER_INCREMENTAL_PUT)
    public void testAppsIndexerImpl_incrementalPut_differentTimestamp_reindexes() throws Exception {
        // Simulate the first update: no changes, just adding initial apps
        PackageManager pm1 = Mockito.mock(PackageManager.class);
        List<PackageInfo> fakePackages = new ArrayList<>(createFakePackageInfos(3));
        List<ResolveInfo> fakeActivities = new ArrayList<>(createFakeResolveInfos(3));
        fakePackages.get(1).lastUpdateTime = 1000;
        fakePackages.get(2).lastUpdateTime = 1000;
        setupMockPackageManager(
                pm1, fakePackages, fakeActivities, /* appFunctionServices= */ ImmutableList.of());
        Context context1 = createContextWithPackageManager(pm1);

        // Perform the first update
        try (AppsIndexerImpl appsIndexerImpl = new AppsIndexerImpl(context1, mAppsIndexerConfig)) {
            AppsUpdateStats stats1 = new AppsUpdateStats();
            appsIndexerImpl.doUpdateIncrementalPut(
                    new AppsIndexerSettings(), stats1, /* isFullUpdateRequired= */ false);

            // Check the stats object after the first update
            assertThat(stats1.mNumberOfAppsAdded).isEqualTo(3); // Three new apps added
            assertThat(stats1.mNumberOfAppsRemoved).isEqualTo(0); // No apps deleted
            assertThat(stats1.mNumberOfAppsUnchanged).isEqualTo(0); // No apps unchanged
            assertThat(stats1.mNumberOfAppsUpdated).isEqualTo(0); // No apps updated

            // Verify the state of the indexed apps after the first update
            Set<String> ids =
                    mAppSearchHelper
                            .getAppsLastUpdatedTimeAndAppFunctionServiceEnabledFromAppSearch()
                            .keySet();
            assertThat(ids)
                    .containsExactly("com.fake.package0", "com.fake.package1", "com.fake.package2");
        }

        PackageManager pm2 = Mockito.mock(PackageManager.class);
        // Simulate an update where last update time goes down due to an incorrect system clock for
        // one package. It should still be re-indexed
        fakePackages.get(1).lastUpdateTime = 999;
        fakePackages.get(2).lastUpdateTime = 1001;

        setupMockPackageManager(
                pm2, fakePackages, fakeActivities, /* appFunctionServices= */ ImmutableList.of());
        Context context2 = createContextWithPackageManager(pm2);

        // Perform the second update
        try (AppsIndexerImpl appsIndexerImpl = new AppsIndexerImpl(context2, mAppsIndexerConfig)) {
            AppsUpdateStats stats2 = new AppsUpdateStats();
            appsIndexerImpl.doUpdateIncrementalPut(
                    new AppsIndexerSettings(), stats2, /* isFullUpdateRequired= */ false);

            // Check the stats object after the second update
            assertThat(stats2.mNumberOfAppsAdded).isEqualTo(0); // No apps added
            assertThat(stats2.mNumberOfAppsRemoved).isEqualTo(0); // No apps deleted
            assertThat(stats2.mNumberOfAppsUnchanged).isEqualTo(1); // One app unchanged
            assertThat(stats2.mNumberOfAppsUpdated).isEqualTo(2); // Two apps updated

            // Verify the state of the indexed apps after the second update
            Set<String> ids =
                    mAppSearchHelper
                            .getAppsLastUpdatedTimeAndAppFunctionServiceEnabledFromAppSearch()
                            .keySet();
            assertThat(ids)
                    .containsExactly("com.fake.package0", "com.fake.package1", "com.fake.package2");
        }
    }

    private static void setUpAppFunctionProperties(PackageManager pm, ResolveInfo resolveInfo)
            throws Exception {
        when(pm.getProperty(
                        eq("android.app.appfunctions.schema"),
                        eq(
                                new ComponentName(
                                        resolveInfo.serviceInfo.packageName,
                                        resolveInfo.serviceInfo.name))))
                .thenReturn(new PackageManager.Property("", "app_function_schema.xml", "", ""));
        when(pm.getProperty(eq("android.app.appfunctions.schema"), anyString()))
                .thenThrow(new PackageManager.NameNotFoundException());
        when(pm.getProperty(
                        eq("android.app.appfunctions.v2"),
                        eq(
                                new ComponentName(
                                        resolveInfo.serviceInfo.packageName,
                                        resolveInfo.serviceInfo.name))))
                .thenReturn(new PackageManager.Property("", "app_functions.xml", "", ""));
    }

    private static void setUpResourcesForApp(
            AssetManager assetManager, PackageManager pm1, String packageName)
            throws PackageManager.NameNotFoundException {
        Resources resources = Mockito.mock(Resources.class);
        when(resources.getAssets()).thenReturn(assetManager);
        when(pm1.getResourcesForApplication(eq(packageName))).thenReturn(resources);
    }

    private ContextWrapper createContextWithPackageManager(PackageManager pm) {
        return new ContextWrapper(mContext) {
            @Override
            public PackageManager getPackageManager() {
                return pm;
            }
        };
    }
}
