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
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.PackageIdentifier;
import android.app.appsearch.exceptions.AppSearchException;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.appsearch.appsindexer.appsearchtypes.AppFunctionStaticMetadata;
import com.android.server.appsearch.appsindexer.appsearchtypes.MobileApplication;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Interactions with PackageManager and AppSearch.
 *
 * <p>This class is NOT thread-safe.
 *
 * @hide
 */
public final class AppsIndexerImpl implements Closeable {
    static final String TAG = "AppSearchAppsIndexerImpl";

    private final Context mContext;
    private final AppSearchHelper mAppSearchHelper;
    private final AppsIndexerConfig mAppsIndexerConfig;

    public AppsIndexerImpl(@NonNull Context context, @NonNull AppsIndexerConfig appsIndexerConfig)
            throws AppSearchException {
        mContext = Objects.requireNonNull(context);
        mAppSearchHelper = new AppSearchHelper(context);
        mAppsIndexerConfig = Objects.requireNonNull(appsIndexerConfig);
    }

    /**
     * Checks PackageManager and AppSearch to sync the Apps Index in AppSearch.
     *
     * <p>It deletes removed apps, inserts newly-added ones, and updates existing ones in the App
     * corpus in AppSearch.
     *
     * @param settings contains update timestamps that help the indexer determine which apps were
     *     updated.
     * @param appsUpdateStats contains stats about the apps indexer update. This method will
     *     populate the fields of this {@link AppsUpdateStats} structure.
     */
    @VisibleForTesting
    @WorkerThread
    public void doUpdate(
            @NonNull AppsIndexerSettings settings, @NonNull AppsUpdateStats appsUpdateStats)
            throws AppSearchException {
        Objects.requireNonNull(settings);
        Objects.requireNonNull(appsUpdateStats);
        long currentTimeMillis = System.currentTimeMillis();

        // Search AppSearch for MobileApplication objects to get a "current" list of indexed apps.
        long beforeGetTimestamp = SystemClock.elapsedRealtime();
        Map<String, Long> appUpdatedTimestamps = mAppSearchHelper.getAppsFromAppSearch();
        appsUpdateStats.mAppSearchGetLatencyMillis =
                SystemClock.elapsedRealtime() - beforeGetTimestamp;

        long beforePackageManagerTimestamp = SystemClock.elapsedRealtime();
        PackageManager packageManager = mContext.getPackageManager();
        Map<PackageInfo, ResolveInfos> packagesToIndex =
                AppsUtil.getPackagesToIndex(packageManager);
        appsUpdateStats.mPackageManagerLatencyMillis =
                SystemClock.elapsedRealtime() - beforePackageManagerTimestamp;
        Set<PackageInfo> packageInfos = packagesToIndex.keySet();

        Map<PackageInfo, ResolveInfos> packagesToBeAddedOrUpdated = new ArrayMap<>();
        long mostRecentAppUpdatedTimestampMillis = settings.getLastAppUpdateTimestampMillis();

        // Prepare a set of current app IDs for efficient lookup
        Set<String> currentAppIds = new ArraySet<>();
        for (PackageInfo packageInfo : packageInfos) {
            currentAppIds.add(packageInfo.packageName);

            // Update the most recent timestamp as we iterate
            if (packageInfo.lastUpdateTime > mostRecentAppUpdatedTimestampMillis) {
                mostRecentAppUpdatedTimestampMillis = packageInfo.lastUpdateTime;
            }

            Long storedUpdateTime = appUpdatedTimestamps.get(packageInfo.packageName);

            boolean added = storedUpdateTime == null;
            boolean updated =
                    storedUpdateTime != null && packageInfo.lastUpdateTime != storedUpdateTime;

            if (added) {
                appsUpdateStats.mNumberOfAppsAdded++;
            }
            if (updated) {
                appsUpdateStats.mNumberOfAppsUpdated++;
            }
            if (added || updated) {
                packagesToBeAddedOrUpdated.put(packageInfo, packagesToIndex.get(packageInfo));
            } else {
                appsUpdateStats.mNumberOfAppsUnchanged++;
            }
        }

        List<GenericDocument> appSearchAppFunctions =
                mAppSearchHelper.getAppFunctionsFromAppSearch();

        try {
            if (!currentAppIds.equals(appUpdatedTimestamps.keySet())
                    || requiresInsertSchemaForAppFunction(packagesToIndex, appSearchAppFunctions)) {
                // The current list of apps/app functions in AppSearch does not match what is in
                // PackageManager. This means this is the first sync, an app/app function was
                // removed, or an app/app function was added. In all cases, we need to call
                // setSchema to keep AppSearch in sync with PackageManager.

                // currentAppIds comes from PackageManager, appUpdatedTimestamps comes from
                // AppSearch. Deleted apps are those in appUpdateTimestamps and NOT in currentAppIds
                appsUpdateStats.mNumberOfAppsRemoved = 0;
                for (String appSearchApp : appUpdatedTimestamps.keySet()) {
                    if (!currentAppIds.contains(appSearchApp)) {
                        appsUpdateStats.mNumberOfAppsRemoved++;
                    }
                }

                List<PackageIdentifier> packageIdentifiers = new ArrayList<>();
                List<PackageIdentifier> packageIdentifiersWithAppFunctions = new ArrayList<>();
                for (Map.Entry<PackageInfo, ResolveInfos> entry : packagesToIndex.entrySet()) {
                    // We get certificates here as getting the certificates during the previous for
                    // loop would be wasteful if we end up not needing to call set schema
                    PackageInfo packageInfo = entry.getKey();
                    byte[] certificate = AppsUtil.getCertificate(packageInfo);
                    if (certificate == null) {
                        Log.e(TAG, "Certificate not found for package: " + packageInfo.packageName);
                        continue;
                    }
                    PackageIdentifier packageIdentifier =
                            new PackageIdentifier(packageInfo.packageName, certificate);
                    packageIdentifiers.add(packageIdentifier);
                    if (entry.getValue().getAppFunctionServiceInfo() != null) {
                        packageIdentifiersWithAppFunctions.add(packageIdentifier);
                    }
                }
                // The certificate is necessary along with the package name as it is used in
                // visibility settings.
                long beforeSetSchemaTimestamp = SystemClock.elapsedRealtime();
                mAppSearchHelper.setSchemasForPackages(
                        packageIdentifiers, packageIdentifiersWithAppFunctions);
                appsUpdateStats.mAppSearchSetSchemaLatencyMillis =
                        SystemClock.elapsedRealtime() - beforeSetSchemaTimestamp;
            }

            if (!packagesToBeAddedOrUpdated.isEmpty()) {
                long beforePutTimestamp = SystemClock.elapsedRealtime();
                List<MobileApplication> mobileApplications =
                        AppsUtil.buildAppsFromPackageInfos(
                                packageManager, packagesToBeAddedOrUpdated);
                List<AppFunctionStaticMetadata> appFunctions =
                        AppsUtil.buildAppFunctionStaticMetadata(
                                packageManager,
                                packagesToBeAddedOrUpdated,
                                /* indexerPackageName= */ mContext.getPackageName(),
                                mAppsIndexerConfig.getMaxAppFunctionsPerPackage());

                AppSearchBatchResult<String, Void> result =
                        mAppSearchHelper.indexApps(
                                mobileApplications,
                                appFunctions,
                                appSearchAppFunctions,
                                appsUpdateStats);
                // Here we log all of these functions as "updated". However, some of these may be
                // added or unchanged.
                // TODO(b/357551503): Log function counts more accurately
                appsUpdateStats.mNumberOfFunctionsUpdated = appFunctions.size();
                if (result.isSuccess()) {
                    appsUpdateStats.mUpdateStatusCodes.add(AppSearchResult.RESULT_OK);
                } else {
                    Collection<AppSearchResult<Void>> values = result.getAll().values();

                    for (AppSearchResult<Void> putResult : values) {
                        appsUpdateStats.mUpdateStatusCodes.add(putResult.getResultCode());
                    }
                }
                appsUpdateStats.mAppSearchPutLatencyMillis =
                        SystemClock.elapsedRealtime() - beforePutTimestamp;
            }

            settings.setLastAppUpdateTimestampMillis(mostRecentAppUpdatedTimestampMillis);
            settings.setLastUpdateTimestampMillis(currentTimeMillis);

            appsUpdateStats.mLastAppUpdateTimestampMillis = mostRecentAppUpdatedTimestampMillis;
        } catch (AppSearchException e) {
            // Reset the last update time stamp and app update timestamp so we can try again later.
            settings.reset();
            appsUpdateStats.mUpdateStatusCodes.clear();
            appsUpdateStats.mUpdateStatusCodes.add(e.getResultCode());
            throw e;
        }
    }

    /** Returns whether the indexer should insert schema for app functions. */
    private boolean requiresInsertSchemaForAppFunction(
            @NonNull Map<PackageInfo, ResolveInfos> targetedPackages,
            List<GenericDocument> appSearchAppFunctions)
            throws AppSearchException {
        // Should re-insert the schema as long as the indexed packages does not match the current
        // set of packages.
        Set<String> indexedAppFunctionPackages = new ArraySet<>();
        for (int i = 0; i < appSearchAppFunctions.size(); i++) {
            indexedAppFunctionPackages.add(
                    appSearchAppFunctions
                            .get(i)
                            .getPropertyString(AppFunctionStaticMetadata.PROPERTY_PACKAGE_NAME));
        }
        Set<String> currentAppFunctionPackages = getCurrentAppFunctionPackages(targetedPackages);
        return !indexedAppFunctionPackages.equals(currentAppFunctionPackages);
    }

    /** Returns a set of currently installed packages that have app functions. */
    private Set<String> getCurrentAppFunctionPackages(
            @NonNull Map<PackageInfo, ResolveInfos> targetedPackages) {
        Set<String> currentAppFunctionPackages = new ArraySet<>();
        for (Map.Entry<PackageInfo, ResolveInfos> entry : targetedPackages.entrySet()) {
            PackageInfo packageInfo = entry.getKey();
            ResolveInfos resolveInfos = entry.getValue();
            if (resolveInfos.getAppFunctionServiceInfo() != null) {
                currentAppFunctionPackages.add(packageInfo.packageName);
            }
        }
        return currentAppFunctionPackages;
    }

    /** Shuts down the {@link AppsIndexerImpl} and its {@link AppSearchHelper}. */
    @Override
    public void close() {
        mAppSearchHelper.close();
    }
}
