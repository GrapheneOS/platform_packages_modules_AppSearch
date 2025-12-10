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

package com.android.server.appsearch.appsindexer;

import android.annotation.IntDef;
import android.app.appfunctions.flags.Flags;

/** Defines constants and annotations for managing App Indexer versions. */
public final class AppIndexerVersions {

    /** Default value when the version is not specified or can not be determined. */
    public static final int APP_INDEXER_VERSION_UNKNOWN = 0;

    /** Represents the App Indexer version where dynamic schema support is enabled. */
    private static final int APP_INDEXER_DYNAMIC_SCHEMA_ENABLED_VERSION = 1;

    /**
     * Represents the App Indexer version where app-level app functions are indexed and serviceName
     * field is populated.
     */
    private static final int APP_INDEXER_APP_LEVEL_APP_FUNCTIONS_ENABLED_VERSION = 2;

    /** Annotation to restrict values to valid App Indexer versions. */
    @IntDef(
            value = {
                APP_INDEXER_VERSION_UNKNOWN,
                APP_INDEXER_DYNAMIC_SCHEMA_ENABLED_VERSION,
                APP_INDEXER_APP_LEVEL_APP_FUNCTIONS_ENABLED_VERSION
            })
    public @interface AppIndexerVersion {}

    /**
     * Stores the current version of App Indexer. If this differs from the {@link
     * AppsIndexerSettings#getPreviousIndexerVersionCode()} all apps will be re-indexed irrespective
     * of whether there was a corresponding package update or not.
     */
    @AppIndexerVersion
    public static final int CURR_APP_INDEXER_VERSION =
            Flags.enableDynamicAppFunctions()
                    ? APP_INDEXER_APP_LEVEL_APP_FUNCTIONS_ENABLED_VERSION
                    : APP_INDEXER_DYNAMIC_SCHEMA_ENABLED_VERSION;

    private AppIndexerVersions() {}
}
