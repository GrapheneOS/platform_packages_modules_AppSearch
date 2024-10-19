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
import android.app.appsearch.util.LogUtil;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.content.res.Resources;
import android.net.Uri;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.appsearch.appsindexer.appsearchtypes.AppFunctionStaticMetadata;
import com.android.server.appsearch.appsindexer.appsearchtypes.AppOpenEvent;
import com.android.server.appsearch.appsindexer.appsearchtypes.MobileApplication;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Utility class for pulling apps details from package manager. */
public final class AppsUtil {
    public static final String TAG = "AppSearchAppsUtil";

    // App Open events are user's activity, which is both privacy and recency sensitive. 14 days was
    // chosen as a reasonable duration to maintain this type of user activity.
    private static final long APP_OPEN_EVENT_TTL_MILLIS = 1000 * 60 * 60 * 24 * 14; // 14 days

    private AppsUtil() {}

    /** Gets the resource Uri given a resource id. */
    @NonNull
    private static Uri getResourceUri(
            @NonNull PackageManager packageManager,
            @NonNull ApplicationInfo appInfo,
            int resourceId)
            throws PackageManager.NameNotFoundException {
        Objects.requireNonNull(packageManager);
        Objects.requireNonNull(appInfo);
        Resources resources = packageManager.getResourcesForApplication(appInfo);
        String resPkg = resources.getResourcePackageName(resourceId);
        String type = resources.getResourceTypeName(resourceId);
        return makeResourceUri(appInfo.packageName, resPkg, type, resourceId);
    }

    /**
     * Appends the resource id instead of name to make the resource uri due to b/161564466. The
     * resource names for some apps (e.g. Chrome) are obfuscated due to resource name collapsing, so
     * we need to use resource id instead.
     *
     * @see Uri
     */
    @NonNull
    private static Uri makeResourceUri(
            @NonNull String appPkg, @NonNull String resPkg, @NonNull String type, int resourceId) {
        Objects.requireNonNull(appPkg);
        Objects.requireNonNull(resPkg);
        Objects.requireNonNull(type);

        // For more details on Android URIs, see the official Android documentation:
        // https://developer.android.com/guide/topics/providers/content-provider-basics#ContentURIs
        Uri.Builder uriBuilder = new Uri.Builder();
        uriBuilder.scheme(ContentResolver.SCHEME_ANDROID_RESOURCE);
        uriBuilder.encodedAuthority(appPkg);
        uriBuilder.appendEncodedPath(type);
        if (!appPkg.equals(resPkg)) {
            uriBuilder.appendEncodedPath(resPkg + ":" + resourceId);
        } else {
            uriBuilder.appendEncodedPath(String.valueOf(resourceId));
        }
        return uriBuilder.build();
    }

    /**
     * Gets the icon uri for the activity.
     *
     * @return the icon Uri string, or null if there is no icon resource.
     */
    @Nullable
    private static String getActivityIconUriString(
            @NonNull PackageManager packageManager, @NonNull ActivityInfo activityInfo) {
        Objects.requireNonNull(packageManager);
        Objects.requireNonNull(activityInfo);
        int iconResourceId = activityInfo.getIconResource();
        if (iconResourceId == 0) {
            return null;
        }

        try {
            return getResourceUri(packageManager, activityInfo.applicationInfo, iconResourceId)
                    .toString();
        } catch (PackageManager.NameNotFoundException e) {
            // If resources aren't found for the application, that is fine. We return null and
            // handle it with getActivityIconUriString
            return null;
        }
    }

    /**
     * Gets {@link PackageInfo}s for packages that have a launch activity or has app functions,
     * along with their corresponding {@link ResolveInfo}. This is useful for building schemas as
     * well as determining which packages to set schemas for.
     *
     * @return a mapping of {@link PackageInfo}s with their corresponding {@link ResolveInfos} for
     *     the packages launch activity and maybe app function resolve info.
     * @see PackageManager#getInstalledPackages
     * @see PackageManager#queryIntentActivities
     * @see PackageManager#queryIntentServices
     */
    @NonNull
    public static Map<PackageInfo, ResolveInfos> getPackagesToIndex(
            @NonNull PackageManager packageManager) {
        Objects.requireNonNull(packageManager);
        List<PackageInfo> packageInfos =
                packageManager.getInstalledPackages(
                        PackageManager.GET_META_DATA | PackageManager.GET_SIGNING_CERTIFICATES);

        Intent launchIntent = new Intent(Intent.ACTION_MAIN, null);
        launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        launchIntent.setPackage(null);
        List<ResolveInfo> activities = packageManager.queryIntentActivities(launchIntent, 0);
        Map<String, ResolveInfo> packageNameToLauncher = new ArrayMap<>();
        for (int i = 0; i < activities.size(); i++) {
            ResolveInfo resolveInfo = activities.get(i);
            packageNameToLauncher.put(resolveInfo.activityInfo.packageName, resolveInfo);
        }

        // This is to workaround the android lint check.
        // AppFunctionService.SERVICE_INTERFACE is defined in API 36 but also it is just a string
        // literal.
        Intent appFunctionServiceIntent = new Intent("android.app.appfunctions.AppFunctionService");
        Map<String, ResolveInfo> packageNameToAppFunctionServiceInfo = new ArrayMap<>();
        List<ResolveInfo> services =
                packageManager.queryIntentServices(appFunctionServiceIntent, 0);
        for (int i = 0; i < services.size(); i++) {
            ResolveInfo resolveInfo = services.get(i);
            packageNameToAppFunctionServiceInfo.put(
                    resolveInfo.serviceInfo.packageName, resolveInfo);
        }

        Map<PackageInfo, ResolveInfos> packagesToIndex = new ArrayMap<>();
        for (int i = 0; i < packageInfos.size(); i++) {
            PackageInfo packageInfo = packageInfos.get(i);
            ResolveInfos.Builder builder = new ResolveInfos.Builder();

            ResolveInfo launchActivityResolveInfo =
                    packageNameToLauncher.get(packageInfo.packageName);
            if (launchActivityResolveInfo != null) {
                builder.setLaunchActivityResolveInfo(launchActivityResolveInfo);
            }

            ResolveInfo appFunctionServiceInfo =
                    packageNameToAppFunctionServiceInfo.get(packageInfo.packageName);
            if (appFunctionServiceInfo != null) {
                builder.setAppFunctionServiceResolveInfo(appFunctionServiceInfo);
            }

            if (launchActivityResolveInfo != null || appFunctionServiceInfo != null) {
                packagesToIndex.put(packageInfo, builder.build());
            }
        }
        return packagesToIndex;
    }

    /**
     * Uses {@link PackageManager} and a Map of {@link PackageInfo}s to {@link ResolveInfos}s to
     * build AppSearch {@link MobileApplication} documents. Info from both are required to build app
     * documents.
     *
     * @param packageInfos a mapping of {@link PackageInfo}s and their corresponding {@link
     *     ResolveInfos} for the packages launch activity.
     */
    @NonNull
    public static List<MobileApplication> buildAppsFromPackageInfos(
            @NonNull PackageManager packageManager,
            @NonNull Map<PackageInfo, ResolveInfos> packageInfos) {
        Objects.requireNonNull(packageManager);
        Objects.requireNonNull(packageInfos);

        List<MobileApplication> mobileApplications = new ArrayList<>();
        for (Map.Entry<PackageInfo, ResolveInfos> entry : packageInfos.entrySet()) {
            ResolveInfo resolveInfo = entry.getValue().getLaunchActivityResolveInfo();

            MobileApplication mobileApplication =
                    createMobileApplication(packageManager, entry.getKey(), resolveInfo);
            if (mobileApplication != null) {
                mobileApplications.add(mobileApplication);
            }
        }
        return mobileApplications;
    }

    /**
     * Uses {@link PackageManager} and a Map of {@link PackageInfo}s to {@link ResolveInfos}s to
     * build AppSearch {@link AppFunctionStaticMetadata} documents. Info from both are required to
     * build app documents.
     *
     * @param packageInfos a mapping of {@link PackageInfo}s and their corresponding {@link
     *     ResolveInfo} for the packages launch activity.
     * @param indexerPackageName the name of the package performing the indexing. This should be the
     *     same as the package running the apps indexer so that qualified ids are correctly created.
     * @param maxAppFunctions the max number of app functions to be indexed per package.
     */
    public static List<AppFunctionStaticMetadata> buildAppFunctionStaticMetadata(
            @NonNull PackageManager packageManager,
            @NonNull Map<PackageInfo, ResolveInfos> packageInfos,
            @NonNull String indexerPackageName,
            int maxAppFunctions) {
        AppFunctionStaticMetadataParser parser =
                new AppFunctionStaticMetadataParserImpl(indexerPackageName, maxAppFunctions);
        return buildAppFunctionStaticMetadata(packageManager, packageInfos, parser);
    }

    /**
     * Similar to the above {@link #buildAppFunctionStaticMetadata}, but allows the caller to
     * provide a custom parser. This is for testing purposes.
     */
    @VisibleForTesting
    static List<AppFunctionStaticMetadata> buildAppFunctionStaticMetadata(
            @NonNull PackageManager packageManager,
            @NonNull Map<PackageInfo, ResolveInfos> packageInfos,
            @NonNull AppFunctionStaticMetadataParser parser) {
        Objects.requireNonNull(packageManager);
        Objects.requireNonNull(packageInfos);
        Objects.requireNonNull(parser);

        List<AppFunctionStaticMetadata> appFunctions = new ArrayList<>();
        for (Map.Entry<PackageInfo, ResolveInfos> entry : packageInfos.entrySet()) {
            PackageInfo packageInfo = entry.getKey();
            ResolveInfo resolveInfo = entry.getValue().getAppFunctionServiceInfo();
            if (resolveInfo == null) {
                continue;
            }

            String assetFilePath;
            try {
                PackageManager.Property property =
                        packageManager.getProperty(
                                "android.app.appfunctions",
                                new ComponentName(
                                        resolveInfo.serviceInfo.packageName,
                                        resolveInfo.serviceInfo.name));
                assetFilePath = property.getString();
            } catch (PackageManager.NameNotFoundException e) {
                Log.w(TAG, "buildAppFunctionMetadataFromPackageInfo: Failed to get property", e);
                continue;
            }
            if (assetFilePath != null) {
                appFunctions.addAll(
                        parser.parse(packageManager, packageInfo.packageName, assetFilePath));
            }
        }
        return appFunctions;
    }

    /**
     * Gets a map of package name to a list of app open timestamps within a specific time range.
     *
     * @param usageStatsManager the {@link UsageStatsManager} to query for app open events.
     * @param startTime the start time in milliseconds since the epoch.
     * @param endTime the end time in milliseconds since the epoch.
     * @return a map of package name to a list of app open timestamps.
     */
    @NonNull
    public static Map<String, List<Long>> getAppOpenTimestamps(
            @NonNull UsageStatsManager usageStatsManager, long startTime, long endTime) {

        Map<String, List<Long>> appOpenTimestamps = new ArrayMap<>();

        UsageEvents usageEvents = usageStatsManager.queryEvents(startTime, endTime);
        while (usageEvents.hasNextEvent()) {
            UsageEvents.Event event = new UsageEvents.Event();
            usageEvents.getNextEvent(event);

            if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND
                    || event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                String packageName = event.getPackageName();

                List<Long> timestamps = appOpenTimestamps.get(packageName);
                if (timestamps == null) {
                    timestamps = new ArrayList<>();
                    appOpenTimestamps.put(packageName, timestamps);
                }
                timestamps.add(event.getTimeStamp());
            }
        }

        return appOpenTimestamps;
    }

    /**
     * Converts a map of package name to a list of app open timestamps to a list of {@link
     * AppOpenEvent} documents. It's a less compact representation of the data, but it's directly
     * writeable to AppSearch.
     *
     * @param appOpenEvents a map of package name to a list of app open timestamps.
     * @return a list of {@link AppOpenEvent} documents.
     */
    public static List<AppOpenEvent> convertMapToAppOpenEvents(
            @NonNull Map<String, List<Long>> appOpenEvents) {
        Objects.requireNonNull(appOpenEvents);
        List<AppOpenEvent> documents = new ArrayList<>();

        for (Map.Entry<String, List<Long>> entry : appOpenEvents.entrySet()) {
            String packageName = entry.getKey();
            List<Long> eventTimes = entry.getValue();

            for (int i = 0; i < eventTimes.size(); i++) {
                Long eventTimeObj = eventTimes.get(i);
                if (eventTimeObj != null) {
                    long eventTimeMillis = eventTimeObj;
                    AppOpenEvent event =
                            new AppOpenEvent.Builder(packageName, eventTimeMillis)
                                    .setCreationTimestampMillis(eventTimeMillis)
                                    .setTtlMillis(APP_OPEN_EVENT_TTL_MILLIS)
                                    .build();
                    documents.add(event);
                } else {
                    Log.w(
                            TAG,
                            "convertMapToAppOpenEvents: eventTimeObj is unexpectedly null.  This"
                                    + " should never happen.");
                }
            }
        }

        return documents;
    }

    /**
     * Converts a list of {@link AppOpenEvent} documents into a map of package names to their
     * corresponding app open timestamps. This provides a more compact representation of the data,
     * potentially useful for further processing.
     *
     * @param appOpenEvents a list of {@link AppOpenEvent} documents.
     * @return a map of package names to lists of their app open timestamps
     */
    public static Map<String, List<Long>> convertAppOpenEventsToMap(
            @NonNull List<AppOpenEvent> appOpenEvents) {
        Objects.requireNonNull(appOpenEvents);
        Map<String, List<Long>> appOpenEventsMap = new ArrayMap<>();

        for (int i = 0; i < appOpenEvents.size(); i++) {
            AppOpenEvent event = appOpenEvents.get(i);
            String packageName = event.getPackageName();
            long timestamp = event.getAppOpenEventTimestampMillis();

            List<Long> timestamps = appOpenEventsMap.get(packageName);
            if (timestamps == null) {
                timestamps = new ArrayList<>();
                appOpenEventsMap.put(packageName, timestamps);
            }
            timestamps.add(timestamp);
        }

        return appOpenEventsMap;
    }

    /** Gets the SHA-256 certificate from a {@link PackageManager}, or null if it is not found */
    @Nullable
    public static byte[] getCertificate(@NonNull PackageInfo packageInfo) {
        Objects.requireNonNull(packageInfo);
        if (packageInfo.signingInfo == null) {
            if (LogUtil.DEBUG) {
                Log.d(TAG, "Signing info not found for package: " + packageInfo.packageName);
            }
            return null;
        }
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA256");
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
        Signature[] signatures = packageInfo.signingInfo.getSigningCertificateHistory();
        if (signatures == null || signatures.length == 0) {
            return null;
        }
        md.update(signatures[0].toByteArray());
        return md.digest();
    }

    /**
     * Uses PackageManager to supplement packageInfos with an application display name and icon uri,
     * if any.
     *
     * @return a MobileApplication representing the packageInfo, null if finding the signing
     *     certificate fails.
     */
    @Nullable
    private static MobileApplication createMobileApplication(
            @NonNull PackageManager packageManager,
            @NonNull PackageInfo packageInfo,
            @Nullable ResolveInfo resolveInfo) {
        Objects.requireNonNull(packageManager);
        Objects.requireNonNull(packageInfo);

        byte[] certificate = getCertificate(packageInfo);
        if (certificate == null) {
            return null;
        }

        MobileApplication.Builder builder =
                new MobileApplication.Builder(packageInfo.packageName, certificate)
                        // TODO(b/275592563): Populate with nicknames from various sources
                        .setCreationTimestampMillis(packageInfo.firstInstallTime)
                        .setUpdatedTimestampMs(packageInfo.lastUpdateTime);

        if (resolveInfo == null) {
            return builder.build();
        }
        String applicationDisplayName = resolveInfo.loadLabel(packageManager).toString();
        if (TextUtils.isEmpty(applicationDisplayName)) {
            applicationDisplayName = packageInfo.applicationInfo.className;
        }
        builder.setDisplayName(applicationDisplayName);
        String iconUri = getActivityIconUriString(packageManager, resolveInfo.activityInfo);
        if (iconUri != null) {
            builder.setIconUri(iconUri);
        }
        String applicationLabel =
                packageManager.getApplicationLabel(packageInfo.applicationInfo).toString();
        if (!applicationDisplayName.equals(applicationLabel)) {
            // This can be different from applicationDisplayName, and should be indexed
            builder.setAlternateNames(applicationLabel);
        }
        if (resolveInfo.activityInfo.name != null) {
            builder.setClassName(resolveInfo.activityInfo.name);
        }
        return builder.build();
    }
}
