/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.appsearch.appsindexer;

import android.util.ArraySet;

import java.util.Collections;
import java.util.Set;

public final class AppOpenEventStats {

    private final Set<Integer> mUpdateStatusCodes;
    private final int mNumberOfAppOpenEventsAdded;
    private final long mTotalLatencyMillis;
    private final long mUsageStatsManagerReadLatencyMillis;
    private final long mAppSearchSetSchemaLatencyMillis;
    private final long mAppSearchPutLatencyMillis;
    private final long mUpdateStartTimestampMillis;
    private final long mLastAppUpdateTimestampMillis;

    private AppOpenEventStats(Builder builder) {
        mUpdateStatusCodes =
                Collections.unmodifiableSet(new ArraySet<>(builder.mUpdateStatusCodes));
        mNumberOfAppOpenEventsAdded = builder.mNumberOfAppOpenEventsAdded;
        mTotalLatencyMillis = builder.mTotalLatencyMillis;
        mUsageStatsManagerReadLatencyMillis = builder.mUsageStatsManagerReadLatencyMillis;
        mAppSearchSetSchemaLatencyMillis = builder.mAppSearchSetSchemaLatencyMillis;
        mAppSearchPutLatencyMillis = builder.mAppSearchPutLatencyMillis;
        mUpdateStartTimestampMillis = builder.mUpdateStartTimestampMillis;
        mLastAppUpdateTimestampMillis = builder.mLastAppUpdateTimestampMillis;
    }

    public Set<Integer> getUpdateStatusCodes() {
        return mUpdateStatusCodes;
    }

    public int getNumberOfAppOpenEventsAdded() {
        return mNumberOfAppOpenEventsAdded;
    }

    public long getTotalLatencyMillis() {
        return mTotalLatencyMillis;
    }

    public long getUsageStatsManagerReadLatencyMillis() {
        return mUsageStatsManagerReadLatencyMillis;
    }

    public long getAppSearchSetSchemaLatencyMillis() {
        return mAppSearchSetSchemaLatencyMillis;
    }

    public long getAppSearchPutLatencyMillis() {
        return mAppSearchPutLatencyMillis;
    }

    public long getUpdateStartTimestampMillis() {
        return mUpdateStartTimestampMillis;
    }

    public long getLastAppUpdateTimestampMillis() {
        return mLastAppUpdateTimestampMillis;
    }

    public static class Builder {
        private Set<Integer> mUpdateStatusCodes = new ArraySet<>();
        private int mNumberOfAppOpenEventsAdded = 0;
        private long mTotalLatencyMillis = 0;
        private long mUsageStatsManagerReadLatencyMillis = 0;
        private long mAppSearchSetSchemaLatencyMillis = 0;
        private long mAppSearchPutLatencyMillis = 0;
        private long mUpdateStartTimestampMillis = 0;
        private long mLastAppUpdateTimestampMillis = 0;

        public Builder addUpdateStatusCode(int statusCode) {
            mUpdateStatusCodes.add(statusCode);
            return this;
        }

        public Builder setUpdateStatusCodes(Set<Integer> statusCodes) {
            mUpdateStatusCodes = new ArraySet<>(statusCodes);
            return this;
        }

        public Builder setNumberOfAppOpenEventsAdded(int numberOfAppOpenEventsAdded) {
            mNumberOfAppOpenEventsAdded = numberOfAppOpenEventsAdded;
            return this;
        }

        public Builder setTotalLatencyMillis(long totalLatencyMillis) {
            mTotalLatencyMillis = totalLatencyMillis;
            return this;
        }

        public Builder setUsageStatsManagerReadLatencyMillis(
                long usageStatsManagerReadLatencyMillis) {
            mUsageStatsManagerReadLatencyMillis = usageStatsManagerReadLatencyMillis;
            return this;
        }

        public Builder setAppSearchSetSchemaLatencyMillis(long appSearchSetSchemaLatencyMillis) {
            mAppSearchSetSchemaLatencyMillis = appSearchSetSchemaLatencyMillis;
            return this;
        }

        public Builder setAppSearchPutLatencyMillis(long appSearchPutLatencyMillis) {
            mAppSearchPutLatencyMillis = appSearchPutLatencyMillis;
            return this;
        }

        public Builder setUpdateStartTimestampMillis(long updateStartTimestampMillis) {
            mUpdateStartTimestampMillis = updateStartTimestampMillis;
            return this;
        }

        public Builder setLastAppUpdateTimestampMillis(long lastAppUpdateTimestampMillis) {
            mLastAppUpdateTimestampMillis = lastAppUpdateTimestampMillis;
            return this;
        }

        public AppOpenEventStats build() {
            return new AppOpenEventStats(this);
        }
    }
}
