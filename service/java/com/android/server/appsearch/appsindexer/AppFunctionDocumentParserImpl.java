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
import android.app.appsearch.AppSearchSchema.DocumentPropertyConfig;
import android.app.appsearch.AppSearchSchema.PropertyConfig;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.util.LogUtil;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.util.ArrayMap;
import android.util.Log;

import com.android.server.appsearch.appsindexer.appsearchtypes.AppFunctionDocument;
import com.android.server.appsearch.appsindexer.appsearchtypes.AppFunctionStaticMetadata;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * This class parses static metadata about App Functions from an XML file located within an app's
 * assets.
 */
public class AppFunctionDocumentParserImpl implements AppFunctionDocumentParser {
    private static final String TAG = "AppSearchMetadataParser";
    private static final String XML_TAG_APPFUNCTION = "appfunction";
    private static final String XML_TAG_APPFUNCTIONS_ROOT = "appfunctions";
    private static final String XML_TAG_ID = "id";
    private static final String SNAKE_CASE_SEPARATOR = "_";

    @NonNull private final String mIndexerPackageName;
    private final int mMaxAppFunctions;

    /**
     * @param indexerPackageName the name of the package performing the indexing. This should be the
     *     same as the package running the apps indexer.
     * @param config the app indexer config used to enforce various limits during parsing.
     */
    public AppFunctionDocumentParserImpl(
            @NonNull String indexerPackageName, AppsIndexerConfig config) {
        mIndexerPackageName = Objects.requireNonNull(indexerPackageName);
        mMaxAppFunctions = config.getMaxAppFunctionsPerPackage();
    }

    // TODO(b/367410454): Remove this method once enable_apps_indexer_incremental_put flag is
    //  rolled out
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
            return parseAppFunctions(
                    initializeParser(packageManager, packageName, assetFilePath), packageName);
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

    @NonNull
    @Override
    public Map<String, AppFunctionStaticMetadata> parseIntoMap(
            @NonNull PackageManager packageManager,
            @NonNull String packageName,
            @NonNull String assetFilePath) {
        Objects.requireNonNull(packageManager);
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(assetFilePath);
        try {
            return parseAppFunctionsIntoMap(
                    initializeParser(packageManager, packageName, assetFilePath), packageName);
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
        return Collections.emptyMap();
    }

    /**
     * Initializes an {@link XmlPullParser} to parse xml based on the packageName and assetFilePath.
     */
    @NonNull
    private XmlPullParser initializeParser(
            @NonNull PackageManager packageManager,
            @NonNull String packageName,
            @NonNull String assetFilePath)
            throws XmlPullParserException, PackageManager.NameNotFoundException, IOException {
        Objects.requireNonNull(packageManager);
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(assetFilePath);
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        factory.setNamespaceAware(true);
        XmlPullParser parser = factory.newPullParser();
        AssetManager assetManager =
                packageManager.getResourcesForApplication(packageName).getAssets();
        parser.setInput(new InputStreamReader(assetManager.open(assetFilePath)));
        return parser;
    }

    // TODO(b/367410454): Remove this method once enable_apps_indexer_incremental_put flag is
    //  rolled out
    /**
     * Parses a sequence of `appfunction` elements from the XML into a list of {@link
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
            if (eventType == XmlPullParser.START_TAG && XML_TAG_APPFUNCTION.equals(tagName)) {
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
     * Parses a sequence of `appfunction` elements from the XML into a map of function ids to their
     * corresponding {@link AppFunctionStaticMetadata}.
     *
     * @param parser the XmlPullParser positioned at the start of the xml file
     */
    @NonNull
    private Map<String, AppFunctionStaticMetadata> parseAppFunctionsIntoMap(
            @NonNull XmlPullParser parser, @NonNull String packageName)
            throws XmlPullParserException, IOException {
        Map<String, AppFunctionStaticMetadata> appFunctions = new ArrayMap<>();

        int eventType = parser.getEventType();

        while (eventType != XmlPullParser.END_DOCUMENT) {
            String tagName = parser.getName();
            if (eventType == XmlPullParser.START_TAG && XML_TAG_APPFUNCTION.equals(tagName)) {
                AppFunctionStaticMetadata appFunction = parseAppFunction(parser, packageName);
                appFunctions.put(appFunction.getId(), appFunction);
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
        while (!(eventType == XmlPullParser.END_TAG
                && XML_TAG_APPFUNCTION.equals(parser.getName()))) {
            if (eventType == XmlPullParser.START_TAG
                    && !XML_TAG_APPFUNCTION.equals(parser.getName())) {
                String tagName = parser.getName();
                switch (tagName) {
                    case "function_id":
                        functionId = parser.nextText().trim();
                        break;
                    case "schema_name":
                        schemaName = parser.nextText().trim();
                        break;
                    case "schema_version":
                        schemaVersion = Long.parseLong(parser.nextText().trim());
                        break;
                    case "schema_category":
                        schemaCategory = parser.nextText().trim();
                        break;
                    case "enabled_by_default":
                        enabledByDefault = Boolean.parseBoolean(parser.nextText().trim());
                        break;
                    case "restrict_callers_with_execute_app_functions":
                        restrictCallersWithExecuteAppFunctions =
                                Boolean.parseBoolean(parser.nextText().trim());
                        break;
                    case "display_name_string_res":
                        displayNameStringRes = Integer.parseInt(parser.nextText().trim());
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

    @NonNull
    @Override
    public Map<String, AppFunctionDocument> parseIntoMapForGivenSchemas(
            @NonNull PackageManager packageManager,
            @NonNull String packageName,
            @NonNull XmlPullParser xmlPullParser,
            @NonNull Map<String, AppSearchSchema> schemas) {
        Objects.requireNonNull(packageManager);
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(xmlPullParser);
        Objects.requireNonNull(schemas);

        try {
            return parseAppFunctionsIntoMapForGivenSchemas(xmlPullParser, packageName, schemas);
        } catch (Exception ex) {
            // The code parses an XML file from another app's assets, using a broad try-catch to
            // handle potential errors since the XML structure might be unpredictable.
            Log.e(TAG, String.format("Failed to parse XML from package '%s'", packageName), ex);
        }
        return Collections.emptyMap();
    }

    @NonNull
    private Map<String, AppFunctionDocument> parseAppFunctionsIntoMapForGivenSchemas(
            @NonNull XmlPullParser parser,
            @NonNull String packageName,
            @NonNull Map<String, AppSearchSchema> schemas)
            throws XmlPullParserException, IOException {
        Objects.requireNonNull(parser);
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(schemas);

        Map<String, AppFunctionDocument> appFnMetadatas = new ArrayMap<>();

        Map<String, PropertyConfig> qualifiedPropertyNamesToPropertyConfig =
                buildQualifiedPropertyNameToPropertyConfigMap(schemas);

        int eventType = parser.getEventType();

        while (eventType != XmlPullParser.END_DOCUMENT) {
            String tagName = parser.getName();
            // In previous document formats <appfunction> XML tag was used for denoting
            // AppFunctionStaticMetadata type.
            String schemaType =
                    XML_TAG_APPFUNCTION.equals(tagName)
                            ? AppFunctionStaticMetadata.SCHEMA_TYPE
                            : tagName;
            String schemaNameForPackage =
                    AppFunctionDocument.getSchemaNameForPackage(packageName, schemaType);
            if (eventType == XmlPullParser.START_TAG && schemas.containsKey(schemaNameForPackage)) {
                // Id of the document will be set after parsing the value from xml.
                AppFunctionDocument.Builder appFnDocBuilder =
                        new AppFunctionDocument.Builder(
                                packageName, "", mIndexerPackageName, schemaType);
                buildGenericDocumentFromXmlElement(
                        parser,
                        packageName,
                        schemaNameForPackage,
                        qualifiedPropertyNamesToPropertyConfig,
                        appFnDocBuilder);

                AppFunctionDocument appFunctionDocument = appFnDocBuilder.build();
                appFnMetadatas.put(appFunctionDocument.getId(), appFunctionDocument);
                if (appFnMetadatas.size() >= mMaxAppFunctions) {
                    if (LogUtil.DEBUG) {
                        Log.d(TAG, "Exceeding the max number of app functions: " + packageName);
                    }
                    return appFnMetadatas;
                }
            }
            eventType = parser.next();
        }
        return appFnMetadatas;
    }

    /**
     * Tries to parse a single XML element and populate the {@link GenericDocument.Builder} object
     * recursively.
     *
     * <p>When this function is called the parser should point to the xml element that marks the
     * beginning of the {@link GenericDocument}, and would point to the end tag of the corresponding
     * doc once this function completes.
     *
     * @param parser the XmlPullParser positioned at the start of an XML element.
     * @param packageName the package name of the app that owns the XML element.
     * @param schemaType the type of the schema that the XML element belongs to.
     * @param qualifiedPropertyNamesToPropertyConfig the mapping of qualified property names to
     *     their corresponding {@link PropertyConfig} objects.
     * @param docBuilder {@link GenericDocument.Builder} object to populate with the data from the
     *     XML element.
     * @throws XmlPullParserException if the XML element is malformed.
     */
    private static void buildGenericDocumentFromXmlElement(
            @NonNull XmlPullParser parser,
            @NonNull String packageName,
            @NonNull String schemaType,
            @NonNull Map<String, PropertyConfig> qualifiedPropertyNamesToPropertyConfig,
            @NonNull GenericDocument.Builder docBuilder)
            throws XmlPullParserException, IOException {
        Objects.requireNonNull(parser);
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(qualifiedPropertyNamesToPropertyConfig);

        Map<String, List<String>> primitivePropertyValues = new ArrayMap<>();
        Map<String, List<GenericDocument>> nestedDocumentValues = new ArrayMap<>();
        String startTag = parser.getName();
        String currentPropertyPath;
        boolean wasDocIdSet = false;

        // Skip the current tag that marks the beginning of the current document.
        parser.next();

        while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
            switch (parser.getEventType()) {
                case XmlPullParser.START_TAG:
                    currentPropertyPath =
                            createQualifiedPropertyName(
                                    schemaType,
                                    toLowerCamelCase(parser.getName(), SNAKE_CASE_SEPARATOR));
                    PropertyConfig propertyConfig =
                            qualifiedPropertyNamesToPropertyConfig.get(currentPropertyPath);
                    if (propertyConfig instanceof DocumentPropertyConfig) {
                        String nestedSchemaType =
                                ((DocumentPropertyConfig) propertyConfig).getSchemaType();
                        GenericDocument.Builder nestedDoc =
                                new GenericDocument.Builder(
                                        AppFunctionStaticMetadata.APP_FUNCTION_NAMESPACE,
                                        "",
                                        nestedSchemaType);
                        buildGenericDocumentFromXmlElement(
                                parser,
                                packageName,
                                nestedSchemaType,
                                qualifiedPropertyNamesToPropertyConfig,
                                nestedDoc);
                        nestedDocumentValues
                                .computeIfAbsent(currentPropertyPath, k -> new ArrayList<>())
                                .add(nestedDoc.build());
                    } else if (propertyConfig != null) {
                        primitivePropertyValues
                                .computeIfAbsent(currentPropertyPath, k -> new ArrayList<>())
                                .add(parser.nextText().trim());
                    } else if (parser.getName().equals(XML_TAG_ID)) {
                        String id = parser.nextText().trim();
                        if (!id.isEmpty()) {
                            docBuilder.setId(packageName + "/" + id);
                            wasDocIdSet = true;
                        }
                    }
                    break;

                case XmlPullParser.END_TAG:
                    if (startTag.equals(parser.getName())) {
                        for (Map.Entry<String, List<String>> entry :
                                primitivePropertyValues.entrySet()) {
                            addPrimitiveProperty(
                                    docBuilder,
                                    qualifiedPropertyNamesToPropertyConfig.get(entry.getKey()),
                                    entry.getValue());
                        }
                        for (Map.Entry<String, List<GenericDocument>> entry :
                                nestedDocumentValues.entrySet()) {
                            String propertyName =
                                    qualifiedPropertyNamesToPropertyConfig
                                            .get(entry.getKey())
                                            .getName();
                            docBuilder.setPropertyDocument(
                                    propertyName, entry.getValue().toArray(new GenericDocument[0]));
                        }
                        if (!wasDocIdSet) {
                            throw new XmlPullParserException(
                                    "No id found for document of type: " + schemaType);
                        }
                        return;
                    }
                    break;
            }
            parser.next();
        }

        throw new IllegalStateException("Code should never reach here.");
    }

    /**
     * Builds a mapping of qualified property names to their corresponding {@link PropertyConfig}
     * objects.
     *
     * <p>The key is a concatenation of enclosing schema type and property name, separated by a
     * period to avoid conflicts between properties with the same name in different schemas. For
     * example, if the "Person" and "Address" schemas both have a property named "name", then the
     * qualified property names will be "Person#name" and "Address#name" respectively.
     *
     * @param schemaMap the mapping of schema types to their corresponding {@link AppSearchSchema}
     *     objects.
     * @return a {@link Map} of qualified property names to their corresponding {@link
     *     PropertyConfig} objects.
     */
    @NonNull
    private static Map<String, PropertyConfig> buildQualifiedPropertyNameToPropertyConfigMap(
            @NonNull Map<String, AppSearchSchema> schemaMap) {
        Objects.requireNonNull(schemaMap);

        Map<String, PropertyConfig> propertyMap = new ArrayMap<>();

        for (Map.Entry<String, AppSearchSchema> entry : schemaMap.entrySet()) {
            String schemaType = entry.getKey();
            AppSearchSchema schema = entry.getValue();

            List<AppSearchSchema.PropertyConfig> properties = schema.getProperties();
            for (int i = 0; i < properties.size(); i++) {
                AppSearchSchema.PropertyConfig property = properties.get(i);
                String propertyPath = createQualifiedPropertyName(schemaType, property.getName());
                propertyMap.put(propertyPath, property);
            }
        }

        return propertyMap;
    }

    /**
     * Converts a string of words separated by separator to lowerCamelCase.
     *
     * <p>Returns the same string if string doesn't contain the separator.
     */
    private static String toLowerCamelCase(@NonNull String str, @NonNull String separator) {
        if (str.isEmpty()) {
            return "";
        }

        // Return the original string if the separator is not present
        if (!str.contains(separator)) {
            return str;
        }

        StringBuilder builder = new StringBuilder(str.length());
        boolean capitalizeNext = false;

        for (int i = 0; i < str.length(); i++) {
            char currentChar = str.charAt(i);
            // skip multiple consecutive separators
            if (str.startsWith(separator, i)) {
                capitalizeNext = true;
                i += separator.length() - 1;
            } else {
                if (capitalizeNext) {
                    builder.append(Character.toUpperCase(currentChar));
                    capitalizeNext = false;
                } else {
                    builder.append(Character.toLowerCase(currentChar));
                }
            }
        }

        return builder.toString();
    }

    /**
     * Creates a qualified property name by concatenating the schema type and property name with a #
     * separator to avoid conflicts between properties with the same name in different schemas.
     */
    @NonNull
    private static String createQualifiedPropertyName(
            @NonNull String schemaType, @NonNull String propertyName) {
        return Objects.requireNonNull(schemaType) + "#" + Objects.requireNonNull(propertyName);
    }

    /**
     * Adds primitive property values to the given {@link GenericDocument.Builder} based on the
     * given {@link PropertyConfig}.
     *
     * <p>Ignores unsupported data types.
     */
    private static void addPrimitiveProperty(
            @NonNull GenericDocument.Builder builder,
            @NonNull PropertyConfig propertyConfig,
            @NonNull List<String> values) {
        Objects.requireNonNull(builder);
        Objects.requireNonNull(propertyConfig);
        Objects.requireNonNull(values);

        switch (propertyConfig.getDataType()) {
            case PropertyConfig.DATA_TYPE_BOOLEAN:
                boolean[] booleanValues = new boolean[values.size()];
                for (int i = 0; i < values.size(); i++) {
                    booleanValues[i] = Boolean.parseBoolean(values.get(i));
                }
                builder.setPropertyBoolean(propertyConfig.getName(), booleanValues);
                break;
            case PropertyConfig.DATA_TYPE_LONG:
                long[] longValues = new long[values.size()];
                for (int i = 0; i < values.size(); i++) {
                    longValues[i] = Long.parseLong(values.get(i));
                }
                builder.setPropertyLong(propertyConfig.getName(), longValues);
                break;
            case PropertyConfig.DATA_TYPE_STRING:
                builder.setPropertyString(propertyConfig.getName(), values.toArray(new String[0]));
                break;
            default:
                // fall-through
        }
    }
}
