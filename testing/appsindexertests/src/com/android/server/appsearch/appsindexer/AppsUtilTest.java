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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.util.ArrayMap;

import androidx.test.core.app.ApplicationProvider;

import com.android.server.appsearch.appsindexer.appsearchtypes.AppFunctionStaticMetadata;
import com.android.server.appsearch.appsindexer.appsearchtypes.MobileApplication;

import com.google.common.collect.ImmutableList;

import org.junit.Test;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

/** This tests that we can convert what comes from PackageManager to a MobileApplication */
public class AppsUtilTest {

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
                    fakePackages.get(i), new ResolveInfos(null, fakeActivities.get(i)));
        }

        setupMockPackageManager(
                pm, fakePackages, fakeActivities, /* appFunctionServices= */ ImmutableList.of());
        List<MobileApplication> resultApps =
                AppsUtil.buildAppsFromPackageInfos(pm, packageLaunchActivityMapping);

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
                AppsUtil.getPackagesToIndex(context.getPackageManager());
        List<MobileApplication> resultApps =
                AppsUtil.buildAppsFromPackageInfos(
                        context.getPackageManager(), packageActivityMapping);

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

        Map<String, List<Long>> appOpenTimestamps =
                AppsUtil.getAppOpenTimestamps(
                        mockUsageStatsManager, 0, Calendar.getInstance().getTimeInMillis());

        assertThat(appOpenTimestamps)
                .containsExactly(
                        "com.example.package", List.of(1000L, 2000L),
                        "com.example.package2", List.of(3000L));
    }

    @Test
    public void testRetrieveAppFunctionResolveInfo() throws Exception {
        // Set up fake PackageManager with 10 Packages and 10 AppFunctions
        PackageManager pm = Mockito.mock(PackageManager.class);
        List<PackageInfo> fakePackages = new ArrayList<>();
        List<ResolveInfo> fakeActivities = new ArrayList<>();
        List<ResolveInfo> fakeAppFunctionServices = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            fakePackages.add(createFakePackageInfo(i));
            fakeActivities.add(createFakeLaunchResolveInfo(i));
            fakeAppFunctionServices.add(createFakeAppFunctionResolveInfo(i));
        }

        setupMockPackageManager(pm, fakePackages, fakeActivities, fakeAppFunctionServices);

        Map<PackageInfo, ResolveInfos> packageActivityMapping = AppsUtil.getPackagesToIndex(pm);

        // Make assertions
        assertThat(packageActivityMapping).hasSize(10);
        for (PackageInfo packageInfo : packageActivityMapping.keySet()) {
            assertThat(packageInfo.packageName).startsWith("com.fake.package");
        }
        assertThat(packageActivityMapping.values()).hasSize(10);
        for (ResolveInfos targetedResolveInfo : packageActivityMapping.values()) {
            assertThat(targetedResolveInfo.getLaunchActivityResolveInfo().activityInfo.packageName)
                    .isEqualTo(
                            targetedResolveInfo.getAppFunctionServiceInfo()
                                    .serviceInfo
                                    .packageName);
            assertThat(targetedResolveInfo.getAppFunctionServiceInfo().serviceInfo.packageName)
                    .isEqualTo(
                            targetedResolveInfo.getLaunchActivityResolveInfo()
                                    .activityInfo
                                    .packageName);
        }
    }

    @Test
    public void testBuildAppFunctionStaticMetadata() throws Exception {
        PackageManager pm = Mockito.mock(PackageManager.class);
        List<PackageInfo> fakePackages = new ArrayList<>();
        List<ResolveInfo> fakeActivities = new ArrayList<>();
        List<ResolveInfo> fakeAppFunctionServices = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            fakePackages.add(createFakePackageInfo(i));
            fakeActivities.add(createFakeLaunchResolveInfo(i));
            fakeAppFunctionServices.add(createFakeAppFunctionResolveInfo(i));
        }

        // Set up mocking
        when(pm.getProperty(any(String.class), any(ComponentName.class)))
                .thenReturn(new PackageManager.Property("", "", "", ""));
        AssetManager assetManager = Mockito.mock(AssetManager.class);

        when(assetManager.open(any())).thenReturn(new ByteArrayInputStream("".getBytes()));

        Resources resources = Mockito.mock(Resources.class);
        when(resources.getAssets()).thenReturn(assetManager);
        when(pm.getResourcesForApplication(any(String.class))).thenReturn(resources);

        setupMockPackageManager(pm, fakePackages, fakeActivities, fakeAppFunctionServices);

        AppFunctionStaticMetadataParser parser =
                Mockito.mock(AppFunctionStaticMetadataParser.class);
        for (PackageInfo packageInfo : fakePackages) {
            when(parser.parse(any(), eq(packageInfo.packageName), any()))
                    .thenReturn(
                            ImmutableList.of(
                                    new AppFunctionStaticMetadata.Builder(
                                                    packageInfo.packageName,
                                                    /* functionId= */ "com.example.utils#print",
                                                    /* indexerPackageName= */ "android")
                                            .build()));
        }

        Map<PackageInfo, ResolveInfos> packageActivityMapping = AppsUtil.getPackagesToIndex(pm);

        List<AppFunctionStaticMetadata> resultAppFunctions =
                AppsUtil.buildAppFunctionStaticMetadata(pm, packageActivityMapping, parser);

        assertThat(resultAppFunctions).hasSize(10);
        for (AppFunctionStaticMetadata appFunction : resultAppFunctions) {
            assertThat(appFunction.getFunctionId()).isEqualTo("com.example.utils#print");
        }
    }
}
