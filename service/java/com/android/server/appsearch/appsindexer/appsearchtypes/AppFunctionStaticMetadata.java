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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.util.DocumentIdUtil;
import android.os.Build;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.appsearch.appsindexer.AppSearchHelper;

import java.util.Objects;

/**
 * Represents static function metadata of an app function.
 *
 * <p>This is a temporary solution for app function indexing, as later we would like to index the
 * actual function signature entity class shape instead of just the schema info.
 */
// TODO(b/357551503): Link to canonical docs rather than duplicating once they
// are available.
public class AppFunctionStaticMetadata extends GenericDocument {
    private static final String TAG = "AppSearchAppFunction";

    public static final String SCHEMA_TYPE = "AppFunctionStaticMetadata";

    public static final String APP_FUNCTION_NAMESPACE = "app_functions";
    public static final String PROPERTY_FUNCTION_ID = "functionId";
    public static final String PROPERTY_PACKAGE_NAME = "packageName";
    public static final String PROPERTY_SCHEMA_NAME = "schemaName";
    public static final String PROPERTY_SCHEMA_VERSION = "schemaVersion";
    public static final String PROPERTY_SCHEMA_CATEGORY = "schemaCategory";
    public static final String PROPERTY_DISPLAY_NAME_STRING_RES = "displayNameStringRes";
    public static final String PROPERTY_ENABLED_BY_DEFAULT = "enabledByDefault";
    public static final String PROPERTY_RESTRICT_CALLERS_WITH_EXECUTE_APP_FUNCTIONS =
            "restrictCallersWithExecuteAppFunctions";
    public static final String PROPERTY_MOBILE_APPLICATION_QUALIFIED_ID =
            "mobileApplicationQualifiedId";
    public static final AppSearchSchema PARENT_TYPE_APPSEARCH_SCHEMA =
            createAppFunctionSchemaForPackage(/* packageName= */ null);

    /** Returns a per-app schema name, to store all functions for that package. */
    public static String getSchemaNameForPackage(@NonNull String pkg) {
        return SCHEMA_TYPE + "-" + Objects.requireNonNull(pkg);
    }

    /**
     * Different packages have different visibility requirements. To allow for different visibility,
     * we need to have per-package app function schemas.
     *
     * @param packageName The package name to create a schema for. Will create the base schema if it
     *     is null.
     */
    @NonNull
    public static AppSearchSchema createAppFunctionSchemaForPackage(@Nullable String packageName) {
        AppSearchSchema.Builder builder =
                new AppSearchSchema.Builder(
                        (packageName == null) ? SCHEMA_TYPE : getSchemaNameForPackage(packageName));
        if (shouldSetParentType() && packageName != null) {
            // This is a child schema, setting the parent type.
            builder.addParentType(SCHEMA_TYPE);
        }
        return builder.addProperty(
                        new AppSearchSchema.StringPropertyConfig.Builder(PROPERTY_FUNCTION_ID)
                                .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                                .setIndexingType(
                                        AppSearchSchema.StringPropertyConfig
                                                .INDEXING_TYPE_EXACT_TERMS)
                                .setTokenizerType(
                                        AppSearchSchema.StringPropertyConfig
                                                .TOKENIZER_TYPE_VERBATIM)
                                .build())
                .addProperty(
                        new AppSearchSchema.StringPropertyConfig.Builder(PROPERTY_PACKAGE_NAME)
                                .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                                .setIndexingType(
                                        AppSearchSchema.StringPropertyConfig
                                                .INDEXING_TYPE_EXACT_TERMS)
                                .setTokenizerType(
                                        AppSearchSchema.StringPropertyConfig
                                                .TOKENIZER_TYPE_VERBATIM)
                                .build())
                .addProperty(
                        new AppSearchSchema.StringPropertyConfig.Builder(PROPERTY_SCHEMA_NAME)
                                .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                                .setIndexingType(
                                        AppSearchSchema.StringPropertyConfig
                                                .INDEXING_TYPE_EXACT_TERMS)
                                .setTokenizerType(
                                        AppSearchSchema.StringPropertyConfig
                                                .TOKENIZER_TYPE_VERBATIM)
                                .build())
                .addProperty(
                        new AppSearchSchema.LongPropertyConfig.Builder(PROPERTY_SCHEMA_VERSION)
                                .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                                .setIndexingType(
                                        AppSearchSchema.LongPropertyConfig.INDEXING_TYPE_RANGE)
                                .build())
                .addProperty(
                        new AppSearchSchema.StringPropertyConfig.Builder(PROPERTY_SCHEMA_CATEGORY)
                                .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                                .setIndexingType(
                                        AppSearchSchema.StringPropertyConfig
                                                .INDEXING_TYPE_EXACT_TERMS)
                                .setTokenizerType(
                                        AppSearchSchema.StringPropertyConfig
                                                .TOKENIZER_TYPE_VERBATIM)
                                .build())
                .addProperty(
                        new AppSearchSchema.BooleanPropertyConfig.Builder(
                                        PROPERTY_ENABLED_BY_DEFAULT)
                                .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                                .build())
                .addProperty(
                        new AppSearchSchema.BooleanPropertyConfig.Builder(
                                        PROPERTY_RESTRICT_CALLERS_WITH_EXECUTE_APP_FUNCTIONS)
                                .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                                .build())
                .addProperty(
                        new AppSearchSchema.LongPropertyConfig.Builder(
                                        PROPERTY_DISPLAY_NAME_STRING_RES)
                                .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                                .build())
                .addProperty(
                        new AppSearchSchema.StringPropertyConfig.Builder(
                                        PROPERTY_MOBILE_APPLICATION_QUALIFIED_ID)
                                .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                                .setJoinableValueType(
                                        AppSearchSchema.StringPropertyConfig
                                                .JOINABLE_VALUE_TYPE_QUALIFIED_ID)
                                .build())
                .build();
    }

    public AppFunctionStaticMetadata(@NonNull GenericDocument genericDocument) {
        super(genericDocument);
    }

    /** Returns the function id. This might look like "com.example.message#send_message". */
    @NonNull
    public String getFunctionId() {
        return Objects.requireNonNull(getPropertyString(PROPERTY_FUNCTION_ID));
    }

    /** Returns the package name of the package that owns this function. */
    @NonNull
    public String getPackageName() {
        return Objects.requireNonNull(getPropertyString(PROPERTY_PACKAGE_NAME));
    }

    /**
     * Returns the schema name of the schema acted on by this function. This might look like
     * "send_message". The schema name should correspond to a schema defined in the canonical
     * source.
     */
    @Nullable
    public String getSchemaName() {
        return getPropertyString(PROPERTY_SCHEMA_NAME);
    }

    /**
     * Returns the schema version of the schema acted on by this function. The schema version should
     * correspond to a schema defined in the canonical source.
     */
    public long getSchemaVersion() {
        return getPropertyLong(PROPERTY_SCHEMA_VERSION);
    }

    /**
     * Returns the category of the schema. This allows for logical grouping of schemas. For
     * instance, all schemas related to email functionality would be categorized as 'email'.
     */
    @Nullable
    public String getSchemaCategory() {
        return getPropertyString(PROPERTY_SCHEMA_CATEGORY);
    }

    /**
     * Returns if the function is enabled by default or not. Apps can override the enabled status in
     * runtime. The default value is true.
     */
    // TODO(b/357551503): Mention the API to flip the enabled status in runtime.
    public boolean getEnabledByDefault() {
        return getPropertyBoolean(PROPERTY_ENABLED_BY_DEFAULT);
    }

    /**
     * Returns a boolean indicating whether or not to restrict the callers with only the
     * EXECUTE_APP_FUNCTIONS permission.
     *
     * <p>If true, callers with the EXECUTE_APP_FUNCTIONS permission cannot call this function. If
     * false, callers with the EXECUTE_APP_FUNCTIONS permission can call this function. Note that
     * callers with the EXECUTE_APP_FUNCTIONS_TRUSTED permission can always call this function. If
     * not set, the default value is false.
     */
    public boolean getRestrictCallersWithExecuteAppFunctions() {
        return getPropertyBoolean(PROPERTY_RESTRICT_CALLERS_WITH_EXECUTE_APP_FUNCTIONS);
    }

    /** Returns the display name of this function as a string resource. */
    @StringRes
    public int getDisplayNameStringRes() {
        return (int) getPropertyLong(PROPERTY_DISPLAY_NAME_STRING_RES);
    }

    /** Returns the qualified id linking to the Apps Indexer document. */
    @Nullable
    @VisibleForTesting
    public String getMobileApplicationQualifiedId() {
        return getPropertyString(PROPERTY_MOBILE_APPLICATION_QUALIFIED_ID);
    }

    /** Whether a parent type should be set for {@link AppFunctionStaticMetadata}. */
    public static boolean shouldSetParentType() {
        // addParentTypes() is also available on T Extensions 10+. However, we only need it to work
        // on V+ devices because that is where AppFunctionManager will be available anyway. So,
        // we're just checking for V+ here to keep it simple.
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM;
    }

    public static final class Builder extends GenericDocument.Builder<Builder> {
        /**
         * Creates a Builder for a {@link AppFunctionStaticMetadata}.
         *
         * @param packageName the name of the package that owns the function.
         * @param functionId the id of the function.
         * @param indexerPackageName the name of the package performing the indexing. This should be
         *     the same as the package running the apps indexer so that qualified ids are correctly
         *     created.
         */
        public Builder(
                @NonNull String packageName,
                @NonNull String functionId,
                @NonNull String indexerPackageName) {
            super(
                    APP_FUNCTION_NAMESPACE,
                    Objects.requireNonNull(packageName) + "/" + Objects.requireNonNull(functionId),
                    getSchemaNameForPackage(packageName));
            setPropertyString(PROPERTY_FUNCTION_ID, functionId);
            setPropertyString(PROPERTY_PACKAGE_NAME, packageName);

            // Default values of properties.
            setPropertyBoolean(PROPERTY_ENABLED_BY_DEFAULT, true);

            // Set qualified id automatically
            setPropertyString(
                    PROPERTY_MOBILE_APPLICATION_QUALIFIED_ID,
                    DocumentIdUtil.createQualifiedId(
                            indexerPackageName,
                            AppSearchHelper.APP_DATABASE,
                            MobileApplication.APPS_NAMESPACE,
                            packageName));
        }

        /**
         * Sets the name of the schema the function uses. The schema name should correspond to a
         * schema defined in the canonical source.
         */
        @NonNull
        public Builder setSchemaName(@NonNull String schemaName) {
            setPropertyString(PROPERTY_SCHEMA_NAME, schemaName);
            return this;
        }

        /**
         * Sets the version of the schema the function uses. The schema version should correspond to
         * a schema defined in the canonical source.
         */
        @NonNull
        public Builder setSchemaVersion(long schemaVersion) {
            setPropertyLong(PROPERTY_SCHEMA_VERSION, schemaVersion);
            return this;
        }

        /**
         * Specifies the category of the schema used by this function. This allows for logical
         * grouping of schemas. For instance, all schemas related to email functionality would be
         * categorized as 'email'.
         */
        @NonNull
        public Builder setSchemaCategory(@NonNull String category) {
            setPropertyString(PROPERTY_SCHEMA_CATEGORY, category);
            return this;
        }

        /** Sets the display name as a string resource of this function. */
        @NonNull
        public Builder setDisplayNameStringRes(@StringRes int displayName) {
            setPropertyLong(PROPERTY_DISPLAY_NAME_STRING_RES, displayName);
            return this;
        }

        /**
         * Sets an indicator specifying if the function is enabled by default or not. Apps can
         * override the enabled status in runtime. The default value is true.
         */
        // TODO(b/357551503): Mention the API to flip the enabled status in runtime.
        @NonNull
        public Builder setEnabledByDefault(boolean enabled) {
            setPropertyBoolean(PROPERTY_ENABLED_BY_DEFAULT, enabled);
            return this;
        }

        /**
         * Sets whether this app function restricts the callers with only the EXECUTE_APP_FUNCTIONS
         * permission.
         *
         * <p>If true, callers with the EXECUTE_APP_FUNCTIONS permission cannot call this function.
         * If false, callers with the EXECUTE_APP_FUNCTIONS permission can call this function. Note
         * that callers with the EXECUTE_APP_FUNCTIONS_TRUSTED permission can always call this
         * function. If not set, the default value is false.
         */
        @NonNull
        public Builder setRestrictCallersWithExecuteAppFunctions(
                boolean restrictCallersWithExecuteAppFunctions) {
            setPropertyBoolean(
                    PROPERTY_RESTRICT_CALLERS_WITH_EXECUTE_APP_FUNCTIONS,
                    restrictCallersWithExecuteAppFunctions);
            return this;
        }

        /** Creates the {@link AppFunctionStaticMetadata} GenericDocument. */
        @NonNull
        public AppFunctionStaticMetadata build() {
            return new AppFunctionStaticMetadata(super.build());
        }
    }
}
