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

import static com.android.server.appsearch.appsindexer.AppFunctionsIndexerUtil.isAppLevelAppFunctionsEnabled;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.GenericDocument;
import android.os.Build;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Objects;

/**
 * Represents static function metadata of an app function.
 *
 * <p>This is a temporary solution for app function indexing, as later we would like to index the
 * actual function signature entity class shape instead of just the schema info.
 */
// TODO(b/357551503): Link to canonical docs rather than duplicating once they
// are available.
public class AppFunctionStaticMetadata extends AppFunctionDocument {
    private static final String TAG = "AppSearchAppFunction";

    public static final String SCHEMA_TYPE = "AppFunctionStaticMetadata";

    public static final String APP_FUNCTION_NAMESPACE = "app_functions";
    public static final String PROPERTY_FUNCTION_ID = "functionId";
    public static final String PROPERTY_SCHEMA_NAME = "schemaName";
    public static final String PROPERTY_SERVICE_NAME = "serviceName";
    public static final String PROPERTY_SCHEMA_VERSION = "schemaVersion";
    public static final String PROPERTY_SCHEMA_CATEGORY = "schemaCategory";
    public static final String PROPERTY_DISPLAY_NAME_STRING_RES = "displayNameStringRes";
    public static final String PROPERTY_ENABLED_BY_DEFAULT = "enabledByDefault";
    public static final String PROPERTY_RESTRICT_CALLERS_WITH_EXECUTE_APP_FUNCTIONS =
            "restrictCallersWithExecuteAppFunctions";
    public static final String PROPERTY_SCOPE = "scope";

    /**
     * The hash of the package name of the app that owns the function.
     *
     * <p>This is to allow the search to sort the results by the package name.
     */
    public static final String PROPERTY_PACKAGE_NAME_HASH = "packageNameHash";

    // TODO: b/479798651 - Use constants from the AppFunctionMetadata API once it's available.
    public static final String SCOPE_GLOBAL = "global";
    public static final String SCOPE_ACTIVITY = "activity";

    /** Service name for app functions which are defined on the application level. */
    public static final String APPLICATION_LEVEL_SERVICE_NAME = "@null";

    public static final AppSearchSchema.StringPropertyConfig SERVICE_PROPERTY_CONFIG =
            new AppSearchSchema.StringPropertyConfig.Builder(PROPERTY_SERVICE_NAME)
                    .setCardinality(AppSearchSchema.StringPropertyConfig.CARDINALITY_OPTIONAL)
                    .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_EXACT_TERMS)
                    .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_VERBATIM)
                    .build();

    public static final AppSearchSchema.StringPropertyConfig SCOPE_PROPERTY_CONFIG =
            new AppSearchSchema.StringPropertyConfig.Builder(PROPERTY_SCOPE)
                    .setCardinality(AppSearchSchema.StringPropertyConfig.CARDINALITY_OPTIONAL)
                    .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_EXACT_TERMS)
                    .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_VERBATIM)
                    .build();

    public static final AppSearchSchema.LongPropertyConfig PACKAGE_NAME_HASH_PROPERTY_CONFIG =
            new AppSearchSchema.LongPropertyConfig.Builder(PROPERTY_PACKAGE_NAME_HASH)
                    .setCardinality(AppSearchSchema.LongPropertyConfig.CARDINALITY_OPTIONAL)
                    .setScoringEnabled(true)
                    .build();

    public static final AppSearchSchema PARENT_TYPE_APPSEARCH_SCHEMA =
            createAppFunctionSchemaForPackage(/* packageName= */ null);

    /**
     * Different packages have different visibility requirements. To allow for different visibility,
     * we need to have per-package app function schemas.
     *
     * @param packageName The package name to create a schema for. Will create the base schema if it
     *     is null.
     */
    // LINT.IfChange
    // IMPORTANT: Any new properties added here must be added to AppFunctionSchemaParser as well
    // to automatically include them in child types of this schema, preventing apps build with older
    // dynamic schema to become incompatible.
    @NonNull
    public static AppSearchSchema createAppFunctionSchemaForPackage(@Nullable String packageName) {
        AppSearchSchema.Builder builder =
                new AppSearchSchema.Builder(
                        (packageName == null)
                                ? SCHEMA_TYPE
                                : getSchemaNameForPackage(packageName, SCHEMA_TYPE));
        if (shouldSetParentType() && packageName != null) {
            // This is a child schema, setting the parent type.
            builder.addParentType(SCHEMA_TYPE);
        }

        if (isAppLevelAppFunctionsEnabled()) {
            builder.addProperty(SERVICE_PROPERTY_CONFIG);
            builder.addProperty(SCOPE_PROPERTY_CONFIG);
            builder.addProperty(PACKAGE_NAME_HASH_PROPERTY_CONFIG);
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

    // LINT.ThenChange(/packages/modules/AppSearch/service/java/com/android/server/appsearch/appsindexer/AppFunctionSchemaParser.java)

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

    /** Returns the hash of the package name of the package that owns this function. */
    public long getPackageNameHash() {
        return getPropertyLong(PROPERTY_PACKAGE_NAME_HASH);
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

    /**
     * Returns the scope of the function.
     *
     * <p>It can be either {@link #SCOPE_GLOBAL} or {@link #SCOPE_ACTIVITY}.
     *
     * <p>Functions that are defined at the service level in the manifest will default to {@link
     * #SCOPE_GLOBAL} scope. While functions defined at the application level would explicitly need
     * to specify the scope.
     *
     * @see android.app.appfunctions.AppFunctionMetadata#getScope()
     */
    @Nullable
    public String getScope() {
        return getPropertyString(PROPERTY_SCOPE);
    }

    /**
     * Returns the service name for the function derived from the enclosing service tag where the
     * function defining XML is specified.
     *
     * <p>If the function is defined at the application level, this will be {@link
     * #APPLICATION_LEVEL_SERVICE_NAME}.
     */
    @Nullable
    public String getServiceName() {
        return getPropertyString(PROPERTY_SERVICE_NAME);
    }

    /** Whether a parent type should be set for {@link AppFunctionStaticMetadata}. */
    public static boolean shouldSetParentType() {
        // While added to the SDK in V, addParentTypes() is safe to call on versions above U (Icing
        // added support for inheritance in the original U build, but the AppSearch API was not
        // exposed until M-2023-08).
        //
        // The fact that this code is running at all implies that the current version > M-2023-08.
        // Therefore, we know that addParentTypes is an available API.
        //
        // However, we cannot call addParentTypes() on T devices as a mainline rollback could break
        // the schema.
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
    }

    public static final class Builder extends AppFunctionDocument.Builder<Builder> {
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
            super(packageName, Objects.requireNonNull(functionId), indexerPackageName, SCHEMA_TYPE);
            // v1 indexer mandates adding <function_id> tag in the XML and a corresponding GD
            // property because this has usage in previous legacy releases of app function library,
            // while v2 indexer doesn't. In v2 indexer documents of type other than functions can
            // also be indexed hence it was replaced by GenericDocument#id.
            if (!functionId.isEmpty()) {
                setPropertyString(PROPERTY_FUNCTION_ID, functionId);
            }
            // Default values of properties.
            setPropertyBoolean(PROPERTY_ENABLED_BY_DEFAULT, true);
            if (isAppLevelAppFunctionsEnabled()) {
                super.setPropertyLong(PROPERTY_PACKAGE_NAME_HASH, packageName.hashCode());
            }
        }

        public Builder setId(@NonNull String id) {
            super.setId(id);
            // In older platform versions, the runtime metadata sync depends on functionId being
            // present in the document hence this needs to be set explicitly.
            setPropertyString(PROPERTY_FUNCTION_ID, id.substring(id.indexOf('/') + 1));
            return this;
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
         * Sets the classpath for the service that executes this function.
         *
         * @param serviceName The service classpath, or the string {@link
         *     #APPLICATION_LEVEL_SERVICE_NAME} if no service is required.
         */
        @NonNull
        public Builder setServiceName(@NonNull String serviceName) {
            Objects.requireNonNull(serviceName);

            if (APPLICATION_LEVEL_SERVICE_NAME.equals(serviceName)) {
                // Application level functions should always be disabled by default. They are only
                // enabled when registered in runtime. Override the constructor set default value.
                setPropertyBoolean(PROPERTY_ENABLED_BY_DEFAULT, false);
            } else {
                // Service level functions were launched in A16, hence we default to global scope
                // for them, however functions defined at the application level (to be launched in
                // next version) need to explicitly specify the scope.
                setScope(SCOPE_GLOBAL);
            }

            if (!serviceName.isEmpty()) {
                // Use the superclass method to set the service name property. The override method
                // throws an exception if the service name is set. This is to avoid the service name
                // being accidentally overridden in the XML.
                super.setPropertyString(PROPERTY_SERVICE_NAME, serviceName);
            }
            return this;
        }

        /**
         * Sets the scope of the function.
         *
         * @param scope The scope of the function.
         */
        @NonNull
        public Builder setScope(@NonNull String scope) {
            setPropertyString(PROPERTY_SCOPE, scope);
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

        @NonNull
        @Override
        public Builder setPropertyLong(@NonNull String propertyName, long... values) {
            if (isAppLevelAppFunctionsEnabled()
                    && propertyName.equals(PROPERTY_PACKAGE_NAME_HASH)) {
                throw new IllegalArgumentException(
                        "Package name hash cannot be set via the XML. It is derived from the"
                                + " package name directly.");
            }
            return super.setPropertyLong(propertyName, values);
        }

        @NonNull
        @Override
        public Builder setPropertyString(@NonNull String propertyName, @NonNull String... values) {
            if (isAppLevelAppFunctionsEnabled() && propertyName.equals(PROPERTY_SERVICE_NAME)) {
                throw new IllegalArgumentException(
                        "Service name cannot be set via the XML. It is derived from the enclosing"
                                + " service tag where the function defining XML is specified.");
            }
            return super.setPropertyString(propertyName, values);
        }

        /**
         * Creates the {@link AppFunctionStaticMetadata} GenericDocument.
         *
         * @throws IllegalStateException if the built document is invalid.
         */
        @NonNull
        public AppFunctionStaticMetadata build() {
            AppFunctionStaticMetadata appFunctionStaticMetadata =
                    new AppFunctionStaticMetadata(super.build());
            if (isAppLevelAppFunctionsEnabled() && !isScopeValid(appFunctionStaticMetadata)) {
                throw new IllegalStateException(
                        "Invalid scope: " + appFunctionStaticMetadata.getScope());
            }
            // Ensure XML doesn't override the value in an invalid way.
            if (isAppLevelAppFunctionsEnabled()
                    && !isEnabledByDefaultValid(appFunctionStaticMetadata)) {
                throw new IllegalStateException(
                        "App level app functions must be disabled by default.");
            }
            return appFunctionStaticMetadata;
        }

        /**
         * Checks if the enabled by default property is valid.
         *
         * <p>The enabled by default property must be false for application level functions.
         */
        private static boolean isEnabledByDefaultValid(
                @NonNull AppFunctionStaticMetadata appFunctionStaticMetadata) {
            return !APPLICATION_LEVEL_SERVICE_NAME.equals(
                            appFunctionStaticMetadata.getServiceName())
                    || !appFunctionStaticMetadata.getEnabledByDefault();
        }

        /**
         * Checks if the scope is valid.
         *
         * <p>The scope must be either {@link #SCOPE_GLOBAL} or {@link #SCOPE_ACTIVITY}. Activity
         * scope is only valid for application level app functions.
         */
        private static boolean isScopeValid(
                @NonNull AppFunctionStaticMetadata appFunctionStaticMetadata) {
            final String scope = appFunctionStaticMetadata.getScope();
            if (SCOPE_GLOBAL.equals(scope)) {
                return true;
            }
            if (SCOPE_ACTIVITY.equals(scope)) {
                // Activity scope is only valid for application level app functions.
                return APPLICATION_LEVEL_SERVICE_NAME.equals(
                        appFunctionStaticMetadata.getServiceName());
            }
            // Other scope types are not supported, or scope is not set.
            return false;
        }
    }
}
