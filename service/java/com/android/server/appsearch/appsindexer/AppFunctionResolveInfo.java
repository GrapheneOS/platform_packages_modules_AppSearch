/*
 * Copyright (C) 2026 The Android Open Source Project
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

import static com.android.server.appsearch.appsindexer.AppFunctionsIndexerUtil.isAppLevelAppFunctionsEnabled;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.appsearch.util.LogUtil;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.appsearch.appsindexer.appsearchtypes.AppFunctionStaticMetadata;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

/**
 * Represents the resolved information for app functions of a specific package.
 *
 * <p>This class encapsulates the resolution of both service-level and application-level app
 * function XMLs.
 */
public class AppFunctionResolveInfo {
    private static final String APP_FUNCTION_V2_XML_PROPERTY_NAME = "android.app.appfunctions.v2";
    private static final String APP_FUNCTION_V1_XML_PROPERTY_NAME = "android.app.appfunctions";
    private static final String APP_FUNCTION_SCHEMA_XML_PROPERTY_NAME =
            "android.app.appfunctions.schema";
    private static final String TAG = "AppSearchAppFunctionResolveInfo";

    private final String mPackageName;
    private final List<ResolveInfo> mAppFunctionServiceResolveInfos;
    private final PackageManager.Property mAppFunctionAppLevelXmlProperty;

    /**
     * Creates an instance of {@link AppFunctionResolveInfo}.
     *
     * @param packageName The package name of the app.
     * @param appFunctionServiceResolveInfos The list of {@link ResolveInfo} for the app function
     *     services.
     * @param appFunctionAppLevelXmlProperty The {@link PackageManager.Property} for the app-level
     *     app function XML.
     */
    @VisibleForTesting
    AppFunctionResolveInfo(
            @NonNull String packageName,
            @NonNull List<ResolveInfo> appFunctionServiceResolveInfos,
            @Nullable PackageManager.Property appFunctionAppLevelXmlProperty) {
        mPackageName = packageName;
        mAppFunctionServiceResolveInfos = appFunctionServiceResolveInfos;
        mAppFunctionAppLevelXmlProperty = appFunctionAppLevelXmlProperty;
    }

    /**
     * Creates an {@link AppFunctionResolveInfo} instance.
     *
     * @param packageManager The {@link PackageManager}.
     * @param packageName The package name of the app.
     * @param appFunctionServiceResolveInfos The list of {@link ResolveInfo} for the app function
     *     services.
     * @return An {@link AppFunctionResolveInfo} instance, or {@code null} if there are no app
     *     function definitions in the given package.
     */
    @Nullable
    public static AppFunctionResolveInfo create(
            @NonNull PackageManager packageManager,
            @NonNull String packageName,
            @NonNull List<ResolveInfo> appFunctionServiceResolveInfos) {
        PackageManager.Property appFunctionAppLevelXmlProperty = null;
        if (isAppLevelAppFunctionsEnabled()) {
            appFunctionAppLevelXmlProperty =
                    getAppFunctionAppLevelXmlProperty(packageManager, packageName);
        }

        if (appFunctionServiceResolveInfos.isEmpty() && appFunctionAppLevelXmlProperty == null) {
            return null;
        }
        return new AppFunctionResolveInfo(
                packageName, appFunctionServiceResolveInfos, appFunctionAppLevelXmlProperty);
    }

    private static PackageManager.Property getAppFunctionAppLevelXmlProperty(
            PackageManager packageManager, String packageName) {
        PackageManager.Property v2XmlProperty = null;

        try {
            v2XmlProperty =
                    packageManager.getProperty(APP_FUNCTION_V2_XML_PROPERTY_NAME, packageName);
        } catch (PackageManager.NameNotFoundException e) {
            // Do nothing.
        }

        if (v2XmlProperty != null) {
            return v2XmlProperty;
        }

        try {
            return packageManager.getProperty(APP_FUNCTION_V1_XML_PROPERTY_NAME, packageName);
        } catch (PackageManager.NameNotFoundException e) {
            // Do nothing.
        }
        return null;
    }

    private static PackageManager.Property getAppFunctionXmlProperty(
            PackageManager packageManager, ComponentName componentName) {
        PackageManager.Property v2XmlProperty = null;
        try {
            v2XmlProperty =
                    packageManager.getProperty(APP_FUNCTION_V2_XML_PROPERTY_NAME, componentName);
        } catch (PackageManager.NameNotFoundException e) {
            // Do nothing.
        }
        if (v2XmlProperty != null) {
            return v2XmlProperty;
        }
        try {
            return packageManager.getProperty(APP_FUNCTION_V1_XML_PROPERTY_NAME, componentName);
        } catch (PackageManager.NameNotFoundException e) {
            // Do nothing.
        }
        return null;
    }

    /**
     * Gets the list of {@link ResolveInfo} for the app function services.
     *
     * @return The list of app function service resolve infos.
     */
    @NonNull
    public List<ResolveInfo> getAppFunctionServiceResolveInfos() {
        return mAppFunctionServiceResolveInfos;
    }

    /**
     * Gets a list of {@link AppFunctionXmlInfo} for the app functions.
     *
     * @param packageManager The {@link PackageManager}.
     * @return A list of {@link AppFunctionXmlInfo}.
     */
    public List<AppFunctionXmlInfo> getAppFunctionXmlInfos(@NonNull PackageManager packageManager) {
        Objects.requireNonNull(packageManager);
        List<AppFunctionXmlInfo> appFunctionXmlInfos = new ArrayList<>();

        PackageManager.Property appFunctionSchemaProperty = null;

        if (isAppLevelAppFunctionsEnabled()) {
            try {
                appFunctionSchemaProperty =
                        packageManager.getProperty(
                                APP_FUNCTION_SCHEMA_XML_PROPERTY_NAME, mPackageName);
            } catch (PackageManager.NameNotFoundException e) {
                if (LogUtil.DEBUG) {
                    Log.d(TAG, "Failed to get app level schema property for: " + mPackageName, e);
                }
            }
        }

        // Handle service level app functions.
        for (int i = 0; i < mAppFunctionServiceResolveInfos.size(); i++) {
            ResolveInfo resolveInfo = mAppFunctionServiceResolveInfos.get(i);
            PackageManager.Property serviceLevelXmlProperty =
                    getAppFunctionXmlProperty(
                            packageManager,
                            new ComponentName(
                                    resolveInfo.serviceInfo.packageName,
                                    resolveInfo.serviceInfo.name));
            PackageManager.Property serviceLevelSchemaProperty = null;
            try {
                serviceLevelSchemaProperty =
                        packageManager.getProperty(
                                APP_FUNCTION_SCHEMA_XML_PROPERTY_NAME,
                                new ComponentName(
                                        resolveInfo.serviceInfo.packageName,
                                        resolveInfo.serviceInfo.name));
            } catch (PackageManager.NameNotFoundException e) {
                // Do nothing.
            }
            // Previously schemaProperty was only defined at the service level.
            boolean isSchemaPropertyDefined =
                    serviceLevelSchemaProperty != null || appFunctionSchemaProperty != null;
            if (serviceLevelXmlProperty == null) {
                continue;
            }

            if (serviceLevelXmlProperty.isResourceId()) {
                appFunctionXmlInfos.add(
                        new AppFunctionXmlInfo(
                                mPackageName,
                                new XmlFile(null, serviceLevelXmlProperty.getResourceId()),
                                isSchemaPropertyDefined,
                                resolveInfo.serviceInfo.name));
            } else if (serviceLevelXmlProperty.isString()) {
                appFunctionXmlInfos.add(
                        new AppFunctionXmlInfo(
                                mPackageName,
                                new XmlFile(serviceLevelXmlProperty.getString(), Resources.ID_NULL),
                                isSchemaPropertyDefined,
                                resolveInfo.serviceInfo.name));
            }
        }

        if (!isAppLevelAppFunctionsEnabled()) {
            return appFunctionXmlInfos;
        }

        if (mAppFunctionAppLevelXmlProperty == null) {
            return appFunctionXmlInfos;
        }

        // Handle app level app functions.
        boolean isSchemaPropertyDefined = appFunctionSchemaProperty != null;
        if (mAppFunctionAppLevelXmlProperty.isResourceId()) {
            appFunctionXmlInfos.add(
                    new AppFunctionXmlInfo(
                            mPackageName,
                            new XmlFile(null, mAppFunctionAppLevelXmlProperty.getResourceId()),
                            isSchemaPropertyDefined,
                            AppFunctionStaticMetadata.APPLICATION_LEVEL_SERVICE_NAME));
        } else if (mAppFunctionAppLevelXmlProperty.isString()) {
            String[] filePaths = mAppFunctionAppLevelXmlProperty.getString().split(",", -1);
            for (int i = 0; i < filePaths.length; i++) {
                String filePath = filePaths[i];
                String trimmedFilePath = filePath.trim();
                if (trimmedFilePath.isEmpty()) {
                    continue;
                }
                appFunctionXmlInfos.add(
                        new AppFunctionXmlInfo(
                                mPackageName,
                                new XmlFile(trimmedFilePath, Resources.ID_NULL),
                                isSchemaPropertyDefined,
                                AppFunctionStaticMetadata.APPLICATION_LEVEL_SERVICE_NAME));
            }
        }

        return appFunctionXmlInfos;
    }

    /** Represents an XML file containing app function definitions. */
    public static class XmlFile {
        @Nullable private final String mXmlFilePath;
        private final int mFileResourceId;

        /**
         * Creates an instance of {@link XmlFile}.
         *
         * @param xmlFilePath The path to the XML file in assets.
         * @param fileResourceId The resource ID of the XML file.
         */
        public XmlFile(@Nullable String xmlFilePath, int fileResourceId) {
            mXmlFilePath = xmlFilePath;
            mFileResourceId = fileResourceId;
        }

        /** Gets the XML file path. */
        @Nullable
        public String getXmlFilePath() {
            return mXmlFilePath;
        }

        /** Gets the file resource ID. */
        public int getFileResourceId() {
            return mFileResourceId;
        }
    }

    /** Represents the information required to parse an app function XML. */
    public static final class AppFunctionXmlInfo {
        @NonNull private final String mPackageName;
        @NonNull private final XmlFile mXmlFile;
        private final boolean mHasSchemaProperty;
        @NonNull private final String mServiceName;

        /**
         * Creates an instance of {@link AppFunctionXmlInfo}.
         *
         * @param packageName The package name of the app.
         * @param xmlFile The {@link XmlFile} containing the app function XML.
         * @param hasSchemaProperty Whether the app has an app function schema property defined.
         * @param serviceName The name of the service.
         */
        public AppFunctionXmlInfo(
                @NonNull String packageName,
                @NonNull XmlFile xmlFile,
                boolean hasSchemaProperty,
                @NonNull String serviceName) {
            this.mPackageName = Objects.requireNonNull(packageName);
            this.mXmlFile = Objects.requireNonNull(xmlFile);
            this.mHasSchemaProperty = hasSchemaProperty;
            this.mServiceName = Objects.requireNonNull(serviceName);
        }

        /** Executes a task using an XmlPullParser and ensures resources are closed. */
        public void runWithXmlParser(
                PackageManager packageManager, Consumer<XmlPullParser> action) {
            try {
                Resources resources = packageManager.getResourcesForApplication(mPackageName);

                // Compiled XML Resource
                if (mXmlFile.getFileResourceId() != Resources.ID_NULL) {
                    try (XmlResourceParser parser =
                            resources.getXml(mXmlFile.getFileResourceId())) {
                        action.accept(parser);
                    }
                }

                // Raw Asset XML
                else if (mXmlFile.getXmlFilePath() != null) {
                    XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
                    try (InputStreamReader isr =
                            new InputStreamReader(
                                    resources.getAssets().open(mXmlFile.getXmlFilePath()))) {
                        parser.setInput(isr);
                        action.accept(parser);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to process XML for: " + mPackageName, e);
            }
        }

        /** Gets the package name. */
        @NonNull
        public String getPackageName() {
            return mPackageName;
        }

        /** Gets the XML file. */
        @NonNull
        public XmlFile getXmlFile() {
            return mXmlFile;
        }

        /** Gets whether the app has an app function schema property defined. */
        public boolean hasSchemaProperty() {
            return mHasSchemaProperty;
        }

        /** Gets the service name. */
        @NonNull
        public String getServiceName() {
            return mServiceName;
        }

        /**
         * Returns whether to use the schema for parsing the XML.
         *
         * @return {@code true} if the schema should be used, {@code false} to use hardcoded XML
         *     tags.
         */
        public boolean useSchemaForParsing() {
            // Above A17 or when dynamic schema is defined, the XML will be parsed based on the
            // schema properties.
            return isAppLevelAppFunctionsEnabled() || mHasSchemaProperty;
        }

    }
}
