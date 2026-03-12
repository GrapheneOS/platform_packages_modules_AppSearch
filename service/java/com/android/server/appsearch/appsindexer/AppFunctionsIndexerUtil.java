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
import java.util.List;

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
            AppFunctionResolveInfo appFunctionResolveInfo =
                    entry.getValue().getAppFunctionResolveInfo();

            String assetFilePath =
                    getDynamicSchemaAssetPath(packageManager, packageInfo, appFunctionResolveInfo);

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
            @Nullable AppFunctionResolveInfo appFunctionResolveInfo) {
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
        if (appFunctionResolveInfo == null
                || appFunctionResolveInfo.getAppFunctionServiceResolveInfos().isEmpty()) {
            return null;
        }

        // TODO: b/468288106 - Support multiple services per package.
        ResolveInfo resolveInfo = appFunctionResolveInfo.getAppFunctionServiceResolveInfos().get(0);
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
            AppFunctionResolveInfo appFunctionResolveInfo =
                    entry.getValue().getAppFunctionResolveInfo();

            if (appFunctionResolveInfo == null) {
                continue;
            }

            // If dynamic schema is provided, use properties from those schemas. Otherwise, use the
            // one defined by platform.
            Map<String, AppSearchSchema> packageSchemas;
            if (schemasPerPackage == null
                    || schemasPerPackage
                            .getOrDefault(packageInfo.packageName, Collections.emptyMap())
                            .isEmpty()) {
                packageSchemas =
                        Map.of(
                                AppFunctionDocument.getSchemaNameForPackage(
                                        packageInfo.packageName,
                                        AppFunctionStaticMetadata.SCHEMA_TYPE),
                                AppFunctionStaticMetadata.createAppFunctionSchemaForPackage(
                                        packageInfo.packageName));
            } else {
                packageSchemas = schemasPerPackage.get(packageInfo.packageName);
            }

            Map<String, AppFunctionDocument> packageAppFunctions = new ArrayMap<>();

            List<AppFunctionResolveInfo.AppFunctionXmlInfo> appFunctionXmlInfos =
                    appFunctionResolveInfo.getAppFunctionXmlInfos(packageManager);
            for (int i = 0; i < appFunctionXmlInfos.size(); i++) {
                AppFunctionResolveInfo.AppFunctionXmlInfo appFunctionXmlInfo =
                        appFunctionXmlInfos.get(i);
                if (appFunctionXmlInfo.useSchemaForParsing()) {
                    appFunctionXmlInfo.runWithXmlParser(
                            packageManager,
                            xmlParser ->
                                    packageAppFunctions.putAll(
                                            parser.parseIntoMapForGivenSchemas(
                                                    packageManager,
                                                    packageInfo.packageName,
                                                    xmlParser,
                                                    packageSchemas,
                                                    appFunctionXmlInfo.getServiceName())));
                } else {
                    packageAppFunctions.putAll(
                            parser.parseIntoMap(
                                    packageManager,
                                    packageInfo.packageName,
                                    appFunctionXmlInfo.getXmlFile().getXmlFilePath(),
                                    appFunctionXmlInfo.getServiceName()));
                }
            }

            appFunctions.put(packageInfo.packageName, packageAppFunctions);
        }
        return appFunctions;
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
