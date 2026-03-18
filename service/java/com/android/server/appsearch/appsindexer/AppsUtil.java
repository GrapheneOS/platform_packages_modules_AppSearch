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

import static com.android.server.appsearch.appsindexer.AppFunctionsIndexerUtil.getAppFunctionAppProperty;
import static com.android.server.appsearch.appsindexer.AppFunctionsIndexerUtil.isAppLevelAppFunctionsEnabled;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.appfunctions.AppFunctionService;
import android.app.appsearch.AppSearchEnvironmentFactory;
import android.app.appsearch.util.LogUtil;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.appsearch.flags.Flags;
import com.android.server.appsearch.appsindexer.appsearchtypes.AppOpenEvent;
import com.android.server.appsearch.appsindexer.appsearchtypes.MobileApplication;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Utility class for pulling apps details from package manager. */
public final class AppsUtil {
    public static final String TAG = "AppSearchAppsUtil";

    @SuppressLint(
            // AppFunctionService.SERVICE_INTERFACE is only available on API 36+. But it's just a
            // string literal so it should be fine to use.
            "NewApi")
    private static final String APP_FUNCTION_SERVICE_INTERFACE =
            AppFunctionService.SERVICE_INTERFACE;

    @SuppressLint(
            // Manifest.permission.BIND_APP_FUNCTION_SERVICE is only available on API 36+. But it's
            // just a string literal so it should be fine to use.
            "NewApi")
    private static final String APP_FUNCTION_SERVICE_PERMISSION_STRING =
            Manifest.permission.BIND_APP_FUNCTION_SERVICE;

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
     * Queries the {@link PackageManager} for app function services.
     *
     * @param packageManager The {@link PackageManager} to query.
     * @return A map where the key is the package name and the value is a list of {@link
     *     ResolveInfo} objects for the app function services in that package.
     */
    @NonNull
    private static Map<String, List<ResolveInfo>> getAppFunctionServiceInfos(
            @NonNull PackageManager packageManager) {
        Map<String, List<ResolveInfo>> packageNameToAppFunctionServiceInfos = new ArrayMap<>();
        Intent appFunctionServiceIntent = new Intent(APP_FUNCTION_SERVICE_INTERFACE);
        List<ResolveInfo> services =
                packageManager.queryIntentServices(appFunctionServiceIntent, /* flags= */ 0);
        for (int i = 0; i < services.size(); i++) {
            ResolveInfo resolveInfo = services.get(i);
            if (resolveInfo.serviceInfo == null) {
                continue;
            }

            if (Flags.enableAppFunctionServicePermissionCheck()
                    && !APP_FUNCTION_SERVICE_PERMISSION_STRING.equals(
                            resolveInfo.serviceInfo.permission)) {
                continue;
            }
            // Only available on API 37+.
            if (isAppLevelAppFunctionsEnabled() && Flags.enableHandlingMultipleAppFunctionXml()) {
                packageNameToAppFunctionServiceInfos
                        .computeIfAbsent(
                                resolveInfo.serviceInfo.packageName, k -> new ArrayList<>())
                        .add(resolveInfo);
            } else {
                // We keep this for backward compatibility with API < 37.
                packageNameToAppFunctionServiceInfos.put(
                        resolveInfo.serviceInfo.packageName,
                        Collections.singletonList(resolveInfo));
            }
        }
        return packageNameToAppFunctionServiceInfos;
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
            @NonNull Context context, @NonNull PackageManager packageManager) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(packageManager);

        List<PackageInfo> packageInfos;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfos =
                    packageManager.getInstalledPackages(
                            PackageManager.GET_META_DATA | PackageManager.GET_SIGNING_CERTIFICATES);
        } else {
            // P- devices do not support GET_SIGNING_CERTIFICATES. Only request GET_META_DATA here.
            // The certificate history will be manually populated later via populateSignatures.
            packageInfos = packageManager.getInstalledPackages(PackageManager.GET_META_DATA);
        }

        Intent launchIntent = new Intent(Intent.ACTION_MAIN, null);
        launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        launchIntent.setPackage(null);
        List<ResolveInfo> activities = packageManager.queryIntentActivities(launchIntent, 0);
        Map<String, ResolveInfo> packageNameToLauncher = new ArrayMap<>();
        for (int i = 0; i < activities.size(); i++) {
            ResolveInfo resolveInfo = activities.get(i);
            String packageName = resolveInfo.activityInfo.packageName;
            if (!packageNameToLauncher.containsKey(packageName)) {
                // Only put if we haven't found one previously
                packageNameToLauncher.put(packageName, resolveInfo);
            }
        }
        Map<String, List<ResolveInfo>> packageNameToAppFunctionServiceInfos =
                getAppFunctionServiceInfos(packageManager);

        Map<PackageInfo, ResolveInfos> packagesToIndex = new ArrayMap<>();
        for (int i = 0; i < packageInfos.size(); i++) {
            PackageInfo packageInfo = packageInfos.get(i);
            ResolveInfos.Builder builder = new ResolveInfos.Builder();

            ResolveInfo launchActivityResolveInfo =
                    packageNameToLauncher.get(packageInfo.packageName);
            if (launchActivityResolveInfo != null) {
                builder.setLaunchActivityResolveInfo(launchActivityResolveInfo);
            }

            List<ResolveInfo> appFunctionServiceInfos =
                    packageNameToAppFunctionServiceInfos.getOrDefault(
                            packageInfo.packageName, Collections.emptyList());

            AppFunctionResolveInfo appFunctionResolveInfo =
                    AppFunctionResolveInfo.create(
                            packageManager, packageInfo.packageName, appFunctionServiceInfos);
            if (appFunctionResolveInfo != null) {
                builder.setAppFunctionResolveInfo(appFunctionResolveInfo);
            }

            boolean shouldIndexPackage =
                    launchActivityResolveInfo != null || appFunctionResolveInfo != null;
            if (shouldIndexPackage) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    // Populate signatures for P- devices.
                    try {
                        AppSearchEnvironmentFactory.getEnvironmentInstance()
                                .populateSignatures(context, packageInfo);
                    } catch (PackageManager.NameNotFoundException e) {
                        // Skip the package if signatures can't be populated.
                        continue;
                    }
                }
                packagesToIndex.put(packageInfo, builder.build());
            }
        }
        return packagesToIndex;
    }

    /**
     * Uses {@link Context} and a Map of {@link PackageInfo}s to {@link ResolveInfos}s to build
     * AppSearch {@link MobileApplication} documents. Info from both are required to build app
     * documents.
     *
     * @param packageInfos a mapping of {@link PackageInfo}s and their corresponding {@link
     *     ResolveInfos} for the packages launch activity.
     */
    @NonNull
    public static List<MobileApplication> buildAppsFromPackageInfos(
            @NonNull Context context,
            @NonNull PackageManager packageManager,
            @NonNull Map<PackageInfo, ResolveInfos> packageInfos) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(packageManager);
        Objects.requireNonNull(packageInfos);

        List<MobileApplication> mobileApplications = new ArrayList<>();
        for (Map.Entry<PackageInfo, ResolveInfos> entry : packageInfos.entrySet()) {
            MobileApplication mobileApplication =
                    createMobileApplication(
                            context, packageManager, entry.getKey(), entry.getValue());
            if (mobileApplication != null) {
                mobileApplications.add(mobileApplication);
            }
        }
        return mobileApplications;
    }

    /**
     * Gets a list of app open events (package name and timestamp) within a specific time range.
     *
     * @param usageStatsManager the {@link UsageStatsManager} to query for app open events.
     * @param startTime the start time in milliseconds since the epoch.
     * @param endTime the end time in milliseconds since the epoch.
     * @return a list of {@link AppOpenEvent} representing the app open events.
     */
    @NonNull
    public static List<AppOpenEvent> getAppOpenEvents(
            @NonNull UsageStatsManager usageStatsManager, long startTime, long endTime) {

        List<AppOpenEvent> appOpenEvents = new ArrayList<>();

        UsageEvents usageEvents = usageStatsManager.queryEvents(startTime, endTime);
        while (usageEvents.hasNextEvent()) {
            UsageEvents.Event event = new UsageEvents.Event();
            usageEvents.getNextEvent(event);

            if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND
                    || event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                String packageName = event.getPackageName();
                long timestamp = event.getTimeStamp();

                AppOpenEvent appOpenEvent = AppOpenEvent.create(packageName, timestamp);
                appOpenEvents.add(appOpenEvent);
            }
        }

        return appOpenEvents;
    }

    /** Gets the SHA-256 certificate from a {@link PackageManager}, or null if it is not found */
    @Nullable
    public static byte[] getCertificate(@NonNull PackageInfo packageInfo) {
        Objects.requireNonNull(packageInfo);

        Signature[] signatures = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (packageInfo.signingInfo == null) {
                if (LogUtil.DEBUG) {
                    Log.d(TAG, "Signing info not found for package: " + packageInfo.packageName);
                }
                return null;
            }
            signatures = packageInfo.signingInfo.getSigningCertificateHistory();
        } else {
            // Use the legacy signatures field for P- devices.
            signatures = packageInfo.signatures;
        }

        if (signatures == null || signatures.length == 0) {
            return null;
        }

        try {
            MessageDigest md = MessageDigest.getInstance("SHA256");
            md.update(signatures[0].toByteArray());
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
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
            @NonNull Context context,
            @NonNull PackageManager packageManager,
            @NonNull PackageInfo packageInfo,
            @NonNull ResolveInfos resolveInfos) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(packageManager);
        Objects.requireNonNull(packageInfo);
        Objects.requireNonNull(resolveInfos);

        byte[] certificate = getCertificate(packageInfo);
        if (certificate == null) {
            return null;
        }

        MobileApplication.Builder builder =
                new MobileApplication.Builder(packageInfo.packageName, certificate)
                        .setCreationTimestampMillis(packageInfo.firstInstallTime)
                        .setUpdatedTimestampMs(packageInfo.lastUpdateTime);

        AppFunctionResolveInfo appFunctionResolveInfo = resolveInfos.getAppFunctionResolveInfo();
        if (appFunctionResolveInfo != null
                && !appFunctionResolveInfo.getAppFunctionServiceResolveInfos().isEmpty()) {
            builder.setIsAppFunctionServiceEnabled(
                    isAppFunctionServiceEnabled(
                            packageManager,
                            appFunctionResolveInfo.getAppFunctionServiceResolveInfos().get(0)));
        }

        ResolveInfo launchActivityResolveInfo = resolveInfos.getLaunchActivityResolveInfo();
        if (launchActivityResolveInfo == null) {
            return builder.build();
        }
        String applicationDisplayName =
                launchActivityResolveInfo.loadLabel(packageManager).toString();
        if (TextUtils.isEmpty(applicationDisplayName) && packageInfo.applicationInfo != null) {
            applicationDisplayName = packageInfo.applicationInfo.className;
        }
        builder.setDisplayName(applicationDisplayName);
        String iconUri =
                getActivityIconUriString(packageManager, launchActivityResolveInfo.activityInfo);
        if (iconUri != null) {
            builder.setIconUri(iconUri);
        }

        // Use a Set to automatically handle duplicate alternate names.
        final ArraySet<String> alternateNames = new ArraySet<>();

        // Add multilingual names if the corresponding flag is enabled.
        if (Flags.enableAppsIndexerMultilingualNames()) {
            ArraySet<String> multiLingualNames =
                    getMultilingualNames(
                            context,
                            packageInfo.packageName,
                            launchActivityResolveInfo.activityInfo.applicationInfo.labelRes);
            if (multiLingualNames != null) {
                alternateNames.addAll(multiLingualNames);
            }
        }

        // Add labels from the ResolveInfos.
        // A package may have multiple ResolveInfos, and these can have different labels. All of
        // these labels should be added to alternate names if they are different from the
        // display name to better support matching.

        Intent launchIntent = new Intent(Intent.ACTION_MAIN, null);
        launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        launchIntent.setPackage(packageInfo.packageName);
        List<ResolveInfo> activities = packageManager.queryIntentActivities(launchIntent, 0);
        for (int i = 0; i < activities.size(); i++) {
            ResolveInfo resolveInfo = activities.get(i);
            String alternateLabel = resolveInfo.loadLabel(packageManager).toString();
            alternateNames.add(alternateLabel);
        }

        // Always add the application label for the default locale as a fallback. This can be
        // different from the display name derived from the first ResolveInfo.
        String applicationLabel =
                packageManager.getApplicationLabel(packageInfo.applicationInfo).toString();
        alternateNames.add(applicationLabel);

        // Remove the primary display name itself, since it's not an "alternate" name.
        alternateNames.remove(applicationDisplayName);

        if (!alternateNames.isEmpty()) {
            builder.setAlternateNames(alternateNames.toArray(new String[0]));
        }

        if (launchActivityResolveInfo.activityInfo.name != null) {
            builder.setClassName(launchActivityResolveInfo.activityInfo.name);
        }
        return builder.build();
    }

    /** Returns an set of alternate names from all available locales for an app. */
    @NonNull
    private static ArraySet<String> getMultilingualNames(
            @NonNull Context context, @NonNull String packageName, int labelResId) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(packageName);
        ArraySet<String> foundLabels = new ArraySet<>();
        if (labelResId == 0) {
            return foundLabels;
        }

        try {
            // Create a Context for the target package so we can get labels in various locales
            Context appContext;
            try {
                appContext = context.createPackageContext(packageName, Context.CONTEXT_RESTRICTED);
            } catch (PackageManager.NameNotFoundException e) {
                Log.w(TAG, "Failed to create package context for " + packageName, e);
                return foundLabels;
            }

            String[] locales = appContext.getAssets().getLocales();
            if (locales == null || locales.length == 0) {
                return foundLabels;
            }

            Configuration baseAppConfig = appContext.getResources().getConfiguration();

            for (String localeTag : locales) {
                if (localeTag == null || localeTag.isEmpty()) {
                    continue;
                }
                try {
                    Locale locale = Locale.forLanguageTag(localeTag.replace('_', '-'));
                    Configuration localizedConfig = new Configuration(baseAppConfig);
                    localizedConfig.setLocale(locale);

                    // Create a context with the new configuration
                    Context localizedContext =
                            appContext.createConfigurationContext(localizedConfig);

                    // Get Resources from the localized Context
                    Resources localizedResources = localizedContext.getResources();

                    // Get the string
                    String translatedLabel = localizedResources.getString(labelResId);
                    if (!TextUtils.isEmpty(translatedLabel)) {
                        foundLabels.add(translatedLabel);
                    }
                } catch (Resources.NotFoundException e) {
                    // This locale might not have a translation for this specific string
                    Log.w(TAG, "Resource not found for locale " + localeTag + " in " + packageName);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "An unexpected error getting multilingual names occurred.", e);
        }
        return foundLabels;
    }

    /**
     * Returns the current enabled state of AppFunctionService component specified by
     * appFunctionServiceResolveInfo.
     */
    public static boolean isAppFunctionServiceEnabled(
            @NonNull PackageManager packageManager,
            @NonNull ResolveInfo appFunctionServiceResolveInfo) {
        int currentAppFunctionServiceState =
                packageManager.getComponentEnabledSetting(
                        new ComponentName(
                                appFunctionServiceResolveInfo.serviceInfo.packageName,
                                appFunctionServiceResolveInfo.serviceInfo.name));

        boolean isAppFunctionServiceEnabled =
                currentAppFunctionServiceState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                        ? appFunctionServiceResolveInfo.serviceInfo.isEnabled()
                        : currentAppFunctionServiceState
                                == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        return isAppFunctionServiceEnabled;
    }
}
