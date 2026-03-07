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

import static com.android.server.appsearch.appsindexer.appsearchtypes.AppFunctionStaticMetadata.APPLICATION_LEVEL_SERVICE_NAME;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.util.LogUtil;
import android.content.ComponentName;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Build;
import android.util.ArrayMap;
import android.util.Log;

import com.android.server.appsearch.appsindexer.appsearchtypes.AppFunctionDocument;
import com.android.server.appsearch.appsindexer.appsearchtypes.AppFunctionStaticMetadata;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * A utility class that orchestrates the discovery and parsing of App Functions metadata from
 * installed packages.
 *
 * <p>This class acts as a high-level helper for the apps indexer. It interacts with the {@link
 * PackageManager} to locate App Functions metadata definitions within apps' manifests and
 * resources. It is responsible for handling different versions of the App Functions format:
 *
 * <ul>
 *   <li><b>Dynamic Schema (V2)</b>: Parses functions defined against custom {@link AppSearchSchema}
 *       types. It discovers these by looking for the {@code android.app.appfunctions.schema} and
 *       {@code android.app.appfunctions.v2} properties at both the application and service levels.
 *   <li><b>Legacy</b>: Parses functions defined with the original, fixed schema by looking for the
 *       {@code android.app.appfunctions} property at the service level.
 *       <p>It uses {@link AppFunctionSchemaParser} to parse schema definitions and {@link
 *       AppFunctionDocumentParser} to parse the corresponding document instances from XML.
 * </ul>
 */
public class AppFunctionsIndexerUtil {

    public static final String TAG = "AppSearchAppFunctionsIndexerUtil";

    private static final String APP_FUNCTION_V2_XML_PROPERTY_NAME = "android.app.appfunctions.v2";
    private static final String APP_FUNCTION_V1_XML_PROPERTY_NAME = "android.app.appfunctions";

    /**
     * Uses {@link PackageManager} and a Map of {@link PackageInfo}s to {@link ResolveInfos}s to
     * build AppSearch {@link GenericDocument} objects. Info from both are required to build app
     * documents.
     *
     * <p>App documents will be returned as a mapping of packages to a mapping of document ids to
     * documents. This is useful for determining what has changed during an update.
     *
     * <p>The parser will parse app function documents based on schemas if schemasPerPackage is not
     * null or the map of schemas for a package is not empty, else it will default to predefined
     * schema properties created by {@link
     * AppFunctionStaticMetadata#createAppFunctionSchemaForPackage} to create the {@link
     * AppFunctionStaticMetadata} documents.
     *
     * @param packageInfos a mapping of {@link PackageInfo}s and their corresponding {@link
     *     ResolveInfo} for the packages launch activity.
     * @param indexerPackageName the name of the package performing the indexing. This should be the
     *     same as the package running the apps indexer so that qualified ids are correctly created.
     * @param config the app indexer config used to enforce various limits during parsing.
     * @param schemasPerPackage a mapping of packages to a mapping of schema types to their
     *     corresponding {@link AppSearchSchema} objects, or null if there are no schemas to
     *     consider.
     * @return A mapping of packages to a mapping of document ids to AppFunction GenericDocuments
     *     conforming the schemas for the corresponding package.
     */
    public static Map<String, Map<String, ? extends AppFunctionDocument>>
            buildAppFunctionDocumentsIntoMap(
                    @NonNull PackageManager packageManager,
                    @NonNull Map<PackageInfo, ResolveInfos> packageInfos,
                    @NonNull String indexerPackageName,
                    AppsIndexerConfig config,
                    @Nullable Map<String, Map<String, AppSearchSchema>> schemasPerPackage) {
        AppFunctionDocumentParser parser =
                new AppFunctionDocumentParserImpl(indexerPackageName, config);
        return buildAppFunctionDocumentsIntoMap(
                packageManager, packageInfos, parser, schemasPerPackage);
    }

    /**
     * Returns whether app can declare app functions on the application level. These app functions
     * doesn't depend on presence of AppFunctionService as executed directly using AIDL.
     */
    public static boolean isAppLevelAppFunctionsEnabled() {
        // TODO(b/447127837): require at least Android C once finalised here and in the test
        return android.app.appfunctions.flags.Flags.enableDynamicAppFunctions()
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA;
    }

    /**
     * Retrieves the application-level {@link PackageManager.Property} for App Functions.
     *
     * @param packageManager The {@link PackageManager} to query.
     * @param packageName The package to inspect.
     * @return The {@link PackageManager.Property} if found, or {@code null} otherwise.
     */
    @Nullable
    public static PackageManager.Property getAppFunctionAppProperty(
            @NonNull PackageManager packageManager, @NonNull String packageName) {
        if (isAppLevelAppFunctionsEnabled()) {
            PackageManager.Property appFunctionAppProperty =
                    getProperty(packageManager, APP_FUNCTION_V2_XML_PROPERTY_NAME, packageName);

            return appFunctionAppProperty != null
                    ? appFunctionAppProperty
                    : getProperty(packageManager, APP_FUNCTION_V1_XML_PROPERTY_NAME, packageName);
        }
        return null;
    }

    /**
     * Creates dynamic app function schemas defined by the app per package.
     *
     * @param packageManager the {@link PackageManager} to use to get the schema file path.
     * @param packageInfos a mapping of {@link PackageInfo}s and their corresponding {@link
     *     ResolveInfo} for the packages launch activity.
     * @param maxAllowedAppFunctionSchemasPerPackage the max number of schema definitions allowed
     *     per package.
     * @return A mapping of packages to a mapping of schema types to their corresponding {@link
     *     AppSearchSchema} objects or an empty map for a package if there's an error during parsing
     *     or no schema file is found.
     */
    @NonNull
    public static Map<String, Map<String, AppSearchSchema>> getDynamicAppFunctionSchemasForPackages(
            @NonNull PackageManager packageManager,
            @NonNull Map<PackageInfo, ResolveInfos> packageInfos,
            int maxAllowedAppFunctionSchemasPerPackage) {
        Objects.requireNonNull(packageInfos);

        Map<String, Map<String, AppSearchSchema>> schemasPerPackage = new ArrayMap<>();
        AppFunctionSchemaParser parser =
                new AppFunctionSchemaParser(maxAllowedAppFunctionSchemasPerPackage);
        for (Map.Entry<PackageInfo, ResolveInfos> entry : packageInfos.entrySet()) {
            PackageInfo packageInfo = entry.getKey();
            ResolveInfo resolveInfo = entry.getValue().getAppFunctionServiceInfo();

            String assetFilePath =
                    getDynamicSchemaAssetPath(packageManager, packageInfo, resolveInfo);

            String packageName = packageInfo.packageName;
            if (assetFilePath != null) {
                schemasPerPackage.put(
                        packageName,
                        parser.parseAndCreateSchemas(packageManager, packageName, assetFilePath));
            } else {
                schemasPerPackage.put(packageName, Collections.emptyMap());
            }
        }

        return schemasPerPackage;
    }

    /**
     * Retrieves the asset path for the dynamic app function schema from package properties.
     *
     * <p>It first checks for an application-level property, and if not found, falls back to a
     * service-level property.
     *
     * @return The asset file path as a string, or {@code null} if not found.
     */
    @Nullable
    private static String getDynamicSchemaAssetPath(
            @NonNull PackageManager packageManager,
            @NonNull PackageInfo packageInfo,
            @Nullable ResolveInfo resolveInfo) {
        final String dynamicSchemaPropertyName = "android.app.appfunctions.schema";
        final String packageName = packageInfo.packageName;

        // First, try to get the schema path from the application-level property.
        if (isAppLevelAppFunctionsEnabled()) {
            PackageManager.Property property =
                    getProperty(packageManager, dynamicSchemaPropertyName, packageName);
            if (property != null && property.isString()) {
                return property.getString();
            }
        }

        // If not found at the application level, fall back to the service-level property.
        if (resolveInfo == null) {
            return null;
        }
        ComponentName serviceComponent =
                new ComponentName(
                        resolveInfo.serviceInfo.packageName, resolveInfo.serviceInfo.name);
        PackageManager.Property property =
                getProperty(packageManager, dynamicSchemaPropertyName, serviceComponent);
        if (property != null && property.isString()) {
            return property.getString();
        }

        return null;
    }

    /**
     * Similar to the above {@link #buildAppFunctionDocumentsIntoMap(PackageManager, Map, String,
     * AppsIndexerConfig, Map)}, but allows the caller to provide a custom parser. This is for
     * testing purposes.
     *
     * @see #buildAppFunctionDocumentsIntoMap(PackageManager, Map, String, AppsIndexerConfig, Map)
     */
    private static Map<String, Map<String, ? extends AppFunctionDocument>>
            buildAppFunctionDocumentsIntoMap(
                    @NonNull PackageManager packageManager,
                    @NonNull Map<PackageInfo, ResolveInfos> packageInfos,
                    @NonNull AppFunctionDocumentParser parser,
                    @Nullable Map<String, Map<String, AppSearchSchema>> schemasPerPackage) {
        Objects.requireNonNull(packageManager);
        Objects.requireNonNull(packageInfos);
        Objects.requireNonNull(parser);
        Map<String, Map<String, ? extends AppFunctionDocument>> appFunctions = new ArrayMap<>();
        for (Map.Entry<PackageInfo, ResolveInfos> entry : packageInfos.entrySet()) {
            PackageInfo packageInfo = entry.getKey();
            ResolveInfo serviceResolveInfo = entry.getValue().getAppFunctionServiceInfo();
            PackageManager.Property appFunctionAppLevelProperty =
                    entry.getValue().getAppFunctionAppLevelProperty();
            String packageName = packageInfo.packageName;

            boolean isDynamicSchemaDefined =
                    schemasPerPackage != null
                            && !schemasPerPackage
                                    .getOrDefault(packageName, Collections.emptyMap())
                                    .isEmpty();

            Map<String, AppFunctionDocument> packageAppFunctions = new ArrayMap<>();

            // Handle the base case where there's no dynamic schema and it's below A17.
            if (!isDynamicSchemaDefined && !isAppLevelAppFunctionsEnabled()
                    && serviceResolveInfo != null) {
                parseServiceAppFunctionsUsingXmlTags(
                        packageManager,
                        packageName,
                        serviceResolveInfo,
                        parser,
                        packageAppFunctions);
                appFunctions.put(packageName, packageAppFunctions);
                continue;
            }

            // Above A17 or when dynamic schema is defined, the XML will be parsed based on the
            // schema properties.
            // If dynamic schema is provided, use properties from those schemas. Otherwise, use the
            // one defined by platform.
            Map<String, AppSearchSchema> schemas =
                    isDynamicSchemaDefined
                            ? schemasPerPackage.get(packageName)
                            : Map.of(
                                    AppFunctionDocument.getSchemaNameForPackage(
                                            packageName, AppFunctionStaticMetadata.SCHEMA_TYPE),
                                    AppFunctionStaticMetadata.createAppFunctionSchemaForPackage(
                                            packageName));

            // Try to get property from service component
            if (serviceResolveInfo != null) {
                parseServiceAppFunctionsBasedOnSchema(
                        packageManager,
                        packageName,
                        serviceResolveInfo,
                        parser,
                        packageAppFunctions,
                        schemas);
            }

            // Try to get property from application component
            if (isAppLevelAppFunctionsEnabled()) {
                parseApplicationAppFunctionsBasedOnSchema(
                        packageManager,
                        packageName,
                        parser,
                        packageAppFunctions,
                        schemas,
                        appFunctionAppLevelProperty);
            }

            appFunctions.put(packageName, packageAppFunctions);
        }
        return appFunctions;
    }

    /**
     * Parses and adds app functions from XML specified by the property (android.app.appfunctions)
     * to the appFunctions map, using hardcoded XML tags.
     */
    private static void parseServiceAppFunctionsUsingXmlTags(
            @NonNull PackageManager packageManager,
            @NonNull String packageName,
            @NonNull ResolveInfo resolveInfo,
            @NonNull AppFunctionDocumentParser parser,
            @NonNull Map<String, AppFunctionDocument> packageAppFunctions) {
        PackageManager.Property xmlProperty =
                getProperty(
                        packageManager,
                        APP_FUNCTION_V1_XML_PROPERTY_NAME,
                        new ComponentName(
                                resolveInfo.serviceInfo.packageName, resolveInfo.serviceInfo.name));
        if (xmlProperty != null && xmlProperty.isString()) {
            packageAppFunctions.putAll(
                    parser.parseIntoMap(
                            packageManager,
                            packageName,
                            Objects.requireNonNull(xmlProperty.getString()),
                            resolveInfo.serviceInfo.name));
        }
    }

    private static void parseServiceAppFunctionsBasedOnSchema(
            @NonNull PackageManager packageManager,
            @NonNull String packageName,
            @NonNull ResolveInfo resolveInfo,
            @NonNull AppFunctionDocumentParser parser,
            @NonNull Map<String, AppFunctionDocument> packageAppFunctions,
            @Nullable Map<String, AppSearchSchema> schemas) {
        PackageManager.Property v2XmlProperty =
                getProperty(
                        packageManager,
                        APP_FUNCTION_V2_XML_PROPERTY_NAME,
                        new ComponentName(
                                resolveInfo.serviceInfo.packageName, resolveInfo.serviceInfo.name));

        PackageManager.Property serviceXmlProperty =
                v2XmlProperty != null
                        ? v2XmlProperty
                        : getProperty(
                                packageManager,
                                APP_FUNCTION_V1_XML_PROPERTY_NAME,
                                new ComponentName(
                                        resolveInfo.serviceInfo.packageName,
                                        resolveInfo.serviceInfo.name));
        if (serviceXmlProperty == null) {
            return;
        }

        XmlPullParser xmlPullParser =
                createXmlParser(packageName, serviceXmlProperty, packageManager);
        if (xmlPullParser != null) {
            packageAppFunctions.putAll(
                    parser.parseIntoMapForGivenSchemas(
                            packageManager,
                            packageName,
                            xmlPullParser,
                            schemas,
                            resolveInfo.serviceInfo.name));
        }
    }

    private static void parseApplicationAppFunctionsBasedOnSchema(
            @NonNull PackageManager packageManager,
            @NonNull String packageName,
            @NonNull AppFunctionDocumentParser parser,
            @NonNull Map<String, AppFunctionDocument> packageAppFunctions,
            @Nullable Map<String, AppSearchSchema> schemas,
            @Nullable PackageManager.Property appProperty) {
        if (appProperty == null) {
            return;
        }

        if (appProperty.isString()) {
            // For app-level string properties, we treat them as a comma-separated list of asset
            // paths.
            String[] assetPaths = Objects.requireNonNull(appProperty.getString()).split(",");
            for (String assetPath : assetPaths) {
                String trimmedAssetPath = assetPath.trim();
                if (trimmedAssetPath.isEmpty()) {
                    continue;
                }
                XmlPullParser xmlPullParser =
                        createXmlParserFromAsset(packageName, assetPath, packageManager);
                if (xmlPullParser != null) {
                    packageAppFunctions.putAll(
                            parser.parseIntoMapForGivenSchemas(
                                    packageManager,
                                    packageName,
                                    xmlPullParser,
                                    schemas,
                                    APPLICATION_LEVEL_SERVICE_NAME));
                }
            }
        } else { // isResourceId()
            XmlPullParser xmlPullParser = createXmlParser(packageName, appProperty, packageManager);
            if (xmlPullParser != null) {
                packageAppFunctions.putAll(
                        parser.parseIntoMapForGivenSchemas(
                                packageManager,
                                packageName,
                                xmlPullParser,
                                schemas,
                                APPLICATION_LEVEL_SERVICE_NAME));
            }
        }
    }

    @Nullable
    private static XmlPullParser createXmlParserFromAsset(
            @NonNull String packageName,
            @NonNull String assetPath,
            @NonNull PackageManager packageManager) {
        try {
            Resources resources = packageManager.getResourcesForApplication(packageName);
            AssetManager assetManager = resources.getAssets();
            XmlPullParser xmlPullParser = XmlPullParserFactory.newInstance().newPullParser();
            xmlPullParser.setInput(new InputStreamReader(assetManager.open(assetPath)));
            return xmlPullParser;
        } catch (PackageManager.NameNotFoundException | XmlPullParserException | IOException e) {
            Log.w(
                    TAG,
                    "Failed to parse dynamic XML from asset: " + assetPath + " for: " + packageName,
                    e);
            return null;
        }
    }

    @Nullable
    private static XmlPullParser createXmlParser(
            @NonNull String packageName,
            @Nullable PackageManager.Property xmlProperty,
            @NonNull PackageManager packageManager) {
        if (xmlProperty == null) {
            return null;
        }
        try {
            if (xmlProperty.isResourceId()) {
                Resources resources = packageManager.getResourcesForApplication(packageName);
                return resources.getXml(xmlProperty.getResourceId());
            } else if (xmlProperty.isString()) {
                return createXmlParserFromAsset(
                        packageName,
                        Objects.requireNonNull(xmlProperty.getString()),
                        packageManager);
            } else {
                return null;
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Failed to parse dynamic XML for: " + packageName, e);
            return null;
        }
    }

    @Nullable
    private static PackageManager.Property getProperty(
            @NonNull PackageManager packageManager,
            @NonNull String propertyName,
            @NonNull String packageName) {
        try {
            return packageManager.getProperty(propertyName, packageName);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    @Nullable
    private static PackageManager.Property getProperty(
            @NonNull PackageManager packageManager,
            @NonNull String propertyName,
            @NonNull ComponentName componentName) {
        try {
            return packageManager.getProperty(propertyName, componentName);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }
}
