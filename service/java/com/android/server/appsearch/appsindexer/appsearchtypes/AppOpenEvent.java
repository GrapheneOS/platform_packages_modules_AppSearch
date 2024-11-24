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

package com.android.server.appsearch.appsindexer.appsearchtypes;

import android.annotation.CurrentTimeMillisLong;
import android.annotation.NonNull;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.util.DocumentIdUtil;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.appsearch.appsindexer.AppSearchHelper;

import java.util.Objects;

/**
 * Represents an app open event in AppSearch. App open events track when a user opens an application
 * and stores relevant information like package name and timestamp.
 *
 * @hide
 */
public class AppOpenEvent extends GenericDocument {
    // Properties
    public static final String SCHEMA_TYPE = "builtin:AppOpenEvent";

    public static final String ANDROID_PACKAGE_NAME = "android";
    public static final String APP_OPEN_EVENT_NAMESPACE = "app-open-event";

    public static final String APP_OPEN_EVENT_PROPERTY_PACKAGE_NAME = "packageName";
    public static final String APP_OPEN_EVENT_PROPERTY_MOBILE_APPLICATION_QUALIFIED_ID =
            "mobileApplicationQualifiedId"; // Joins to MobileApplication
    public static final String APP_OPEN_EVENT_PROPERTY_APP_OPEN_TIMESTAMP_MILLIS =
            "appOpenTimestampMillis";

    // Schema
    public static final AppSearchSchema SCHEMA =
            new AppSearchSchema.Builder(SCHEMA_TYPE)
                    .addProperty(
                            new AppSearchSchema.StringPropertyConfig.Builder(
                                            APP_OPEN_EVENT_PROPERTY_PACKAGE_NAME)
                                    .setCardinality(
                                            AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                                    .setIndexingType(
                                            AppSearchSchema.StringPropertyConfig
                                                    .INDEXING_TYPE_PREFIXES)
                                    .setTokenizerType(
                                            AppSearchSchema.StringPropertyConfig
                                                    .TOKENIZER_TYPE_PLAIN)
                                    .build())
                    .addProperty(
                            new AppSearchSchema.StringPropertyConfig.Builder(
                                            APP_OPEN_EVENT_PROPERTY_MOBILE_APPLICATION_QUALIFIED_ID)
                                    .setJoinableValueType(
                                            AppSearchSchema.StringPropertyConfig
                                                    .JOINABLE_VALUE_TYPE_QUALIFIED_ID)
                                    .setCardinality(
                                            AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                                    .build())
                    .addProperty(
                            new AppSearchSchema.LongPropertyConfig.Builder(
                                            APP_OPEN_EVENT_PROPERTY_APP_OPEN_TIMESTAMP_MILLIS)
                                    .setIndexingType(
                                            AppSearchSchema.LongPropertyConfig.INDEXING_TYPE_RANGE)
                                    .setCardinality(
                                            AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                                    .build())
                    .build();

    /** Constructs an {@link AppOpenEvent}. */
    public AppOpenEvent(@NonNull GenericDocument document) {
        super(document);
    }

    /**
     * Returns the package name this {@link AppOpenEvent} represents. For example,
     * "com.android.vending".
     */
    @NonNull
    public String getPackageName() {
        return getPropertyString(APP_OPEN_EVENT_PROPERTY_PACKAGE_NAME);
    }

    /**
     * Returns the qualified id of the {@link AppOpenEvent} which links to the {@link
     * MobileApplication} schema.
     */
    @NonNull
    public String getMobileApplicationQualifiedId() {
        return getPropertyString(APP_OPEN_EVENT_PROPERTY_MOBILE_APPLICATION_QUALIFIED_ID);
    }

    /** Returns the timestamp associated with the app open event. */
    @NonNull
    @CurrentTimeMillisLong
    public Long getAppOpenEventTimestampMillis() {
        return getPropertyLong(APP_OPEN_EVENT_PROPERTY_APP_OPEN_TIMESTAMP_MILLIS);
    }

    /**
     * Creates a new {@link AppOpenEvent} instance using the provided package name, timestamp, and
     * context. This version of the method uses the package name and timestamp to generate a unique
     * identifier for the app open event, and links it to the application using a qualified ID based
     * on the context's package name.
     *
     * @param packageName The package name of the app being opened, e.g., "com.android.settings".
     * @param appOpenEventTimestampMillis The timestamp when the app open event occurred, in
     *     milliseconds.
     * @param contextPackageName The name of the package that is indexing the app open event. ID.
     * @return A new {@link AppOpenEvent} instance populated with the provided information.
     */
    @VisibleForTesting
    public static AppOpenEvent create(
            @NonNull String packageName,
            @CurrentTimeMillisLong long appOpenEventTimestampMillis,
            @NonNull String contextPackageName) {
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(contextPackageName);

        String id = packageName + appOpenEventTimestampMillis;
        String qualifiedId =
                DocumentIdUtil.createQualifiedId(
                        contextPackageName,
                        AppSearchHelper.APP_DATABASE,
                        MobileApplication.APPS_NAMESPACE,
                        packageName);

        GenericDocument document =
                new GenericDocument.Builder(APP_OPEN_EVENT_NAMESPACE, id, SCHEMA_TYPE)
                        .setPropertyString(APP_OPEN_EVENT_PROPERTY_PACKAGE_NAME, packageName)
                        .setPropertyLong(
                                APP_OPEN_EVENT_PROPERTY_APP_OPEN_TIMESTAMP_MILLIS,
                                appOpenEventTimestampMillis)
                        .setPropertyString(
                                APP_OPEN_EVENT_PROPERTY_MOBILE_APPLICATION_QUALIFIED_ID,
                                qualifiedId)
                        .build();

        return new AppOpenEvent(document);
    }

    /**
     * Creates a new {@link AppOpenEvent} instance using the provided package name and timestamp,
     * with a default qualified ID based on the Android package name. This method does not require a
     * context and uses the android package name for the qualified ID since this should be the
     * standard usage.
     *
     * @param packageName The package name of the app being opened, e.g., "com.android.settings".
     * @param appOpenEventTimestampMillis The timestamp when the app open event occurred, in
     *     milliseconds.
     * @return A new {@link AppOpenEvent} instance populated with the provided information.
     */
    public static AppOpenEvent create(
            @NonNull String packageName, @CurrentTimeMillisLong long appOpenEventTimestampMillis) {
        return create(packageName, appOpenEventTimestampMillis, ANDROID_PACKAGE_NAME);
    }
}
