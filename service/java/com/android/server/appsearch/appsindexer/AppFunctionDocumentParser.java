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
package com.android.server.appsearch.appsindexer;

import android.annotation.NonNull;
import android.app.appsearch.AppSearchSchema;
import android.content.pm.PackageManager;

import com.android.server.appsearch.appsindexer.appsearchtypes.AppFunctionDocument;
import com.android.server.appsearch.appsindexer.appsearchtypes.AppFunctionStaticMetadata;

import org.xmlpull.v1.XmlPullParser;

import java.util.Map;

/**
 * This class parses static metadata about App Functions from an XML file located within an app's
 * assets.
 *
 * <p>The generated {@link android.app.appsearch.GenericDocument} objects are inserted into
 * AppSearch after a successful {@link SyncAppSearchSession#setSchema} call under the {@link
 * AppSearchHelper#APP_DATABASE} database. Within the database, each {@link AppSearchSchema} is
 * named dynamically to be unique to the app package name.
 */
public interface AppFunctionDocumentParser {
    /**
     * Parses static metadata about App Functions from the given XML asset file.
     *
     * @param packageManager The PackageManager used to access app resources.
     * @param packageName The package name of the app whose assets contain the XML file.
     * @param assetFilePath The path to the XML file within the app's assets.
     * @param serviceName the full name of the service class under which app functions are declared
     *     or {@link AppFunctionStaticMetadata#APPLICATION_LEVEL_SERVICE_NAME} if app functions are
     *     declared on the application level.
     * @return A mapping of document ids to their corresponding {@link AppFunctionStaticMetadata}
     *     objects representing the parsed App Functions. An empty map is returned if there's an
     *     error during parsing.
     */
    @NonNull
    Map<String, AppFunctionStaticMetadata> parseIntoMap(
            @NonNull PackageManager packageManager,
            @NonNull String packageName,
            @NonNull String assetFilePath,
            @NonNull String serviceName);

    /**
     * Parses metadata about App Functions from the given XML parser, using type information from
     * the given schemas.
     *
     * <p>Note: The following requirements must be met for successful parsing:
     *
     * <ul>
     *   <li>Each document must contain an `id` tag; otherwise, an empty map is returned.
     *   <li>Root level schemas should always define `packageName` and
     *       `mobileApplicationQualifiedId` for easy retrievals.
     * </ul>
     *
     * @param packageManager PackageManager used to access app resources.
     * @param packageName the package name of the app for which app functions are being indexed.
     * @param xmlParser an {@link XmlPullParser} instance that can be used to read the app function
     *     documents serialized as XML in the app's APK.
     * @param schemas the mapping of schema types to their corresponding {@link AppSearchSchema}
     *     objects.
     * @param serviceName the full name of the service class under which app functions are declared
     *     or {@link AppFunctionStaticMetadata#APPLICATION_LEVEL_SERVICE_NAME} if app functions are
     *     declared on the application level.
     * @return a mapping of document IDs to their corresponding {@link AppFunctionDocument} objects.
     *     The returned document's schema type will match one of the provided schemas. Returns an
     *     empty map if there's an error during parsing or if no `id` tags are found.
     */
    @NonNull
    Map<String, AppFunctionDocument> parseIntoMapForGivenSchemas(
            @NonNull PackageManager packageManager,
            @NonNull String packageName,
            @NonNull XmlPullParser xmlParser,
            @NonNull Map<String, AppSearchSchema> schemas,
            @NonNull String serviceName);
}
