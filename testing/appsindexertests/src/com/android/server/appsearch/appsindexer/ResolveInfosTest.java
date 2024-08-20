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
import android.content.pm.ResolveInfo;

import org.junit.Test;

public class ResolveInfosTest {
    @Test
    public void testBuilder() {
        ResolveInfo appFunctionResolveInfo = new ResolveInfo();
        appFunctionResolveInfo.activityInfo = new ActivityInfo();
        appFunctionResolveInfo.activityInfo.packageName = "package1";
        appFunctionResolveInfo.activityInfo.name = "activity1";

        ResolveInfo launchActivityResolveInfo = new ResolveInfo();
        launchActivityResolveInfo.activityInfo = new ActivityInfo();
        launchActivityResolveInfo.activityInfo.packageName = "package1";
        launchActivityResolveInfo.activityInfo.name = "activity2";

        ResolveInfos resolveInfos =
                new ResolveInfos.Builder()
                        .setAppFunctionServiceResolveInfo(appFunctionResolveInfo)
                        .setLaunchActivityResolveInfo(launchActivityResolveInfo)
                        .build();

        assertThat(resolveInfos.getAppFunctionServiceInfo()).isEqualTo(appFunctionResolveInfo);
        assertThat(resolveInfos.getLaunchActivityResolveInfo())
                .isEqualTo(launchActivityResolveInfo);
    }
}
