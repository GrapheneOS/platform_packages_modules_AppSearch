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

package com.android.server.appsearch.contactsindexer.appsearchtypes;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.GenericDocument;

import com.android.appsearch.flags.Flags;
import com.android.internal.annotations.VisibleForTesting;

/**
 * Represents a CONTACT_RELATION in AppSearch.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_ENABLE_CONTACTS_INDEXER_EXTENDED_PROPERTIES)
public class ContactRelation extends GenericDocument {
    public static final String SCHEMA_TYPE = "builtin:ContactRelation";

    // Properties
    public static final String RELATION_PROPERTY_NAME = "relationName";
    public static final String RELATION_PROPERTY_LABEL = "relationLabel";

    public static final AppSearchSchema SCHEMA =
            new AppSearchSchema.Builder(SCHEMA_TYPE)
                    // name
                    .addProperty(
                            new AppSearchSchema.StringPropertyConfig.Builder(
                                    RELATION_PROPERTY_NAME)
                                    .setCardinality(
                                            AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                                    .setIndexingType(
                                            AppSearchSchema.StringPropertyConfig
                                                    .INDEXING_TYPE_PREFIXES)
                                    .setTokenizerType(
                                            AppSearchSchema.StringPropertyConfig
                                                    .TOKENIZER_TYPE_PLAIN)
                                    .build())
                    // label
                    .addProperty(
                            new AppSearchSchema.StringPropertyConfig.Builder(
                                    RELATION_PROPERTY_LABEL)
                                    .setCardinality(
                                            AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                                    .setIndexingType(
                                            AppSearchSchema.StringPropertyConfig
                                                    .INDEXING_TYPE_PREFIXES)
                                    .setTokenizerType(
                                            AppSearchSchema.StringPropertyConfig
                                                    .TOKENIZER_TYPE_PLAIN)
                                    .build())
                    .build();

    /** Constructs a {@link ContactRelation}. */
    @VisibleForTesting
    public ContactRelation(@NonNull GenericDocument document) {
        super(document);
    }

    /**
     * Returns the name of the person in this relation.
     */
    @Nullable
    public String getRelationName() {
        return getPropertyString(RELATION_PROPERTY_NAME);
    }

    /**
     * Returns the label describing the relation (e.g., "SPOUSE", "ASSISTANT", or a custom label).
     */
    @Nullable
    public String getRelationLabel() {
        return getPropertyString(RELATION_PROPERTY_LABEL);
    }

    /** Builder for {@link ContactRelation}. */
    public static final class Builder extends GenericDocument.Builder<ContactRelation.Builder> {
        /**
         * Creates a new {@link ContactRelation.Builder}
         *
         * @param namespace The namespace for this document.
         * @param id The id of this {@link ContactRelation}.
         */
        public Builder(@NonNull String namespace, @NonNull String id) {
            super(namespace, id, SCHEMA_TYPE);
        }

        /**
         * Sets the name of the person in this relation.
         */
        @NonNull
        public ContactRelation.Builder setRelationName(@NonNull String name) {
            setPropertyString(RELATION_PROPERTY_NAME, name);
            return this;
        }

        /**
         * Sets the label for this relation.
         *
         * <p>This can be a standard type (e.g., "Father") or a custom user-defined label.
         */
        @NonNull
        public ContactRelation.Builder setRelationLabel(@NonNull String label) {
            setPropertyString(RELATION_PROPERTY_LABEL, label);
            return this;
        }

        @NonNull
        public ContactRelation build() {
            return new ContactRelation(super.build());
        }
    }
}
