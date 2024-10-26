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

import android.annotation.IntDef;
import android.util.ArraySet;

import com.android.server.appsearch.stats.AppSearchStatsLog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Set;

public class AppsUpdateStats {

    @IntDef(
            value = {
                UNKNOWN_UPDATE_TYPE,
                FULL_UPDATE,
                // TODO(b/275592563): Add package event update types
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface UpdateType {}

    public static final int UNKNOWN_UPDATE_TYPE =
            AppSearchStatsLog.APP_SEARCH_APPS_INDEXER_STATS_REPORTED__UPDATE_TYPE__UNKNOWN;

    /** Complete update to bring AppSearch in sync with PackageManager. */
    public static final int FULL_UPDATE =
            AppSearchStatsLog.APP_SEARCH_APPS_INDEXER_STATS_REPORTED__UPDATE_TYPE__FULL;

    @UpdateType int mUpdateType = UNKNOWN_UPDATE_TYPE;

    // Ok by default, will be set to something else if there is a failure while updating.
    Set<Integer> mUpdateStatusCodes = new ArraySet<>();
    int mNumberOfAppsAdded;
    int mNumberOfAppsRemoved;
    int mNumberOfAppsUpdated;
    int mNumberOfAppsUnchanged;
    long mTotalLatencyMillis;
    long mPackageManagerLatencyMillis;
    long mAppSearchGetLatencyMillis;
    long mAppSearchSetSchemaLatencyMillis;
    long mAppSearchPutLatencyMillis;

    // Same as in settings
    long mUpdateStartTimestampMillis;
    long mLastAppUpdateTimestampMillis;

    int mNumberOfFunctionsAdded;
    // For apps that get deleted, we don't check what functions were indexed into AppSearch, and
    // delete the entire database corresponding to the packages functions. We use a setSchema call
    // with override set to true, so it's not clear how many functions were deleted from AppSearch
    // for deleted packages.
    int mApproximateNumberOfFunctionsRemoved;
    // As of now, added and unchanged and updated functions are all logged as updated
    // TODO(b/357551503): Log indexed function counts more accurately
    int mNumberOfFunctionsUpdated;
    // For apps that don't get updated, we don't check functions at all. So it's not clear how many
    // functions have remained unchanged in packages that were unchanged.
    int mApproximateNumberOfFunctionsUnchanged;

    long mAppSearchRemoveLatencyMillis;

    /** Resets the Apps Indexer update stats. */
    public void clear() {
        mUpdateType = UNKNOWN_UPDATE_TYPE;
        mUpdateStatusCodes = new ArraySet<>();

        mPackageManagerLatencyMillis = 0;
        mAppSearchGetLatencyMillis = 0;
        mAppSearchSetSchemaLatencyMillis = 0;
        mAppSearchPutLatencyMillis = 0;
        mTotalLatencyMillis = 0;

        mNumberOfAppsRemoved = 0;
        mNumberOfAppsAdded = 0;
        mNumberOfAppsUpdated = 0;
        mNumberOfAppsUnchanged = 0;

        mLastAppUpdateTimestampMillis = 0;
        mUpdateStartTimestampMillis = 0;

        mNumberOfFunctionsAdded = 0;
        mApproximateNumberOfFunctionsRemoved = 0;
        mApproximateNumberOfFunctionsUnchanged = 0;
        mNumberOfFunctionsUpdated = 0;

        mAppSearchRemoveLatencyMillis = 0;
    }
}
