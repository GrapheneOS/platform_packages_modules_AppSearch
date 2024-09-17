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

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

public class AppOpenEventIndexerSettingsTest {

    @Rule public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    private AppOpenEventIndexerSettings mAppOpenEventIndexerSettings;

    @Before
    public void setUp() throws IOException {
        // Create a test folder for each test
        File baseDirectory = mTemporaryFolder.newFolder("testAppOpenEventIndexerSettings");
        mAppOpenEventIndexerSettings = new AppOpenEventIndexerSettings(baseDirectory);
    }

    @Test
    public void testLoadAndPersist() throws IOException {
        // Set some values, persist them, and then load them back
        mAppOpenEventIndexerSettings.setLastUpdateTimestampMillis(123456789L);
        mAppOpenEventIndexerSettings.persist();

        // Reset the settings to ensure loading happens from the file
        mAppOpenEventIndexerSettings.setLastUpdateTimestampMillis(0);

        mAppOpenEventIndexerSettings.load();
        Assert.assertEquals(
                123456789L, mAppOpenEventIndexerSettings.getLastUpdateTimestampMillis());
    }

    @Test
    public void testReset() {
        mAppOpenEventIndexerSettings.setLastUpdateTimestampMillis(123456789L);
        mAppOpenEventIndexerSettings.reset();
        Assert.assertEquals(0, mAppOpenEventIndexerSettings.getLastUpdateTimestampMillis());
    }
}
