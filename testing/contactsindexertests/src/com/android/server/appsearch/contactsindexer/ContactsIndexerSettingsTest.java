/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.server.appsearch.contactsindexer;

import static com.google.common.truth.Truth.assertThat;

import com.android.server.appsearch.indexer.SettingsStore;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

public abstract class ContactsIndexerSettingsTest {

    @Rule public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    private ContactsIndexerSettings mContactsIndexerSettings;
    private SettingsStore mSettingsStore;

    protected abstract SettingsStore createSettingsStore(File baseDirectory);

    @Before
    public void setUp() throws IOException {
        // Create a test folder for each test
        File baseDirectory = mTemporaryFolder.newFolder("testContactsIndexerSettings");
        mContactsIndexerSettings = new ContactsIndexerSettings();
        mSettingsStore = createSettingsStore(baseDirectory);
    }

    @Test
    public void testLoadAndPersist() throws IOException {
        // Set some values.
        mContactsIndexerSettings.setLastUpdateTimestampMillis(11111L);
        mContactsIndexerSettings.setLastAttemptedUpdateTimestampMillis(22222L);
        mContactsIndexerSettings.setIndexerForceUpdateEmergencyCounter(3);
        mContactsIndexerSettings.setLastFullUpdateTimestampMillis(44444L);
        mContactsIndexerSettings.setLastDeltaUpdateTimestampMillis(55555L);
        mContactsIndexerSettings.setLastContactUpdateTimestampMillis(66666L);
        mContactsIndexerSettings.setLastContactDeleteTimestampMillis(77777L);

        // Persist settings.
        mSettingsStore.persist(mContactsIndexerSettings);

        // Load settings.
        mSettingsStore.loadInto(mContactsIndexerSettings);

        // Reset the settings to ensure loading happens from the file
        mContactsIndexerSettings.setIndexerForceUpdateEmergencyCounter(0);

        // Load settings again.
        mSettingsStore.loadInto(mContactsIndexerSettings);

        // Loaded settings should match persisted settings.
        assertThat(mContactsIndexerSettings.getLastUpdateTimestampMillis()).isEqualTo(11111L);
        assertThat(mContactsIndexerSettings.getLastAttemptedUpdateTimestampMillis())
                .isEqualTo(22222L);
        assertThat(mContactsIndexerSettings.getIndexerForceUpdateEmergencyCounter()).isEqualTo(3);
        assertThat(mContactsIndexerSettings.getLastFullUpdateTimestampMillis()).isEqualTo(44444L);
        assertThat(mContactsIndexerSettings.getLastDeltaUpdateTimestampMillis()).isEqualTo(55555L);
        assertThat(mContactsIndexerSettings.getLastContactUpdateTimestampMillis())
                .isEqualTo(66666L);
        assertThat(mContactsIndexerSettings.getLastContactDeleteTimestampMillis())
                .isEqualTo(77777L);
    }
}
