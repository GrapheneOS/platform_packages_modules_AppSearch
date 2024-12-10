package com.android.server.appsearch.appsindexer;

import android.annotation.NonNull;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.GenericDocument;
import android.content.pm.PackageManager;

import com.android.server.appsearch.appsindexer.appsearchtypes.AppFunctionStaticMetadata;

import java.util.List;
import java.util.Map;

/**
 * This class parses static metadata about App Functions from an XML file located within an app's
 * assets.
 *
 * <p>The generated {@link AppFunctionStaticMetadata} objects are inserted into AppSearch after a
 * successful {@link SyncAppSearchSession#setSchema} call under the {@link
 * AppSearchHelper#APP_DATABASE} database. Within the database, each {@link AppSearchSchema} is
 * named dynamically to be unique to the app package name.
 */
public interface AppFunctionStaticMetadataParser {
    // TODO(b/367410454): Remove this method once enable_apps_indexer_incremental_put flag is
    //  rolled out
    /**
     * Parses static metadata about App Functions from the given XML asset file.
     *
     * @param packageManager The PackageManager used to access app resources.
     * @param packageName The package name of the app whose assets contain the XML file.
     * @param assetFilePath The path to the XML file within the app's assets.
     * @return A list of {@link AppFunctionStaticMetadata} objects representing the parsed App
     *     Functions. An empty list is returned if there's an error during parsing.
     */
    @NonNull
    List<AppFunctionStaticMetadata> parse(
            @NonNull PackageManager packageManager,
            @NonNull String packageName,
            @NonNull String assetFilePath);

    /**
     * Parses static metadata about App Functions from the given XML asset file.
     *
     * @param packageManager The PackageManager used to access app resources.
     * @param packageName The package name of the app whose assets contain the XML file.
     * @param assetFilePath The path to the XML file within the app's assets.
     * @return A mapping of function ids to their corresponding {@link AppFunctionStaticMetadata}
     *     objects representing the parsed App Functions. An empty map is returned if there's an
     *     error during parsing.
     */
    @NonNull
    Map<String, AppFunctionStaticMetadata> parseIntoMap(
            @NonNull PackageManager packageManager,
            @NonNull String packageName,
            @NonNull String assetFilePath);

    /**
     * Parses static metadata about App Functions from the given XML asset file, using type
     * information from the given schemas.
     *
     * <p>Note: The root schema should have property with name {@link
     * AppFunctionStaticMetadata#PROPERTY_FUNCTION_ID} to construct the mapping of function id to
     * {@link AppFunctionStaticMetadata} else an empty map is returned.
     *
     * @param packageManager The PackageManager used to access app resources.
     * @param packageName The package name of the app whose assets contain the XML file.
     * @param assetFilePath The path to the XML file within the app's assets.
     * @param schemas The mapping of schema types to their corresponding {@link AppSearchSchema}
     *     objects.
     * @return A mapping of function ids to their corresponding {@link AppFunctionStaticMetadata}
     *     objects. An empty map is returned if there's an error during parsing.
     */
    @NonNull
    Map<String, AppFunctionStaticMetadata> parseIntoMapForGivenSchemas(
            @NonNull PackageManager packageManager,
            @NonNull String packageName,
            @NonNull String assetFilePath,
            @NonNull Map<String, AppSearchSchema> schemas);
}
