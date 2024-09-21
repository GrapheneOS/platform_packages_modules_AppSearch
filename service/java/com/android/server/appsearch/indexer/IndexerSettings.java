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

import android.annotation.CurrentTimeMillisLong;
import android.annotation.NonNull;
import android.annotation.WorkerThread;
import android.os.PersistableBundle;
import android.util.AtomicFile;

import com.android.internal.annotations.VisibleForTesting;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Objects;

/**
 * Settings backed by a PersistableBundle, providing common functionality for settings handling.
 *
 * <p>This class provides common functionality for settings handling, including:
 *
 * <ul>
 *   <li>getting and setting the timestamp of the last update
 *   <li>loading and persisting settings to/from a file
 * </ul>
 *
 * <p>This class is NOT thread safe (similar to {@link PersistableBundle} which it wraps).
 */
public abstract class IndexerSettings {

    public static final String LAST_UPDATE_TIMESTAMP_KEY = "last_update_timestamp_millis";

    private final File mBaseDir;
    private File mFile;
    protected PersistableBundle mBundle = new PersistableBundle();

    public IndexerSettings(@NonNull File baseDir) {
        mBaseDir = Objects.requireNonNull(baseDir);
    }

    /** Allows for late initialization of the settings file. */
    @WorkerThread
    private void ensureFileCreated() {
        if (mFile != null) {
            return;
        }
        mFile = new File(mBaseDir, getSettingsFileName());
    }

    protected abstract String getSettingsFileName();

    /** Loads the bundle from the file. */
    @WorkerThread
    public void load() throws IOException {
        ensureFileCreated();
        mBundle = readBundle(mFile);
    }

    /** Saves the bundle to the file. */
    @WorkerThread
    public void persist() throws IOException {
        ensureFileCreated();
        writeBundle(mFile, mBundle);
    }

    /** Returns the timestamp of when the last update occurred in milliseconds. */
    public @CurrentTimeMillisLong long getLastUpdateTimestampMillis() {
        return mBundle.getLong(LAST_UPDATE_TIMESTAMP_KEY);
    }

    /** Sets the timestamp of when the last update occurred in milliseconds. */
    public void setLastUpdateTimestampMillis(@CurrentTimeMillisLong long timestampMillis) {
        mBundle.putLong(LAST_UPDATE_TIMESTAMP_KEY, timestampMillis);
    }

    /** Resets all the settings to default values. */
    public void reset() {
        setLastUpdateTimestampMillis(0);
    }

    /** Static util method to read a bundle from a file. */
    @VisibleForTesting
    @NonNull
    @WorkerThread
    public static PersistableBundle readBundle(@NonNull File src) throws IOException {
        AtomicFile atomicFile = new AtomicFile(src);
        try (FileInputStream fis = atomicFile.openRead()) {
            return PersistableBundle.readFromStream(fis);
        }
    }

    /** Static util method to write a bundle to a file. */
    @VisibleForTesting
    @WorkerThread
    public static void writeBundle(@NonNull File dest, @NonNull PersistableBundle bundle)
            throws IOException {
        AtomicFile atomicFile = new AtomicFile(dest);
        FileOutputStream fos = null;
        try {
            fos = atomicFile.startWrite();
            bundle.writeToStream(fos);
            atomicFile.finishWrite(fos);
        } catch (IOException e) {
            if (fos != null) {
                atomicFile.failWrite(fos);
            }
            throw e;
        }
    }
}
