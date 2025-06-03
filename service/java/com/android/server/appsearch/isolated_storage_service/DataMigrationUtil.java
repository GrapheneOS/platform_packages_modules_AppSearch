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
import android.app.appsearch.AppSearchEnvironmentFactory;
import android.content.Context;
import android.os.UserHandle;
import android.util.Log;

import com.android.server.appsearch.ServiceAppSearchConfig;
import com.android.server.appsearch.external.localstorage.AppSearchImpl;

import com.google.android.icing.IcingSearchEngineInterface;
import com.google.android.icing.proto.BatchPutResultProto;
import com.google.android.icing.proto.PutDocumentRequest;
import com.google.android.icing.proto.ResetResultProto;
import com.google.android.icing.proto.ResultSpecProto;
import com.google.android.icing.proto.ScoringSpecProto;
import com.google.android.icing.proto.SearchResultProto;
import com.google.android.icing.proto.SearchSpecProto;
import com.google.android.icing.proto.SetSchemaResultProto;
import com.google.android.icing.proto.StatusProto;
import com.google.android.icing.proto.TermMatchType;

import java.io.File;

/**
 * Utils to migrate data from AppSearch to IsolatedStorage.
 *
 * @hide
 */
public class DataMigrationUtil {

    private DataMigrationUtil() {}

    private static final String TAG = "IcingDataMigration";

    /** Checks if data migration is needed from AppSearch to Isolated Storage. */
    // TODO(b/407815165) Right now just check if the icing/version on host exists
    //  We can persist a file to save the migration status, so dir deletion could happen later.
    //
    // TODO(b/407815165) Right now we are checking for USE_ISOLATED_STORAGE flag, later this can be
    // replaced by checking DATA_MIGRATION_TO_ISOLATED_STORAGE_ENABLED flag as well.
    public static boolean needDataMigration(
            @NonNull Context userContext,
            @NonNull UserHandle userHandle,
            @NonNull ServiceAppSearchConfig config) {
        if (!IsolatedStorageServiceManager.useIsolatedStorage(userContext, config)) {
            return false;
        }

        File appSearchDir =
                AppSearchEnvironmentFactory.getEnvironmentInstance()
                        .getAppSearchDir(userContext, userHandle);
        File icingDir = new File(appSearchDir, "icing/version");

        return icingDir.exists();
    }

    /**
     * Copies all the data from the source to the destination.
     *
     * <p>{@code source} won't be modified by this method.
     *
     * <p>This method calls AppSearchImpl's thread-safe APIs for source.
     */
    public static StatusProto copyData(
            @NonNull AppSearchImpl source,
            @NonNull IcingSearchEngineInterface destination,
            boolean resetDestination,
            boolean forceOverride) {
        // TODO(b/407815165): Either remove this limit or make it configurable in future.
        int maxBytesPerPage =
                IsolatedStorageServiceManager.DEFAULT_MAX_PAGE_BYTES_LIMIT_FOR_ISOLATED_STORAGE;

        if (resetDestination) {
            // Clear all current data from icing instance and reinitialize it.
            ResetResultProto resetResult = destination.reset();
            if (resetResult.getStatus().getCode() != StatusProto.Code.OK) {
                return resetResult.getStatus();
            }

            Log.i(TAG, "Destination reset during data migration successful");
        }

        // Step-1 Set schema in destination by getting schema from source.
        SetSchemaResultProto setSchemaResult =
                destination.setSchema(source.rawGetSchema(), forceOverride);
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
                                // half of the maxBytesPerPage is 250_000.
                                // so 250_000 / 700 = 350 results per page.
                                .setNumPerPage(350)
                                .build());
        if (searchResult.getStatus().getCode() != StatusProto.Code.OK) {
            return searchResult.getStatus();
        }

        Log.i(TAG, "Querying documents from source for data migration successful");

        // Step-3 Put all documents in searchResult in destination using a batchPut call.
        //
        // TODO(b/407815165) Add exception handling for any exceptions during put.
        long nextPageToken = searchResult.getNextPageToken();
        long totalDocuments = 0L;
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
            totalDocuments += searchResult.getResultsCount();
            searchResult = source.rawGetNextPage(nextPageToken);
        }
        Log.i(TAG, "Successfully migrated " + totalDocuments + " documents");

        return StatusProto.newBuilder().setCode(StatusProto.Code.OK).build();
    }
}
