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

package com.android.server.appsearch.indexer;

import android.annotation.IntDef;
import android.annotation.NonNull;

import com.android.server.LocalManagerRegistry;
import com.android.server.appsearch.appsindexer.AppsIndexerMaintenanceConfig;
import com.android.server.appsearch.contactsindexer.ContactsIndexerMaintenanceConfig;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Contains information needed to dispatch a maintenance job for an indexer. */
public interface IndexerMaintenanceConfig {
    int APPS_INDEXER = 0;
    int CONTACTS_INDEXER = 1;

    @IntDef(
            value = {
                APPS_INDEXER,
                CONTACTS_INDEXER,
            })
    @Retention(RetentionPolicy.SOURCE)
    @interface IndexerType {}

    /** Returns the {@link IndexerMaintenanceConfig} for the requested indexer type. */
    @NonNull
    static IndexerMaintenanceConfig getConfigForIndexer(@IndexerType int indexerType) {
        if (indexerType == APPS_INDEXER) {
            return AppsIndexerMaintenanceConfig.INSTANCE;
        } else if (indexerType == CONTACTS_INDEXER) {
            return ContactsIndexerMaintenanceConfig.INSTANCE;
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
