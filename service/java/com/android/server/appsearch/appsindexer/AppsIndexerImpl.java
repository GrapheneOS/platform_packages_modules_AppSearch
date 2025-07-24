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
import android.annotation.Nullable;
import android.annotation.WorkerThread;
import android.app.appsearch.AppSearchBatchResult;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.PackageIdentifier;
import android.app.appsearch.exceptions.AppSearchException;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;

import com.android.appsearch.flags.Flags;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.appsearch.appsindexer.appsearchtypes.AppFunctionDocument;
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
    static final String TAG = "AppSearchAppIndxrImpl";

    private final Context mContext;
    private final AppSearchHelper mAppSearchHelper;
    private final AppsIndexerConfig mAppsIndexerConfig;

    public AppsIndexerImpl(@NonNull Context context, @NonNull AppsIndexerConfig appsIndexerConfig) {
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
     * @param isFullUpdateRequired whether to re-index all apps irrespective of their last update
     *     timestamp.
     */
    @VisibleForTesting
    @WorkerThread
    public void doUpdateIncrementalPut(
            @NonNull AppsIndexerSettings settings,
            @NonNull AppsUpdateStats appsUpdateStats,
            boolean isFullUpdateRequired)
            throws AppSearchException {
        // TODO(b/357551503): Split this method up into helper methods
        Objects.requireNonNull(settings);
        Objects.requireNonNull(appsUpdateStats);
        long currentTimeMillis = System.currentTimeMillis();

        // Search AppSearch for MobileApplication objects to get a "current" list of indexed apps.
        long beforeGetTimestamp = SystemClock.elapsedRealtime();
        Map<String, MobileApplication> previouslyIndexedAppDetails =
                mAppSearchHelper.getAppsLastUpdatedTimeAndAppFunctionServiceEnabledFromAppSearch();

        appsUpdateStats.mAppSearchGetLatencyMillis =
                SystemClock.elapsedRealtime() - beforeGetTimestamp;

        long beforePackageManagerTimestamp = SystemClock.elapsedRealtime();
        PackageManager packageManager = mContext.getPackageManager();
        Map<PackageInfo, ResolveInfos> packagesToIndex =
                AppsUtil.getPackagesToIndex(mContext, packageManager);
        appsUpdateStats.mPackageManagerLatencyMillis =
                SystemClock.elapsedRealtime() - beforePackageManagerTimestamp;

        long mostRecentAppUpdatedTimestampMillis = settings.getLastAppUpdateTimestampMillis();

        // This boolean will be turned on if an app was added, an app was removed, all app
        // functions were removed from an app, or app functions were added to an app that
        // didn't have them previously. In all cases, we need to call setSchema to keep
        // AppSearch in sync with PackageManager.
        boolean addedOrRemovedFlag = false;

        Set<String> packagesToIndexIdSet = new ArraySet<>();

        // Prepare a list of newly added and updated packages. Added packages will have all
        // their app functions added to AppSearch, without checking what's in AppSearch. Updated
        // packages will require an additional call to AppSearch to see if we need to
        // add/update/remove individual app function documents. We don't do this for added apps as
        // we can just assume we need to add all of them. This saves a call to AppSearch. For both
        // added and updated packages, we parse xml. We don't check what functions are in AppSearch
        // for removed packages, as we can just remove the entire MobileApplication +
        // AppFunctionStaticMetadata schemas, which will in turn remove the documents.
        Map<PackageInfo, ResolveInfos> packagesToBeAddedOrUpdated = new ArrayMap<>();
        Set<String> updatedPackageIds = new ArraySet<>();

        // First loop, determine the status of apps
        for (Map.Entry<PackageInfo, ResolveInfos> packageEntry : packagesToIndex.entrySet()) {
            PackageInfo packageInfo = packageEntry.getKey();
            ResolveInfo appFunctionServiceResolveInfo =
                    packageEntry.getValue().getAppFunctionServiceInfo();
            packagesToIndexIdSet.add(packageInfo.packageName);

            // Update the most recent timestamp as we iterate
            if (packageInfo.lastUpdateTime > mostRecentAppUpdatedTimestampMillis) {
                mostRecentAppUpdatedTimestampMillis = packageInfo.lastUpdateTime;
            }

            long storedAppUpdateTime = -1;

            boolean storedIsAppFunctionServiceEnabled = false;

            MobileApplication appDetails = previouslyIndexedAppDetails.get(packageInfo.packageName);
            if (appDetails != null) {
                storedAppUpdateTime = appDetails.getUpdatedTimestamp();
                storedIsAppFunctionServiceEnabled = appDetails.isAppFunctionServiceEnabled();
            }

            if (storedAppUpdateTime == -1) {
                // New app.
                addedOrRemovedFlag = true;
                appsUpdateStats.mNumberOfAppsAdded++;
                packagesToBeAddedOrUpdated.put(packageInfo, packageEntry.getValue());
            } else if (isPackageUpdatedOrChanged(
                            packageManager,
                            packageInfo,
                            storedAppUpdateTime,
                            appFunctionServiceResolveInfo,
                            storedIsAppFunctionServiceEnabled)
                    || isFullUpdateRequired) {
                // Package last update timestamp discrepancy between AppSearch and PackageManager
                // or app indexer code was updated. Add this to the list of updated
                // apps so we can check what functions are indexed in AppSearch
                appsUpdateStats.mNumberOfAppsUpdated++;
                updatedPackageIds.add(packageInfo.packageName);
                ResolveInfos resolveInfos = packagesToIndex.get(packageInfo);
                if (resolveInfos != null) {
                    packagesToBeAddedOrUpdated.put(packageInfo, resolveInfos);
                }
            } else {
                // Not updated.
                appsUpdateStats.mNumberOfAppsUnchanged++;
            }
        }

        // Now check for removed apps
        for (String appPackageId : previouslyIndexedAppDetails.keySet()) {
            if (!packagesToIndexIdSet.contains(appPackageId)) {
                // App was removed, remove all it's functions. This is simple because removing the
                // schema will remove all the functions. Do not add the app to the list of schemas
                // to set.
                appsUpdateStats.mNumberOfAppsRemoved++;
                addedOrRemovedFlag = true;
            }
        }

        Map<String, Map<String, AppSearchSchema>> dynamicAppFunctionSchemasForPackages = null;
        if (Flags.enableAppFunctionsSchemaParser()) {
            // TODO(b/382254638): Skip XML parsing for packages that were not updated by using
            // AppSearchSessio#getSchema.
            dynamicAppFunctionSchemasForPackages =
                    AppsUtil.getDynamicAppFunctionSchemasForPackages(
                            packageManager,
                            packagesToIndex,
                            mAppsIndexerConfig.getMaxAllowedAppFunctionSchemasPerPackage());
        }

        // Parse and build all necessary AppFunctionStaticMetadata from PackageManager.
        Map<String, Map<String, ? extends AppFunctionDocument>>
                currentAppFunctionsForAddedUpdatedPackages =
                        AppsUtil.buildAppFunctionDocumentsIntoMap(
                                packageManager,
                                packagesToBeAddedOrUpdated,
                                /* indexerPackageName= */ mContext.getPackageName(),
                                mAppsIndexerConfig,
                                dynamicAppFunctionSchemasForPackages);

        // Get all currently indexed AppFunctionStaticMetadata docs for the necessary packages.
        Map<String, Map<String, AppFunctionDocument>> appFunctionsFromAppSearch =
                mAppSearchHelper.getAppFunctionDocumentsFromAppSearch(updatedPackageIds);

        AppFunctionDiffCalculator.AppFunctionDiff appFunctionDiff =
                AppFunctionDiffCalculator.calculate(
                        appFunctionsFromAppSearch, currentAppFunctionsForAddedUpdatedPackages);
        addedOrRemovedFlag |= appFunctionDiff.modifySchema;
        List<AppFunctionDocument> functionDocumentsToAddOrUpdate =
                new ArrayList<>(appFunctionDiff.addedAppFunctions.values());
        functionDocumentsToAddOrUpdate.addAll(appFunctionDiff.updatedAppFunctions.values());

        try {
            // TODO(b/382254638): Skip set schema calls if no packages have an updated schema.
            if (dynamicAppFunctionSchemasForPackages != null) {
                // Since dynamic schemas are enabled, we need to account for schema changes in
                // both updated packages and newly added packages.
                Pair<List<PackageIdentifier>, List<PackageIdentifier>>
                        mobileAppAndAppFunctionIdentifiers =
                                getPackageIdentifiers(
                                        packagesToIndex,
                                        currentAppFunctionsForAddedUpdatedPackages);

                long beforeSetSchemaTimestamp = SystemClock.elapsedRealtime();
                mAppSearchHelper.setSchemasForPackages(
                        /* mobileAppPkgs= */ mobileAppAndAppFunctionIdentifiers.first,
                        /* appFunctionPkgs= */ mobileAppAndAppFunctionIdentifiers.second,
                        dynamicAppFunctionSchemasForPackages);
                appsUpdateStats.mAppSearchSetSchemaLatencyMillis =
                        SystemClock.elapsedRealtime() - beforeSetSchemaTimestamp;
            } else if (addedOrRemovedFlag) {
                // This branch is executed when dynamic schemas are disabled and new packages are
                // added or removed to keep the AppSearch schema in sync with
                // PackageManager.
                Pair<List<PackageIdentifier>, List<PackageIdentifier>>
                        mobileAppAndAppFunctionIdentifiers =
                                getPackageIdentifiers(
                                        packagesToIndex,
                                        currentAppFunctionsForAddedUpdatedPackages);

                // The certificate is necessary along with the package name as it is used in
                // visibility settings.
                long beforeSetSchemaTimestamp = SystemClock.elapsedRealtime();
                mAppSearchHelper.setSchemasForPackages(
                        /* mobileAppPkgs= */ mobileAppAndAppFunctionIdentifiers.first,
                        /* appFunctionPkgs= */ mobileAppAndAppFunctionIdentifiers.second);
                appsUpdateStats.mAppSearchSetSchemaLatencyMillis =
                        SystemClock.elapsedRealtime() - beforeSetSchemaTimestamp;
            }

            if (!packagesToBeAddedOrUpdated.isEmpty()
                    || !functionDocumentsToAddOrUpdate.isEmpty()) {
                long beforePutTimestamp = SystemClock.elapsedRealtime();
                List<MobileApplication> mobileApplications =
                        AppsUtil.buildAppsFromPackageInfos(
                                packageManager, packagesToBeAddedOrUpdated);

                AppSearchBatchResult<String, Void> result =
                        mAppSearchHelper.indexApps(
                                mobileApplications, functionDocumentsToAddOrUpdate);
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

            if (!appFunctionDiff.functionIdsToRemove.isEmpty()) {
                AppSearchBatchResult<String, Void> result =
                        mAppSearchHelper.removeAppFunctionsById(
                                appFunctionDiff.functionIdsToRemove);
                if (result.isSuccess()) {
                    appsUpdateStats.mUpdateStatusCodes.add(AppSearchResult.RESULT_OK);
                }
            }

            settings.setLastAppUpdateTimestampMillis(mostRecentAppUpdatedTimestampMillis);
            settings.setLastUpdateTimestampMillis(currentTimeMillis);

            appsUpdateStats.mNumberOfFunctionsAdded = appFunctionDiff.addedAppFunctions.size();
            appsUpdateStats.mNumberOfFunctionsUpdated = appFunctionDiff.updatedAppFunctions.size();
            appsUpdateStats.mApproximateNumberOfFunctionsRemoved =
                    appFunctionDiff.allDeletedFunctionIds.size();
            appsUpdateStats.mLastAppUpdateTimestampMillis = mostRecentAppUpdatedTimestampMillis;
        } catch (AppSearchException e) {
            // Reset the last update time stamp and app update timestamp so we can try again later.
            settings.reset();
            appsUpdateStats.mUpdateStatusCodes.clear();
            appsUpdateStats.mUpdateStatusCodes.add(e.getResultCode());
            settings.appendLog(String.format("Error updating apps indexer: %s", e));
            throw e;
        } finally {
            Set<String> removedApps = new ArraySet<>(previouslyIndexedAppDetails.keySet());
            removedApps.removeAll(packagesToIndexIdSet);

            List<String> addedAppPackageNames = new ArrayList<>();
            List<String> updatedAppPackageNames = new ArrayList<>();
            for (Map.Entry<PackageInfo, ResolveInfos> entry :
                    packagesToBeAddedOrUpdated.entrySet()) {
                if (!previouslyIndexedAppDetails.containsKey(entry.getKey().packageName)) {
                    addedAppPackageNames.add(entry.getKey().packageName);
                } else if (entry.getKey().lastUpdateTime
                        != previouslyIndexedAppDetails
                                .get(entry.getKey().packageName)
                                .getUpdatedTimestamp()) {
                    updatedAppPackageNames.add(entry.getKey().packageName);
                }
            }

            final int functionLogLimit = 50;
            settings.appendLog(
                    String.format(
                            "Apps Indexer Update [%d]: Cause - Incremental %s,\n"
                                    + "APPS\n"
                                    + "\tAdded - [%s],\n"
                                    + "\tUpdated - [%s],\n"
                                    + "\tDeleted - [%s]\n"
                                    + "FUNCTIONS\n"
                                    + "\tAdded - [%s],\n"
                                    + "\tUpdated - [%s],\n"
                                    + "\tDeleted - [%s]",
                            currentTimeMillis,
                            isFullUpdateRequired ? " (Full)" : "",
                            addedAppPackageNames,
                            updatedAppPackageNames,
                            removedApps,
                            new ArrayList<>(appFunctionDiff.addedAppFunctions.keySet())
                                    .subList(
                                            0,
                                            Math.min(
                                                    functionLogLimit,
                                                    appFunctionDiff
                                                            .addedAppFunctions
                                                            .keySet()
                                                            .size())),
                            new ArrayList<>(appFunctionDiff.updatedAppFunctions.keySet())
                                    .subList(
                                            0,
                                            Math.min(
                                                    functionLogLimit,
                                                    appFunctionDiff
                                                            .updatedAppFunctions
                                                            .keySet()
                                                            .size())),
                            new ArrayList<>(appFunctionDiff.allDeletedFunctionIds)
                                    .subList(
                                            0,
                                            Math.min(
                                                    functionLogLimit,
                                                    appFunctionDiff.allDeletedFunctionIds
                                                            .size()))));
        }
    }

    /**
     * Returns true if the package update time is not equal to stored app update time or if
     * AppFunctionService enabled state is not the same as the stored one.
     *
     * @param packageManager PackageManager instance.
     * @param packageInfo Current information of the package with last update time.
     * @param storedAppUpdateTime The update time stored for the package in appsearch.
     * @param appFunctionServiceResolveInfo Current resolve info for the AppFunctionService in the
     *     package.
     * @param storedIsAppFunctionServiceEnabled Stored enabled state for the AppFunctionService in
     *     apppsearch.
     */
    private static boolean isPackageUpdatedOrChanged(
            PackageManager packageManager,
            @NonNull PackageInfo packageInfo,
            long storedAppUpdateTime,
            @Nullable ResolveInfo appFunctionServiceResolveInfo,
            boolean storedIsAppFunctionServiceEnabled) {
        if (packageInfo.lastUpdateTime != storedAppUpdateTime) {
            return true;
        }

        if (!Flags.enableIndexerRunOnAppFunctionComponentChange()) {
            return false;
        }

        if (appFunctionServiceResolveInfo == null) {
            // appFunctionServiceResolveInfo being null means the service is disabled/does not
            // exist, hence the package status is determined by the stored state.
            return storedIsAppFunctionServiceEnabled;
        }

        boolean isAppFunctionServiceEnabled =
                AppsUtil.isAppFunctionServiceEnabled(packageManager, appFunctionServiceResolveInfo);
        return isAppFunctionServiceEnabled != storedIsAppFunctionServiceEnabled;
    }

    /**
     * Return a pair of lists of {@link PackageIdentifier}s, the first list representing all
     * packages, and the second list representing packages with app functions.
     *
     * <p>The second list is always a subset of the first list.
     *
     * @param packagesToIndex a mapping of {@link PackageInfo}s with their corresponding {@link
     *     ResolveInfos} for the packages launch activity and maybe app function resolve info.
     * @param currentAppFunctionsForAddedUpdatedPackages a mapping of package name to a map of all
     *     app functions for the packages that were either updated or added.
     * @return a pair of lists of {@link PackageIdentifier}s, the first list representing all
     *     packages, and the second list representing packages with app functions.
     */
    private Pair<List<PackageIdentifier>, List<PackageIdentifier>> getPackageIdentifiers(
            @NonNull Map<PackageInfo, ResolveInfos> packagesToIndex,
            Map<String, Map<String, ? extends AppFunctionDocument>>
                    currentAppFunctionsForAddedUpdatedPackages) {
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
            // Check if the package was updated and all app functions were removed. The map only
            // contains entries for packages that updated or newly added, for packages with no
            // change we would rely solely on presence of AppFunctionServiceInfo to decide if it's
            // an app function package.
            boolean appFunctionsRemoved =
                    currentAppFunctionsForAddedUpdatedPackages.containsKey(packageInfo.packageName)
                            && currentAppFunctionsForAddedUpdatedPackages
                                    .get(packageInfo.packageName)
                                    .isEmpty();
            if (entry.getValue().getAppFunctionServiceInfo() != null && !appFunctionsRemoved) {
                packageIdentifiersWithAppFunctions.add(packageIdentifier);
            }
        }
        return new Pair<>(packageIdentifiers, packageIdentifiersWithAppFunctions);
    }

    /** Shuts down the {@link AppsIndexerImpl} and its {@link AppSearchHelper}. */
    @Override
    public void close() {
        mAppSearchHelper.close();
    }
}
