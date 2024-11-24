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

package com.android.server.appsearch.appsindexer.appsearchtypes;

import static com.google.common.truth.Truth.assertThat;

import android.net.Uri;

import org.junit.Test;

public class AppsIndexerSchemaTests {
    @Test
    public void testMobileApplication() {
        String packageName = "com.android.apps.food";
        String className = "com.android.foodapp.SearchActivity";
        String displayName = "The Food App";
        String iconUri = "https://www.android.com/images/branding/product/1x/appg_24dp.png";
        String[] alternateNames = {"Food", "Eat"};
        long updatedTimestamp = System.currentTimeMillis();
        byte[] sha256Certificate = new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};

        MobileApplication mobileApplication =
                new MobileApplication.Builder(packageName, sha256Certificate)
                        .setClassName(className)
                        .setDisplayName(displayName)
                        .setIconUri(iconUri)
                        .setAlternateNames(alternateNames)
                        .setUpdatedTimestampMs(updatedTimestamp)
                        .build();

        assertThat(mobileApplication.getPackageName()).isEqualTo(packageName);
        assertThat(mobileApplication.getClassName()).isEqualTo(className);
        assertThat(mobileApplication.getDisplayName()).isEqualTo(displayName);
        assertThat(mobileApplication.getIconUri()).isEqualTo(Uri.parse(iconUri));
        assertThat(mobileApplication.getAlternateNames()).isEqualTo(alternateNames);
        assertThat(mobileApplication.getSha256Certificate()).isEqualTo(sha256Certificate);
        assertThat(mobileApplication.getUpdatedTimestamp()).isEqualTo(updatedTimestamp);
    }

    @Test
    public void testAppOpenEvent() {
        String packageName = "com.android.apps.food";
        String mobileApplicationQualifiedId = "android$apps-db/apps#com.android.apps.food";
        long appOpenEventTimestampMillis = System.currentTimeMillis();

        AppOpenEvent appOpenEvent =
                AppOpenEvent.create(packageName, appOpenEventTimestampMillis);

        assertThat(appOpenEvent.getPackageName()).isEqualTo(packageName);
        assertThat(appOpenEvent.getMobileApplicationQualifiedId())
                .isEqualTo(mobileApplicationQualifiedId);
        assertThat(appOpenEvent.getAppOpenEventTimestampMillis())
                .isEqualTo(appOpenEventTimestampMillis);
    }
}
