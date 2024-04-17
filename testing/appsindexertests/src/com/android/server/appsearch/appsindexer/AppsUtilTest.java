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
import android.widget.ImageView;

import androidx.test.core.app.ApplicationProvider;

import com.android.server.appsearch.appsindexer.appsearchtypes.MobileApplication;

import org.junit.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;


/** This tests that we can convert what comes from PackageManager to a MobileApplication */
public class AppsUtilTest {
    @Test
    public void testBuildAppsFromPackageInfos_ReturnsNonNullList() throws Exception {
        PackageManager pm = Mockito.mock(PackageManager.class);
        // Populate fake PackageManager with 10 Packages.
        List<PackageInfo> fakePackages = new ArrayList<>();
        List<ResolveInfo> fakeActivities = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            fakePackages.add(createFakePackageInfo(i));
            fakeActivities.add(createFakeResolveInfo(i));
        }

        setupMockPackageManager(pm, fakePackages, fakeActivities);
        List<MobileApplication> resultApps =
                AppsUtil.buildAppsFromPackageInfos(pm, fakePackages.subList(0, 5));

        // Package manager "has" 10 fake packages, but we're choosing just 5 of them to simulate the
        // case that not all the apps need to be synced. For example, 5 new apps were added and the
        // rest of the existing apps don't need to be re-indexed.
        assertThat(resultApps).hasSize(5);
        assertThat(resultApps.get(0).getPackageName()).isEqualTo("com.fake.package0");
        assertThat(resultApps.get(1).getPackageName()).isEqualTo("com.fake.package1");
        assertThat(resultApps.get(2).getPackageName()).isEqualTo("com.fake.package2");
        assertThat(resultApps.get(3).getPackageName()).isEqualTo("com.fake.package3");
        assertThat(resultApps.get(4).getPackageName()).isEqualTo("com.fake.package4");
    }

    @Test
    public void testBuildRealApps() {
        // This shouldn't crash, and shouldn't be an empty list
        Context context = ApplicationProvider.getApplicationContext();
        List<PackageInfo> pkgs = context.getPackageManager().getInstalledPackages(
                PackageManager.GET_SIGNING_CERTIFICATES | PackageManager.GET_META_DATA);
        List<MobileApplication> resultApps =
                AppsUtil.buildAppsFromPackageInfos(context.getPackageManager(), pkgs);

        ImageView imageView = new ImageView(context);
        imageView.setImageURI(resultApps.get(0).getIconUri());
        assertThat(imageView.getDrawable().getIntrinsicWidth()).isGreaterThan(0);
        assertThat(imageView.getDrawable().getIntrinsicHeight()).isGreaterThan(0);
    }
}
