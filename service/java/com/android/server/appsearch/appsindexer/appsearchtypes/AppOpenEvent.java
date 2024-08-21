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

import android.app.appsearch.annotation.CanIgnoreReturnValue;
import android.annotation.CurrentTimeMillisLong;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.GenericDocument;
import android.net.Uri;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Objects;

/**
 * Represents an app open event in AppSearch. App open events track when a user opens an application
 * and stores relevant information like package name and timestamp.
 *
 * @hide
 */
public class AppOpenEvent extends GenericDocument {
    // Properties
    private static final String SCHEMA_TYPE = "builtin:AppOpenEvent";

    private static final String APP_OPEN_EVENT_NAMESPACE = "app-open-event";

    private static final String APP_OPEN_EVENT_PROPERTY_PACKAGE_NAME = "packageName";
    private static final String APP_OPEN_EVENT_PROPERTY_MOBILE_APPLICATION_QUALIFIED_ID =
            "mobileApplicationQualifiedId"; // Joins to MobileApplication
    private static final String APP_OPEN_EVENT_PROPERTY_APP_OPEN_TIMESTAMP_MILLIS =
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
    @VisibleForTesting
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

    /** Builder for {@link AppOpenEvent}. */
    public static final class Builder extends GenericDocument.Builder<Builder> {
        public Builder(
                @NonNull String packageName,
                @CurrentTimeMillisLong long appOpenEventTimestampMillis) {
            // Package name + timestamp is unique, since if an app was somehow opened twice at the
            // same time, it would be considered the same event.
            super(
                    APP_OPEN_EVENT_NAMESPACE,
                    /* id= */ packageName + appOpenEventTimestampMillis,
                    SCHEMA_TYPE);
            setPropertyString(APP_OPEN_EVENT_PROPERTY_PACKAGE_NAME, packageName);
            setPropertyLong(
                    APP_OPEN_EVENT_PROPERTY_APP_OPEN_TIMESTAMP_MILLIS, appOpenEventTimestampMillis);
        }

        /** Sets the app open event timestamp. */
        @NonNull
        @CanIgnoreReturnValue
        public Builder setAppOpenEventTimestampMillis(
                @CurrentTimeMillisLong long appOpenEventTimestampMillis) {
            setPropertyLong(
                    APP_OPEN_EVENT_PROPERTY_APP_OPEN_TIMESTAMP_MILLIS, appOpenEventTimestampMillis);
            return this;
        }

        /** Sets the mobile application qualified id */
        @NonNull
        @CanIgnoreReturnValue
        public Builder setMobileApplicationQualifiedId(
                @NonNull String mobileApplicationQualifiedId) {
            setPropertyString(
                    APP_OPEN_EVENT_PROPERTY_MOBILE_APPLICATION_QUALIFIED_ID,
                    Objects.requireNonNull(mobileApplicationQualifiedId));
            return this;
        }

        /** Sets the package name. */
        @NonNull
        @CanIgnoreReturnValue
        public Builder setPackageName(@NonNull String packageName) {
            setPropertyString(
                    APP_OPEN_EVENT_PROPERTY_PACKAGE_NAME, Objects.requireNonNull(packageName));
            return this;
        }

        @NonNull
        @CanIgnoreReturnValue
        public AppOpenEvent build() {
            return new AppOpenEvent(super.build());
        }
    }
}
