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
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.GenericDocument;
import android.provider.ContactsContract;

import com.android.appsearch.flags.Flags;
import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Represents a SIGNIFICANT_DATE in AppSearch.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_ENABLE_CONTACTS_INDEXER_EXTENDED_PROPERTIES)
public class SignificantDate extends GenericDocument {
    public static final String SCHEMA_TYPE = "builtin:SignificantDate";

    /**
     * The type for the SignificantDate. The SIGNIFICANT_DATE_CUSTOM_LABEL field can be populated if
     * {@link #TYPE_CUSTOM} or {@link #TYPE_OTHER}
     *
     * <p> This must be kept in sync with {@link ContactsContract.CommonDataKinds.Event#TYPE}
     * constants as defined by CP2:
     * https://cs.android.com/android/platform/superproject/+/android-latest-release:frameworks
     * /base/core/java/android/provider/ContactsContract.java;
     * l=7942?q=ContactsContract%20Event&ss=android%2Fplatform%2Fsuperproject
     */
    @IntDef(
            value = {
                    TYPE_CUSTOM,
                    TYPE_ANNIVERSARY,
                    TYPE_OTHER,
                    TYPE_BIRTHDAY,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SignificantDateType {
    }

    /**
     * Indicates a date with a user-defined label.
     *
     * <p>In the Contacts Provider, this indicates the label is stored in the
     * custom label field. For indexing, this is treated the same as {@link #TYPE_OTHER}.
     */
    public static final int TYPE_CUSTOM = 0;
    public static final int TYPE_ANNIVERSARY = 1;
    /**
     * Indicates a generic date that does not fall into other specific categories.
     *
     * <p>For indexing, this is treated the same as {@link #TYPE_CUSTOM}. Both types
     * will have their associated label indexed in {@link #getCustomLabel()}.
     */
    public static final int TYPE_OTHER = 2;
    public static final int TYPE_BIRTHDAY = 3;

    // Properties
    public static final String SIGNIFICANT_DATE_PROPERTY_RAW_DATE = "rawDate";
    public static final String SIGNIFICANT_DATE_PROPERTY_TYPE = "type";
    public static final String SIGNIFICANT_DATE_PROPERTY_CUSTOM_LABEL = "customLabel";

    public static final AppSearchSchema SCHEMA =
            new AppSearchSchema.Builder(SCHEMA_TYPE)
                    // rawDate
                    .addProperty(
                            new AppSearchSchema.StringPropertyConfig.Builder(
                                    SIGNIFICANT_DATE_PROPERTY_RAW_DATE)
                                    .setIndexingType(
                                            AppSearchSchema.StringPropertyConfig
                                                    .INDEXING_TYPE_PREFIXES)
                                    .setTokenizerType(
                                            AppSearchSchema.StringPropertyConfig
                                                    .TOKENIZER_TYPE_VERBATIM)
                                    .setCardinality(
                                            AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                                    .build())
                    // type
                    .addProperty(
                            new AppSearchSchema.LongPropertyConfig.Builder(
                                    SIGNIFICANT_DATE_PROPERTY_TYPE)
                                    .setIndexingType(
                                            AppSearchSchema.LongPropertyConfig.INDEXING_TYPE_RANGE)
                                    .setCardinality(
                                            AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                                    .build())
                    // customLabel
                    .addProperty(
                            new AppSearchSchema.StringPropertyConfig.Builder(
                                    SIGNIFICANT_DATE_PROPERTY_CUSTOM_LABEL)
                                    .setIndexingType(
                                            AppSearchSchema.StringPropertyConfig
                                                    .INDEXING_TYPE_PREFIXES)
                                    .setTokenizerType(
                                            AppSearchSchema.StringPropertyConfig
                                                    .TOKENIZER_TYPE_PLAIN)
                                    .setCardinality(
                                            AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                                    .build())
                    .build();

    /** Constructs a {@link SignificantDate}. */
    @VisibleForTesting
    public SignificantDate(@NonNull GenericDocument document) {
        super(document);
    }

    /**
     * Returns the raw date string, which serves as the source of truth for the date.
     *
     * <p>This typically follows the format stored in the Contacts Provider (CP2).
     * For example:
     * <ul>
     *     <li>{@code 1990-03-04} (Full date)</li>
     *     <li>{@code --03-04} (Date without year, per ISO 8601)</li>
     * </ul>
     */
    @Nullable
    public String getRawDate() {
        return getPropertyString(SIGNIFICANT_DATE_PROPERTY_RAW_DATE);
    }

    /**
     * Returns the type of the significant date (e.g., birthday, anniversary).
     */
    @SignificantDateType
    public int getSignificantDateType() {
        return (int) getPropertyLong(SIGNIFICANT_DATE_PROPERTY_TYPE);
    }

    /**
     * Returns the custom label associated with this date.
     *
     * <p>This field is typically used when {@link #getSignificantDateType()} is set to a "custom"
     * or "other" type, allowing users to define their own event categories.
     */
    @Nullable
    public String getCustomLabel() {
        return getPropertyString(SIGNIFICANT_DATE_PROPERTY_CUSTOM_LABEL);
    }

    /** Builder for {@link SignificantDate}. */
    public static final class Builder extends GenericDocument.Builder<SignificantDate.Builder> {
        /**
         * Creates a new {@link SignificantDate.Builder}
         *
         * @param namespace The namespace for this document.
         * @param id        The id of this {@link SignificantDate}.
         */
        public Builder(@NonNull String namespace, @NonNull String id) {
            super(namespace, id, SCHEMA_TYPE);
        }

        /**
         * The raw date string recorded in CP2.
         *
         * <p>This typically follows the format stored in the Contacts Provider.
         * For example:
         * <ul>
         *     <li>{@code 1990-03-04} (Full date)</li>
         *     <li>{@code --03-04} (Date without year, per ISO 8601)</li>
         * </ul>
         */
        @NonNull
        public SignificantDate.Builder setRawDate(@NonNull String rawDate) {
            setPropertyString(SIGNIFICANT_DATE_PROPERTY_RAW_DATE, rawDate);
            return this;
        }

        @NonNull
        public SignificantDate.Builder setSignificantDateType(@SignificantDateType int type) {
            setPropertyLong(SIGNIFICANT_DATE_PROPERTY_TYPE, type);
            return this;
        }

        @NonNull
        public SignificantDate.Builder setCustomLabel(@NonNull String label) {
            setPropertyString(SIGNIFICANT_DATE_PROPERTY_CUSTOM_LABEL, label);
            return this;
        }

        @NonNull
        public SignificantDate build() {
            return new SignificantDate(super.build());
        }
    }
}
