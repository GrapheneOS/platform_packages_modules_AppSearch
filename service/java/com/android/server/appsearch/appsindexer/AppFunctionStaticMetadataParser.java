package com.android.server.appsearch.appsindexer;

import android.annotation.NonNull;
import android.content.pm.PackageManager;

import com.android.server.appsearch.appsindexer.appsearchtypes.AppFunctionStaticMetadata;

import java.util.List;
import java.util.Map;

/**
 * This class parses static metadata about App Functions from an XML file located within an app's
 * assets.
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
}
