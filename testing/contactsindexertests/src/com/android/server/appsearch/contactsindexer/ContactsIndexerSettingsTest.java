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

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

public class ContactsIndexerSettingsTest {

    @Rule public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    private ContactsIndexerSettings mContactsIndexerSettings;

    @Before
    public void setUp() throws IOException {
        // Create a test folder for each test
        File baseDirectory = mTemporaryFolder.newFolder("testContactsIndexerSettings");
        mContactsIndexerSettings = new ContactsIndexerSettings(baseDirectory);
    }

    @Test
    public void testLoadAndPersist() throws IOException {
        // Set some values, persist them, and then load them back
        mContactsIndexerSettings.setIndexerForceUpdateEmergencyCounterKey(1);
        mContactsIndexerSettings.persist();

        // Reset the settings to ensure loading happens from the file
        mContactsIndexerSettings.setIndexerForceUpdateEmergencyCounterKey(0);

        mContactsIndexerSettings.load();
        assertThat(mContactsIndexerSettings.getIndexerForceUpdateEmergencyCounter()).isEqualTo(1);
    }
}
