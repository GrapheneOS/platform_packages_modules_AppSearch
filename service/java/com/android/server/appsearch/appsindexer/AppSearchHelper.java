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

import static android.app.appsearch.AppSearchResult.RESULT_INVALID_ARGUMENT;
import static android.app.appsearch.AppSearchResult.RESULT_IO_ERROR;

import android.annotation.CurrentTimeMillisLong;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.WorkerThread;
import android.app.appsearch.AppSearchBatchResult;
import android.app.appsearch.AppSearchEnvironmentFactory;
import android.app.appsearch.AppSearchManager;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.JoinSpec;
import android.app.appsearch.PackageIdentifier;
import android.app.appsearch.PutDocumentsRequest;
import android.app.appsearch.RemoveByDocumentIdRequest;
import android.app.appsearch.SearchResult;
import android.app.appsearch.SearchSpec;
import android.app.appsearch.SetSchemaRequest;
import android.app.appsearch.exceptions.AppSearchException;
import android.content.Context;
import android.util.AndroidRuntimeException;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.appsearch.appsindexer.appsearchtypes.AppFunctionDocument;
import com.android.server.appsearch.appsindexer.appsearchtypes.AppFunctionStaticMetadata;
import com.android.server.appsearch.appsindexer.appsearchtypes.AppOpenEvent;
import com.android.server.appsearch.appsindexer.appsearchtypes.MobileApplication;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * Helper class to manage the App corpus in AppSearch.
 *
 * <p>There are two primary methods in this class, {@link #setSchemasForPackages} and {@link
 * #indexApps}. On a given Apps Index update, they may not necessarily both be called. For instance,
 * if the indexer determines that the only change is that an app was deleted, there is no reason to
 * insert any * apps, so we can save time by only calling setSchemas to erase the deleted app
 * schema. On the other hand, if the only change is that an app was update, there is no reason to
 * call setSchema. We can instead just update the updated app with a call to indexApps. Figuring out
 * what needs to be done is left to {@link AppsIndexerImpl}.
 *
 * <p>This class is thread-safe.
 *
 * @hide
 */
public class AppSearchHelper implements Closeable {
    private static final String TAG = "AppSearchAppsIndexerAppSearchHelper";

    // The apps indexer uses one database, and in that database we have one schema for every app
    // that is indexed. The reason for this is that we keep the schema types the same for every app
    // (MobileApplication), but we need different visibility settings for each app. These different
    // visibility settings are set with Public ACL and rely on PackageManager#canPackageQuery.
    // Therefore each application needs its own schema. We put all these schema into a single
    // database by dynamically renaming the schema so that they have different names.
    public static final String APP_DATABASE = "apps-db";

    // The app open event indexer needs to be in a separate database from the apps indexer because
    // they will have schemas set at separate times by separate services.
    public static final String APP_OPEN_EVENTS_DATABASE = "app-open-events-db";

    private static final int GET_APP_IDS_PAGE_SIZE = 1000;
    private final Context mContext;
    // Volatile, not final due to being swapped during some tests
    private volatile SyncAppSearchSession mSyncAppSearchAppsDbSession;
    private volatile SyncAppSearchSession mSyncAppSearchAppOpenEventDbSession;

    private final SyncGlobalSearchSession mSyncGlobalSearchSession;

    /** Creates an {@link AppSearchHelper}. */
    public AppSearchHelper(@NonNull Context context) {
        mContext = Objects.requireNonNull(context);
        AppSearchManager appSearchManager = mContext.getSystemService(AppSearchManager.class);
        if (appSearchManager == null) {
            throw new AndroidRuntimeException(
                    "Can't get AppSearchManager to initialize AppSearchHelper.");
        }
        AppSearchManager.SearchContext appsSearchContext =
                new AppSearchManager.SearchContext.Builder(APP_DATABASE).build();
        AppSearchManager.SearchContext appOpenEventsSearchContext =
                new AppSearchManager.SearchContext.Builder(APP_OPEN_EVENTS_DATABASE).build();
        ExecutorService executor =
                AppSearchEnvironmentFactory.getEnvironmentInstance().createSingleThreadExecutor();

        mSyncAppSearchAppsDbSession =
                new SyncAppSearchSessionImpl(appSearchManager, appsSearchContext, executor);
        mSyncAppSearchAppOpenEventDbSession =
                new SyncAppSearchSessionImpl(
                        appSearchManager, appOpenEventsSearchContext, executor);

        mSyncGlobalSearchSession = new SyncGlobalSearchSessionImpl(appSearchManager, executor);
    }

    /**
     * Allows us to test various scenarios involving SyncAppSearchSession. Sets all sessions to the
     * SyncAppSearchSession passed in for convenience only as it is only used for testing.
     *
     * <p>This method is not thread-safe, as it could be ran in the middle of a set schema, index,
     * or search operation. It should only be called from tests, and threading safety should be
     * handled by the test.
     */
    @VisibleForTesting
    /* package */ void setAppSearchSessionForTest(@NonNull SyncAppSearchSession session) {
        // Close the existing one
        if (mSyncAppSearchAppsDbSession != null) {
            mSyncAppSearchAppsDbSession.close();
        }
        if (mSyncAppSearchAppOpenEventDbSession != null) {
            mSyncAppSearchAppOpenEventDbSession.close();
        }
        mSyncAppSearchAppsDbSession = Objects.requireNonNull(session);
        mSyncAppSearchAppOpenEventDbSession = Objects.requireNonNull(session);
    }

    /**
     * Sets the AppsIndexer database schema to correspond to the list of passed in {@link
     * PackageIdentifier}s, representing app schemas, and a list of {@link PackageIdentifier}s,
     * representing app functions. Note that this means if a schema exists in AppSearch that does
     * not get passed in to this method, it will be erased. And if a schema does not exist in
     * AppSearch that is passed in to this method, it will be created.
     *
     * @param mobileAppPkgs A list of {@link PackageIdentifier}s for which to set {@link
     *     MobileApplication} schemas for
     * @param appFunctionPkgs A list of {@link PackageIdentifier}s for which to set {@link
     *     AppFunctionStaticMetadata} schemas for. These are packages with an AppFunctionService. It
     *     is always a subset of `mobileAppPkgs`.
     */
    @WorkerThread
    public void setSchemasForPackages(
            @NonNull List<PackageIdentifier> mobileAppPkgs,
            @NonNull List<PackageIdentifier> appFunctionPkgs)
            throws AppSearchException {
        Objects.requireNonNull(mobileAppPkgs);
        Objects.requireNonNull(appFunctionPkgs);

        SetSchemaRequest schemaRequest =
                buildMobileAppAndPreDefinedAppFuncSchemaRequest(
                        mobileAppPkgs, appFunctionPkgs, Collections.emptyMap());

        // TODO(b/275592563): Log app removal in metrics
        mSyncAppSearchAppsDbSession.setSchema(schemaRequest);
    }

    /**
     * Sets the AppsIndexer database schema to correspond to the list of passed in {@link
     * PackageIdentifier}s, representing app schemas, and a list of {@link PackageIdentifier}s,
     * representing app functions. Note that this means if a schema exists in AppSearch that does
     * not get passed in to this method, it will be erased. And if a schema does not exist in
     * AppSearch that is passed in to this method, it will be created.
     *
     * <p>Note the following for dynamicAppFunctionSchemasForPackages:
     *
     * <ul>
     *   <li>For packages with no dynamic app function schemas mapping, a predefined schema will be
     *       created using {@link AppFunctionStaticMetadata#createAppFunctionSchemaForPackage}.
     *   <li>This method first tries to setSchema for all packages in a single call to {@link
     *       SyncAppSearchSession#setSchema(SetSchemaRequest)}. If this fails, it iteratively adds
     *       the dynamic schemas to the request and excludes packages with invalid schemas from
     *       schema updates.
     * </ul>
     *
     * @param mobileAppPkgs A list of {@link PackageIdentifier}s for which to set {@link
     *     MobileApplication} schemas for.
     * @param appFunctionPkgs A list of {@link PackageIdentifier}s for which to set {@link
     *     AppFunctionStaticMetadata} schemas for. These are packages with an AppFunctionService. It
     *     is always a subset of `mobileAppPkgs`.
     * @param dynamicAppFunctionSchemasForPackages A map of package name to a map of schema name to
     *     {@link AppSearchSchema} for dynamic app functions.
     */
    @WorkerThread
    public void setSchemasForPackages(
            @NonNull List<PackageIdentifier> mobileAppPkgs,
            @NonNull List<PackageIdentifier> appFunctionPkgs,
            @NonNull
                    Map<String, Map<String, AppSearchSchema>>
                            dynamicAppFunctionSchemasForPackages) {
        Objects.requireNonNull(mobileAppPkgs);
        Objects.requireNonNull(appFunctionPkgs);
        Objects.requireNonNull(dynamicAppFunctionSchemasForPackages);

        // Build predefined schemas for mobile app packages and app function packages that don't
        // have dynamic schemas.
        SetSchemaRequest preDefinedSchemaRequest =
                buildMobileAppAndPreDefinedAppFuncSchemaRequest(
                        mobileAppPkgs, appFunctionPkgs, dynamicAppFunctionSchemasForPackages);

        // Build all schemas (predefined + dynamic)
        SetSchemaRequest.Builder allPackagesRequestBuilder =
                new SetSchemaRequest.Builder(preDefinedSchemaRequest);
        addDynamicSchemasToBuilder(
                allPackagesRequestBuilder, appFunctionPkgs, dynamicAppFunctionSchemasForPackages);

        try {
            mSyncAppSearchAppsDbSession.setSchema(allPackagesRequestBuilder.build());
        } catch (AppSearchException e) {

            Log.e(TAG, "Failed to setSchema in batch due to invalid schema.", e);
            iterativelyAddDynamicSchema(
                    preDefinedSchemaRequest, appFunctionPkgs, dynamicAppFunctionSchemasForPackages);
        }
    }

    /**
     * Builds a schema request for the specified mobile application and app function packages.
     *
     * <p>Only adds pre-defined schemas for app function packages without a dynamic schema mapping.
     *
     * @param mobileAppPkgs A list of {@link PackageIdentifier}s for which to set {@link
     *     MobileApplication} schemas.
     * @param appFunctionPkgs A list of {@link PackageIdentifier}s for which to set {@link
     *     AppFunctionStaticMetadata} schemas.
     * @param dynamicSchemas A map of package names to their dynamic schemas, represented as a map
     *     of schema names to {@link AppSearchSchema}.
     * @return A {@link SetSchemaRequest} containing the predefined schemas.
     */
    private SetSchemaRequest buildMobileAppAndPreDefinedAppFuncSchemaRequest(
            @NonNull List<PackageIdentifier> mobileAppPkgs,
            @NonNull List<PackageIdentifier> appFunctionPkgs,
            @NonNull Map<String, Map<String, AppSearchSchema>> dynamicSchemas) {
        SetSchemaRequest.Builder builder = new SetSchemaRequest.Builder().setForceOverride(true);

        populateMobileApplicationSchemas(builder, mobileAppPkgs);

        if (!appFunctionPkgs.isEmpty() && AppFunctionStaticMetadata.shouldSetParentType()) {
            builder.addSchemas(AppFunctionStaticMetadata.PARENT_TYPE_APPSEARCH_SCHEMA);
        }

        for (int i = 0; i < appFunctionPkgs.size(); i++) {
            PackageIdentifier pkg = appFunctionPkgs.get(i);
            Map<String, AppSearchSchema> packageSchemas =
                    dynamicSchemas.getOrDefault(pkg.getPackageName(), Collections.emptyMap());
            if (!packageSchemas.isEmpty()) {
                // Dynamic schemas are handled separately.
                continue;
            }
            AppSearchSchema schema =
                    AppFunctionStaticMetadata.createAppFunctionSchemaForPackage(
                            pkg.getPackageName());
            builder.addSchemas(schema);
            builder.setPubliclyVisibleSchema(schema.getSchemaType(), pkg);
        }
        return builder.build();
    }

    /**
     * Adds dynamic schemas to a schema request builder for the specified app function packages.
     *
     * @param builder The {@link SetSchemaRequest.Builder} to which dynamic schemas will be added.
     * @param appFunctionPkgs A list of {@link PackageIdentifier}s representing app function
     *     packages.
     * @param dynamicSchemas A map of package names to their dynamic schemas, represented as a map
     *     of schema names to {@link AppSearchSchema}.
     */
    private void addDynamicSchemasToBuilder(
            @NonNull SetSchemaRequest.Builder builder,
            @NonNull List<PackageIdentifier> appFunctionPkgs,
            @NonNull Map<String, Map<String, AppSearchSchema>> dynamicSchemas) {
        for (int i = 0; i < appFunctionPkgs.size(); i++) {
            PackageIdentifier pkg = appFunctionPkgs.get(i);
            Map<String, AppSearchSchema> packageSchemas =
                    dynamicSchemas.getOrDefault(pkg.getPackageName(), Collections.emptyMap());
            for (Map.Entry<String, AppSearchSchema> entry : packageSchemas.entrySet()) {
                builder.addSchemas(entry.getValue());
                builder.setPubliclyVisibleSchema(entry.getKey(), pkg);
            }
        }
    }

    /**
     * Iteratively adds dynamic schemas to the AppsIndexer database to ensure all schemas are
     * applied successfully, skipping invalid schemas.
     *
     * @param preDefinedSchemaRequest The base schema request containing predefined schemas.
     * @param appFunctionPkgs A list of {@link PackageIdentifier}s representing app function
     *     packages.
     * @param dynamicSchemas A map of package names to their dynamic schemas, represented as a map
     *     of schema names to {@link AppSearchSchema}.
     */
    private void iterativelyAddDynamicSchema(
            @NonNull SetSchemaRequest preDefinedSchemaRequest,
            @NonNull List<PackageIdentifier> appFunctionPkgs,
            @NonNull Map<String, Map<String, AppSearchSchema>> dynamicSchemas) {
        SetSchemaRequest prevSuccessfulRequest = preDefinedSchemaRequest;

        for (int i = 0; i < appFunctionPkgs.size(); i++) {
            PackageIdentifier pkg = appFunctionPkgs.get(i);
            Map<String, AppSearchSchema> packageSchemas =
                    dynamicSchemas.getOrDefault(pkg.getPackageName(), Collections.emptyMap());
            if (packageSchemas.isEmpty()) {
                continue;
            }

            SetSchemaRequest.Builder builder = new SetSchemaRequest.Builder(prevSuccessfulRequest);
            for (Map.Entry<String, AppSearchSchema> entry : packageSchemas.entrySet()) {
                builder.addSchemas(entry.getValue());
                builder.setPubliclyVisibleSchema(entry.getKey(), pkg);
            }

            try {
                SetSchemaRequest currentRequest = builder.build();
                mSyncAppSearchAppsDbSession.setSchema(currentRequest);
                prevSuccessfulRequest = currentRequest; // Update on success
            } catch (AppSearchException e) {
                Log.e(TAG, "Skipping invalid schemas for package: " + pkg.getPackageName(), e);
            }
        }
    }

    /**
     * Creates and populate the schemas for MobileApplications per package in the SetSchemaRequest.
     */
    private static void populateMobileApplicationSchemas(
            @NonNull SetSchemaRequest.Builder schemaBuilder,
            @NonNull List<PackageIdentifier> mobileAppPkgs) {
        for (int i = 0; i < mobileAppPkgs.size(); i++) {
            PackageIdentifier pkg = mobileAppPkgs.get(i);
            // As all apps are in the same db, we have to make sure that even if it's getting
            // updated, the schema is in the list of schemas
            String packageName = pkg.getPackageName();
            AppSearchSchema schemaVariant =
                    MobileApplication.createMobileApplicationSchemaForPackage(packageName);
            schemaBuilder.addSchemas(schemaVariant);

            // Since the Android package of the underlying apps are different from the package name
            // that "owns" the builtin:MobileApplication corpus in AppSearch, we needed to add the
            // PackageIdentifier parameter to setPubliclyVisibleSchema.
            schemaBuilder.setPubliclyVisibleSchema(schemaVariant.getSchemaType(), pkg);
        }
    }

    /**
     * Sets the schema for AppOpenEvent. Unlike the apps indexer and apps functions, this schema is
     * not per-package permissioned. It is a single schema that is shared by all packages, with
     * PACKAGE_USAGE_STATS as the required permission to mimic the UsageStatsManager API.
     */
    @WorkerThread
    public void setSchemaForAppOpenEvents() throws AppSearchException {
        SetSchemaRequest.Builder schemaBuilder =
                new SetSchemaRequest.Builder()
                        .addRequiredPermissionsForSchemaTypeVisibility(
                                AppOpenEvent.SCHEMA_TYPE,
                                Collections.singleton(SetSchemaRequest.PACKAGE_USAGE_STATS))
                        .setForceOverride(true)
                        .addSchemas(AppOpenEvent.SCHEMA);

        mSyncAppSearchAppOpenEventDbSession.setSchema(schemaBuilder.build());
    }

    /**
     * Indexes a collection of app open events into AppSearch. This requires that the AppOpenEvent
     * schema is already set by a previous call to {@link setSchemaForAppOpenEvents}.
     *
     * @param appOpenEvents a list of {@link AppOpenEvent}s.
     * @throws AppSearchException if indexing results in a {@link
     *     AppSearchResult#RESULT_OUT_OF_SPACE} result code.
     */
    @WorkerThread
    public AppSearchBatchResult<String, Void> indexAppOpenEvents(
            @NonNull List<AppOpenEvent> appOpenEvents,
            @NonNull AppOpenEventStats.Builder appOpenEventStatsBuilder)
            throws AppSearchException {
        Objects.requireNonNull(appOpenEvents);

        PutDocumentsRequest request =
                new PutDocumentsRequest.Builder().addGenericDocuments(appOpenEvents).build();

        AppSearchBatchResult<String, Void> result =
                mSyncAppSearchAppOpenEventDbSession.put(request);
        if (!result.isSuccess()) {
            Map<String, AppSearchResult<Void>> failures = result.getFailures();
            for (AppSearchResult<Void> failure : failures.values()) {
                appOpenEventStatsBuilder.addUpdateStatusCode(failure.getResultCode());
                // If it's out of space, stop indexing
                if (failure.getResultCode() == AppSearchResult.RESULT_OUT_OF_SPACE) {
                    throw new AppSearchException(
                            failure.getResultCode(), failure.getErrorMessage());
                } else {
                    Log.e(TAG, "Ran into error while indexing apps: " + failure);
                }
            }
        } else {
            appOpenEventStatsBuilder.addUpdateStatusCode(AppSearchResult.RESULT_OK);
        }
        return result;
    }

    /**
     * Indexes a collection of apps and a collection of app functions into AppSearch. This requires
     * that the corresponding {@link MobileApplication} and {@link AppFunctionStaticMetadata}
     * schemas are already set by a previous call to {@link #setSchemasForPackages}. The call
     * doesn't necessarily have to happen in the current sync.
     *
     * @param apps a list of {@link MobileApplication} documents to be inserted.
     * @param currentAppFunctionDocuments a list of {@link AppFunctionDocument} documents to be
     *     indexed.
     * @throws AppSearchException if indexing results in a {@link
     *     AppSearchResult#RESULT_OUT_OF_SPACE} result code. It will also throw this if the put call
     *     results in a system error as in {@link BatchResultCallback#onSystemError}. This may
     *     happen if the AppSearch service unexpectedly fails to initialize and can't be recovered,
     *     for instance.
     * @return an {@link AppSearchBatchResult} containing the results of the put operation. The keys
     *     of the returned {@link AppSearchBatchResult} are the IDs of the input documents. The
     *     values are {@code null} if they were successfully indexed, or a failed {@link
     *     AppSearchResult} otherwise.
     * @see AppSearchSession#put
     */
    @WorkerThread
    public AppSearchBatchResult<String, Void> indexApps(
            @NonNull List<MobileApplication> apps,
            @NonNull List<AppFunctionDocument> currentAppFunctionDocuments)
            throws AppSearchException {
        Objects.requireNonNull(apps);
        Objects.requireNonNull(currentAppFunctionDocuments);

        // Insert all the documents. At this point, the proper schemas should've been set.
        PutDocumentsRequest request =
                new PutDocumentsRequest.Builder()
                        .addGenericDocuments(apps)
                        .addGenericDocuments(currentAppFunctionDocuments)
                        .build();

        AppSearchBatchResult<String, Void> result = mSyncAppSearchAppsDbSession.put(request);
        if (!result.isSuccess()) {
            Map<String, AppSearchResult<Void>> failures = result.getFailures();
            for (AppSearchResult<Void> failure : failures.values()) {
                // If it's out of space, stop indexing
                if (failure.getResultCode() == AppSearchResult.RESULT_OUT_OF_SPACE) {
                    throw new AppSearchException(
                            failure.getResultCode(), failure.getErrorMessage());
                } else {
                    Log.e(TAG, "Ran into error while indexing apps: " + failure);
                }
            }
        }
        return result;
    }

    /** Uses remove by id to remove app functions from AppSearch */
    @WorkerThread
    public AppSearchBatchResult<String, Void> removeAppFunctionsById(
            @NonNull Collection<String> appFunctionIds) throws AppSearchException {
        Objects.requireNonNull(appFunctionIds);
        return mSyncAppSearchAppsDbSession.remove(
                new RemoveByDocumentIdRequest.Builder(
                                AppFunctionStaticMetadata.APP_FUNCTION_NAMESPACE)
                        .addIds(appFunctionIds)
                        .build());
    }

    /**
     * Returns a mapping of packages to a mapping of document ids to {@link AppFunctionDocument}
     * objects in {@link AppFunctionStaticMetadata#APP_FUNCTION_NAMESPACE}. This is useful for
     * determining what has changed during an update.
     *
     * <p>This method is used for testing purposes only.
     *
     * @param appPackageIds a set of package ids for which to retrieve functions from AppSearch.
     */
    @NonNull
    @WorkerThread
    @VisibleForTesting
    Map<String, Map<String, AppFunctionDocument>> getAppFunctionDocumentsFromAppSearch(
            Set<String> appPackageIds) throws AppSearchException {
        return getAppFunctionDocumentsFromAppSearch(appPackageIds, /* appsIndexerConfig= */ null);
    }

    /**
     * Returns a mapping of packages to a mapping of document ids to {@link AppFunctionDocument}
     * objects in {@link AppFunctionStaticMetadata#APP_FUNCTION_NAMESPACE}. This is useful for
     * determining what has changed during an update.
     *
     * @param appPackageIds a set of package ids for which to retrieve functions from AppSearch.
     * @param config the {@link AppsIndexerConfig} to use for number of results per app. If null,
     *     the defaults for spec will be used.
     */
    @NonNull
    @WorkerThread
    public Map<String, Map<String, AppFunctionDocument>> getAppFunctionDocumentsFromAppSearch(
            Set<String> appPackageIds, @Nullable AppsIndexerConfig appsIndexerConfig)
            throws AppSearchException {
        SearchSpec allAppFunctionsSpec =
                new SearchSpec.Builder()
                        .addFilterNamespaces(AppFunctionStaticMetadata.APP_FUNCTION_NAMESPACE)
                        .build();

        JoinSpec.Builder appFunctionJoinSpecBuilder =
                new JoinSpec.Builder(AppFunctionDocument.PROPERTY_MOBILE_APPLICATION_QUALIFIED_ID)
                        .setNestedSearch("", allAppFunctionsSpec);

        if (appsIndexerConfig != null) {
            appFunctionJoinSpecBuilder.setMaxJoinedResultCount(
                    appsIndexerConfig.getMaxAppFunctionsPerPackage());
        } else {
            appFunctionJoinSpecBuilder.setMaxJoinedResultCount(Integer.MAX_VALUE);
        }

        SearchSpec mobileApplicationSearchSpec =
                new SearchSpec.Builder()
                        .addFilterNamespaces(MobileApplication.APPS_NAMESPACE)
                        .addProjection(
                                SearchSpec.SCHEMA_TYPE_WILDCARD,
                                List.of(MobileApplication.APP_PROPERTY_PACKAGE_NAME))
                        .setJoinSpec(appFunctionJoinSpecBuilder.build())
                        .build();

        try (SyncSearchResults results =
                mSyncAppSearchAppsDbSession.search("", mobileApplicationSearchSpec)) {
            return collectAppFunctionDocumentsFromAllPages(results, new ArraySet<>(appPackageIds));
        } catch (IOException e) {
            throw new AppSearchException(RESULT_IO_ERROR, "Failed to close search results", e);
        }
    }

    /**
     * Iterates through result pages and returns a mapping of package names to a mapping of document
     * ids to the corresponding app function documents currently indexed into AppSearch.
     *
     * @param results results from a search query to retrieve all the app function documents.
     * @param appPackageIds a set of package ids for which to retrieve functions from AppSearch.
     */
    @NonNull
    @WorkerThread
    private Map<String, Map<String, AppFunctionDocument>> collectAppFunctionDocumentsFromAllPages(
            @NonNull SyncSearchResults results, Set<String> appPackageIds) {
        Map<String, Map<String, AppFunctionDocument>> appFunctionDocumentsMap = new ArrayMap<>();
        // TODO(b/357551503): If possible, use pagination instead of building a map containing all
        // function docs.
        try {
            List<SearchResult> resultList = results.getNextPage();
            while (!resultList.isEmpty()) {
                for (int i = 0; i < resultList.size(); i++) {
                    GenericDocument genericDocument = resultList.get(i).getGenericDocument();
                    String packageName =
                            genericDocument.getPropertyString(
                                    MobileApplication.APP_PROPERTY_PACKAGE_NAME);
                    List<SearchResult> joinedResultList = resultList.get(i).getJoinedResults();
                    if (!appPackageIds.contains(packageName) || joinedResultList.isEmpty()) {
                        continue;
                    }

                    Map<String, AppFunctionDocument> functionDocumentsForPackage =
                            appFunctionDocumentsMap.computeIfAbsent(
                                    packageName, k -> new ArrayMap<>());
                    for (int j = 0; j < joinedResultList.size(); j++) {
                        AppFunctionDocument functionDocument =
                                new AppFunctionDocument(
                                        joinedResultList.get(j).getGenericDocument());
                        functionDocumentsForPackage.put(functionDocument.getId(), functionDocument);
                    }
                }
                resultList = results.getNextPage();
            }
        } catch (AppSearchException e) {
            Log.e(TAG, "Error while searching for all app documents", e);
        }
        return appFunctionDocumentsMap;
    }

    /**
     * Searches AppSearch and returns a Map with the package ids to their last updated times and
     * whether app function service was enabled. This helps us determine which app documents need to
     * be re-indexed.
     *
     * @return a mapping of document id Strings to MobileApplication document with updatedTimestamp
     *     and isAppFunctionServiceEnabled properties.
     */
    @NonNull
    @WorkerThread
    public Map<String, MobileApplication>
            getAppsLastUpdatedTimeAndAppFunctionServiceEnabledFromAppSearch()
                    throws AppSearchException {
        SearchSpec allAppsSpec =
                new SearchSpec.Builder()
                        .addFilterNamespaces(MobileApplication.APPS_NAMESPACE)
                        .addProjection(
                                SearchSpec.SCHEMA_TYPE_WILDCARD,
                                List.of(
                                        MobileApplication.APP_PROPERTY_UPDATED_TIMESTAMP,
                                        MobileApplication
                                                .APP_PROPERTY_IS_APP_FUNCTION_SERVICE_ENABLED))
                        .addFilterPackageNames(mContext.getPackageName())
                        .setResultCountPerPage(GET_APP_IDS_PAGE_SIZE)
                        .build();
        try (SyncSearchResults results =
                mSyncGlobalSearchSession.search(/* query= */ "", allAppsSpec)) {
            return collectUpdatedTimestampAndAppFunctionServiceEnabledFromAllPages(results);
        } catch (IOException e) {
            throw new AppSearchException(RESULT_IO_ERROR, "Failed to close search results", e);
        }
    }

    /**
     * Searches AppSearch and returns the AppOpenEvent with the next app open event timestamp
     * (larger time in epoch) after the provided timestamp threshold.
     *
     * @param timestampThresholdMillis the timestamp to filter the app open events by. The returned
     *     timestamp will be after this timestamp.
     * @return the first AppOpenEvent whose timestamp occurs after the provided timestamp.
     * @throws AppSearchException if no results are found for the given timestamp threshold.
     */
    @NonNull
    @WorkerThread
    public AppOpenEvent getSubsequentAppOpenEventAfterThreshold(
            @CurrentTimeMillisLong long timestampThresholdMillis) throws AppSearchException {

        // Creation timestamp is set to event timestamp, so sorting in ascending order of creation
        // timestamp gives us
        // the first event after the threshold.
        SearchSpec latestAppOpenEventsSpec =
                new SearchSpec.Builder()
                        .addFilterNamespaces(AppOpenEvent.APP_OPEN_EVENT_NAMESPACE)
                        .setOrder(SearchSpec.ORDER_ASCENDING)
                        .setListFilterQueryLanguageEnabled(true)
                        .setNumericSearchEnabled(true)
                        .setResultCountPerPage(1)
                        .setRankingStrategy(SearchSpec.RANKING_STRATEGY_CREATION_TIMESTAMP)
                        .build();

        try (SyncSearchResults results =
                mSyncAppSearchAppOpenEventDbSession.search(
                        /* query= */ "appOpenTimestampMillis > " + timestampThresholdMillis,
                        latestAppOpenEventsSpec)) {

            List<SearchResult> page = results.getNextPage();

            if (page.isEmpty()) {
                throw new AppSearchException(
                        RESULT_INVALID_ARGUMENT,
                        "No app open events were found for the given timestamp threshold.");
            }
            return new AppOpenEvent(page.get(0).getGenericDocument());

        } catch (IOException e) {
            throw new AppSearchException(RESULT_IO_ERROR, "Failed to close search results", e);
        }
    }

    /**
     * Iterates through result pages to get the last updated times and AppFunctionService's enabled
     * state in the app.
     *
     * @return a mapping of package name to MobileApplication documents with updatedTimestamps and
     *     isAppFunctionServiceEnabled properties.
     */
    @NonNull
    @WorkerThread
    private Map<String, MobileApplication>
            collectUpdatedTimestampAndAppFunctionServiceEnabledFromAllPages(
                    @NonNull SyncSearchResults results) {
        Objects.requireNonNull(results);
        Map<String, MobileApplication> appUpdatedMap = new ArrayMap<>();

        try {
            List<SearchResult> resultList = results.getNextPage();

            while (!resultList.isEmpty()) {
                for (int i = 0; i < resultList.size(); i++) {
                    SearchResult result = resultList.get(i);
                    appUpdatedMap.put(
                            result.getGenericDocument().getId(),
                            new MobileApplication(result.getGenericDocument()));
                }

                resultList = results.getNextPage();
            }
        } catch (AppSearchException e) {
            Log.e(TAG, "Error while searching for all app documents", e);
        }
        // Return what we have so far. Even if this doesn't fetch all documents, that is fine as we
        // can continue with indexing. The documents that aren't fetched will be detected as new
        // apps and re-indexed.
        return appUpdatedMap;
    }

    /** Closes the AppSearch sessions. */
    @Override
    public void close() {
        mSyncAppSearchAppsDbSession.close();
        mSyncAppSearchAppOpenEventDbSession.close();
        mSyncGlobalSearchSession.close();
    }
}
