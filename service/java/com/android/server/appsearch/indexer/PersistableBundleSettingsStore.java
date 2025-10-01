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

import android.annotation.NonNull;
import android.annotation.WorkerThread;
import android.os.PersistableBundle;
import android.util.AtomicFile;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.appsearch.appsindexer.AppIndexerVersions;
import com.android.server.appsearch.appsindexer.AppOpenEventIndexerSettings;
import com.android.server.appsearch.appsindexer.AppsIndexerSettings;
import com.android.server.appsearch.contactsindexer.ContactsIndexerSettings;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;

/** Implementation of {@link SettingsStore} that is backed by {@link PersistableBundle}. */
public class PersistableBundleSettingsStore implements SettingsStore {

    private static final String CONTACTS_INDEXER_SETTINGS_FILE = "contacts_indexer_settings.pb";
    private static final String APPS_INDEXER_SETTINGS_FILE = "apps_indexer_settings.pb";
    private static final String APP_OPEN_EVENT_INDEXER_SETTINGS_FILE =
            "app_open_event_indexer_settings.pb";

    public static final String LAST_UPDATE_TIMESTAMP_KEY = "last_update_timestamp_millis";
    public static final String LAST_ATTEMPTED_UPDATE_TIMESTAMP_KEY =
            "last_attempted_update_timestamp_millis";
    public static final String FORCE_UPDATE_EMERGENCY_COUNTER_KEY = "emergency_counter";

    static final String LAST_FULL_UPDATE_TIMESTAMP_KEY = "last_full_update_timestamp_millis";
    static final String LAST_DELTA_UPDATE_TIMESTAMP_KEY = "last_delta_update_timestamp_key";
    // TODO(b/296078517): rename the keys to match the constants but keep backwards compatibility
    // Note this constant was renamed from LAST_DELTA_UPDATE_TIMESTAMP_KEY but the key itself has
    // been kept the same for backwards compatibility
    static final String LAST_CONTACT_UPDATE_TIMESTAMP_KEY = "last_delta_update_timestamp_millis";
    // Note this constant was renamed from LAST_DELTA_DELETE_TIMESTAMP_KEY but the key itself has
    // been kept the same for backwards compatibility
    static final String LAST_CONTACT_DELETE_TIMESTAMP_KEY = "last_delta_delete_timestamp_millis";

    static final String LAST_APP_UPDATE_TIMESTAMP_KEY = "last_app_update_timestamp_millis";
    static final String PREVIOUS_APP_INDEXER_VERSION_CODE = "previous_app_indexer_version_code";
    private static final String LAST_PARTITIONS_FINGERPRINT_SORTED_BY_PARTITION_NAME =
            "last_partitions_fingerprint_sorted_by_partition_name";
    private static final String PREVIOUS_LOCALE_CODE = "previous_locale_code";
    private static final String LOG_LINES_KEY = "log_lines";

    private final File mBaseDir;

    public PersistableBundleSettingsStore(@NonNull File baseDir) {
        mBaseDir = Objects.requireNonNull(baseDir);
    }

    @Override
    public void loadInto(@NonNull BaseSettings settings) throws IOException {
        Objects.requireNonNull(settings);

        String fileName = getFileName(settings.getClass());
        File settingsFile = new File(mBaseDir, fileName);

        // Exit early if the file does not exist.
        if (!settingsFile.exists()) {
            return;
        }

        PersistableBundle bundle = readBundle(settingsFile);
        loadBaseSettings(settings, bundle);

        if (settings instanceof ContactsIndexerSettings) {
            loadContactsIndexerSettings((ContactsIndexerSettings) settings, bundle);
        } else if (settings instanceof AppsIndexerSettings) {
            loadAppsIndexerSettings((AppsIndexerSettings) settings, bundle);
        } else if (settings instanceof AppOpenEventIndexerSettings) {
            // No-op because AppOpenEventIndexerSettings lacks additional settings.
        } else {
            throw new IllegalArgumentException(
                    "Unsupported settings object: " + settings.getClass().getName());
        }
    }

    @Override
    public <T extends BaseSettings> void persist(@NonNull T settings) throws IOException {
        Objects.requireNonNull(settings);

        PersistableBundle bundle = new PersistableBundle();
        String fileName = getFileName(settings.getClass());

        persistBaseSettings(settings, bundle);

        if (settings instanceof ContactsIndexerSettings) {
            persistContactsIndexerSettings((ContactsIndexerSettings) settings, bundle);
        } else if (settings instanceof AppsIndexerSettings) {
            persistAppsIndexerSettings((AppsIndexerSettings) settings, bundle);
        } else if (settings instanceof AppOpenEventIndexerSettings) {
            // No-op because AppOpenEventIndexerSettings lacks additional settings.
        } else {
            throw new IllegalArgumentException(
                    "Unsupported settings object: " + settings.getClass().getName());
        }

        writeBundle(new File(mBaseDir, fileName), bundle);
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

        // Note: We cannot use try-with-resources here because AtomicFile
        // requires either finishWrite() or failWrite() to be called
        // on the FileOutputStream returned by startWrite().
        // These methods handle the closing of the stream and the finalization of the atomic write.
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

    /** Load {@link BaseSettings} from a given {@link PersistableBundle}. */
    private void loadBaseSettings(BaseSettings settings, PersistableBundle bundle) {
        settings.setLastUpdateTimestampMillis(bundle.getLong(LAST_UPDATE_TIMESTAMP_KEY, 0L));
        settings.setLastAttemptedUpdateTimestampMillis(
                bundle.getLong(LAST_ATTEMPTED_UPDATE_TIMESTAMP_KEY, 0L));
        settings.setIndexerForceUpdateEmergencyCounter(
                bundle.getInt(FORCE_UPDATE_EMERGENCY_COUNTER_KEY, 0));
    }

    /** Persist {@link BaseSettings} to a given {@link PersistableBundle}. */
    private void persistBaseSettings(BaseSettings settings, PersistableBundle bundle) {
        bundle.putLong(LAST_UPDATE_TIMESTAMP_KEY, settings.getLastUpdateTimestampMillis());
        bundle.putLong(
                LAST_ATTEMPTED_UPDATE_TIMESTAMP_KEY,
                settings.getLastAttemptedUpdateTimestampMillis());
        bundle.putInt(
                FORCE_UPDATE_EMERGENCY_COUNTER_KEY,
                settings.getIndexerForceUpdateEmergencyCounter());
    }

    /** Get the file name for a given indexer settings class. */
    private String getFileName(@NonNull Class<?> settingsClass) {
        Objects.requireNonNull(settingsClass);

        if (settingsClass == ContactsIndexerSettings.class) {
            return CONTACTS_INDEXER_SETTINGS_FILE;
        } else if (settingsClass == AppsIndexerSettings.class) {
            return APPS_INDEXER_SETTINGS_FILE;
        } else if (settingsClass == AppOpenEventIndexerSettings.class) {
            return APP_OPEN_EVENT_INDEXER_SETTINGS_FILE;
        }
        throw new IllegalArgumentException(
                "Unsupported settings class: " + settingsClass.getName());
    }

    /** Loads {@link ContactsIndexerSettings} from {@link PersistableBundle}. */
    private void loadContactsIndexerSettings(
            @NonNull ContactsIndexerSettings settings, @NonNull PersistableBundle bundle)
            throws IOException {
        settings.setLastFullUpdateTimestampMillis(
                bundle.getLong(LAST_FULL_UPDATE_TIMESTAMP_KEY, 0L));
        settings.setLastDeltaUpdateTimestampMillis(
                bundle.getLong(LAST_DELTA_UPDATE_TIMESTAMP_KEY, 0L));
        settings.setLastContactUpdateTimestampMillis(
                bundle.getLong(LAST_CONTACT_UPDATE_TIMESTAMP_KEY, 0L));
        settings.setLastContactDeleteTimestampMillis(
                bundle.getLong(LAST_CONTACT_DELETE_TIMESTAMP_KEY, 0L));
    }

    /** Loads {@link AppsIndexerSettings} from {@link PersistableBundle}. */
    private void loadAppsIndexerSettings(
            @NonNull AppsIndexerSettings settings, @NonNull PersistableBundle bundle)
            throws IOException {
        settings.setLastAppUpdateTimestampMillis(bundle.getLong(LAST_APP_UPDATE_TIMESTAMP_KEY, 0L));
        settings.setPreviousIndexerVersionCode(
                bundle.getInt(
                        PREVIOUS_APP_INDEXER_VERSION_CODE,
                        AppIndexerVersions.APP_INDEXER_VERSION_UNKNOWN));
        settings.setLastPartitionFingerprints(
                bundle.getStringArray(LAST_PARTITIONS_FINGERPRINT_SORTED_BY_PARTITION_NAME));
        settings.setPreviousLocaleCode(bundle.getString(PREVIOUS_LOCALE_CODE, ""));
        String[] logLines = bundle.getStringArray(LOG_LINES_KEY);

        if (logLines != null) {
            settings.setLogLines(new ArrayDeque<>(Arrays.asList(logLines)));
        }
    }

    /** Persists {@link ContactsIndexerSettings} into storage. */
    private void persistContactsIndexerSettings(
            @NonNull ContactsIndexerSettings settings, @NonNull PersistableBundle bundle) {
        bundle.putLong(LAST_FULL_UPDATE_TIMESTAMP_KEY, settings.getLastFullUpdateTimestampMillis());
        bundle.putLong(
                LAST_DELTA_UPDATE_TIMESTAMP_KEY, settings.getLastDeltaUpdateTimestampMillis());
        bundle.putLong(
                LAST_CONTACT_UPDATE_TIMESTAMP_KEY, settings.getLastContactUpdateTimestampMillis());
        bundle.putLong(
                LAST_CONTACT_DELETE_TIMESTAMP_KEY, settings.getLastContactDeleteTimestampMillis());
    }

    /** Persists {@link AppsIndexerSettings} into storage. */
    private void persistAppsIndexerSettings(
            @NonNull AppsIndexerSettings settings, @NonNull PersistableBundle bundle) {
        bundle.putLong(LAST_APP_UPDATE_TIMESTAMP_KEY, settings.getLastAppUpdateTimestampMillis());
        bundle.putInt(PREVIOUS_APP_INDEXER_VERSION_CODE, settings.getPreviousIndexerVersionCode());
        if (settings.getLastPartitionFingerprints() != null) {
            bundle.putStringArray(
                    LAST_PARTITIONS_FINGERPRINT_SORTED_BY_PARTITION_NAME,
                    settings.getLastPartitionFingerprints());
        }
        bundle.putString(PREVIOUS_LOCALE_CODE, settings.getPreviousLocaleCode());
        bundle.putStringArray(LOG_LINES_KEY, settings.getLogLines().toArray(new String[0]));
    }
}
