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

import static com.android.server.appsearch.appsindexer.TestUtils.createFakePackageInfo;
import static com.android.server.appsearch.appsindexer.TestUtils.createFakeResolveInfo;
import static com.android.server.appsearch.appsindexer.TestUtils.setupMockPackageManager;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.ArrayMap;

import androidx.test.core.app.ApplicationProvider;

import com.android.server.appsearch.appsindexer.appsearchtypes.MobileApplication;

import org.junit.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
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
        Map<PackageInfo, ResolveInfo> packageActivityMapping = new ArrayMap<>();

        for (int i = 0; i < 10; i++) {
            fakePackages.add(createFakePackageInfo(i));
            fakeActivities.add(createFakeResolveInfo(i));
        }

        // Package manager "has" 10 fake packages, but we're choosing just 5 of them to simulate the
        // case that not all the apps need to be synced. For example, 5 new apps were added and the
        // rest of the existing apps don't need to be re-indexed.
        for (int i = 0; i < 5; i++) {
            packageActivityMapping.put(fakePackages.get(i), fakeActivities.get(i));
        }

        setupMockPackageManager(pm, fakePackages, fakeActivities);
        List<MobileApplication> resultApps =
                AppsUtil.buildAppsFromPackageInfos(pm, packageActivityMapping);

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
    public void testBuildRealApps() {
        // This shouldn't crash, and shouldn't be an empty list
        Context context = ApplicationProvider.getApplicationContext();
        Map<PackageInfo, ResolveInfo> packageActivityMapping =
                AppsUtil.getLaunchablePackages(context.getPackageManager());
        List<MobileApplication> resultApps =
                AppsUtil.buildAppsFromPackageInfos(
                        context.getPackageManager(), packageActivityMapping);

        assertThat(resultApps).isNotEmpty();
        assertThat(resultApps.get(0).getDisplayName()).isNotEmpty();
    }
}

