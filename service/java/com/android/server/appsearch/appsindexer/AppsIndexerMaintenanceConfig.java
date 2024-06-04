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

import android.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.appsearch.indexer.IndexerLocalService;
import com.android.server.appsearch.indexer.IndexerMaintenanceConfig;

/** Singleton class containing configuration for the apps indexer maintenance task. */
public class AppsIndexerMaintenanceConfig implements IndexerMaintenanceConfig {
    @VisibleForTesting
    static final int MIN_APPS_INDEXER_JOB_ID = 16964307; // Contacts Indexer Max Job Id + 1

    public static final IndexerMaintenanceConfig INSTANCE = new AppsIndexerMaintenanceConfig();

    /** Enforces singleton class pattern. */
    private AppsIndexerMaintenanceConfig() {}

    @NonNull
    @Override
    public Class<? extends IndexerLocalService> getLocalService() {
        return AppsIndexerManagerService.LocalService.class;
    }

    @Override
    public int getMinJobId() {
        return MIN_APPS_INDEXER_JOB_ID;
    }
}
