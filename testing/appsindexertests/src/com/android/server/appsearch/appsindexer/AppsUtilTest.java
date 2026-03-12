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
import static com.android.server.appsearch.appsindexer.TestUtils.createFakePackageInfo;
import static com.android.server.appsearch.appsindexer.TestUtils.createIndividualUsageEvent;
import static com.android.server.appsearch.appsindexer.TestUtils.createUsageEvents;
import static com.android.server.appsearch.appsindexer.TestUtils.setupMockPackageManager;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.annotation.SuppressLint;
import android.Manifest;
import android.app.appsearch.testutil.AppSearchTestUtils;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.util.ArrayMap;

import androidx.test.core.app.ApplicationProvider;

import com.android.appsearch.flags.Flags;
import com.android.server.appsearch.appsindexer.appsearchtypes.AppOpenEvent;
import com.android.server.appsearch.appsindexer.appsearchtypes.MobileApplication;

import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

/** This tests that we can convert what comes from PackageManager to a MobileApplication */
public class AppsUtilTest {
    @Rule public final RuleChain mRuleChain = AppSearchTestUtils.createCommonTestRules();
    private final PackageManager mMockPackageManager = mock(PackageManager.class);
    private final Context mContext = ApplicationProvider.getApplicationContext();

    private PackageInfo mPackageInfo;
    private ResolveInfo mLaunchResolveInfo;
    private ResolveInfo mAppFunctionServiceResolveInfo;
    private Map<PackageInfo, ResolveInfos> mPackageMapping;

    @Before
    public void setUp() throws Exception {
        mPackageInfo = createFakePackageInfo(0);
        mLaunchResolveInfo = createFakeLaunchResolveInfo(0);
        mAppFunctionServiceResolveInfo = createFakeAppFunctionResolveInfo(0);
        mPackageMapping = new ArrayMap<>();

        // Common mocking for icon uri and application label to avoid NPEs during build
        Resources res = Mockito.mock(Resources.class);
        when(res.getResourcePackageName(anyInt())).thenReturn("package");
        when(res.getResourceTypeName(anyInt())).thenReturn("type");
        when(mMockPackageManager.getResourcesForApplication((ApplicationInfo) any()))
                .thenReturn(res);
        when(mMockPackageManager.getApplicationLabel(any(ApplicationInfo.class)))
                .thenReturn("Fake App");
    }

    @Test
    public void testBuildAppsFromPackageInfos_ReturnsNonNullList() throws Exception {
        PackageManager pm = Mockito.mock(PackageManager.class);
        // Populate fake PackageManager with 10 Packages.
        List<PackageInfo> fakePackages = new ArrayList<>();
        List<ResolveInfo> fakeActivities = new ArrayList<>();
        Map<PackageInfo, ResolveInfos> packageLaunchActivityMapping = new ArrayMap<>();

        for (int i = 0; i < 10; i++) {
            fakePackages.add(createFakePackageInfo(i));
            fakeActivities.add(createFakeLaunchResolveInfo(i));
        }

        // Package manager "has" 10 fake packages, but we're choosing just 5 of them to simulate the
        // case that not all the apps need to be synced. For example, 5 new apps were added and the
        // rest of the existing apps don't need to be re-indexed.
        for (int i = 0; i < 5; i++) {
            packageLaunchActivityMapping.put(
                    fakePackages.get(i),
                    new ResolveInfos(
                            /* launchActivityResolveInfo= */ fakeActivities.get(i),
                            /* appFunctionResolveInfo= */ null));
        }

        setupMockPackageManager(
                pm, fakePackages, fakeActivities, /* appFunctionServices= */ ImmutableList.of());
        List<MobileApplication> resultApps =
                AppsUtil.buildAppsFromPackageInfos(mContext, pm, packageLaunchActivityMapping);

        assertThat(resultApps).hasSize(5);
        List<String> packageNames = new ArrayList<>();
        for (int i = 0; i < resultApps.size(); i++) {
            packageNames.add(resultApps.get(i).getPackageName());
        }
        assertThat(packageNames)
                .containsExactly(
                        "com.fake.package0",
                        "com.fake.package1",
                        "com.fake.package2",
                        "com.fake.package3",
                        "com.fake.package4");
    }

    @Test
    public void testBuildRealApps_returnsNonEmptyList() {
        // This shouldn't crash, and shouldn't be an empty list
        Context context = ApplicationProvider.getApplicationContext();
        Map<PackageInfo, ResolveInfos> packageActivityMapping =
                AppsUtil.getPackagesToIndex(context, context.getPackageManager());
        List<MobileApplication> resultApps =
                AppsUtil.buildAppsFromPackageInfos(
                        context, context.getPackageManager(), packageActivityMapping);

        assertThat(resultApps).isNotEmpty();
    }

    // TODO(b/361879099): Add a test that checks that building apps from real PackageManager info
    // results in non-empty documents

    @Test
    public void testRealUsageStatsManager() {
        UsageStatsManager mockUsageStatsManager = Mockito.mock(UsageStatsManager.class);

        UsageEvents.Event[] events =
                new UsageEvents.Event[] {
                    createIndividualUsageEvent(
                            UsageEvents.Event.MOVE_TO_FOREGROUND, 1000L, "com.example.package"),
                    createIndividualUsageEvent(
                            UsageEvents.Event.ACTIVITY_RESUMED, 2000L, "com.example.package"),
                    createIndividualUsageEvent(
                            UsageEvents.Event.MOVE_TO_FOREGROUND, 3000L, "com.example.package2"),
                    createIndividualUsageEvent(
                            UsageEvents.Event.MOVE_TO_BACKGROUND, 4000L, "com.example.package2")
                };

        UsageEvents mockUsageEvents = createUsageEvents(events);
        when(mockUsageStatsManager.queryEvents(anyLong(), anyLong())).thenReturn(mockUsageEvents);

        List<AppOpenEvent> appOpenTimestamps =
                AppsUtil.getAppOpenEvents(
                        mockUsageStatsManager, 0, Calendar.getInstance().getTimeInMillis());

        assertThat(appOpenTimestamps).hasSize(3);
        assertThat(appOpenTimestamps.stream().map(AppOpenEvent::getId))
                .containsExactly(
                        "com.example.package1000",
                        "com.example.package2000",
                        "com.example.package23000");
    }

    @Test
    public void testRetrieveAppFunctionResolveInfo() throws Exception {
        // Set up fake PackageManager with 10 Packages and 10 AppFunctions
        PackageManager pm = Mockito.mock(PackageManager.class);
        Context mockContext =
                new ContextWrapper(ApplicationProvider.getApplicationContext()) {
                    @Override
                    public PackageManager getPackageManager() {
                        return pm;
                    }
                };
        List<PackageInfo> fakePackages = new ArrayList<>();
        List<ResolveInfo> fakeActivities = new ArrayList<>();
        List<ResolveInfo> fakeAppFunctionServices = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            fakePackages.add(createFakePackageInfo(i));
            fakeActivities.add(createFakeLaunchResolveInfo(i));
            fakeAppFunctionServices.add(createFakeAppFunctionResolveInfo(i));
        }

        setupMockPackageManager(pm, fakePackages, fakeActivities, fakeAppFunctionServices);

        Map<PackageInfo, ResolveInfos> packageActivityMapping =
                AppsUtil.getPackagesToIndex(mockContext, pm);

        // Make assertions
        assertThat(packageActivityMapping).hasSize(10);
        for (PackageInfo packageInfo : packageActivityMapping.keySet()) {
            assertThat(packageInfo.packageName).startsWith("com.fake.package");
        }
        assertThat(packageActivityMapping.values()).hasSize(10);
        for (ResolveInfos targetedResolveInfo : packageActivityMapping.values()) {
            assertThat(targetedResolveInfo.getLaunchActivityResolveInfo().activityInfo.packageName)
                    .isEqualTo(
                            targetedResolveInfo
                                    .getAppFunctionResolveInfo()
                                    .getAppFunctionServiceResolveInfos()
                                    .get(0)
                                    .serviceInfo
                                    .packageName);
            assertThat(
                            targetedResolveInfo
                                    .getAppFunctionResolveInfo()
                                    .getAppFunctionServiceResolveInfos()
                                    .get(0)
                                    .serviceInfo
                                    .packageName)
                    .isEqualTo(
                            targetedResolveInfo.getLaunchActivityResolveInfo()
                                    .activityInfo
                                    .packageName);
        }
    }

    @Test
    public void testMultipleResolveInfosForApp() throws Exception {
        PackageManager pm = Mockito.mock(PackageManager.class);
        List<PackageInfo> fakePackages = new ArrayList<>();
        List<ResolveInfo> fakeActivities = new ArrayList<>();

        // Define values to be used in the test explicitly
        final String primaryActivitySuffix = ".PrimaryActivity";
        final String primaryAppName = "Primary Application Name";
        final int primaryIcon = 123;
        final String alternateAppName = "Alternate Application Name";
        final int alternateIcon = 456;

        for (int i = 0; i < 10; i++) {
            PackageInfo packageInfo = createFakePackageInfo(i);
            fakePackages.add(packageInfo);
            String packageName = packageInfo.packageName;

            // Create the first ResolveInfo (will be treated as primary)
            ResolveInfo fakeResolveInfo1 = createFakeLaunchResolveInfo(i);
            // Explicitly set the fields this test will assert against.
            fakeResolveInfo1.activityInfo.name = packageName + primaryActivitySuffix;
            fakeResolveInfo1.activityInfo.applicationInfo.name = primaryAppName;
            fakeResolveInfo1.activityInfo.applicationInfo.nonLocalizedLabel = primaryAppName;
            fakeResolveInfo1.activityInfo.icon = primaryIcon;
            fakeActivities.add(fakeResolveInfo1);

            // Create the second ResolveInfo (will contribute to alternate names)
            ResolveInfo fakeResolveInfo2 = createFakeLaunchResolveInfo(i);
            fakeResolveInfo2.activityInfo.name = packageName + ".SomeOtherActivity";
            fakeResolveInfo2.activityInfo.applicationInfo.name = alternateAppName;
            fakeResolveInfo2.activityInfo.applicationInfo.nonLocalizedLabel = alternateAppName;
            fakeResolveInfo2.activityInfo.icon = alternateIcon;
            fakeActivities.add(fakeResolveInfo2);
        }

        setupMockPackageManager(
                pm, fakePackages, fakeActivities, /* appFunctionServices= */ ImmutableList.of());
        Map<PackageInfo, ResolveInfos> packageLaunchActivityMapping =
                AppsUtil.getPackagesToIndex(mContext, pm);
        List<MobileApplication> resultApps =
                AppsUtil.buildAppsFromPackageInfos(mContext, pm, packageLaunchActivityMapping);

        // We just indexed 10 apps with 2 resolve infos each. In the first resolve info for each,
        // the name is "Fake Application Name". In the second resolve info for each, we set the name
        // to "Legacy Application Name".
        // With the fix, the displayName should be "Fake Application Name" while "Legacy Application
        // Name" is added to alternateNames
        assertThat(resultApps).hasSize(10);
        for (int i = 0; i < resultApps.size(); i++) {
            MobileApplication app = resultApps.get(i);
            String expectedPackageName = "com.fake.package";

            assertThat(app.getPackageName()).startsWith(expectedPackageName);
            // Assertions based on values explicitly set on fakeResolveInfo1
            assertThat(app.getClassName()).endsWith(primaryActivitySuffix);
            assertThat(app.getDisplayName()).isEqualTo(primaryAppName);
            assertThat(app.getIconUri().toString()).endsWith(String.valueOf(primaryIcon));
            // Assertion based on values explicitly set on fakeResolveInfo2
            assertThat(app.getAlternateNames()).asList().contains(alternateAppName);
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_APPS_INDEXER_MULTILINGUAL_NAMES)
    public void testBuildApps_multilingual_populatesAlternateNames() throws Exception {
        String packageName = TestUtils.FAKE_PACKAGE_PREFIX + "0";
        String defaultName = "App Name";
        String frenchName = "Nom de l'application";
        String spanishName = "Nombre de la aplicación";
        int appLabelResId = 0x5678;
        int activityLabelResId = 0x1234;

        // Mock ApplicationInfo & PackageInfo
        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.packageName = packageName;
        appInfo.labelRes = appLabelResId;

        PackageInfo packageInfo = TestUtils.createFakePackageInfo(0);
        packageInfo.applicationInfo = appInfo;

        // ResolveInfo for DisplayName
        ResolveInfo resolveInfo = TestUtils.createFakeLaunchResolveInfo(0);
        resolveInfo.activityInfo.applicationInfo = appInfo;
        resolveInfo.labelRes = activityLabelResId;

        // Mock PackageManager calls used by resolveInfo.loadLabel() and getApplicationLabel()
        when(mMockPackageManager.getText(
                        eq(packageName), eq(activityLabelResId), any(ApplicationInfo.class)))
                .thenReturn(defaultName);
        when(mMockPackageManager.getApplicationLabel(any(ApplicationInfo.class)))
                .thenReturn(defaultName);

        Map<PackageInfo, ResolveInfos> packageMapping = new ArrayMap<>();
        packageMapping.put(
                packageInfo,
                new ResolveInfos(
                        /* launchActivityResolveInfo= */ resolveInfo,
                        /* appFunctionResolveInfo= */ null));

        // Mocking for icon uri related paths
        Resources res = Mockito.mock(Resources.class);
        when(res.getResourcePackageName(anyInt())).thenReturn("package");
        when(res.getResourceTypeName(anyInt())).thenReturn("type");
        when(mMockPackageManager.getResourcesForApplication((ApplicationInfo) any()))
                .thenReturn(res);

        // Ensure the spyContext returns the mock PackageManager
        when(mMockPackageManager.getApplicationInfo(packageName, 0)).thenReturn(appInfo);

        Context mockAppContext = Mockito.mock(Context.class);

        Context multiLangContext =
                new ContextWrapper(mContext) {
                    @Override
                    public Context createPackageContext(String packageName, int flags) {
                        return mockAppContext;
                    }
                };

        // Mock mockAppContext.getAssets()
        AssetManager mockAssetManager = Mockito.mock(AssetManager.class);
        when(mockAppContext.getAssets()).thenReturn(mockAssetManager);
        when(mockAssetManager.getLocales()).thenReturn(new String[] {"en-US", "fr-FR", "es"});

        // Mock mockAppContext.getResources() to return a base Configuration
        Resources mockBaseResources = Mockito.mock(Resources.class);
        Configuration baseConfig = new Configuration();
        when(mockAppContext.getResources()).thenReturn(mockBaseResources);
        when(mockBaseResources.getConfiguration()).thenReturn(baseConfig);

        Context mockFrenchContext = Mockito.mock(Context.class);
        Resources mockFrenchResources = Mockito.mock(Resources.class);
        when(mockFrenchContext.getResources()).thenReturn(mockFrenchResources);
        when(mockFrenchResources.getString(anyInt())).thenReturn(frenchName);

        Context mockSpanishContext = Mockito.mock(Context.class);
        Resources mockSpanishResources = Mockito.mock(Resources.class);
        when(mockSpanishContext.getResources()).thenReturn(mockSpanishResources);
        when(mockSpanishResources.getString(anyInt())).thenReturn(spanishName);

        Context mockEnglishContext = Mockito.mock(Context.class);
        Resources mockEnglishResources = Mockito.mock(Resources.class);
        when(mockEnglishContext.getResources()).thenReturn(mockEnglishResources);
        when(mockEnglishResources.getString(anyInt())).thenReturn(defaultName);

        when(mockAppContext.createConfigurationContext(any(Configuration.class)))
                .thenReturn(mockFrenchContext, mockSpanishContext, mockEnglishContext);

        List<MobileApplication> resultApps =
                AppsUtil.buildAppsFromPackageInfos(
                        multiLangContext, mMockPackageManager, packageMapping);

        assertThat(resultApps).hasSize(1);
        MobileApplication app = resultApps.get(0);
        assertThat(app.getDisplayName()).isEqualTo(defaultName);
        assertThat(app.getAlternateNames()).asList().containsExactly(frenchName, spanishName);
    }

    @Test
    public void testBuildApps_appFunctionServiceComponentEnabled_setsEnabledStatusTrue()
            throws Exception {
        ResolveInfos resolveInfos =
                new ResolveInfos(
                        mLaunchResolveInfo,
                        new AppFunctionResolveInfo(
                                mPackageInfo.packageName,
                                ImmutableList.of(mAppFunctionServiceResolveInfo),
                                /* appFunctionAppLevelXmlProperty= */ null));
        mPackageMapping.put(mPackageInfo, resolveInfos);
        when(mMockPackageManager.getComponentEnabledSetting(any(ComponentName.class)))
                .thenReturn(PackageManager.COMPONENT_ENABLED_STATE_ENABLED);

        List<MobileApplication> resultApps =
                AppsUtil.buildAppsFromPackageInfos(mContext, mMockPackageManager, mPackageMapping);

        assertThat(resultApps).hasSize(1);
        assertThat(resultApps.get(0).isAppFunctionServiceEnabled()).isTrue();
    }

    @Test
    public void testBuildApps_appFunctionServiceComponentDisabled_setsEnabledStatusFalse()
            throws Exception {
        ResolveInfos resolveInfos =
                new ResolveInfos(
                        mLaunchResolveInfo,
                        new AppFunctionResolveInfo(
                                mPackageInfo.packageName,
                                ImmutableList.of(mAppFunctionServiceResolveInfo),
                                /* appFunctionAppLevelXmlProperty= */ null));
        mPackageMapping.put(mPackageInfo, resolveInfos);
        when(mMockPackageManager.getComponentEnabledSetting(any(ComponentName.class)))
                .thenReturn(PackageManager.COMPONENT_ENABLED_STATE_DISABLED);

        List<MobileApplication> resultApps =
                AppsUtil.buildAppsFromPackageInfos(mContext, mMockPackageManager, mPackageMapping);

        assertThat(resultApps).hasSize(1);
        assertThat(resultApps.get(0).isAppFunctionServiceEnabled()).isFalse();
    }

    @Test
    public void testBuildApps_appFunctionServiceDefaultAndManifestEnabled_setsEnabledStatusTrue()
            throws Exception {
        mAppFunctionServiceResolveInfo.serviceInfo.enabled = true;
        ResolveInfos resolveInfos =
                new ResolveInfos(
                        mLaunchResolveInfo,
                        new AppFunctionResolveInfo(
                                mPackageInfo.packageName,
                                ImmutableList.of(mAppFunctionServiceResolveInfo),
                                /* appFunctionAppLevelXmlProperty= */ null));
        mPackageMapping.put(mPackageInfo, resolveInfos);
        when(mMockPackageManager.getComponentEnabledSetting(any(ComponentName.class)))
                .thenReturn(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);

        List<MobileApplication> resultApps =
                AppsUtil.buildAppsFromPackageInfos(mContext, mMockPackageManager, mPackageMapping);

        assertThat(resultApps).hasSize(1);
        assertThat(resultApps.get(0).isAppFunctionServiceEnabled()).isTrue();
    }

    @Test
    public void testBuildApps_appFunctionServiceDefaultAndManifestDisabled_setsEnabledStatusFalse()
            throws Exception {
        mAppFunctionServiceResolveInfo.serviceInfo.enabled = false;
        ResolveInfos resolveInfos =
                new ResolveInfos(
                        mLaunchResolveInfo,
                        new AppFunctionResolveInfo(
                                mPackageInfo.packageName,
                                ImmutableList.of(mAppFunctionServiceResolveInfo),
                                /* appFunctionAppLevelXmlProperty= */ null));
        mPackageMapping.put(mPackageInfo, resolveInfos);
        when(mMockPackageManager.getComponentEnabledSetting(any(ComponentName.class)))
                .thenReturn(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);

        List<MobileApplication> resultApps =
                AppsUtil.buildAppsFromPackageInfos(mContext, mMockPackageManager, mPackageMapping);

        assertThat(resultApps).hasSize(1);
        assertThat(resultApps.get(0).isAppFunctionServiceEnabled()).isFalse();
    }

    @Test
    public void testBuildApps_noAppFunctionService_setsEnabledStatusFalse() throws Exception {
        ResolveInfos noAppFunctionResolveInfos =
                new ResolveInfos(
                        mLaunchResolveInfo,
                        new AppFunctionResolveInfo(
                                mPackageInfo.packageName,
                                ImmutableList.of(),
                                /* appFunctionAppLevelXmlProperty= */ null));
        mPackageMapping.put(mPackageInfo, noAppFunctionResolveInfos);

        List<MobileApplication> resultApps =
                AppsUtil.buildAppsFromPackageInfos(mContext, mMockPackageManager, mPackageMapping);

        assertThat(resultApps).hasSize(1);
        // The builder's default for a boolean is false, which is the expected value.
        assertThat(resultApps.get(0).isAppFunctionServiceEnabled()).isFalse();
    }

    @Test
    @SuppressLint(
            // Manifest.permission.BIND_APP_FUNCTION_SERVICE is only available on API 36+. But it's
            // just a string literal so it should be fine to use.
            "NewApi")
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_APP_FUNCTION_SERVICE_PERMISSION_CHECK)
    public void testGetPackagesToIndex_withPermissionCheck_correctPermission() throws Exception {
        PackageManager pm = Mockito.mock(PackageManager.class);
        Context mockContext =
                new ContextWrapper(ApplicationProvider.getApplicationContext()) {
                    @Override
                    public PackageManager getPackageManager() {
                        return pm;
                    }
                };
        List<PackageInfo> fakePackages = new ArrayList<>();
        List<ResolveInfo> fakeActivities = new ArrayList<>();
        List<ResolveInfo> fakeAppFunctionServices = new ArrayList<>();
        // Package with correct permission
        PackageInfo packageWithPermission = createFakePackageInfo(0);
        fakePackages.add(packageWithPermission);
        fakeActivities.add(createFakeLaunchResolveInfo(0));
        ResolveInfo serviceWithPermission = createFakeAppFunctionResolveInfo(0);
        serviceWithPermission.serviceInfo.permission =
                Manifest.permission.BIND_APP_FUNCTION_SERVICE;
        fakeAppFunctionServices.add(serviceWithPermission);
        setupMockPackageManager(pm, fakePackages, fakeActivities, fakeAppFunctionServices);

        Map<PackageInfo, ResolveInfos> packageActivityMapping =
                AppsUtil.getPackagesToIndex(mockContext, pm);

        assertThat(packageActivityMapping).hasSize(1);
        ResolveInfos resolveInfosWithPermission = packageActivityMapping.get(packageWithPermission);
        assertThat(resolveInfosWithPermission.getAppFunctionResolveInfo()).isNotNull();
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_APP_FUNCTION_SERVICE_PERMISSION_CHECK)
    public void testGetPackagesToIndex_withPermissionCheck_wrongPermission() throws Exception {
        PackageManager pm = Mockito.mock(PackageManager.class);
        Context mockContext =
                new ContextWrapper(ApplicationProvider.getApplicationContext()) {
                    @Override
                    public PackageManager getPackageManager() {
                        return pm;
                    }
                };
        List<PackageInfo> fakePackages = new ArrayList<>();
        List<ResolveInfo> fakeActivities = new ArrayList<>();
        List<ResolveInfo> fakeAppFunctionServices = new ArrayList<>();
        // Package with wrong permission
        PackageInfo packageWithoutPermission = createFakePackageInfo(0);
        fakePackages.add(packageWithoutPermission);
        fakeActivities.add(createFakeLaunchResolveInfo(0));
        ResolveInfo serviceWithoutPermission = createFakeAppFunctionResolveInfo(0);
        serviceWithoutPermission.serviceInfo.permission = "some.other.permission";
        fakeAppFunctionServices.add(serviceWithoutPermission);
        setupMockPackageManager(pm, fakePackages, fakeActivities, fakeAppFunctionServices);

        Map<PackageInfo, ResolveInfos> packageActivityMapping =
                AppsUtil.getPackagesToIndex(mockContext, pm);

        assertThat(packageActivityMapping).hasSize(1);
        ResolveInfos resolveInfosWithoutPermission =
                packageActivityMapping.get(packageWithoutPermission);
        // Service with wrong permission IS filtered.
        assertThat(resolveInfosWithoutPermission.getAppFunctionResolveInfo()).isNull();
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_APP_FUNCTION_SERVICE_PERMISSION_CHECK)
    public void testGetPackagesToIndex_withPermissionCheck_nullPermission() throws Exception {
        PackageManager pm = Mockito.mock(PackageManager.class);
        Context mockContext =
                new ContextWrapper(ApplicationProvider.getApplicationContext()) {
                    @Override
                    public PackageManager getPackageManager() {
                        return pm;
                    }
                };
        List<PackageInfo> fakePackages = new ArrayList<>();
        List<ResolveInfo> fakeActivities = new ArrayList<>();
        List<ResolveInfo> fakeAppFunctionServices = new ArrayList<>();
        // Package with null permission
        PackageInfo packageWithNullPermission = createFakePackageInfo(0);
        fakePackages.add(packageWithNullPermission);
        fakeActivities.add(createFakeLaunchResolveInfo(0));
        ResolveInfo serviceWithNullPermission = createFakeAppFunctionResolveInfo(0);
        serviceWithNullPermission.serviceInfo.permission = null;
        fakeAppFunctionServices.add(serviceWithNullPermission);
        setupMockPackageManager(pm, fakePackages, fakeActivities, fakeAppFunctionServices);

        Map<PackageInfo, ResolveInfos> packageActivityMapping =
                AppsUtil.getPackagesToIndex(mockContext, pm);

        assertThat(packageActivityMapping).hasSize(1);
        ResolveInfos resolveInfosWithNullPermission =
                packageActivityMapping.get(packageWithNullPermission);
        // Service with null permission IS filtered.
        assertThat(resolveInfosWithNullPermission.getAppFunctionResolveInfo()).isNull();
    }
}
