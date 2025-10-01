/*
 * Copyright (C) 2022 The Android Open Source Project
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

import com.android.server.appsearch.indexer.BaseSettings;

/**
 * Holds settings and persistent state for ContactsIndexer.
 *
 * <p>This class is NOT thread safe.
 */
public class ContactsIndexerSettings extends BaseSettings {

    private long mLastFullUpdateTimestampMillis;
    private long mLastDeltaUpdateTimestampMillis;
    private long mLastContactUpdateTimestampMillis;
    private long mLastContactDeleteTimestampMillis;

    /** Returns the timestamp of when the last full update occurred in milliseconds. */
    public long getLastFullUpdateTimestampMillis() {
        return mLastFullUpdateTimestampMillis;
    }

    /** Sets the timestamp of when the last full update occurred in milliseconds. */
    public void setLastFullUpdateTimestampMillis(long timestampMillis) {
        mLastFullUpdateTimestampMillis = timestampMillis;
    }

    /** Returns the timestamp of when the last delta update occurred in milliseconds. */
    public long getLastDeltaUpdateTimestampMillis() {
        return mLastDeltaUpdateTimestampMillis;
    }

    /** Sets the timestamp of when the last delta update occurred in milliseconds. */
    public void setLastDeltaUpdateTimestampMillis(long timestampMillis) {
        mLastDeltaUpdateTimestampMillis = timestampMillis;
    }

    /** Returns the timestamp of when the last contact in CP2 was updated in milliseconds. */
    public long getLastContactUpdateTimestampMillis() {
        return mLastContactUpdateTimestampMillis;
    }

    /** Sets the timestamp of when the last contact in CP2 was updated in milliseconds. */
    public void setLastContactUpdateTimestampMillis(long timestampMillis) {
        mLastContactUpdateTimestampMillis = timestampMillis;
    }

    /** Returns the timestamp of when the last contact in CP2 was deleted in milliseconds. */
    public long getLastContactDeleteTimestampMillis() {
        return mLastContactDeleteTimestampMillis;
    }

    /** Sets the timestamp of when the last contact in CP2 was deleted in milliseconds. */
    public void setLastContactDeleteTimestampMillis(long timestampMillis) {
        mLastContactDeleteTimestampMillis = timestampMillis;
    }

    /** Resets all settings fields to their default values. */
    @Override
    public void reset() {
        super.reset();
        mLastFullUpdateTimestampMillis = 0L;
        mLastDeltaUpdateTimestampMillis = 0L;
        mLastContactUpdateTimestampMillis = 0L;
        mLastContactDeleteTimestampMillis = 0L;
    }
}
