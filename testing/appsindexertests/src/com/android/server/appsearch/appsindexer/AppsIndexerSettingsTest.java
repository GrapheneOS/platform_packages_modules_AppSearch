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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.os.Build;

import com.android.server.appsearch.indexer.SettingsStore;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public abstract class AppsIndexerSettingsTest {

    @Rule public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    private AppsIndexerSettings mIndexerSettings;
    private SettingsStore mSettingsStore;
    private List<Build.Partition> mMockPartitions;

    protected abstract SettingsStore createSettingsStore(File baseDirectory);

    @Before
    public void setUp() throws IOException {
        // Create a test folder for each test
        File baseDirectory = mTemporaryFolder.newFolder("testAppsIndexerSettings");
        mIndexerSettings = new AppsIndexerSettings();
        mSettingsStore = createSettingsStore(baseDirectory);

        // Mock partition fingerprints.
        Build.Partition partition1 = mock(Build.Partition.class);
        Build.Partition partition2 = mock(Build.Partition.class);
        when(partition1.getFingerprint()).thenReturn("fingerprint123");
        when(partition2.getFingerprint()).thenReturn("fingerprint456");
        mMockPartitions = Arrays.asList(partition1, partition2);
    }

    @Test
    public void testLoadAndPersist() throws IOException {
        // Set some values, persist them, and then load them back
        mIndexerSettings.setLastUpdateTimestampMillis(123456789L);
        mIndexerSettings.setLastAttemptedUpdateTimestampMillis(567891234L);
        mIndexerSettings.setIndexerForceUpdateEmergencyCounter(3);
        mIndexerSettings.setLastAppUpdateTimestampMillis(987654321L);
        mIndexerSettings.setPreviousIndexerVersionCode(5);
        mIndexerSettings.setLastPartitionFingerprintsSortedByPartitionName(mMockPartitions);
        mIndexerSettings.setPreviousLocaleCode("en-US");
        mIndexerSettings.appendLog("log123");
        mIndexerSettings.appendLog("log456");

        // Persist to file
        mSettingsStore.persist(mIndexerSettings);

        // Reset the settings to ensure loading happens from the file
        mIndexerSettings.setLastUpdateTimestampMillis(0);
        mIndexerSettings.setLastAppUpdateTimestampMillis(0);
        mIndexerSettings.setLastAttemptedUpdateTimestampMillis(0);

        // Load from file
        mSettingsStore.loadInto(mIndexerSettings);

        // Check values after loading
        Assert.assertEquals(123456789L, mIndexerSettings.getLastUpdateTimestampMillis());
        Assert.assertEquals(567891234L, mIndexerSettings.getLastAttemptedUpdateTimestampMillis());
        Assert.assertEquals(3, mIndexerSettings.getIndexerForceUpdateEmergencyCounter());
        Assert.assertEquals(987654321L, mIndexerSettings.getLastAppUpdateTimestampMillis());
        Assert.assertEquals(5, mIndexerSettings.getPreviousIndexerVersionCode());
        Assert.assertArrayEquals(
                new String[] {"fingerprint123", "fingerprint456"},
                mIndexerSettings.getLastPartitionFingerprints());
        Assert.assertEquals("en-US", mIndexerSettings.getPreviousLocaleCode());
        Collection<String> logs = mIndexerSettings.getLogLines();
        Assert.assertEquals(2, logs.size());
        Assert.assertEquals(Arrays.asList("log123", "log456"), new java.util.ArrayList<>(logs));
    }

    @Test
    public void testReset() {
        mIndexerSettings.setLastUpdateTimestampMillis(123456789L);
        mIndexerSettings.setLastAttemptedUpdateTimestampMillis(567891234L);
        mIndexerSettings.setIndexerForceUpdateEmergencyCounter(3);
        mIndexerSettings.setLastAppUpdateTimestampMillis(987654321L);
        mIndexerSettings.setPreviousIndexerVersionCode(5);
        mIndexerSettings.setLastPartitionFingerprintsSortedByPartitionName(mMockPartitions);
        mIndexerSettings.setPreviousLocaleCode("en-US");
        mIndexerSettings.appendLog("log123");

        mIndexerSettings.reset();

        Assert.assertEquals(0, mIndexerSettings.getLastUpdateTimestampMillis());
        Assert.assertEquals(0, mIndexerSettings.getLastAttemptedUpdateTimestampMillis());
        Assert.assertEquals(0, mIndexerSettings.getIndexerForceUpdateEmergencyCounter());
        Assert.assertEquals(0, mIndexerSettings.getLastAppUpdateTimestampMillis());
        Assert.assertEquals(
                AppIndexerVersions.APP_INDEXER_VERSION_UNKNOWN,
                mIndexerSettings.getPreviousIndexerVersionCode());
        Assert.assertNull(mIndexerSettings.getLastPartitionFingerprints());
        Assert.assertEquals("", mIndexerSettings.getPreviousLocaleCode());
        Assert.assertTrue(mIndexerSettings.getLogLines().isEmpty());
    }
}
