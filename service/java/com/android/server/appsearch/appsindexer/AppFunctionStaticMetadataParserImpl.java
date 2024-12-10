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
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.AppSearchSchema.BooleanPropertyConfig;
import android.app.appsearch.AppSearchSchema.LongPropertyConfig;
import android.app.appsearch.AppSearchSchema.PropertyConfig;
import android.app.appsearch.AppSearchSchema.StringPropertyConfig;
import android.app.appsearch.AppSearchSchema.DocumentPropertyConfig;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.util.LogUtil;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.util.ArrayMap;
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
import java.util.Map;
import java.util.Objects;

/**
 * This class parses static metadata about App Functions from an XML file located within an app's
 * assets.
 */
public class AppFunctionStaticMetadataParserImpl implements AppFunctionStaticMetadataParser {
    private static final String TAG = "AppSearchMetadataParser";
    private static final String XML_TAG_APPFUNCTION = "appfunction";
    private static final String XML_TAG_APPFUNCTIONS_ROOT = "appfunctions";

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
                appFunctions.put(appFunction.getFunctionId(), appFunction);
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
                String tagValue = parser.nextText().trim();
                switch (tagName) {
                    case "function_id":
                        functionId = tagValue;
                        break;
                    case "schema_name":
                        schemaName = tagValue;
                        break;
                    case "schema_version":
                        schemaVersion = Long.parseLong(tagValue);
                        break;
                    case "schema_category":
                        schemaCategory = tagValue;
                        break;
                    case "enabled_by_default":
                        enabledByDefault = Boolean.parseBoolean(tagValue);
                        break;
                    case "restrict_callers_with_execute_app_functions":
                        restrictCallersWithExecuteAppFunctions = Boolean.parseBoolean(tagValue);
                        break;
                    case "display_name_string_res":
                        displayNameStringRes = Integer.parseInt(tagValue);
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
    public Map<String, AppFunctionStaticMetadata> parseIntoMapForGivenSchemas(
            @NonNull PackageManager packageManager,
            @NonNull String packageName,
            @NonNull String assetFilePath,
            @NonNull Map<String, AppSearchSchema> schemas) {
        Objects.requireNonNull(packageManager);
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(assetFilePath);
        Objects.requireNonNull(schemas);

        try {
            return parseAppFunctionsIntoMapForGivenSchemas(
                    initializeParser(packageManager, packageName, assetFilePath),
                    packageName,
                    schemas);
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

    @NonNull
    private Map<String, AppFunctionStaticMetadata> parseAppFunctionsIntoMapForGivenSchemas(
            @NonNull XmlPullParser parser,
            @NonNull String packageName,
            @NonNull Map<String, AppSearchSchema> schemas)
            throws XmlPullParserException, IOException {
        Objects.requireNonNull(parser);
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(schemas);

        Map<String, AppFunctionStaticMetadata> appFnMetadatas = new ArrayMap<>();

        Map<String, PropertyConfig> qualifiedPropertyNamesToPropertyConfig =
                buildQualifiedPropertyNameToPropertyConfigMap(schemas);

        int eventType = parser.getEventType();

        while (eventType != XmlPullParser.END_DOCUMENT) {
            String tagName = parser.getName();
            String schemaName =
                    AppFunctionStaticMetadata.getSchemaNameForPackage(packageName, tagName);
            if (eventType == XmlPullParser.START_TAG && schemas.containsKey(schemaName)) {
                GenericDocument appFnMetadata =
                        parseXmlElementToGenericDocument(
                                parser,
                                packageName,
                                tagName,
                                qualifiedPropertyNamesToPropertyConfig);
                if (appFnMetadata != null) {
                    appFnMetadatas.put(
                            appFnMetadata.getPropertyString(
                                    AppFunctionStaticMetadata.PROPERTY_FUNCTION_ID),
                            new AppFunctionStaticMetadata(appFnMetadata));
                } else if (!XML_TAG_APPFUNCTIONS_ROOT.equals(tagName)) {
                    throw new XmlPullParserException("Unknwown tag: " + tagName);
                }
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
     * Tries to parse a single XML element into a {@link GenericDocument} object.
     *
     * @param parser the XmlPullParser positioned at the start of an XML element.
     * @param packageName the package name of the app that owns the XML element.
     * @param schemaType the type of the schema that the XML element belongs to.
     * @param qualifiedPropertyNamesToPropertyConfig the mapping of qualified property names to
     *     their corresponding {@link PropertyConfig} objects.
     * @return a {@link GenericDocument} object populated with the data from the XML element, or
     *     null.
     * @throws XmlPullParserException if the XML element is malformed.
     */
    @NonNull
    private static GenericDocument parseXmlElementToGenericDocument(
            @NonNull XmlPullParser parser,
            @NonNull String packageName,
            @NonNull String schemaType,
            @NonNull Map<String, PropertyConfig> qualifiedPropertyNamesToPropertyConfig)
            throws XmlPullParserException, IOException {
        Objects.requireNonNull(parser);
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(schemaType);
        Objects.requireNonNull(qualifiedPropertyNamesToPropertyConfig);

        GenericDocument.Builder docBuilder =
                new GenericDocument.Builder(
                        AppFunctionStaticMetadata.APP_FUNCTION_NAMESPACE,
                        packageName + "/" + schemaType,
                        AppFunctionStaticMetadata.getSchemaNameForPackage(packageName, schemaType));

        Map<String, List<String>> primitivePropertyValues = new ArrayMap<>();
        Map<String, List<GenericDocument>> nestedDocumentValues = new ArrayMap<>();
        String startTag = parser.getName();
        String currentPropertyPath = null;

        while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
            switch (parser.getEventType()) {
                case XmlPullParser.START_TAG:
                    currentPropertyPath = createQualifiedPropertyName(schemaType, parser.getName());
                    PropertyConfig propertyConfig =
                            qualifiedPropertyNamesToPropertyConfig.get(currentPropertyPath);
                    if (propertyConfig instanceof DocumentPropertyConfig) {
                        GenericDocument nestedDoc =
                                parseXmlElementToGenericDocument(
                                        parser,
                                        packageName,
                                        getSchemaTypeWithoutPackage(
                                                ((DocumentPropertyConfig) propertyConfig)
                                                        .getSchemaType()),
                                        qualifiedPropertyNamesToPropertyConfig);
                        nestedDocumentValues
                                .computeIfAbsent(currentPropertyPath, k -> new ArrayList<>())
                                .add(nestedDoc);
                    } else if (propertyConfig != null) {
                        primitivePropertyValues
                                .computeIfAbsent(currentPropertyPath, k -> new ArrayList<>())
                                .add(parser.nextText().trim());
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
                        return docBuilder.build();
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
            String schemaType = getSchemaTypeWithoutPackage(entry.getKey());
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
     * Creates a qualified property name by concatenating the schema type and property name with a #
     * separator to avoid conflicts between properties with the same name in different schemas.
     */
    @NonNull
    private static String createQualifiedPropertyName(
            @NonNull String schemaType, @NonNull String propertyName) {
        return Objects.requireNonNull(schemaType) + "#" + Objects.requireNonNull(propertyName);
    }

    /**
     * Returns the schema type without the package name suffix.
     *
     * <p>For example, if the schema name is "Person-com.example.app", this method will return
     * "Person".
     */
    @NonNull
    private static String getSchemaTypeWithoutPackage(@NonNull String schemaName) {
        return Objects.requireNonNull(schemaName).substring(0, schemaName.indexOf('-'));
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
        }
    }
}
