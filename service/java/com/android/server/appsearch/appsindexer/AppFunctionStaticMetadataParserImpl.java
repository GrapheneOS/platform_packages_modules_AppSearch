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
package com.android.server.appsearch.appsindexer;

import android.annotation.NonNull;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.util.Log;

import com.android.server.appsearch.appsindexer.appsearchtypes.AppFunctionStaticMetadata;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * This class parses static metadata about App Functions from an XML file located within an app's
 * assets.
 */
public class AppFunctionStaticMetadataParserImpl implements AppFunctionStaticMetadataParser {
    private static final String TAG = "AppSearchMetadataParser";
    public static final String TAG_APPFUNCTION = "appfunction";

    @NonNull private final String mIndexerPackageName;
    private final int mMaxAppFunctions;

    /**
     * @param indexerPackageName the name of the package performing the indexing. This should be the
     *     same as the package running the apps indexer.
     * @param maxAppFunctions The maximum number of app functions to be parsed per app. The parser
     *     will stop once it exceeds the limit.
     */
    public AppFunctionStaticMetadataParserImpl(
            @NonNull String indexerPackageName, int maxAppFunctions) {
        mIndexerPackageName = Objects.requireNonNull(indexerPackageName);
        mMaxAppFunctions = maxAppFunctions;
    }

    @NonNull
    @Override
    public List<AppFunctionStaticMetadata> parse(
            @NonNull PackageManager packageManager,
            @NonNull String packageName,
            @NonNull String assetFilePath) {
        Objects.requireNonNull(packageManager);
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(assetFilePath);
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(true);
            XmlPullParser parser = factory.newPullParser();
            AssetManager assetManager =
                    packageManager.getResourcesForApplication(packageName).getAssets();
            parser.setInput(new InputStreamReader(assetManager.open(assetFilePath)));
            return parseAppFunctions(parser, packageName);
        } catch (Exception ex) {
            // The code parses an XML file from another app's assets, using a broad try-catch to
            // handle potential errors since the XML structure might be unpredictable.
            Log.e(
                    TAG,
                    String.format(
                            "Failed to parse XML from package '%s', asset file '%s'",
                            packageName, assetFilePath),
                    ex);
        }
        return Collections.emptyList();
    }

    /**
     * Parses a sequence of `appfunction` elements from the XML into an a list of {@link
     * AppFunctionStaticMetadata}.
     *
     * @param parser the XmlPullParser positioned at the start of the xml file
     */
    @NonNull
    private List<AppFunctionStaticMetadata> parseAppFunctions(
            @NonNull XmlPullParser parser, @NonNull String packageName)
            throws XmlPullParserException, IOException {
        List<AppFunctionStaticMetadata> appFunctions = new ArrayList<>();

        int eventType = parser.getEventType();

        while (eventType != XmlPullParser.END_DOCUMENT) {
            String tagName = parser.getName();
            if (eventType == XmlPullParser.START_TAG && TAG_APPFUNCTION.equals(tagName)) {
                AppFunctionStaticMetadata appFunction = parseAppFunction(parser, packageName);
                appFunctions.add(appFunction);
                if (appFunctions.size() >= mMaxAppFunctions) {
                    Log.d(TAG, "Exceeding the max number of app functions: " + packageName);
                    return appFunctions;
                }
            }
            eventType = parser.next();
        }
        return appFunctions;
    }

    /**
     * Parses a single `appfunction` element from the XML into an {@link AppFunctionStaticMetadata}
     * object.
     *
     * @param parser the XmlPullParser positioned at the start of an `appfunction` element.
     * @return an AppFunction object populated with the data from the XML.
     */
    @NonNull
    private AppFunctionStaticMetadata parseAppFunction(
            @NonNull XmlPullParser parser, @NonNull String packageName)
            throws XmlPullParserException, IOException {
        String functionId = null;
        String schemaName = null;
        Long schemaVersion = null;
        String schemaCategory = null;
        Boolean enabledByDefault = null;
        Integer displayNameStringRes = null;
        Boolean restrictCallersWithExecuteAppFunctions = null;
        int eventType = parser.getEventType();
        while (!(eventType == XmlPullParser.END_TAG && TAG_APPFUNCTION.equals(parser.getName()))) {
            if (eventType == XmlPullParser.START_TAG) {
                String tagName = parser.getName();
                switch (tagName) {
                    case "function_id":
                        functionId = parser.nextText();
                        break;
                    case "schema_name":
                        schemaName = parser.nextText();
                        break;
                    case "schema_version":
                        schemaVersion = Long.parseLong(parser.nextText());
                        break;
                    case "schema_category":
                        schemaCategory = parser.nextText();
                        break;
                    case "enabled_by_default":
                        enabledByDefault = Boolean.parseBoolean(parser.nextText());
                        break;
                    case "restrict_callers_with_execute_app_functions":
                        restrictCallersWithExecuteAppFunctions =
                                Boolean.parseBoolean(parser.nextText());
                        break;
                    case "display_name_string_res":
                        displayNameStringRes = Integer.parseInt(parser.nextText());
                        break;
                }
            }
            eventType = parser.next();
        }

        if (functionId == null) {
            throw new XmlPullParserException("parseAppFunction: Missing functionId in the xml.");
        }
        AppFunctionStaticMetadata.Builder builder =
                new AppFunctionStaticMetadata.Builder(packageName, functionId, mIndexerPackageName);
        if (schemaName != null) {
            builder.setSchemaName(schemaName);
        }
        if (schemaVersion != null) {
            builder.setSchemaVersion(schemaVersion);
        }
        if (schemaCategory != null) {
            builder.setSchemaCategory(schemaCategory);
        }
        if (enabledByDefault != null) {
            builder.setEnabledByDefault(enabledByDefault);
        }
        if (restrictCallersWithExecuteAppFunctions != null) {
            builder.setRestrictCallersWithExecuteAppFunctions(
                    restrictCallersWithExecuteAppFunctions);
        }
        if (displayNameStringRes != null) {
            builder.setDisplayNameStringRes(displayNameStringRes);
        }
        return builder.build();
    }
}
