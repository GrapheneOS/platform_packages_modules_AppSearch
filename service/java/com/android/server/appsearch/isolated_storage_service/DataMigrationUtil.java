/*
 * Copyright 2025 The Android Open Source Project
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
package com.android.server.appsearch.isolated_storage_service;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.appsearch.AppSearchEnvironmentFactory;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.exceptions.AppSearchException;
import android.app.appsearch.util.LogUtil;
import android.content.Context;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Log;

import com.android.server.appsearch.external.localstorage.AppSearchImpl;
import com.android.server.appsearch.external.localstorage.util.PrefixUtil;
import com.android.server.appsearch.indexer.IndexerSettings;

import com.google.android.icing.IcingSearchEngineInterface;
import com.google.android.icing.proto.BatchPutResultProto;
import com.google.android.icing.proto.InitializeResultProto;
import com.google.android.icing.proto.PersistToDiskResultProto;
import com.google.android.icing.proto.PersistType;
import com.google.android.icing.proto.PutDocumentRequest;
import com.google.android.icing.proto.ResetResultProto;
import com.google.android.icing.proto.ResultSpecProto;
import com.google.android.icing.proto.SchemaProto;
import com.google.android.icing.proto.SchemaTypeConfigProto;
import com.google.android.icing.proto.ScoringSpecProto;
import com.google.android.icing.proto.SearchResultProto;
import com.google.android.icing.proto.SearchSpecProto;
import com.google.android.icing.proto.SetSchemaResultProto;
import com.google.android.icing.proto.StatusProto;
import com.google.android.icing.proto.TermMatchType;

import java.io.File;
import java.io.IOException;

/**
 * Utils to migrate data from AppSearch to IsolatedStorage.
 *
 * @hide
 */
public class DataMigrationUtil {
    private DataMigrationUtil() {}

    private static final String TAG = "IcingDataMigration";

    public static final String DATA_MIGRATION_STATUS_FILE = "data_migration_status";

    /** Checks if data migration is needed from AppSearch to Isolated Storage. */
    // TODO(b/407815165) Right now just check if the icing/version on host exists
    //  We can persist a file to save the migration status, so dir deletion could happen later.
    public static boolean needDataMigration(
            @NonNull Context userContext, @NonNull UserHandle userHandle) {
        File appSearchDir =
                AppSearchEnvironmentFactory.getEnvironmentInstance()
                        .getAppSearchDir(userContext, userHandle);
        File icingVersion = new File(appSearchDir, "icing/version");
        File dataMigrationStatus = new File(
                appSearchDir, DATA_MIGRATION_STATUS_FILE);

        // icing version exists means icing has been initialized and used.
        // And if there is no migration_status file created, it means migration has not been
        // scheduled yet, so we need to schedule a migration.
        return icingVersion.exists() && !dataMigrationStatus.exists();
    }

    /** deletes the migration status file if it exists. */
    public static void deleteMigrationStatus(
            @NonNull UserHandle userHandle,
            @NonNull File appSearchDir) {
        try {
            File icingMigrationStatus = new File(
                    appSearchDir,  DATA_MIGRATION_STATUS_FILE);
            if (icingMigrationStatus.exists())  {
                Log.i(TAG,
                        "data migration was run before for " + userHandle);
                icingMigrationStatus.delete();
            }
        } catch (Exception e) {
            Log.e(TAG,
                    "Exception thrown while checking migration status "
                            + "file for " + userHandle,
                    e);
        }
    }

    /** creates the migration status file. */
    public static void createMigrationStatus(@NonNull File appSearchDir,
            @Nullable DataMigrationStats migrationStats) {
        try {
            // TODO(b/407815165) right now we just create this file
            //  to mark migration done successfully.
            //  In the future we might want to create the file
            //  earlier and put different statuses for
            //  different stages during migration.
            File icingMigrationStatus = new File(
                    appSearchDir, DATA_MIGRATION_STATUS_FILE);
            icingMigrationStatus.createNewFile();

            // Also dump the migration stats
            if (migrationStats != null) {
                IndexerSettings.writeBundle(icingMigrationStatus, migrationStats.getBundle());
            }
        } catch (IOException e) {
            Log.e(TAG,
                    "Fail to create marker file to mark "
                            + "data migration done.", e);
        }
    }

    /**
     * Wipes source Icing directory after a successful migration.
     *
     * <p>Currently everything will be wiped except for blob_dir.
     * @param icingDir
     */
    public static void wipeSourceIcingDir(@NonNull File icingDir) {
        if (icingDir.exists() && icingDir.isDirectory()) {
            File[] files = icingDir.listFiles();
            boolean all_deleted = true;
            if (files != null) {
                for (int i = 0; i < files.length; ++i) {
                    File file = files[i];
                    // blob_dir will still be used no matter which backend we are
                    // connecting. So we need to keep it.
                    if (!file.getName().equals("blob_dir")) {
                        if (!deleteDirectoryRecursively(file)) {
                            Log.e(TAG, "Data migration: failed to delete " + file.getName());
                            all_deleted = false;
                        }
                    } else {
                        all_deleted = false;
                    }
                }
            }

            // icingDir is now empty. We can just delete it.
            if (all_deleted) {
                icingDir.delete();
            }
        }
    }

    // Returns true if file/directory gets deleted. false otherwise.
    private static boolean deleteDirectoryRecursively(@NonNull File dir) {
        if (!dir.exists()) {
            return true;
        }
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (int i = 0; i < files.length; ++i) {
                    deleteDirectoryRecursively(files[i]);
                }
            }
        }
        return dir.delete();
    }

    /**
     * Copies all the data from the source to the destination.
     *
     * <p>{@code source} won't be modified by this method.
     *
     * <p>This method calls AppSearchImpl's thread-safe APIs for source.
     */
    private static StatusProto copyData(
            @NonNull AppSearchImpl source,
            @NonNull IcingSearchEngineInterface destination,
            boolean resetDestination,
            boolean forceOverride,
            @NonNull DataMigrationStats migrationStatsOut) {
        // TODO(b/407815165): Either remove this limit or make it configurable in future.
        int maxBytesPerPage =
                IsolatedStorageServiceManager.DEFAULT_MAX_PAGE_BYTES_LIMIT_FOR_ISOLATED_STORAGE;

        if (resetDestination) {
            // Clear all current data from icing instance and reinitialize it.
            ResetResultProto resetResult = destination.reset();
            migrationStatsOut.setResetStatus(resetResult.getStatus().getCode().getNumber());
            if (resetResult.getStatus().getCode() != StatusProto.Code.OK) {
                return resetResult.getStatus();
            }

            Log.i(TAG, "Destination reset during data migration successful");
        }

        // Step-1 Set schema in destination by getting schema from source.
        SchemaProto rawSchema = source.rawGetSchema();
        if (!source.useDatabaseScopedSchemaOperations()) {
            rawSchema = getSchemaProtoWithDatabase(rawSchema);
        }
        SetSchemaResultProto setSchemaResult = destination.setSchema(rawSchema, forceOverride);
        migrationStatsOut.setSetSchemaStatus(setSchemaResult.getStatus().getCode().getNumber());
        if (setSchemaResult.getStatus().getCode() != StatusProto.Code.OK) {
            return setSchemaResult.getStatus();
        }

        Log.i(TAG, "Set Schema during data migration successful");

        // Step-2 Query all documents from source using an empty query.
        //
        // TODO(b/407815165) Add exception handling for any exceptions while querying.
        //
        // TODO(b/407815165) Add logic to queue any incoming changes during copy.
        SearchSpecProto searchSpec =
                SearchSpecProto.newBuilder()
                        .setQuery("") // an empty query will return all docs.
                        .setTermMatchType(TermMatchType.Code.PREFIX)
                        .build();
        SearchResultProto searchResult =
                source.rawSearch(
                        searchSpec,
                        ScoringSpecProto.newBuilder().build(),
                        ResultSpecProto.newBuilder()
                                .setNumTotalBytesPerPageThreshold(maxBytesPerPage)
                                // median doc size is 700b.
                                // half of the maxBytesPerPage is 64_000b.
                                // so 64_000 / 700 ~= 95 results per page.
                                .setNumPerPage(95)
                                .setNumToScore(Integer.MAX_VALUE)
                                .build());
        migrationStatsOut.setQueryStatus(searchResult.getStatus().getCode().getNumber());
        if (searchResult.getStatus().getCode() != StatusProto.Code.OK) {
            return searchResult.getStatus();
        }

        Log.i(TAG, "Querying documents from source for data migration successful");

        // Step-3 Put all documents in searchResult in destination using a batchPut call.
        //
        // TODO(b/407815165) Add exception handling for any exceptions during put.
        long nextPageToken = searchResult.getNextPageToken();
        ArraySet<Integer> putStatusCodes = new ArraySet<>();
        long totalDocsSucceeded = 0L;
        long totalDocsFailed = 0L;
        while (searchResult != null && searchResult.getResultsCount() > 0) {
            PutDocumentRequest.Builder requestBuilder = PutDocumentRequest.newBuilder();
            for (int i = 0; i < searchResult.getResultsCount(); ++i) {
                requestBuilder.addDocuments(searchResult.getResults(i).getDocument());
            }
            BatchPutResultProto batchPutResult = destination.batchPut(requestBuilder.build());
            // TODO(b/407815165) Either return error if some put fails (if we cannot tolerate data
            //  loss) or save IDs for documents that failed to index in order to retry / log.
            if (batchPutResult.getStatus().getCode() != StatusProto.Code.OK) {
                Log.w(
                        TAG,
                        "Error calling batchPut during data migration "
                                + batchPutResult.getStatus().getMessage());
            }
            int count = batchPutResult.getPutResultProtosCount();
            for (int i = 0; i < count; ++i) {
                StatusProto putResult = batchPutResult.getPutResultProtos(i).getStatus();
                putStatusCodes.add(putResult.getCode().getNumber());
                if (putResult.getCode() != StatusProto.Code.OK) {
                    ++totalDocsFailed;
                } else {
                    ++totalDocsSucceeded;
                }
            }
            searchResult = source.rawGetNextPage(nextPageToken);
        }
        Log.i(TAG, "Successfully migrated " + totalDocsSucceeded + " documents");
        Log.i(TAG, "Failed to migrate " + totalDocsFailed + " documents");
        if (!putStatusCodes.isEmpty()) {
            int[] statusCodes = new int[putStatusCodes.size()];
            int i = 0;
            for (Integer code : putStatusCodes) {
                statusCodes[i++] = code;
            }
            migrationStatsOut.setPutStatus(statusCodes);
        }
        migrationStatsOut.setNumberOfDocsSucceeded(totalDocsSucceeded);
        migrationStatsOut.setNumberOfDocsFailed(totalDocsFailed);

        // Step-4 Persist the change to disk.
        PersistToDiskResultProto persistResultProto = destination.persistToDisk(
                PersistType.Code.FULL);
        migrationStatsOut.setFlushStatus(persistResultProto.getStatus().getCode().getNumber());
        if (persistResultProto.getStatus().getCode() != StatusProto.Code.OK) {
            Log.w(TAG, "PersistToDisk Full failed");
            return persistResultProto.getStatus();
        }

        migrationStatsOut.setDataMigrationStatus(StatusProto.Code.OK.getNumber());
        return StatusProto.newBuilder().setCode(StatusProto.Code.OK).build();
    }

    /**
     * Returns a new {@link SchemaProto} with the database field populated for each type in the
     * input schema.
     */
    private static SchemaProto getSchemaProtoWithDatabase(SchemaProto schema) {
        SchemaProto.Builder schemaBuilder = SchemaProto.newBuilder();
        for (int i = 0; i < schema.getTypesList().size(); i++) {
            SchemaTypeConfigProto type = schema.getTypes(i);
            String prefix = "";
            try {
                prefix = PrefixUtil.getPrefix(type.getSchemaType());
            } catch (AppSearchException e) {
                Log.w(
                        TAG,
                        "No database prefix found in schema type: "
                                + type.getSchemaType()
                                + ". This should never happen.");
            }
            SchemaTypeConfigProto.Builder typeBuilder =
                    SchemaTypeConfigProto.newBuilder(type).setDatabase(prefix);
            schemaBuilder.addTypes(typeBuilder);
        }
        return schemaBuilder.build();
    }

    /**
     * Runs data migration for the specified user to move data from {@code source} to {@code dest}.
     *
     * <p>{@code dest} must be instantiated with all VM options enabled.
     *
     * @param context User Context
     * @param userHandle User to run migration for
     * @param source {@link AppSearchImpl} instance for the source.
     * @param dest {@link IcingSearchEngineInterface} instance for the destination.
     * @throws AppSearchException if there is any failures during migration.
     */
    @NonNull
    public static DataMigrationStats runDataMigrationForUser(
            @NonNull Context context,
            @NonNull UserHandle userHandle,
            @NonNull AppSearchImpl source,
            @NonNull IcingSearchEngineInterface dest)
            throws AppSearchException {
        DataMigrationStats migrationStats = new DataMigrationStats();
        InitializeResultProto initResult = dest.initialize();
        migrationStats.setVMInitStatus(initResult.getStatus().getCode().getNumber());
        if (initResult.getStatus().getCode() != StatusProto.Code.OK) {
            // TODO(b/430610163) we can dump the migration stats for failure cases.
            throw new AppSearchException(
                    AppSearchResult.RESULT_INTERNAL_ERROR,
                    "Failed to initialize Isolated Storage Icing!"
                            + " Status code: "
                            + initResult.getStatus().getCode().getNumber());
        }

        // TODO(b/407815165) probably this should be moved to AppSearchImpl so lock can be
        //  grabbed there.
        StatusProto status =
                DataMigrationUtil.copyData(source, dest,
                        /* resetDestination= */ true,
                        /* forceOverride= */ true,
                        migrationStats);

        // TODO(b/407815165) Rework those logging and put useful information in the marker file, so
        //  dumpsys can read and print those information.
        Log.i(TAG, "Data migration status: " + status);

        if (status.getCode() != StatusProto.Code.OK) {
            // TODO(b/430610163) we can dump the migration stats for failure cases.
            throw new AppSearchException(
                    AppSearchResult.RESULT_INTERNAL_ERROR,
                    "Data migration: failed with status code: "
                            + status.getCode().getNumber()
                            + ", status message: "
                            + status.getMessage());
        }

        // Switch to the isolated instance.
        IcingSearchEngineInterface priorIcingSearchEngine = source.swapIcingSearchEngineLocked(
                dest, /*isVMEnabled=*/ true);

        File appSearchDir = AppSearchEnvironmentFactory
                .getEnvironmentInstance()
                .getAppSearchDir(context, userHandle);
        DataMigrationUtil.createMigrationStatus(appSearchDir, migrationStats);
        priorIcingSearchEngine.close();

        // Destroy the current instance.
        if (LogUtil.INFO) {
            Log.i(TAG, "Data migration: wiping source directory.");
        }
        File icingDir = new File(appSearchDir, "icing");
        DataMigrationUtil.wipeSourceIcingDir(icingDir);

        return migrationStats;
    }
}
