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
import android.util.AtomicFile;

import com.android.server.appsearch.appsindexer.AppOpenEventIndexerSettings;
import com.android.server.appsearch.appsindexer.AppsIndexerSettings;
import com.android.server.appsearch.contactsindexer.ContactsIndexerSettings;

import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Objects;

/** Implementation of {@link SettingsStore} that is backed by Protobuf. */
public class ProtoSettingsStore implements SettingsStore {

    private static final String CONTACTS_INDEXER_SETTINGS_FILE = "contacts_indexer_settings_v2.pb";
    private static final String APPS_INDEXER_SETTINGS_FILE = "apps_indexer_settings_v2.pb";
    private static final String APP_OPEN_EVENT_INDEXER_SETTINGS_FILE =
            "app_open_event_indexer_settings_v2.pb";

    private final File mBaseDir;

    public ProtoSettingsStore(File baseDir) {
        mBaseDir = Objects.requireNonNull(baseDir);
    }

    @Override
    public void loadInto(@NonNull BaseSettings settings) throws IOException {
        Objects.requireNonNull(settings);

        String fileName = getFileName(settings.getClass());
        File settingsFile = new File(mBaseDir, fileName);

        if (!settingsFile.exists()) {
            return;
        }

        if (settings instanceof ContactsIndexerSettings contactsIndexerSettings) {
            loadContactsIndexerSettings(settingsFile, contactsIndexerSettings);
        } else if (settings instanceof AppsIndexerSettings appsIndexerSettings) {
            loadAppsIndexerSettings(settingsFile, appsIndexerSettings);
        } else if (settings instanceof AppOpenEventIndexerSettings appOpenEventIndexerSettings) {
            loadAppOpenEventIndexerSettings(settingsFile, appOpenEventIndexerSettings);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported settings object: " + settings.getClass().getName());
        }
    }

    @Override
    public <T extends BaseSettings> void persist(@NonNull T settings) throws IOException {
        Objects.requireNonNull(settings);

        MessageLite protoMessage;
        String fileName = getFileName(settings.getClass());

        if (settings instanceof ContactsIndexerSettings contactsIndexerSettings) {
            protoMessage = buildContactsIndexerSettingsProto(contactsIndexerSettings);
        } else if (settings instanceof AppsIndexerSettings appsIndexerSettings) {
            protoMessage = buildAppsIndexerSettingsProto(appsIndexerSettings);
        } else if (settings instanceof AppOpenEventIndexerSettings appOpenEventIndexerSettings) {
            protoMessage = buildAppOpenEventIndexerSettingsProto(appOpenEventIndexerSettings);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported settings object: " + settings.getClass().getName());
        }

        writeProto(new File(mBaseDir, fileName), protoMessage);
    }

    /** Util method to read a proto message from a file. */
    @SuppressWarnings(
            "ProtoParseWithRegistry") // ExtensionRegistryLite is not available in Platform.
    @WorkerThread
    @NonNull
    private <T extends MessageLite> T readProto(@NonNull File src, @NonNull Parser<T> parser)
            throws IOException {
        AtomicFile atomicFile = new AtomicFile(src);
        try (FileInputStream fis = atomicFile.openRead()) {
            return parser.parseFrom(fis);
        }
    }

    /** Util method to write a proto message to a file. */
    @WorkerThread
    private void writeProto(@NonNull File dest, @NonNull MessageLite protoMessage)
            throws IOException {
        AtomicFile atomicFile = new AtomicFile(dest);
        FileOutputStream fos = null;
        boolean isFileWritten = false;

        // Note: We cannot use try-with-resources here because AtomicFile
        // requires either finishWrite() or failWrite() to be called
        // on the FileOutputStream returned by startWrite().
        // These methods handle the closing of the stream and the finalization of the atomic write.
        try {
            fos = atomicFile.startWrite();
            protoMessage.writeTo(fos);
            atomicFile.finishWrite(fos);
            isFileWritten = true;
        } finally {
            if (!isFileWritten && fos != null) {
                atomicFile.failWrite(fos);
            }
        }
    }

    /** Load {@link BaseSettings} from a given {@link BaseSettingsProto}. */
    private void loadBaseSettings(
            @NonNull BaseSettings settings, @NonNull BaseSettingsProto proto) {
        Objects.requireNonNull(settings);
        Objects.requireNonNull(proto);

        settings.setLastUpdateTimestampMillis(proto.getLastUpdateTimestampMillis());
        settings.setLastAttemptedUpdateTimestampMillis(
                proto.getLastAttemptedUpdateTimestampMillis());
        settings.setIndexerForceUpdateEmergencyCounter(
                proto.getIndexerForceUpdateEmergencyCounter());
    }

    /** Get the file name for a given indexer settings class. */
    @NonNull
    private String getFileName(@NonNull Class<? extends BaseSettings> settingsClass) {
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

    /**
     * Loads {@link ContactsIndexerSettings} from a settings file into a provided object.
     *
     * @param settingsFile The file to read the settings from.
     * @param outSettings The {@link ContactsIndexerSettings} object that the settings will be
     *     loaded into.
     */
    private void loadContactsIndexerSettings(
            @NonNull File settingsFile, @NonNull ContactsIndexerSettings outSettings)
            throws IOException {
        ContactsIndexerSettingsProto contactsIndexerSettingsProto =
                readProto(settingsFile, ContactsIndexerSettingsProto.parser());
        loadBaseSettings(outSettings, contactsIndexerSettingsProto.getBaseSettings());

        outSettings.setLastFullUpdateTimestampMillis(
                contactsIndexerSettingsProto.getLastFullUpdateTimestampMillis());
        outSettings.setLastDeltaUpdateTimestampMillis(
                contactsIndexerSettingsProto.getLastDeltaUpdateTimestampMillis());
        outSettings.setLastContactUpdateTimestampMillis(
                contactsIndexerSettingsProto.getLastContactUpdateTimestampMillis());
        outSettings.setLastContactDeleteTimestampMillis(
                contactsIndexerSettingsProto.getLastContactDeleteTimestampMillis());
    }

    /**
     * Loads {@link AppsIndexerSettings} from a settings file into a provided object.
     *
     * @param settingsFile The file to read the settings from.
     * @param outSettings The {@link AppsIndexerSettings} object that the settings will be loaded
     *     into.
     */
    private void loadAppsIndexerSettings(
            @NonNull File settingsFile, @NonNull AppsIndexerSettings outSettings)
            throws IOException {
        AppsIndexerSettingsProto appsIndexerSettingsProto =
                readProto(settingsFile, AppsIndexerSettingsProto.parser());
        loadBaseSettings(outSettings, appsIndexerSettingsProto.getBaseSettings());

        outSettings.setLastAppUpdateTimestampMillis(
                appsIndexerSettingsProto.getLastAppUpdateTimestampMillis());
        outSettings.setPreviousIndexerVersionCode(
                appsIndexerSettingsProto.getPreviousIndexerVersionCode());
        outSettings.setPreviousLocaleCode(appsIndexerSettingsProto.getPreviousLocaleCode());
        outSettings.setLogLines(new ArrayDeque<>(appsIndexerSettingsProto.getLogLinesList()));
    }

    /**
     * Loads {@link AppOpenEventIndexerSettings} from a settings file into a provided object.
     *
     * @param settingsFile The file to read the settings from.
     * @param outSettings The {@link AppOpenEventIndexerSettings} object that the settings will be
     *     loaded into.
     */
    private void loadAppOpenEventIndexerSettings(
            @NonNull File settingsFile, @NonNull AppOpenEventIndexerSettings outSettings)
            throws IOException {
        AppOpenEventIndexerSettingsProto appOpenEventIndexerSettingsProto =
                readProto(settingsFile, AppOpenEventIndexerSettingsProto.parser());
        loadBaseSettings(outSettings, appOpenEventIndexerSettingsProto.getBaseSettings());
    }

    /**
     * Builds a new {@link ContactsIndexerSettingsProto} from a given {@link
     * ContactsIndexerSettings} for storage.
     */
    @NonNull
    private ContactsIndexerSettingsProto buildContactsIndexerSettingsProto(
            @NonNull ContactsIndexerSettings settings) {
        BaseSettingsProto baseSettingsProto = buildBaseSettingsProto(settings);
        return ContactsIndexerSettingsProto.newBuilder()
                .setBaseSettings(baseSettingsProto)
                .setLastFullUpdateTimestampMillis(settings.getLastFullUpdateTimestampMillis())
                .setLastDeltaUpdateTimestampMillis(settings.getLastDeltaUpdateTimestampMillis())
                .setLastContactUpdateTimestampMillis(settings.getLastContactUpdateTimestampMillis())
                .setLastContactDeleteTimestampMillis(settings.getLastContactDeleteTimestampMillis())
                .build();
    }

    /**
     * Builds a new {@link AppsIndexerSettingsProto} from a given {@link AppsIndexerSettings} for
     * storage.
     */
    @NonNull
    private AppsIndexerSettingsProto buildAppsIndexerSettingsProto(
            @NonNull AppsIndexerSettings settings) {
        BaseSettingsProto baseSettingsProto = buildBaseSettingsProto(settings);
        return AppsIndexerSettingsProto.newBuilder()
                .setBaseSettings(baseSettingsProto)
                .setLastAppUpdateTimestampMillis(settings.getLastAppUpdateTimestampMillis())
                .setPreviousIndexerVersionCode(settings.getPreviousIndexerVersionCode())
                .setPreviousLocaleCode(settings.getPreviousLocaleCode())
                .addAllLogLines(settings.getLogLines())
                .build();
    }

    /**
     * Builds a new {@link AppOpenEventIndexerSettingsProto} from a given {@link
     * AppOpenEventIndexerSettings} for storage.
     */
    @NonNull
    private AppOpenEventIndexerSettingsProto buildAppOpenEventIndexerSettingsProto(
            @NonNull AppOpenEventIndexerSettings settings) {
        BaseSettingsProto baseSettingsProto = buildBaseSettingsProto(settings);
        return AppOpenEventIndexerSettingsProto.newBuilder()
                .setBaseSettings(baseSettingsProto)
                .build();
    }

    /** Build a new {@link BaseSettingsProto} from a given {@link BaseSettings}. */
    private BaseSettingsProto buildBaseSettingsProto(@NonNull BaseSettings settings) {
        Objects.requireNonNull(settings);

        return BaseSettingsProto.newBuilder()
                .setLastUpdateTimestampMillis(settings.getLastUpdateTimestampMillis())
                .setLastAttemptedUpdateTimestampMillis(
                        settings.getLastAttemptedUpdateTimestampMillis())
                .setIndexerForceUpdateEmergencyCounter(
                        settings.getIndexerForceUpdateEmergencyCounter())
                .build();
    }
}
