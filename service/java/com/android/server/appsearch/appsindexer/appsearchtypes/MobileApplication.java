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
import android.annotation.Nullable;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.GenericDocument;
import android.net.Uri;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Objects;

/**
 * Represents a installed app to enable searching using name, nicknames, and package name>
 *
 * @hide
 */
public class MobileApplication extends GenericDocument {

    private static final String TAG = "AppSearchMobileApplication";

    public static final String SCHEMA_TYPE = "builtin:MobileApplication";
    public static final String APPS_NAMESPACE = "apps";

    public static final String APP_PROPERTY_PACKAGE_NAME = "packageName";
    public static final String APP_PROPERTY_DISPLAY_NAME = "displayName";
    public static final String APP_PROPERTY_ALTERNATE_NAMES = "alternateNames";
    public static final String APP_PROPERTY_ICON_URI = "iconUri";
    public static final String APP_PROPERTY_SHA256_CERTIFICATE = "sha256Certificate";
    public static final String APP_PROPERTY_UPDATED_TIMESTAMP = "updatedTimestamp";
    public static final String APP_PROPERTY_CLASS_NAME = "className";

    /** Returns a per-app schema name. */
    @VisibleForTesting
    public static String getSchemaNameForPackage(@NonNull String pkg) {
        return SCHEMA_TYPE + "-" + Objects.requireNonNull(pkg);
    }

    /**
     * Returns a MobileApplication {@link AppSearchSchema} for the a package.
     *
     * <p>This is necessary as the base schema and the per-app schemas share all the same
     * properties. However, we cannot easily modify the base schema to create a per-app schema. So
     * instead, to create the base schema we call this method with a blank AppSearchSchema with a
     * schema type of SCHEMA_TYPE. For per-app schemas, we set the schema type to a per-app schema
     * type, add a parent type of SCHEMA_TYPE, then add the properties.
     *
     * @param packageName The package name to create a schema for. Will create the base schema if
     *     called with null.
     */
    @NonNull
    public static AppSearchSchema createMobileApplicationSchemaForPackage(
            @NonNull String packageName) {
        Objects.requireNonNull(packageName);
        return new AppSearchSchema.Builder(getSchemaNameForPackage(packageName))
                // It's possible the user knows the package name, or wants to search for all apps
                // from a certain developer. They could search for "com.developer.*".
                .addProperty(
                        new AppSearchSchema.StringPropertyConfig.Builder(APP_PROPERTY_PACKAGE_NAME)
                                .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                                .setIndexingType(
                                        AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES)
                                .setTokenizerType(
                                        AppSearchSchema.StringPropertyConfig
                                                .TOKENIZER_TYPE_VERBATIM)
                                .build())
                .addProperty(
                        new AppSearchSchema.StringPropertyConfig.Builder(APP_PROPERTY_DISPLAY_NAME)
                                .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                                .setIndexingType(
                                        AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES)
                                .setTokenizerType(
                                        AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                                .build())
                .addProperty(
                        new AppSearchSchema.StringPropertyConfig.Builder(
                                        APP_PROPERTY_ALTERNATE_NAMES)
                                .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REPEATED)
                                .setIndexingType(
                                        AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES)
                                .setTokenizerType(
                                        AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                                .build())
                .addProperty(
                        new AppSearchSchema.StringPropertyConfig.Builder(APP_PROPERTY_ICON_URI)
                                .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                                .build())
                .addProperty(
                        new AppSearchSchema.BytesPropertyConfig.Builder(
                                        APP_PROPERTY_SHA256_CERTIFICATE)
                                .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                                .build())
                .addProperty(
                        new AppSearchSchema.LongPropertyConfig.Builder(
                                        APP_PROPERTY_UPDATED_TIMESTAMP)
                                .setIndexingType(
                                        AppSearchSchema.LongPropertyConfig.INDEXING_TYPE_RANGE)
                                .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                                .build())
                .addProperty(
                        new AppSearchSchema.StringPropertyConfig.Builder(APP_PROPERTY_CLASS_NAME)
                                .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                                .build())
                .build();
    }

    /** Constructs a {@link MobileApplication}. */
    @VisibleForTesting
    public MobileApplication(@NonNull GenericDocument document) {
        super(document);
    }

    /**
     * Returns the package name this {@link MobileApplication} represents. For example,
     * "com.android.vending".
     */
    @NonNull
    public String getPackageName() {
        return getId();
    }

    /**
     * Returns the display name of the app. This is indexed. This is what is displayed in the
     * launcher. This might look like "Play Store".
     */
    @Nullable
    public String getDisplayName() {
        return getPropertyString(APP_PROPERTY_DISPLAY_NAME);
    }

    /**
     * Returns alternative names of the application. These are indexed. For example, you might have
     * the alternative name "pay" for a wallet app.
     */
    @Nullable
    public String[] getAlternateNames() {
        return getPropertyStringArray(APP_PROPERTY_ALTERNATE_NAMES);
    }

    /**
     * Returns the full name of the resource identifier of the app icon, which can be used for
     * displaying results. The Uri could be
     * "android.resource://com.example.vending/drawable/2131230871", for example.
     */
    @Nullable
    public Uri getIconUri() {
        String uriStr = getPropertyString(APP_PROPERTY_ICON_URI);
        if (uriStr == null) {
            return null;
        }
        try {
            return Uri.parse(uriStr);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Returns the SHA-256 certificate of the application. */
    @NonNull
    public byte[] getSha256Certificate() {
        return getPropertyBytes(APP_PROPERTY_SHA256_CERTIFICATE);
    }

    /** Returns the last time the app was installed or updated on the device. */
    @CurrentTimeMillisLong
    public long getUpdatedTimestamp() {
        return getPropertyLong(APP_PROPERTY_UPDATED_TIMESTAMP);
    }

    /**
     * Returns the fully qualified name of the Application class for this mobile app. This would
     * look something like "com.android.vending.SearchActivity". Combined with the package name, a
     * launch intent can be created with <code>
     *     Intent launcher = new Intent(Intent.ACTION_MAIN);
     *     launcher.setComponent(new ComponentName(app.getPackageName(), app.getClassName()));
     *     launcher.setPackage(app.getPackageName());
     *     launcher.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
     *     launcher.addCategory(Intent.CATEGORY_LAUNCHER);
     *     appListFragment.getActivity().startActivity(launcher);
     *  </code>
     */
    @Nullable
    public String getClassName() {
        return getPropertyString(APP_PROPERTY_CLASS_NAME);
    }

    public static final class Builder extends GenericDocument.Builder<Builder> {
        public Builder(@NonNull String packageName, @NonNull byte[] sha256Certificate) {
            // Changing the schema type dynamically so that we can use separate schemas
            super(
                    APPS_NAMESPACE,
                    Objects.requireNonNull(packageName),
                    getSchemaNameForPackage(packageName));
            setPropertyString(APP_PROPERTY_PACKAGE_NAME, packageName);
            setPropertyBytes(
                    APP_PROPERTY_SHA256_CERTIFICATE, Objects.requireNonNull(sha256Certificate));
        }

        /** Sets the display name. */
        @NonNull
        public Builder setDisplayName(@NonNull String displayName) {
            setPropertyString(APP_PROPERTY_DISPLAY_NAME, Objects.requireNonNull(displayName));
            return this;
        }

        /** Sets the alternate names. An empty array will erase the list. */
        @NonNull
        public Builder setAlternateNames(@NonNull String[] alternateNames) {
            setPropertyString(APP_PROPERTY_ALTERNATE_NAMES, Objects.requireNonNull(alternateNames));
            return this;
        }

        /** Sets the icon uri. */
        @NonNull
        public Builder setIconUri(@NonNull String iconUri) {
            setPropertyString(APP_PROPERTY_ICON_URI, Objects.requireNonNull(iconUri));
            return this;
        }

        @NonNull
        public Builder setUpdatedTimestampMs(@CurrentTimeMillisLong long updatedTimestampMs) {
            setPropertyLong(APP_PROPERTY_UPDATED_TIMESTAMP, updatedTimestampMs);
            return this;
        }

        /** Sets the class name. */
        @NonNull
        public Builder setClassName(@NonNull String className) {
            setPropertyString(APP_PROPERTY_CLASS_NAME, Objects.requireNonNull(className));
            return this;
        }

        @NonNull
        public MobileApplication build() {
            return new MobileApplication(super.build());
        }
    }
}

