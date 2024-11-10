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

import static com.android.server.appsearch.appsindexer.AppsUtil.convertAppOpenEventsToMap;
import static com.android.server.appsearch.appsindexer.TestUtils.COMPATIBLE_APP_OPEN_EVENT_SCHEMA;
import static com.android.server.appsearch.appsindexer.TestUtils.COMPATIBLE_APP_SCHEMA;
import static com.android.server.appsearch.appsindexer.TestUtils.FAKE_PACKAGE_PREFIX;
import static com.android.server.appsearch.appsindexer.TestUtils.FAKE_SIGNATURE;
import static com.android.server.appsearch.appsindexer.TestUtils.INCOMPATIBLE_APP_OPEN_EVENT_SCHEMA;
import static com.android.server.appsearch.appsindexer.TestUtils.INCOMPATIBLE_APP_SCHEMA;
import static com.android.server.appsearch.appsindexer.TestUtils.createFakeAppFunction;
import static com.android.server.appsearch.appsindexer.TestUtils.createFakeAppIndexerSession;
import static com.android.server.appsearch.appsindexer.TestUtils.createFakeAppOpenEvent;
import static com.android.server.appsearch.appsindexer.TestUtils.createFakeAppOpenEventsIndexerSession;
import static com.android.server.appsearch.appsindexer.TestUtils.createFakeMobileApplication;
import static com.android.server.appsearch.appsindexer.TestUtils.createMobileApplications;
import static com.android.server.appsearch.appsindexer.TestUtils.createMockPackageIdentifier;
import static com.android.server.appsearch.appsindexer.TestUtils.createMockPackageIdentifiers;
import static com.android.server.appsearch.appsindexer.TestUtils.removeFakeAppOpenEventDocuments;
import static com.android.server.appsearch.appsindexer.TestUtils.removeFakePackageDocuments;
import static com.android.server.appsearch.appsindexer.TestUtils.searchAppSearchForApps;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import android.app.appsearch.AppSearchBatchResult;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.AppSearchSessionShim;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.GetSchemaResponse;
import android.app.appsearch.PackageIdentifier;
import android.app.appsearch.PutDocumentsRequest;
import android.app.appsearch.SearchResult;
import android.app.appsearch.SetSchemaRequest;
import android.app.appsearch.exceptions.AppSearchException;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.android.server.appsearch.appsindexer.appsearchtypes.AppFunctionStaticMetadata;
import com.android.server.appsearch.appsindexer.appsearchtypes.AppOpenEvent;
import com.android.server.appsearch.appsindexer.appsearchtypes.MobileApplication;

import com.google.common.collect.ImmutableList;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Since AppSearchHelper mainly just calls AppSearch's api to index/remove files, we shouldn't worry
 * too much about it since AppSearch has good test coverage. Here just add some simple checks.
 */
public class AppSearchHelperTest {
    private final ExecutorService mSingleThreadedExecutor = Executors.newSingleThreadExecutor();
    private Context mContext;
    private AppSearchHelper mAppSearchHelper;

    @Before
    public void setUp() throws Exception {
        mContext = ApplicationProvider.getApplicationContext();
        mAppSearchHelper = new AppSearchHelper(mContext);
    }

    @After
    public void tearDown() throws Exception {
        removeFakePackageDocuments(mContext, mSingleThreadedExecutor);
        removeFakeAppOpenEventDocuments(mContext, mSingleThreadedExecutor);
        mAppSearchHelper.close();
    }

    @Test
    public void testAppSearchHelper_permissionSetCorrectlyForMobileApplication() throws Exception {
        mAppSearchHelper.setSchemasForPackages(createMockPackageIdentifiers(1), new ArrayList<>());
        mAppSearchHelper.indexApps(
                createMobileApplications(1),
                /* appFunctions= */ ImmutableList.of(),
                /* existingAppFunctions= */ ImmutableList.of(),
                /* appsUpdateStats= */ new AppsUpdateStats());

        AppSearchSessionShim session =
                createFakeAppIndexerSession(mContext, mSingleThreadedExecutor);
        GetSchemaResponse response = session.getSchemaAsync().get();

        assertThat(response.getSchemas())
                .contains(
                        MobileApplication.createMobileApplicationSchemaForPackage(
                                "com.fake.package0"));
        PackageIdentifier expected =
                new PackageIdentifier("com.fake.package0", FAKE_SIGNATURE.toByteArray());
        assertThat(response.getPubliclyVisibleSchemas().keySet())
                .containsExactly(MobileApplication.SCHEMA_TYPE + "-" + FAKE_PACKAGE_PREFIX + "0");
        PackageIdentifier actual =
                response.getPubliclyVisibleSchemas().values().toArray(new PackageIdentifier[0])[0];
        assertThat(actual.getSha256Certificate()).isEqualTo(expected.getSha256Certificate());
        assertThat(actual.getPackageName()).isEqualTo(expected.getPackageName());
    }

    @Test
    public void testAppSearchHelper_onlyIndexMobileApp_appFunctionParentSchemaIsNotSet()
            throws Exception {
        mAppSearchHelper.setSchemasForPackages(createMockPackageIdentifiers(1), new ArrayList<>());
        mAppSearchHelper.indexApps(
                createMobileApplications(1),
                /* appFunctions= */ ImmutableList.of(),
                /* existingAppFunctions= */ ImmutableList.of(),
                /* appsUpdateStats= */
                /* appsUpdateStats= */ new AppsUpdateStats());

        AppSearchSessionShim session =
                createFakeAppIndexerSession(mContext, mSingleThreadedExecutor);
        GetSchemaResponse response = session.getSchemaAsync().get();

        List<String> schemaTypes = new ArrayList<>();
        for (AppSearchSchema schema : response.getSchemas()) {
            schemaTypes.add(schema.getSchemaType());
        }
        assertThat(schemaTypes).doesNotContain(AppFunctionStaticMetadata.SCHEMA_TYPE);
    }

    @Test
    public void testIndexManyApps() throws Exception {
        mAppSearchHelper.setSchemasForPackages(
                createMockPackageIdentifiers(600), new ArrayList<>());
        mAppSearchHelper.indexApps(
                createMobileApplications(600),
                /* appFunctions= */ ImmutableList.of(),
                /* existingAppFunctions= */ ImmutableList.of(),
                /* appsUpdateStats= */ new AppsUpdateStats());
        Map<String, Long> appsearchIds = mAppSearchHelper.getAppsFromAppSearch();
        assertThat(appsearchIds.size()).isEqualTo(600);
        List<SearchResult> real = searchAppSearchForApps(600 + 1);
        assertThat(real).hasSize(600);
        removeFakePackageDocuments(mContext, mSingleThreadedExecutor);
    }

    @Test
    public void testIndexApps_compatibleSchemaChange() throws Exception {
        SetSchemaRequest setSchemaRequest =
                new SetSchemaRequest.Builder()
                        .addSchemas(COMPATIBLE_APP_SCHEMA)
                        .setForceOverride(true)
                        .build();

        int variant = 0;
        AppSearchSessionShim session =
                createFakeAppIndexerSession(mContext, mSingleThreadedExecutor);
        session.setSchemaAsync(setSchemaRequest).get();

        AppSearchHelper appSearchHelper = new AppSearchHelper(mContext);
        appSearchHelper.setSchemasForPackages(
                ImmutableList.of(createMockPackageIdentifier(variant)), new ArrayList<>());
        appSearchHelper.indexApps(
                ImmutableList.of(createFakeMobileApplication(variant)),
                /* appFunctions= */ ImmutableList.of(),
                /* existingAppFunctions= */ ImmutableList.of(),
                /* appsUpdateStats= */ new AppsUpdateStats());

        assertThat(appSearchHelper).isNotNull();
        List<SearchResult> results = searchAppSearchForApps(1 + 1);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getGenericDocument().getId()).isEqualTo("com.fake.package0");
    }

    @Test
    public void testIndexAppOpenEvents_compatibleSchemaChange() throws Exception {
        SetSchemaRequest setSchemaRequest =
                new SetSchemaRequest.Builder()
                        .addSchemas(COMPATIBLE_APP_OPEN_EVENT_SCHEMA)
                        .setForceOverride(true)
                        .build();

        long currentTimeMillis = System.currentTimeMillis();

        AppSearchSessionShim session =
                createFakeAppOpenEventsIndexerSession(mContext, mSingleThreadedExecutor);
        session.setSchemaAsync(setSchemaRequest).get();

        AppSearchHelper appSearchHelper = new AppSearchHelper(mContext);
        appSearchHelper.setSchemaForAppOpenEvents();
        AppOpenEvent fakeAppOpenEvent = createFakeAppOpenEvent(currentTimeMillis);
        appSearchHelper.indexAppOpenEvents(convertAppOpenEventsToMap(List.of(fakeAppOpenEvent)));

        assertThat(appSearchHelper).isNotNull();
        AppOpenEvent appOpenEvent =
                appSearchHelper.getSubsequentAppOpenEventAfterThreshold(currentTimeMillis - 100L);
        assertThat(appOpenEvent.getPackageName()).isEqualTo(fakeAppOpenEvent.getPackageName());
        assertThat(appOpenEvent.getAppOpenEventTimestampMillis())
                .isEqualTo(fakeAppOpenEvent.getAppOpenEventTimestampMillis());
    }

    @Test
    public void testIndexApps_incompatibleSchemaChange_canBeIndexedOver() throws Exception {
        AppSearchSessionShim session =
                createFakeAppIndexerSession(mContext, mSingleThreadedExecutor);

        // Set incompatible schemas that would be removed
        SetSchemaRequest setSchemaRequest =
                new SetSchemaRequest.Builder()
                        .addSchemas(INCOMPATIBLE_APP_SCHEMA)
                        .setForceOverride(true)
                        .build();
        session.setSchemaAsync(setSchemaRequest).get();

        mAppSearchHelper.setSchemasForPackages(createMockPackageIdentifiers(50), new ArrayList<>());
        mAppSearchHelper.indexApps(
                createMobileApplications(50),
                /* appFunctions= */ ImmutableList.of(),
                /* existingAppFunctions= */ ImmutableList.of(),
                /* appsUpdateStats= */ new AppsUpdateStats());

        List<SearchResult> real = searchAppSearchForApps(50 + 1);
        assertThat(real).hasSize(50);
    }

    @Test
    public void testIndexApps_incompatibleSchemaChange_wipesOutDatabase() throws Exception {
        AppSearchSessionShim session =
                createFakeAppIndexerSession(mContext, mSingleThreadedExecutor);

        // Set incompatible schemas that would be removed
        SetSchemaRequest setSchemaRequest =
                new SetSchemaRequest.Builder()
                        .addSchemas(INCOMPATIBLE_APP_SCHEMA)
                        .setForceOverride(true)
                        .build();
        session.setSchemaAsync(setSchemaRequest).get();

        mAppSearchHelper.setSchemasForPackages(createMockPackageIdentifiers(50), new ArrayList<>());
        List<SearchResult> real = searchAppSearchForApps(50 + 1);
        assertThat(real).hasSize(0);
    }

    @Test
    public void testIndexAppOpenEvents_incompatibleSchemaChange_canBeIndexedOver()
            throws Exception {
        SetSchemaRequest setSchemaRequest =
                new SetSchemaRequest.Builder()
                        .addSchemas(INCOMPATIBLE_APP_OPEN_EVENT_SCHEMA)
                        .setForceOverride(true)
                        .build();

        long currentTimeMillis = System.currentTimeMillis();

        AppSearchSessionShim session =
                createFakeAppOpenEventsIndexerSession(mContext, mSingleThreadedExecutor);
        // Set incompatible schemas that would be removed
        session.setSchemaAsync(setSchemaRequest).get();

        AppSearchHelper appSearchHelper = new AppSearchHelper(mContext);
        appSearchHelper.setSchemaForAppOpenEvents();
        AppOpenEvent fakeAppOpenEvent = createFakeAppOpenEvent(currentTimeMillis);
        appSearchHelper.indexAppOpenEvents(
                convertAppOpenEventsToMap(List.of(createFakeAppOpenEvent(currentTimeMillis))));

        assertThat(appSearchHelper).isNotNull();
        AppOpenEvent appOpenEvent =
                appSearchHelper.getSubsequentAppOpenEventAfterThreshold(currentTimeMillis - 100L);
        assertThat(appOpenEvent.getPackageName()).isEqualTo(fakeAppOpenEvent.getPackageName());
        assertThat(appOpenEvent.getAppOpenEventTimestampMillis())
                .isEqualTo(fakeAppOpenEvent.getAppOpenEventTimestampMillis());
    }

    @Test
    public void testIndexAppOpenEvents_incompatibleSchemaChange_wipesOutDatabase()
            throws Exception {
        SetSchemaRequest setSchemaRequest =
                new SetSchemaRequest.Builder()
                        .addSchemas(INCOMPATIBLE_APP_OPEN_EVENT_SCHEMA)
                        .setForceOverride(true)
                        .build();

        AppSearchSessionShim session =
                createFakeAppOpenEventsIndexerSession(mContext, mSingleThreadedExecutor);
        // Set incompatible schemas that would be removed
        session.setSchemaAsync(setSchemaRequest).get();

        AppSearchHelper appSearchHelper = new AppSearchHelper(mContext);
        appSearchHelper.setSchemaForAppOpenEvents();
        assertThat(appSearchHelper).isNotNull();
        assertThrows(
                AppSearchException.class,
                () -> appSearchHelper.getSubsequentAppOpenEventAfterThreshold(100L));
    }

    @Test
    public void testIndexApps_outOfSpace_shouldNotCompleteNormally() throws Exception {
        // set up AppSearchSession#put to invoke the callback with a RESULT_OUT_OF_SPACE failure
        SyncAppSearchSession fullSession = Mockito.mock(SyncAppSearchSession.class);
        when(fullSession.put(any(PutDocumentsRequest.class)))
                .thenReturn(
                        new AppSearchBatchResult.Builder<String, Void>()
                                .setFailure(
                                        "id", AppSearchResult.RESULT_OUT_OF_SPACE, "errorMessage")
                                .build());
        AppSearchHelper mocked = new AppSearchHelper(mContext);
        mocked.setAppSearchSessionForTest(fullSession);

        mAppSearchHelper.setSchemasForPackages(createMockPackageIdentifiers(1), new ArrayList<>());
        // It should throw if it's out of space
        assertThrows(
                AppSearchException.class,
                () ->
                        mocked.indexApps(
                                ImmutableList.of(createFakeMobileApplication(0)),
                                /* appFunctions= */ ImmutableList.of(),
                                /* existingAppFunctions= */ ImmutableList.of(),
                                /* appsUpdateStats= */ new AppsUpdateStats()));
    }

    @Test
    public void testAppSearchHelper_removeApps() throws Exception {
        mAppSearchHelper.setSchemasForPackages(
                createMockPackageIdentifiers(100), new ArrayList<>());
        mAppSearchHelper.indexApps(
                createMobileApplications(100),
                /* appFunctions= */ ImmutableList.of(),
                /* existingAppFunctions= */ ImmutableList.of(),
                /* appsUpdateStats= */ new AppsUpdateStats());

        mAppSearchHelper.setSchemasForPackages(createMockPackageIdentifiers(50), new ArrayList<>());

        List<String> deletedIds = new ArrayList<>();
        // Last 50 ids should be removed.
        for (int i = 50; i < 100; i++) {
            deletedIds.add(FAKE_PACKAGE_PREFIX + i);
        }

        Map<String, Long> indexedIds = mAppSearchHelper.getAppsFromAppSearch();
        assertThat(indexedIds.size()).isEqualTo(50);
        Map<String, Long> appsearchIds = mAppSearchHelper.getAppsFromAppSearch();
        assertThat(appsearchIds.keySet()).containsNoneIn(deletedIds);
    }

    @Test
    public void test_sameApp_notIndexed() throws Exception {
        MobileApplication app0 = createFakeMobileApplication(0);
        MobileApplication app1 = createFakeMobileApplication(1);

        mAppSearchHelper.setSchemasForPackages(createMockPackageIdentifiers(2), new ArrayList<>());
        mAppSearchHelper.indexApps(
                ImmutableList.of(app0, app1),
                /* appFunctions= */ ImmutableList.of(),
                /* existingAppFunctions= */ ImmutableList.of(),
                /* appsUpdateStats= */ new AppsUpdateStats());
        Map<String, Long> timestampMapping = mAppSearchHelper.getAppsFromAppSearch();
        assertThat(timestampMapping)
                .containsExactly("com.fake.package0", 0L, "com.fake.package1", 1L);

        // Try to add the same apps
        mAppSearchHelper.indexApps(
                ImmutableList.of(app0, app1),
                /* appFunctions= */ ImmutableList.of(),
                /* existingAppFunctions= */ ImmutableList.of(),
                /* appsUpdateStats= */ new AppsUpdateStats());

        // Should still be two
        timestampMapping = mAppSearchHelper.getAppsFromAppSearch();
        assertThat(timestampMapping)
                .containsExactly("com.fake.package0", 0L, "com.fake.package1", 1L);
    }

    @Test
    public void test_appDifferent_reIndexed() throws Exception {
        MobileApplication app0 = createFakeMobileApplication(0);
        MobileApplication app1 = createFakeMobileApplication(1);

        mAppSearchHelper.setSchemasForPackages(createMockPackageIdentifiers(2), new ArrayList<>());
        mAppSearchHelper.indexApps(
                ImmutableList.of(app0, app1),
                /* appFunctions= */ ImmutableList.of(),
                /* existingAppFunctions= */ ImmutableList.of(),
                /* appsUpdateStats= */ new AppsUpdateStats());
        Map<String, Long> timestampMapping = mAppSearchHelper.getAppsFromAppSearch();
        assertThat(timestampMapping)
                .containsExactly("com.fake.package0", 0L, "com.fake.package1", 1L);

        // Check what happens if we keep the same id
        app1 =
                new MobileApplication.Builder(FAKE_PACKAGE_PREFIX + 1, FAKE_SIGNATURE.toByteArray())
                        .setDisplayName("Fake Application Name")
                        .setIconUri("https://cs.android.com")
                        .setClassName(".class")
                        .setUpdatedTimestampMs(300)
                        .setAlternateNames("Joe")
                        .build();

        // Should update the app, not add a new one
        mAppSearchHelper.indexApps(
                ImmutableList.of(app1),
                /* appFunctions= */ ImmutableList.of(),
                /* existingAppFunctions= */ ImmutableList.of(),
                /* appsUpdateStats= */ new AppsUpdateStats());
        timestampMapping = mAppSearchHelper.getAppsFromAppSearch();
        assertThat(timestampMapping)
                .containsExactly("com.fake.package0", 0L, "com.fake.package1", 300L);
    }

    @Test
    public void test_appNew_indexed() throws Exception {
        MobileApplication app0 = createFakeMobileApplication(0);
        MobileApplication app1 = createFakeMobileApplication(1);

        mAppSearchHelper.setSchemasForPackages(createMockPackageIdentifiers(2), new ArrayList<>());
        mAppSearchHelper.indexApps(
                ImmutableList.of(app0, app1),
                /* appFunctions= */ ImmutableList.of(),
                /* existingAppFunctions= */ ImmutableList.of(),
                /* appsUpdateStats= */ new AppsUpdateStats());
        assertThat(mAppSearchHelper.getAppsFromAppSearch()).hasSize(2);

        MobileApplication app2 = createFakeMobileApplication(2);

        mAppSearchHelper.setSchemasForPackages(createMockPackageIdentifiers(3), new ArrayList<>());
        mAppSearchHelper.indexApps(
                ImmutableList.of(app0, app1, app2),
                /* appFunctions= */ ImmutableList.of(),
                /* existingAppFunctions= */ ImmutableList.of(),
                /* appsUpdateStats= */ new AppsUpdateStats());

        // Should be three
        Map<String, Long> timestampMapping = mAppSearchHelper.getAppsFromAppSearch();
        assertThat(timestampMapping)
                .containsExactly(
                        "com.fake.package0", 0L, "com.fake.package1", 1L, "com.fake.package2", 2L);
    }

    @Test
    public void test_newAppFunction_indexed() throws Exception {
        // Set up 2 MobileApplications.
        MobileApplication app0 = createFakeMobileApplication(0);
        MobileApplication app1 = createFakeMobileApplication(1);

        // initially, no apps has app functions.
        mAppSearchHelper.setSchemasForPackages(
                createMockPackageIdentifiers(2), createMockPackageIdentifiers(0));
        mAppSearchHelper.indexApps(
                ImmutableList.of(app0, app1),
                /* appFunctions= */ ImmutableList.of(),
                /* existingAppFunctions= */ ImmutableList.of(),
                /* appsUpdateStats= */ new AppsUpdateStats());
        assertThat(mAppSearchHelper.getAppFunctionsFromAppSearch()).isEmpty();

        // Now app0 has an app function.
        mAppSearchHelper.setSchemasForPackages(
                createMockPackageIdentifiers(2), createMockPackageIdentifiers(1));
        AppFunctionStaticMetadata app0Function0 = createFakeAppFunction(0, 0, mContext);
        mAppSearchHelper.indexApps(
                ImmutableList.of(app0, app1),
                /* appFunctions= */ ImmutableList.of(app0Function0),
                /* existingAppFunctions= */ ImmutableList.of(app0Function0),
                /* appsUpdateStats= */ new AppsUpdateStats());

        assertThat(mAppSearchHelper.getAppFunctionsFromAppSearch().get(0).getId())
                .isEqualTo("com.fake.package0/function_id0");
    }

    @Test
    public void test_newAppFunction_parentSchemaIsInserted() throws Exception {
        assumeTrue(AppFunctionStaticMetadata.shouldSetParentType());

        // Set up 1 MobileApplications with an app function.
        MobileApplication app0 = createFakeMobileApplication(0);
        mAppSearchHelper.setSchemasForPackages(
                createMockPackageIdentifiers(1), createMockPackageIdentifiers(1));
        AppFunctionStaticMetadata app0Function0 = createFakeAppFunction(0, 0, mContext);
        mAppSearchHelper.indexApps(
                ImmutableList.of(app0),
                /* appFunctions= */ ImmutableList.of(app0Function0),
                /* existingAppFunctions= */ ImmutableList.of(),
                /* appsUpdateStats= */ new AppsUpdateStats());

        AppSearchSessionShim session =
                createFakeAppIndexerSession(mContext, mSingleThreadedExecutor);
        GetSchemaResponse response = session.getSchemaAsync().get();
        List<String> schemaTypes = new ArrayList<>();
        for (AppSearchSchema schema : response.getSchemas()) {
            schemaTypes.add(schema.getSchemaType());
        }
        assertThat(schemaTypes).contains(AppFunctionStaticMetadata.SCHEMA_TYPE);
    }

    @Test
    public void test_appFunctionRemoved_indexed() throws Exception {
        // Set up 2 MobileApplications.
        MobileApplication app0 = createFakeMobileApplication(0);
        MobileApplication app1 = createFakeMobileApplication(1);
        // initially, app0 has two app functions.
        AppFunctionStaticMetadata app0Function0 = createFakeAppFunction(0, 0, mContext);
        AppFunctionStaticMetadata app0Function1 = createFakeAppFunction(0, 1, mContext);

        mAppSearchHelper.setSchemasForPackages(
                createMockPackageIdentifiers(2), createMockPackageIdentifiers(1));
        mAppSearchHelper.indexApps(
                ImmutableList.of(app0, app1),
                /* appFunctions= */ ImmutableList.of(app0Function0, app0Function1),
                /* existingAppFunctions= */ ImmutableList.of(),
                /* appsUpdateStats= */ new AppsUpdateStats());
        List<GenericDocument> appFunctionsInAppSearch =
                mAppSearchHelper.getAppFunctionsFromAppSearch();
        assertThat(appFunctionsInAppSearch).hasSize(2);
        List<String> ids = new ArrayList<>();
        ids.add(appFunctionsInAppSearch.get(0).getId());
        ids.add(appFunctionsInAppSearch.get(1).getId());
        assertThat(ids)
                .containsExactly(
                        "com.fake.package0/function_id0", "com.fake.package0/function_id1");

        // Now we remove one app function from app0
        mAppSearchHelper.setSchemasForPackages(
                createMockPackageIdentifiers(2), createMockPackageIdentifiers(1));
        mAppSearchHelper.indexApps(
                ImmutableList.of(app0, app1),
                /* appFunctions= */ ImmutableList.of(app0Function0),
                /* existingAppFunctions= */ ImmutableList.of(app0Function0, app0Function1),
                /* appsUpdateStats= */ new AppsUpdateStats());
        // app0 still have one app function. so app0 is being indexed.
        assertThat(mAppSearchHelper.getAppFunctionsFromAppSearch().get(0).getId())
                .isEqualTo("com.fake.package0/function_id0");

        // We remove the last app function from app0.
        mAppSearchHelper.setSchemasForPackages(
                createMockPackageIdentifiers(2), createMockPackageIdentifiers(0));
        mAppSearchHelper.indexApps(
                ImmutableList.of(app0, app1),
                /* appFunctions= */ ImmutableList.of(),
                /* existingAppFunctions= */ ImmutableList.of(app0Function0),
                /* appsUpdateStats= */ new AppsUpdateStats());
        // App0 is no longer indexed for app functions cause it no longer has any of them.
        assertThat(mAppSearchHelper.getAppFunctionsFromAppSearch()).isEmpty();
    }

    @Test
    public void testIndexAppOpenEvents_incrementalUpdateSupported() throws Exception {
        long currentTimeMillis = System.currentTimeMillis();
        AppOpenEvent event1 = createFakeAppOpenEvent(currentTimeMillis + 100L);
        mAppSearchHelper.setSchemaForAppOpenEvents();
        mAppSearchHelper.indexAppOpenEvents(convertAppOpenEventsToMap(ImmutableList.of(event1)));

        AppOpenEvent event2 = createFakeAppOpenEvent(currentTimeMillis + 200L);
        mAppSearchHelper.setSchemaForAppOpenEvents();
        mAppSearchHelper.indexAppOpenEvents(convertAppOpenEventsToMap(ImmutableList.of(event2)));

        assertThat(
                        mAppSearchHelper
                                .getSubsequentAppOpenEventAfterThreshold(currentTimeMillis)
                                .getId())
                .isEqualTo(event1.getId());
        assertThat(
                        mAppSearchHelper
                                .getSubsequentAppOpenEventAfterThreshold(currentTimeMillis + 150L)
                                .getId())
                .isEqualTo(event2.getId());
    }

    @Test
    public void testIndexAppOpenEvents_doesNotInterfereWithAppSchemaSet() throws Exception {
        long currentTimeMillis = System.currentTimeMillis();
        mAppSearchHelper.setSchemaForAppOpenEvents();
        AppOpenEvent event1 = createFakeAppOpenEvent(currentTimeMillis + 100L);
        mAppSearchHelper.setSchemasForPackages(createMockPackageIdentifiers(1), new ArrayList<>());

        mAppSearchHelper.indexAppOpenEvents(convertAppOpenEventsToMap(ImmutableList.of(event1)));
        assertThat(
                        mAppSearchHelper
                                .getSubsequentAppOpenEventAfterThreshold(currentTimeMillis)
                                .getId())
                .isEqualTo(event1.getId());
    }

    @Test
    public void testIndexAppOpenEvents_multipleAppOpenEventsStored() throws Exception {
        long currentTimeMillis = System.currentTimeMillis();
        AppOpenEvent event1 = createFakeAppOpenEvent(currentTimeMillis + 100L);
        AppOpenEvent event2 = createFakeAppOpenEvent(currentTimeMillis + 200L);

        mAppSearchHelper.setSchemaForAppOpenEvents();
        assertThrows(
                AppSearchException.class,
                () -> mAppSearchHelper.getSubsequentAppOpenEventAfterThreshold(currentTimeMillis));

        mAppSearchHelper.indexAppOpenEvents(
                convertAppOpenEventsToMap(ImmutableList.of(event1, event2)));
        assertThat(
                        mAppSearchHelper
                                .getSubsequentAppOpenEventAfterThreshold(currentTimeMillis)
                                .getId())
                .isEqualTo(event1.getId());
        assertThat(
                        mAppSearchHelper
                                .getSubsequentAppOpenEventAfterThreshold(currentTimeMillis + 150L)
                                .getId())
                .isEqualTo(event2.getId());
        assertThrows(
                AppSearchException.class,
                () ->
                        mAppSearchHelper.getSubsequentAppOpenEventAfterThreshold(
                                currentTimeMillis + 300L));
    }
}
