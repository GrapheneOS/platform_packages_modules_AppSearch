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
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.AssetManager;
import android.util.ArrayMap;

import com.android.server.appsearch.appsindexer.appsearchtypes.AppFunctionStaticMetadata;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.Objects;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

/**
 * This class parses the XSD file from an app's assets and creates AppSearch schemas from document
 * types.
 */
public class AppFunctionSchemaParser {
    private static final String TAG = "AppSearchSchemaParser";
    private static final String TAG_DOCUMENT_TYPE = "xs:documentType";
    private static final String TAG_ELEMENT = "xs:element";
    private static final String APPFN_NAMESPACE_PREFIX = "appfn:";
    private static final String TAG_STRING_TYPE = "xs:string";
    private static final String TAG_LONG_TYPE = "xs:long";
    private static final String TAG_INT_TYPE = "xs:int";
    private static final String TAG_BOOLEAN_TYPE = "xs:boolean";
    private static final String ATTRIBUTE_INDEXING_TYPE = "indexingType";
    private static final String ATTRIBUTE_TOKENIZER_TYPE = "tokenizerType";
    private static final String ATTRIBUTE_CARDINALITY = "cardinality";
    private static final String ATTRIBUTE_SHOULD_INDEX_NESTED_PROPERTIES =
            "shouldIndexNestedProperties";
    private static final String ATTRIBUTE_NAME = "name";
    private static final String ATTRIBUTE_TYPE = "type";

    /**
     * The maximum number of document types allowed in the XSD file. This is to prevent malicious
     * apps from creating too many schema types in AppSearch by modifying the XSD file defined in
     * App Functions SDK.
     */
    private final int mMaxAllowedDocumentType;

    /**
     * @param maxAllowedDocumentType The maximum number of document types allowed in the XSD file.
     *     This is to prevent malicious apps from creating too many schema types in AppSearch by
     *     modifying the XSD file defined in App Functions SDK.
     */
    public AppFunctionSchemaParser(int maxAllowedDocumentType) {
        mMaxAllowedDocumentType = maxAllowedDocumentType;
    }

    /**
     * Parses the XSD and create AppSearch schemas from document types.
     *
     * <p>The schema output isn't guaranteed to have valid dependencies, which can be caught during
     * a {@link SyncAppSearchSession#setSchema} call, however the parser will throw an exception if
     * the number of document types exceeds the maximum allowed or illegal types are encountered.
     *
     * @param packageManager The PackageManager used to access app resources.
     * @param packageName The package name of the app whose assets contain the XSD file.
     * @param assetFilePath The path to the XSD file within the app's assets.
     * @return A mapping of schema types to their corresponding {@link AppSearchSchema} objects.
     */
    @NonNull
    public Map<String, AppSearchSchema> parseAndCreateSchemas(
            @NonNull PackageManager packageManager,
            @NonNull String packageName,
            @NonNull String assetFilePath)
            throws XmlPullParserException, NameNotFoundException, IOException {
        Objects.requireNonNull(packageManager);
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(assetFilePath);

        AssetManager assetManager =
                packageManager.getResourcesForApplication(packageName).getAssets();
        InputStream xsdInputStream = assetManager.open(assetFilePath);
        return parseDocumentTypeAndCreateSchemas(xsdInputStream);
    }

    private Map<String, AppSearchSchema> parseDocumentTypeAndCreateSchemas(
            @NonNull InputStream xsdInputStream) throws XmlPullParserException, IOException {
        Objects.requireNonNull(xsdInputStream);

        Map<String, AppSearchSchema> schemas = new ArrayMap<>();
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        XmlPullParser parser = factory.newPullParser();
        parser.setInput(xsdInputStream, null);

        AppSearchSchema.Builder schemaBuilder = null;

        while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
            switch (parser.getEventType()) {
                case XmlPullParser.START_TAG:
                    if (TAG_DOCUMENT_TYPE.equals(parser.getName())) {
                        if (schemas.size() >= mMaxAllowedDocumentType) {
                            throw new IllegalStateException(
                                    "Exceeded max allowed document types: "
                                            + mMaxAllowedDocumentType);
                        }

                        String documentTypeName = parser.getAttributeValue(null, ATTRIBUTE_NAME);
                        if (documentTypeName != null) {
                            schemaBuilder = new AppSearchSchema.Builder(documentTypeName);
                        }
                    } else if (TAG_ELEMENT.equals(parser.getName()) && schemaBuilder != null) {
                        AppSearchSchema.PropertyConfig propertyConfig =
                                computePropertyConfigFromXsdType(parser);
                        if (propertyConfig != null) schemaBuilder.addProperty(propertyConfig);
                    }
                    break;

                case XmlPullParser.END_TAG:
                    if (TAG_DOCUMENT_TYPE.equals(parser.getName())) {
                        if (schemaBuilder != null) {
                            AppSearchSchema schema = schemaBuilder.build();
                            schemas.put(schema.getSchemaType(), schema);
                            schemaBuilder = null;
                        }
                    }
                    break;
            }
            parser.next();
        }

        return schemas;
    }

    private static PropertyConfig computePropertyConfigFromXsdType(@NonNull XmlPullParser parser)
            throws XmlPullParserException, IOException {
        Objects.requireNonNull(parser);

        String name = parser.getAttributeValue(null, ATTRIBUTE_NAME);
        String type = parser.getAttributeValue(null, ATTRIBUTE_TYPE);

        if (name == null || type == null) return null;

        int cardinality =
                getAttributeIntOrDefault(
                        parser, ATTRIBUTE_CARDINALITY, PropertyConfig.CARDINALITY_OPTIONAL);

        switch (type) {
            case TAG_STRING_TYPE:
                return new StringPropertyConfig.Builder(name)
                        .setCardinality(cardinality)
                        .setIndexingType(
                                getAttributeIntOrDefault(
                                        parser,
                                        ATTRIBUTE_INDEXING_TYPE,
                                        StringPropertyConfig.INDEXING_TYPE_NONE))
                        .setTokenizerType(
                                getAttributeIntOrDefault(
                                        parser,
                                        ATTRIBUTE_TOKENIZER_TYPE,
                                        StringPropertyConfig.TOKENIZER_TYPE_NONE))
                        .build();
            case TAG_LONG_TYPE:
            case TAG_INT_TYPE:
                return new LongPropertyConfig.Builder(name)
                        .setCardinality(cardinality)
                        .setIndexingType(
                                getAttributeIntOrDefault(
                                        parser,
                                        ATTRIBUTE_INDEXING_TYPE,
                                        LongPropertyConfig.INDEXING_TYPE_NONE))
                        .build();
            case TAG_BOOLEAN_TYPE:
                return new BooleanPropertyConfig.Builder(name).setCardinality(cardinality).build();
            default:
                if (type.contains(APPFN_NAMESPACE_PREFIX)) {
                    String localType = type.substring(type.indexOf(':') + 1);
                    return new AppSearchSchema.DocumentPropertyConfig.Builder(name, localType)
                            .setCardinality(cardinality)
                            .setShouldIndexNestedProperties(
                                    getAttributeBoolOrDefault(
                                            parser, ATTRIBUTE_SHOULD_INDEX_NESTED_PROPERTIES, true))
                            .build();
                }
                throw new IllegalArgumentException("Unsupported type: " + type);
        }
    }

    private static boolean getAttributeBoolOrDefault(
            @NonNull XmlPullParser parser, @NonNull String attributeName, boolean defaultValue) {
        Objects.requireNonNull(parser);
        Objects.requireNonNull(attributeName);

        String value = parser.getAttributeValue(null, attributeName);
        return value == null ? defaultValue : Boolean.parseBoolean(value);
    }

    private static int getAttributeIntOrDefault(
            @NonNull XmlPullParser parser, @NonNull String attributeName, int defaultValue) {
        Objects.requireNonNull(parser);
        Objects.requireNonNull(attributeName);

        String value = parser.getAttributeValue(null, attributeName);
        return value == null ? defaultValue : Integer.parseInt(value);
    }
}
