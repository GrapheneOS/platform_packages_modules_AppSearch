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
// @exportToGMSCore:skipFile()
package com.android.server.appsearch.indexer;

import android.annotation.NonNull;

import com.android.server.LocalManagerRegistry;
import com.android.server.appsearch.appsindexer.FrameworkAppOpenEventIndexerMaintenanceConfig;
import com.android.server.appsearch.appsindexer.FrameworkAppsIndexerMaintenanceConfig;
import com.android.server.appsearch.contactsindexer.FrameworkContactsIndexerMaintenanceConfig;
import com.android.server.appsearch.indexer.IndexerJobHandler.IndexerType;

/** Contains Framework-specific information needed to dispatch a maintenance job for an indexer. */
public interface FrameworkIndexerMaintenanceConfig {

    // Platform-specific minimum job ids.
    int MIN_CONTACTS_INDEXER_JOB_ID = 16942831; // corresponds to ag/16942831

    int MIN_APPS_INDEXER_JOB_ID = 16964307; // Contacts Indexer Max Job Id + 1

    int MIN_APP_OPEN_EVENT_INDEXER_JOB_ID = 16985783; // Apps Indexer Max Job Id + 1

    /** Returns the {@link IndexerMaintenanceConfig} for the requested indexer type. */
    @NonNull
    static FrameworkIndexerMaintenanceConfig getConfigForIndexer(@IndexerType int indexerType) {
        if (indexerType == IndexerJobHandler.APPS_INDEXER) {
            return FrameworkAppsIndexerMaintenanceConfig.INSTANCE;
        } else if (indexerType == IndexerJobHandler.CONTACTS_INDEXER) {
            return FrameworkContactsIndexerMaintenanceConfig.INSTANCE;
        } else if (indexerType == IndexerJobHandler.APP_OPEN_EVENT_INDEXER) {
            return FrameworkAppOpenEventIndexerMaintenanceConfig.INSTANCE;
        } else {
            throw new IllegalArgumentException(
                    "Attempted to get config for invalid indexer type: " + indexerType);
        }
    }

    /**
     * Returns the local service for the indexer.
     *
     * @see LocalManagerRegistry#addManager
     */
    @NonNull
    Class<? extends IndexerLocalService> getLocalService();

    /** Returns the minimum job id for the indexer. */
    int getMinJobId();
}
