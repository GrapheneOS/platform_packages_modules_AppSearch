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

package com.android.server.appsearch.indexer;

/** Abstract base class for all indexer settings. */
public abstract class BaseSettings {

    private long mLastUpdateTimestampMillis;
    private long mLastAttemptedUpdateTimestampMillis;
    private int mIndexerForceUpdateEmergencyCounter;

    /** Resets all settings to default values. */
    public void reset() {
        mLastUpdateTimestampMillis = 0L;
        mLastAttemptedUpdateTimestampMillis = 0L;
        mIndexerForceUpdateEmergencyCounter = 0;
    }

    /** Returns the timestamp of when the last update occurred in milliseconds. */
    public long getLastUpdateTimestampMillis() {
        return mLastUpdateTimestampMillis;
    }

    /** Sets the timestamp of when the last update occurred in milliseconds. */
    public void setLastUpdateTimestampMillis(long timestampMillis) {
        mLastUpdateTimestampMillis = timestampMillis;
    }

    /** Returns the timestamp of when the last update attempt occurred in milliseconds. */
    public long getLastAttemptedUpdateTimestampMillis() {
        return mLastAttemptedUpdateTimestampMillis;
    }

    /** Sets the timestamp of when the last update attempt occurred in milliseconds. */
    public void setLastAttemptedUpdateTimestampMillis(long timestampMillis) {
        mLastAttemptedUpdateTimestampMillis = timestampMillis;
    }

    /** Returns the emergency counter that tracks the number of force updates. */
    public int getIndexerForceUpdateEmergencyCounter() {
        return mIndexerForceUpdateEmergencyCounter;
    }

    /** Sets the emergency counter that tracks the number of force updates. */
    public void setIndexerForceUpdateEmergencyCounter(int counter) {
        mIndexerForceUpdateEmergencyCounter = counter;
    }
}
