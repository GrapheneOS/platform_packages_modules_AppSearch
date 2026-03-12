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

import static com.google.common.truth.Truth.assertThat;

import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.Collections;
import org.junit.Test;

public class ResolveInfosTest {
    @Test
    public void testBuilder() {
        ResolveInfo appFunctionServiceResolveInfo = new ResolveInfo();
        appFunctionServiceResolveInfo.activityInfo = new ActivityInfo();
        appFunctionServiceResolveInfo.activityInfo.packageName = "package1";
        appFunctionServiceResolveInfo.activityInfo.name = "activity1";

        AppFunctionResolveInfo appFunctionResolveInfo =
                new AppFunctionResolveInfo(
                        "package1",
                        Collections.singletonList(appFunctionServiceResolveInfo),
                        /* appFunctionAppLevelXmlProperty= */ null);

        ResolveInfo launchActivityResolveInfo = new ResolveInfo();
        launchActivityResolveInfo.activityInfo = new ActivityInfo();
        launchActivityResolveInfo.activityInfo.packageName = "package1";
        launchActivityResolveInfo.activityInfo.name = "activity2";

        ResolveInfos resolveInfos =
                new ResolveInfos.Builder()
                        .setAppFunctionResolveInfo(appFunctionResolveInfo)
                        .setLaunchActivityResolveInfo(launchActivityResolveInfo)
                        .build();

        assertThat(resolveInfos.getAppFunctionResolveInfo()).isEqualTo(appFunctionResolveInfo);
        assertThat(resolveInfos.getLaunchActivityResolveInfo())
                .isEqualTo(launchActivityResolveInfo);
    }

    @Test
    public void testBuilder_withProperty() {
        ResolveInfo appFunctionServiceResolveInfo = new ResolveInfo();
        appFunctionServiceResolveInfo.activityInfo = new ActivityInfo();
        appFunctionServiceResolveInfo.activityInfo.packageName = "package1";
        appFunctionServiceResolveInfo.activityInfo.name = "activity1";

        PackageManager.Property property =
                new PackageManager.Property("name", "value", "package1", "Class1");

        AppFunctionResolveInfo appFunctionResolveInfo =
                new AppFunctionResolveInfo(
                        "package1",
                        Collections.singletonList(appFunctionServiceResolveInfo),
                        property);

        ResolveInfo launchActivityResolveInfo = new ResolveInfo();
        launchActivityResolveInfo.activityInfo = new ActivityInfo();
        launchActivityResolveInfo.activityInfo.packageName = "package1";
        launchActivityResolveInfo.activityInfo.name = "activity2";

        ResolveInfos resolveInfos =
                new ResolveInfos.Builder()
                        .setAppFunctionResolveInfo(appFunctionResolveInfo)
                        .setLaunchActivityResolveInfo(launchActivityResolveInfo)
                        .build();

        assertThat(resolveInfos.getAppFunctionResolveInfo()).isEqualTo(appFunctionResolveInfo);
        assertThat(resolveInfos.getLaunchActivityResolveInfo())
                .isEqualTo(launchActivityResolveInfo);
    }
}
