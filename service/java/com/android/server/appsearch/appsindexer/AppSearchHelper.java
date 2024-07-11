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

import android.annotation.NonNull;
import android.annotation.WorkerThread;
import android.app.appsearch.AppSearchBatchResult;
import android.app.appsearch.AppSearchEnvironmentFactory;
import android.app.appsearch.AppSearchManager;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.BatchResultCallback;
import android.app.appsearch.PackageIdentifier;
import android.app.appsearch.PutDocumentsRequest;
import android.app.appsearch.SearchResult;
import android.app.appsearch.SearchSpec;
import android.app.appsearch.SetSchemaRequest;
import android.app.appsearch.exceptions.AppSearchException;
import android.content.Context;
import android.util.AndroidRuntimeException;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.appsearch.appsindexer.appsearchtypes.MobileApplication;

import java.io.Closeable;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private static final int GET_APP_IDS_PAGE_SIZE = 1000;
    private final Context mContext;
    // Volatile, not final due to being swapped during some tests
    private volatile SyncAppSearchSession mSyncAppSearchSession;
    private final SyncGlobalSearchSession mSyncGlobalSearchSession;

    /** Creates an {@link AppSearchHelper}. */
    public AppSearchHelper(@NonNull Context context) {
        mContext = Objects.requireNonNull(context);
        AppSearchManager appSearchManager = mContext.getSystemService(AppSearchManager.class);
        if (appSearchManager == null) {
            throw new AndroidRuntimeException(
                    "Can't get AppSearchManager to initialize AppSearchHelper.");
        }
        AppSearchManager.SearchContext searchContext =
                new AppSearchManager.SearchContext.Builder(APP_DATABASE).build();
        ExecutorService executor =
                AppSearchEnvironmentFactory.getEnvironmentInstance().createSingleThreadExecutor();
        mSyncAppSearchSession =
                new SyncAppSearchSessionImpl(appSearchManager, searchContext, executor);
        mSyncGlobalSearchSession = new SyncGlobalSearchSessionImpl(appSearchManager, executor);
    }

    /**
     * Allows us to test various scenarios involving SyncAppSearchSession.
     *
     * <p>This method is not thread-safe, as it could be ran in the middle of a set schema, index,
     * or search operation. It should only be called from tests, and threading safety should be
     * handled by the test.
     */
    @VisibleForTesting
    /* package */ void setAppSearchSessionForTest(@NonNull SyncAppSearchSession session) {
        // Close the existing one
        if (mSyncAppSearchSession != null) {
            mSyncAppSearchSession.close();
        }
        mSyncAppSearchSession = Objects.requireNonNull(session);
    }

    /**
     * Sets the AppsIndexer database schema to correspond to the list of passed in {@link
     * PackageIdentifier}s. Note that this means if a schema exists in AppSearch that does not get
     * passed in to this method, it will be erased. And if a schema does not exist in AppSearch that
     * is passed in to this method, it will be created.
     */
    @WorkerThread
    public void setSchemasForPackages(@NonNull List<PackageIdentifier> pkgs)
            throws AppSearchException {
        Objects.requireNonNull(pkgs);
        SetSchemaRequest.Builder schemaBuilder =
                new SetSchemaRequest.Builder()
                        // If MobileApplication schema later gets changed to a compatible schema, we
                        // should first try setting the schema with forceOverride = false.
                        .setForceOverride(true);
        for (int i = 0; i < pkgs.size(); i++) {
            PackageIdentifier pkg = pkgs.get(i);
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

        // TODO(b/275592563): Log app removal in metrics
        mSyncAppSearchSession.setSchema(schemaBuilder.build());
    }

    /**
     * Indexes a collection of apps into AppSearch. This requires that the corresponding
     * MobileApplication schemas are already set by a previous call to {@link
     * #setSchemasForPackages}. The call doesn't necessarily have to happen in the current sync.
     *
     * @throws AppSearchException if indexing results in a {@link
     *     AppSearchResult#RESULT_OUT_OF_SPACE} result code. It will also throw this if the put call
     *     results in a system error as in {@link BatchResultCallback#onSystemError}. This may
     *     happen if the AppSearch service unexpectedly fails to initialize and can't be recovered,
     *     for instance.
     */
    @WorkerThread
    public void indexApps(@NonNull List<MobileApplication> apps) throws AppSearchException {
        Objects.requireNonNull(apps);

        // At this point, the document schema names have already been set to the per-package name.
        // We can just add them to the request.
        PutDocumentsRequest request =
                new PutDocumentsRequest.Builder().addGenericDocuments(apps).build();

        AppSearchBatchResult<String, Void> result = mSyncAppSearchSession.put(request);
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
    }

    /**
     * Searches AppSearch and returns a Map with the package ids and their last updated times. This
     * helps us determine which app documents need to be re-indexed.
     */
    @NonNull
    @WorkerThread
    public Map<String, Long> getAppsFromAppSearch() throws AppSearchException {
        SearchSpec allAppsSpec =
                new SearchSpec.Builder()
                        .addFilterNamespaces(MobileApplication.APPS_NAMESPACE)
                        .addProjection(
                                SearchSpec.SCHEMA_TYPE_WILDCARD,
                                Collections.singletonList(
                                        MobileApplication.APP_PROPERTY_UPDATED_TIMESTAMP))
                        .addFilterPackageNames(mContext.getPackageName())
                        .setResultCountPerPage(GET_APP_IDS_PAGE_SIZE)
                        .build();
        SyncSearchResults results = mSyncGlobalSearchSession.search(/* query= */ "", allAppsSpec);
        return collectUpdatedTimestampFromAllPages(results);
    }

    /** Iterates through result pages to get the last updated times */
    @NonNull
    @WorkerThread
    private Map<String, Long> collectUpdatedTimestampFromAllPages(
            @NonNull SyncSearchResults results) {
        Objects.requireNonNull(results);
        Map<String, Long> appUpdatedMap = new ArrayMap<>();

        try {
            List<SearchResult> resultList = results.getNextPage();

            while (!resultList.isEmpty()) {
                for (int i = 0; i < resultList.size(); i++) {
                    SearchResult result = resultList.get(i);
                    appUpdatedMap.put(
                            result.getGenericDocument().getId(),
                            result.getGenericDocument()
                                    .getPropertyLong(
                                            MobileApplication.APP_PROPERTY_UPDATED_TIMESTAMP));
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
        mSyncAppSearchSession.close();
        mSyncGlobalSearchSession.close();
    }
}
