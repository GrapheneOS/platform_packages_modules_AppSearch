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

package com.android.server.appsearch.appsindexer.appsearchtypes;

import android.annotation.NonNull;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.util.DocumentIdUtil;

import com.android.server.appsearch.appsindexer.AppSearchHelper;

import java.util.Objects;

/** Parent type for all top level App Function related documents, providing common properties. */
public class AppFunctionDocument extends GenericDocument {

    public static final String PROPERTY_PACKAGE_NAME = "packageName";
    public static final String PROPERTY_MOBILE_APPLICATION_QUALIFIED_ID =
            "mobileApplicationQualifiedId";

    /**
     * Returns a per-app schema name.
     *
     * @param pkg the package name of the app that owns the schema.
     * @param schemaType the type of the schema.
     * @return the schema name by concatenating the type and the package name.
     */
    public static String getSchemaNameForPackage(@NonNull String pkg, @NonNull String schemaType) {
        return schemaType + "-" + Objects.requireNonNull(pkg);
    }

    public AppFunctionDocument(@NonNull GenericDocument genericDocument) {
        super(genericDocument);
    }

    /** The package name of the app that owns the function. */
    @NonNull
    public String getPackageName() {
        return Objects.requireNonNull(getPropertyString(PROPERTY_PACKAGE_NAME));
    }

    /**
     * Returns the qualified id linking to the {@link MobileApplication} document representing the
     * metadata about the app that owns this document.
     */
    @NonNull
    public String getMobileApplicationQualifiedId() {
        return Objects.requireNonNull(getPropertyString(PROPERTY_MOBILE_APPLICATION_QUALIFIED_ID));
    }

    /**
     * Builder for {@link AppFunctionDocument} and its subclasses, extending {@link
     * GenericDocument.Builder}.
     *
     * <p>The parameterization allows extending this builder for subclasses of AppFunctionDocument.
     *
     * @param <T> Type of subclass who extends this..
     */
    public static class Builder<T extends Builder<T>> extends GenericDocument.Builder<T> {

        /**
         * Creates a Builder for a {@link AppFunctionDocument}.
         *
         * @param packageName the name of the package that owns the function.
         * @param documentId the id of the document.
         * @param indexerPackageName the name of the package performing the indexing. This should be
         *     the same as the package running the apps indexer so that qualified ids are correctly
         *     created.
         * @param schemaType the schemaType of the document
         */
        public Builder(
                @NonNull String packageName,
                @NonNull String documentId,
                @NonNull String indexerPackageName,
                @NonNull String schemaType) {
            super(
                    AppFunctionStaticMetadata.APP_FUNCTION_NAMESPACE,
                    Objects.requireNonNull(packageName) + "/" + Objects.requireNonNull(documentId),
                    getSchemaNameForPackage(packageName, Objects.requireNonNull(schemaType)));

            setPropertyString(PROPERTY_PACKAGE_NAME, packageName);
            setPropertyString(
                    PROPERTY_MOBILE_APPLICATION_QUALIFIED_ID,
                    DocumentIdUtil.createQualifiedId(
                            Objects.requireNonNull(indexerPackageName),
                            AppSearchHelper.APP_DATABASE,
                            MobileApplication.APPS_NAMESPACE,
                            packageName));
        }

        @Override
        @NonNull
        public AppFunctionDocument build() {
            return new AppFunctionDocument(super.build());
        }
    }
}
