/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.server.appsearch.external.localstorage.usagereporting;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.AppSearchSession;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.annotation.CanIgnoreReturnValue;
import android.app.appsearch.usagereporting.ActionConstants;

import com.android.internal.util.Preconditions;

import java.util.Objects;

/**
 * Wrapper class for
 *
 * <p>search action
 *
 * <p>{@link GenericDocument}, which contains getters for search action properties.
 *
 * @hide
 */
public class SearchActionGenericDocument extends TakenActionGenericDocument {
    private static final String PROPERTY_PATH_QUERY = "query";
    private static final String PROPERTY_PATH_FETCHED_RESULT_COUNT = "fetchedResultCount";

    SearchActionGenericDocument(@NonNull GenericDocument document) {
        super(Objects.requireNonNull(document));
    }

    /** Returns the string value of property {@code query}. */
    @Nullable
    public String getQuery() {
        return getPropertyString(PROPERTY_PATH_QUERY);
    }

    /** Returns the integer value of property {@code fetchedResultCount}. */
    public int getFetchedResultCount() {
        return (int) getPropertyLong(PROPERTY_PATH_FETCHED_RESULT_COUNT);
    }

    /** Builder for {@link SearchActionGenericDocument}. */
    public static final class Builder extends TakenActionGenericDocument.Builder<Builder> {
        /**
         * Creates a new {@link SearchActionGenericDocument.Builder}.
         *
         * <p>Document IDs are unique within a namespace.
         *
         * <p>The number of namespaces per app should be kept small for efficiency reasons.
         *
         * @param namespace the namespace to set for the {@link GenericDocument}.
         * @param id the unique identifier for the {@link GenericDocument} in its namespace.
         * @param schemaType the {@link AppSearchSchema} type of the {@link GenericDocument}. The
         *     provided {@code schemaType} must be defined using {@link AppSearchSession#setSchema}
         *     prior to inserting a document of this {@code schemaType} into the AppSearch index
         *     using {@link AppSearchSession#put}. Otherwise, the document will be rejected by
         *     {@link AppSearchSession#put} with result code {@link
         *     AppSearchResult#RESULT_NOT_FOUND}.
         */
        public Builder(@NonNull String namespace, @NonNull String id, @NonNull String schemaType) {
            super(
                    Objects.requireNonNull(namespace),
                    Objects.requireNonNull(id),
                    Objects.requireNonNull(schemaType),
                    ActionConstants.ACTION_TYPE_SEARCH);
        }

        /**
         * Creates a new {@link SearchActionGenericDocument.Builder} from an existing {@link
         * GenericDocument}.
         *
         * @param document a generic document object.
         * @throws IllegalArgumentException if the integer value of property {@code actionType} is
         *     not {@link ActionConstants#ACTION_TYPE_SEARCH}.
         */
        public Builder(@NonNull GenericDocument document) {
            super(Objects.requireNonNull(document));

            if (document.getPropertyLong(PROPERTY_PATH_ACTION_TYPE)
                    != ActionConstants.ACTION_TYPE_SEARCH) {
                throw new IllegalArgumentException(
                        "Invalid action type for SearchActionGenericDocument");
            }
        }

        /**
         * Sets the string value of property {@code query} by the user-entered search input (without
         * any operators or rewriting).
         */
        @CanIgnoreReturnValue
        @NonNull
        public Builder setQuery(@NonNull String query) {
            Objects.requireNonNull(query);
            setPropertyString(PROPERTY_PATH_QUERY, query);
            return this;
        }

        /**
         * Sets the integer value of property {@code fetchedResultCount} by total number of results
         * fetched from AppSearch by the client in this search action.
         */
        @CanIgnoreReturnValue
        @NonNull
        public Builder setFetchedResultCount(int fetchedResultCount) {
            Preconditions.checkArgumentNonnegative(fetchedResultCount);
            setPropertyLong(PROPERTY_PATH_FETCHED_RESULT_COUNT, fetchedResultCount);
            return this;
        }

        /** Builds a {@link SearchActionGenericDocument}. */
        @Override
        @NonNull
        public SearchActionGenericDocument build() {
            return new SearchActionGenericDocument(super.build());
        }
    }
}
