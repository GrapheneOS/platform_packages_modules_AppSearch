/*
 * Copyright (C) 2022 The Android Open Source Project
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
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.GenericDocument;
import android.provider.ContactsContract;

import com.android.appsearch.flags.Flags;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Represents a ContactPoint in AppSearch.
 *
 * @hide
 */
public final class ContactPoint extends GenericDocument {
    public static final String SCHEMA_TYPE = "builtin:ContactPoint";

    // Properties
    public static final String CONTACT_POINT_PROPERTY_LABEL = "label";
    public static final String CONTACT_POINT_PROPERTY_APP_ID = "appId";
    public static final String CONTACT_POINT_PROPERTY_ADDRESS = "address";
    public static final String CONTACT_POINT_PROPERTY_EMAIL = "email";
    public static final String CONTACT_POINT_PROPERTY_TELEPHONE = "telephone";
    public static final String CONTACT_POINT_IS_SUPER_PRIMARY = "isSuperPrimary";

    /**
     * Returns the ContactPoint schema based on
     * {@link Flags#enableContactsIndexerExtendedProperties}
     */
    public static AppSearchSchema getSchema() {
        AppSearchSchema.Builder builder =
                new AppSearchSchema.Builder(SCHEMA_TYPE)
                        .addProperty(
                                new AppSearchSchema.StringPropertyConfig.Builder(
                                                CONTACT_POINT_PROPERTY_LABEL)
                                        .setCardinality(
                                                AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                                        .setIndexingType(
                                                AppSearchSchema.StringPropertyConfig
                                                        .INDEXING_TYPE_PREFIXES)
                                        .setTokenizerType(
                                                AppSearchSchema.StringPropertyConfig
                                                        .TOKENIZER_TYPE_PLAIN)
                                        .build())
                        // appIds
                        .addProperty(
                                new AppSearchSchema.StringPropertyConfig.Builder(
                                                CONTACT_POINT_PROPERTY_APP_ID)
                                        .setCardinality(
                                                AppSearchSchema.PropertyConfig.CARDINALITY_REPEATED)
                                        .build())
                        // address
                        .addProperty(
                                new AppSearchSchema.StringPropertyConfig.Builder(
                                                CONTACT_POINT_PROPERTY_ADDRESS)
                                        .setCardinality(
                                                AppSearchSchema.PropertyConfig.CARDINALITY_REPEATED)
                                        .setIndexingType(
                                                AppSearchSchema.StringPropertyConfig
                                                        .INDEXING_TYPE_PREFIXES)
                                        .setTokenizerType(
                                                AppSearchSchema.StringPropertyConfig
                                                        .TOKENIZER_TYPE_PLAIN)
                                        .build())
                        // email
                        .addProperty(
                                new AppSearchSchema.StringPropertyConfig.Builder(
                                                CONTACT_POINT_PROPERTY_EMAIL)
                                        .setCardinality(
                                                AppSearchSchema.PropertyConfig.CARDINALITY_REPEATED)
                                        .setIndexingType(
                                                AppSearchSchema.StringPropertyConfig
                                                        .INDEXING_TYPE_PREFIXES)
                                        .setTokenizerType(
                                                AppSearchSchema.StringPropertyConfig
                                                        .TOKENIZER_TYPE_PLAIN)
                                        .build())
                        // telephone
                        .addProperty(
                                new AppSearchSchema.StringPropertyConfig.Builder(
                                                CONTACT_POINT_PROPERTY_TELEPHONE)
                                        .setCardinality(
                                                AppSearchSchema.PropertyConfig.CARDINALITY_REPEATED)
                                        .setIndexingType(
                                                AppSearchSchema.StringPropertyConfig
                                                        .INDEXING_TYPE_PREFIXES)
                                        .setTokenizerType(
                                                AppSearchSchema.StringPropertyConfig
                                                        .TOKENIZER_TYPE_PLAIN)
                                        .build());

        if (Flags.enableContactsIndexerExtendedProperties()) {
            // isSuperPrimary.
            builder.addProperty(
                    new AppSearchSchema.BooleanPropertyConfig.Builder(
                            CONTACT_POINT_IS_SUPER_PRIMARY)
                            .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                            .build());
        }
        return builder.build();
    }

    /** Constructs a {@link ContactPoint}. */
    @VisibleForTesting
    public ContactPoint(@NonNull GenericDocument document) {
        super(document);
    }

    @NonNull
    public String getLabel() {
        return getPropertyString(CONTACT_POINT_PROPERTY_LABEL);
    }

    @NonNull
    public String[] getAppIds() {
        return getPropertyStringArray(CONTACT_POINT_PROPERTY_APP_ID);
    }

    @NonNull
    public String[] getAddresses() {
        return getPropertyStringArray(CONTACT_POINT_PROPERTY_ADDRESS);
    }

    @NonNull
    public String[] getEmails() {
        return getPropertyStringArray(CONTACT_POINT_PROPERTY_EMAIL);
    }

    @NonNull
    public String[] getPhones() {
        return getPropertyStringArray(CONTACT_POINT_PROPERTY_TELEPHONE);
    }

    /**
     * Returns whether this {@link ContactPoint} contains a super primary entry as represented by
     * the {@link ContactsContract.Data#IS_SUPER_PRIMARY} field from the Contacts Provider.
     *
     * <p>In CP2, a Super Primary entry represents a user-designated or system-selected global
     * default for an aggregate contact. While a contact may have multiple "Primary" entries (one
     * per account), it can only have one "Super Primary" entry per data type
     * (e.g., one default phone number) across all linked accounts.
     *
     * <p> When this returns true, the "Super Primary" element is guaranteed to be the first item
     * in its respective list (e.g., the 0-index entry in {@link #getPhones()} or
     * {@link #getEmails()}).
     *
     * <p> E.g: If "John Doe" has a work number (Primary in Outlook) and a mobile number (Primary
     * in Gmail), but the user selected "Always use mobile," the mobile number becomes the one
     * and only Super Primary.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_CONTACTS_INDEXER_EXTENDED_PROPERTIES)
    public boolean isSuperPrimary() {
        return getPropertyBoolean(CONTACT_POINT_IS_SUPER_PRIMARY);
    }

    /** Builder for {@link ContactPoint}. */
    public static final class Builder extends GenericDocument.Builder<Builder> {
        private List<String> mAppIds = new ArrayList<>();
        private List<String> mAddresses = new ArrayList<>();
        private List<String> mEmails = new ArrayList<>();
        private List<String> mTelephones = new ArrayList<>();

        /**
         * Creates a new {@link Builder}
         *
         * @param namespace The namespace for this document.
         * @param id The id of this {@link ContactPoint}. It doesn't matter if it is used as a
         *     nested documents in {@link Person}.
         * @param label The label for this {@link ContactPoint}.
         */
        public Builder(@NonNull String namespace, @NonNull String id, @NonNull String label) {
            super(namespace, id, SCHEMA_TYPE);
            setLabel(label);
        }

        @NonNull
        private Builder setLabel(@NonNull String label) {
            setPropertyString(CONTACT_POINT_PROPERTY_LABEL, label);
            return this;
        }

        /**
         * Add a unique AppId for this {@link ContactPoint}.
         *
         * @param appId a unique identifier for the application.
         */
        @NonNull
        public Builder addAppId(@NonNull String appId) {
            Objects.requireNonNull(appId);
            mAppIds.add(appId);
            return this;
        }

        @NonNull
        public Builder addAddress(@NonNull String address) {
            Objects.requireNonNull(address);
            mAddresses.add(address);
            return this;
        }

        @NonNull
        public Builder addEmail(@NonNull String email) {
            Objects.requireNonNull(email);
            mEmails.add(email);
            return this;
        }

        @NonNull
        public Builder addPhone(@NonNull String phone) {
            Objects.requireNonNull(phone);
            mTelephones.add(phone);
            return this;
        }

        /**
         * Sets whether this {@link ContactPoint} contains the super primary data item.
         *
         * <p>This represents the {@code ContactsContract.Data.IS_SUPER_PRIMARY} field from
         * the Contacts Provider. This {@link ContactPoint} is an aggregate of data, but if
         * {@code isSuperPrimary} is true, the first entry appearing in the specific data
         * lists (e.g., the first telephone number in the telephone list) is guaranteed
         * to be the super primary one.
         */
        @NonNull
        @FlaggedApi(Flags.FLAG_ENABLE_CONTACTS_INDEXER_EXTENDED_PROPERTIES)
        public Builder setIsSuperPrimary(boolean isSuperPrimary) {
            setPropertyBoolean(CONTACT_POINT_IS_SUPER_PRIMARY, isSuperPrimary);
            return this;
        }

        @NonNull
        public ContactPoint build() {
            setPropertyString(CONTACT_POINT_PROPERTY_APP_ID, mAppIds.toArray(new String[0]));
            setPropertyString(CONTACT_POINT_PROPERTY_EMAIL, mEmails.toArray(new String[0]));
            setPropertyString(CONTACT_POINT_PROPERTY_ADDRESS, mAddresses.toArray(new String[0]));
            setPropertyString(CONTACT_POINT_PROPERTY_TELEPHONE, mTelephones.toArray(new String[0]));
            return new ContactPoint(super.build());
        }
    }
}
