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

import static com.android.server.appsearch.appsindexer.appsearchtypes.MobileApplication.SCHEMA_TYPE;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.app.appsearch.AppSearchManager;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.AppSearchSessionShim;
import android.app.appsearch.GlobalSearchSessionShim;
import android.app.appsearch.PackageIdentifier;
import android.app.appsearch.SearchResult;
import android.app.appsearch.SearchResultsShim;
import android.app.appsearch.SearchSpec;
import android.app.appsearch.SetSchemaRequest;
import android.app.appsearch.SetSchemaResponse;
import android.app.appsearch.testutil.AppSearchSessionShimImpl;
import android.app.appsearch.testutil.GlobalSearchSessionShimImpl;
import android.app.usage.UsageEvents;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.content.res.Resources;

import com.android.server.appsearch.appsindexer.appsearchtypes.AppFunctionStaticMetadata;
import com.android.server.appsearch.appsindexer.appsearchtypes.MobileApplication;

import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;

class TestUtils {
    // In the mocking tests, integers are appended to this prefix to create unique package names.
    public static final String FAKE_PACKAGE_PREFIX = "com.fake.package";
    public static final Signature FAKE_SIGNATURE = new Signature("deadbeef");

    // Represents a schema compatible with MobileApplication. This is used to test compatible schema
    // upgrades. It is compatible as changing to MobileApplication just adds properties.
    public static final AppSearchSchema COMPATIBLE_APP_SCHEMA =
            new AppSearchSchema.Builder(SCHEMA_TYPE)
                    .addProperty(
                            new AppSearchSchema.StringPropertyConfig.Builder(
                                            MobileApplication.APP_PROPERTY_PACKAGE_NAME)
                                    .setCardinality(
                                            AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                                    .setIndexingType(
                                            AppSearchSchema.StringPropertyConfig
                                                    .INDEXING_TYPE_PREFIXES)
                                    .setTokenizerType(
                                            AppSearchSchema.StringPropertyConfig
                                                    .TOKENIZER_TYPE_VERBATIM)
                                    .build())
                    .build();

    // Represents a schema incompatible with MobileApplication. This is used to test incompatible
    // schema upgrades. It is incompatible as changing to MobileApplication removes the
    // "NotPackageName" field.
    public static final AppSearchSchema INCOMPATIBLE_APP_SCHEMA =
            new AppSearchSchema.Builder(SCHEMA_TYPE)
                    .addProperty(
                            new AppSearchSchema.StringPropertyConfig.Builder("NotPackageName")
                                    .setCardinality(
                                            AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                                    .setIndexingType(
                                            AppSearchSchema.StringPropertyConfig
                                                    .INDEXING_TYPE_PREFIXES)
                                    .setTokenizerType(
                                            AppSearchSchema.StringPropertyConfig
                                                    .TOKENIZER_TYPE_PLAIN)
                                    .build())
                    .build();

    /**
     * Creates a fake {@link PackageInfo} object.
     *
     * @param variant provides variation in the mocked PackageInfo so we can index multiple fake
     *     apps.
     */
    @NonNull
    public static PackageInfo createFakePackageInfo(int variant) {
        String pkgName = FAKE_PACKAGE_PREFIX + variant;
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = pkgName;
        packageInfo.versionName = "10.0.0";
        packageInfo.lastUpdateTime = variant;
        SigningInfo signingInfo = Mockito.mock(SigningInfo.class);
        when(signingInfo.getSigningCertificateHistory())
                .thenReturn(new Signature[] {FAKE_SIGNATURE});
        packageInfo.signingInfo = signingInfo;

        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.packageName = pkgName;
        appInfo.className = pkgName + ".FakeActivity";
        appInfo.name = "package" + variant;
        appInfo.versionCode = 10;
        packageInfo.applicationInfo = appInfo;

        return packageInfo;
    }

    /**
     * Creates multiple fake {@link PackageInfo} objects
     *
     * @param numApps number of PackageInfos to create.
     * @see #createFakePackageInfo
     */
    @NonNull
    public static List<PackageInfo> createFakePackageInfos(int numApps) {
        List<PackageInfo> packageInfoList = new ArrayList<>();
        for (int i = 0; i < numApps; i++) {
            packageInfoList.add(createFakePackageInfo(i));
        }
        return packageInfoList;
    }

    /**
     * Generates a mock launch activity resolve info corresponding to the same package created by
     * {@link #createFakePackageInfo} with the same variant.
     *
     * @param variant adds variation in the mocked ResolveInfo so we can index multiple fake apps.
     */
    @NonNull
    public static ResolveInfo createFakeLaunchResolveInfo(int variant) {
        String pkgName = FAKE_PACKAGE_PREFIX + variant;
        ResolveInfo mockResolveInfo = new ResolveInfo();
        mockResolveInfo.activityInfo = new ActivityInfo();
        mockResolveInfo.activityInfo.packageName = pkgName;
        mockResolveInfo.activityInfo.name = pkgName + ".FakeActivity";
        mockResolveInfo.activityInfo.icon = 42;

        mockResolveInfo.activityInfo.applicationInfo = new ApplicationInfo();
        mockResolveInfo.activityInfo.applicationInfo.packageName = pkgName;
        mockResolveInfo.activityInfo.applicationInfo.name = "Fake Application Name"; // Optional
        return mockResolveInfo;
    }

    /**
     * Generates a mock app function activity resolve info corresponding to the same package created
     * by {@link #createFakePackageInfo} with the same variant.
     *
     * @param variant adds variation in the mocked ResolveInfo so we can index multiple fake apps.
     */
    @NonNull
    public static ResolveInfo createFakeAppFunctionResolveInfo(int variant) {
        String pkgName = FAKE_PACKAGE_PREFIX + variant;
        ResolveInfo mockResolveInfo = new ResolveInfo();
        mockResolveInfo.serviceInfo = new ServiceInfo();
        mockResolveInfo.serviceInfo.packageName = pkgName;
        mockResolveInfo.serviceInfo.name = pkgName + ".FakeActivity";

        return mockResolveInfo;
    }

    /**
     * Generates multiple mock ResolveInfos.
     *
     * @see #createFakeLaunchResolveInfo
     * @param numApps number of mock ResolveInfos to create
     */
    @NonNull
    public static List<ResolveInfo> createFakeResolveInfos(int numApps) {
        List<ResolveInfo> resolveInfoList = new ArrayList<>();
        for (int i = 0; i < numApps; i++) {
            resolveInfoList.add(createFakeLaunchResolveInfo(i));
        }
        return resolveInfoList;
    }

    /**
     * Configure a mock {@link PackageManager} to return certain {@link PackageInfo}s and {@link
     * ResolveInfo}s when getInstalledPackages and queryIntentActivities are called, respectively.
     */
    public static void setupMockPackageManager(
            @NonNull PackageManager pm,
            @NonNull List<PackageInfo> packages,
            @NonNull List<ResolveInfo> activities,
            @NonNull List<ResolveInfo> appFunctionServices)
            throws Exception {
        Objects.requireNonNull(pm);
        Objects.requireNonNull(packages);
        Objects.requireNonNull(activities);
        when(pm.getInstalledPackages(anyInt())).thenReturn(packages);
        Resources res = Mockito.mock(Resources.class);
        when(res.getResourcePackageName(anyInt())).thenReturn("idk");
        when(res.getResourceTypeName(anyInt())).thenReturn("type");
        when(pm.getResourcesForApplication((ApplicationInfo) any())).thenReturn(res);
        when(pm.getApplicationLabel(any())).thenReturn("label");
        when(pm.queryIntentActivities(any(), eq(0))).then(i -> activities);
        when(pm.queryIntentServices(any(), eq(0))).then(i -> appFunctionServices);
    }

    /** Wipes out the apps database. */
    public static void removeFakePackageDocuments(
            @NonNull Context context, @NonNull ExecutorService executorService)
            throws ExecutionException, InterruptedException {
        Objects.requireNonNull(context);
        Objects.requireNonNull(executorService);

        AppSearchSessionShim db =
                AppSearchSessionShimImpl.createSearchSessionAsync(
                                context,
                                new AppSearchManager.SearchContext.Builder("apps-db").build(),
                                executorService)
                        .get();

        SetSchemaResponse unused =
                db.setSchemaAsync(new SetSchemaRequest.Builder().setForceOverride(true).build())
                        .get();
    }

    /**
     * Search for documents indexed by the Apps Indexer. The database, namespace, and schematype are
     * all configured.
     *
     * @param pageSize The page size to use in the {@link SearchSpec}. By setting to a expected
     *     amount + 1, you can verify that the expected quantity of apps docs are present.
     */
    @NonNull
    public static List<SearchResult> searchAppSearchForApps(int pageSize)
            throws ExecutionException, InterruptedException {
        GlobalSearchSessionShim globalSession =
                GlobalSearchSessionShimImpl.createGlobalSearchSessionAsync().get();
        SearchSpec allDocumentIdsSpec =
                new SearchSpec.Builder()
                        .addFilterNamespaces(MobileApplication.APPS_NAMESPACE)
                        // We don't want to search over real indexed apps here, just the ones in the
                        // test
                        .addFilterPackageNames("com.android.appsearch.appsindexertests")
                        .addProjection(
                                SearchSpec.SCHEMA_TYPE_WILDCARD,
                                Collections.singletonList(
                                        MobileApplication.APP_PROPERTY_UPDATED_TIMESTAMP))
                        .setResultCountPerPage(pageSize)
                        .build();
        // Don't want to get this confused with real indexed apps.
        SearchResultsShim results =
                globalSession.search(/* queryExpression= */ "com.fake.package", allDocumentIdsSpec);
        return results.getNextPageAsync().get();
    }

    /**
     * Creates an {@link AppSearchSessionShim} for the same database the apps indexer interacts with
     * for mock packages. This is useful for verifying indexed documents and directly adding
     * documents.
     */
    @NonNull
    public static AppSearchSessionShim createFakeAppIndexerSession(
            @NonNull Context context, @NonNull ExecutorService executorService)
            throws ExecutionException, InterruptedException {
        Objects.requireNonNull(context);
        Objects.requireNonNull(executorService);
        return AppSearchSessionShimImpl.createSearchSessionAsync(
                        context,
                        new AppSearchManager.SearchContext.Builder("apps-db").build(),
                        executorService)
                .get();
    }

    /**
     * Generates a mock {@link MobileApplication} corresponding to the same package created by
     * {@link #createFakePackageInfo} with the same variant.
     *
     * @param variant adds variation to the MobileApplication document.
     */
    @NonNull
    public static MobileApplication createFakeMobileApplication(int variant) {
        return new MobileApplication.Builder(
                        FAKE_PACKAGE_PREFIX + variant, FAKE_SIGNATURE.toByteArray())
                .setDisplayName("Fake Application Name")
                .setIconUri("https://cs.android.com")
                .setClassName(".class")
                .setUpdatedTimestampMs(variant)
                .setAlternateNames("Mock")
                .build();
    }

    /**
     * Generates multiple mock {@link MobileApplication} objects.
     *
     * @see #createFakeMobileApplication
     */
    @NonNull
    public static List<MobileApplication> createMobileApplications(int numApps) {
        List<MobileApplication> appList = new ArrayList<>();
        for (int i = 0; i < numApps; i++) {
            appList.add(createFakeMobileApplication(i));
        }
        return appList;
    }

    /**
     * Generates a mock {@link AppFunctionStaticMetadata} corresponding to the same package created
     * by {@link #createFakePackageInfo} with the same variant.
     *
     * @param packageVariant changes the package of the AppFunctionStaticMetadata document.
     * @param functionVariant changes the function id of the AppFunctionStaticMetadata document.
     */
    @NonNull
    public static AppFunctionStaticMetadata createFakeAppFunction(
            int packageVariant, int functionVariant, Context context) {
        return new AppFunctionStaticMetadata.Builder(
                        FAKE_PACKAGE_PREFIX + packageVariant,
                        "function_id" + functionVariant,
                        context.getPackageName())
                .build();
    }

    /**
     * Returns a package identifier representing some mock package.
     *
     * @param variant Provides variety in the package name in the same manner as {@link
     *     #createFakePackageInfo} and {@link #createFakeMobileApplication}
     */
    @NonNull
    public static PackageIdentifier createMockPackageIdentifier(int variant) {
        return new PackageIdentifier(FAKE_PACKAGE_PREFIX + variant, FAKE_SIGNATURE.toByteArray());
    }

    /** Returns multiple package identifiers for use in testing. */
    @NonNull
    public static List<PackageIdentifier> createMockPackageIdentifiers(int numApps) {
        List<PackageIdentifier> packageIdList = new ArrayList<>();
        for (int i = 0; i < numApps; i++) {
            packageIdList.add(createMockPackageIdentifier(i));
        }
        return packageIdList;
    }

    /**
     * Creates a mock {@link UsageEvents} object.
     *
     * @param events the events to add to the UsageEvents object.
     * @return a {@link UsageEvents} object with the given events.
     */
    public static UsageEvents createUsageEvents(UsageEvents.Event... events) {
        return new UsageEvents(Arrays.asList(events), new String[] {});
    }

    /**
     * Creates a mock {@link UsageEvents.Event} object.
     *
     * @param eventType the event type of the UsageEvents.Event object.
     * @param timestamp the timestamp of the UsageEvents.Event object.
     * @param packageName the package name of the UsageEvents.Event object.
     * @return a {@link UsageEvents.Event} object with the given event type, timestamp, and package
     *     name.
     */
    public static UsageEvents.Event createIndividualUsageEvent(
            int eventType, long timestamp, String packageName) {
        UsageEvents.Event e = new UsageEvents.Event();
        e.mEventType = eventType;
        e.mTimeStamp = timestamp;
        e.mPackage = packageName;
        return e;
    }
}
