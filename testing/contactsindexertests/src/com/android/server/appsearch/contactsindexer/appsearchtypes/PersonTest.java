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

import static com.google.common.truth.Truth.assertThat;

import android.app.appsearch.AppSearchAccount;
import android.app.appsearch.testutil.AppSearchTestUtils;
import android.net.Uri;
import android.platform.test.annotations.RequiresFlagsEnabled;

import com.android.appsearch.flags.Flags;

import com.google.common.collect.ImmutableList;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;

import java.util.List;

public class PersonTest {
    @Rule public final RuleChain mRuleChain = AppSearchTestUtils.createCommonTestRules();

    @Test
    public void testBuilder() {
        long creationTimestamp = 12345L;
        String namespace = "namespace";
        String id = "id";
        int score = 3;
        String name = "name";
        String givenName = "givenName";
        String middleName = "middleName";
        String lastName = "lastName";
        Uri externalUri = Uri.parse("http://external.com");
        Uri imageUri = Uri.parse("http://image.com");
        byte[] fingerprint = "Hello world!".getBytes();
        List<String> affiliations = ImmutableList.of("Org1", "Org2", "Org3");
        List<String> relations = ImmutableList.of("relation1", "relation2");
        boolean isImportant = true;
        boolean isBot = true;
        String note1 = "note";
        String note2 = "note2";
        ContactPoint contact1 = new ContactPoint.Builder(namespace, id + "1", "Home")
                .addAddress("addr1")
                .addPhone("phone1")
                .addEmail("email1")
                .addAppId("appId1")
                .build();
        ContactPoint contact2 = new ContactPoint.Builder(namespace, id + "2", "Work")
                .addAddress("addr2")
                .addPhone("phone2")
                .addEmail("email2")
                .addAppId("appId2")
                .build();
        ContactPoint contact3 = new ContactPoint.Builder(namespace, id + "3", "Other")
                .addAddress("addr3")
                .addPhone("phone3")
                .addEmail("email3")
                .addAppId("appId3")
                .build();
        List<String> additionalNames = ImmutableList.of("nickname", "phoneticName");
        @Person.NameType
        List<Long> additionalNameTypes = ImmutableList.of((long) Person.TYPE_NICKNAME,
                (long) Person.TYPE_PHONETIC_NAME);

        Person person = new Person.Builder(namespace, id, name)
                .setCreationTimestampMillis(creationTimestamp)
                .setScore(score)
                .setGivenName(givenName)
                .setMiddleName(middleName)
                .setFamilyName(lastName)
                .setExternalUri(externalUri)
                .setImageUri(imageUri)
                .addAdditionalName(additionalNameTypes.get(0), additionalNames.get(0))
                .addAdditionalName(additionalNameTypes.get(1), additionalNames.get(1))
                .addAffiliation(affiliations.get(0))
                .addAffiliation(affiliations.get(1))
                .addAffiliation(affiliations.get(2))
                .addRelation(relations.get(0))
                .addRelation(relations.get(1))
                .setIsImportant(isImportant)
                .setIsBot(isBot)
                .addNote(note1)
                .addNote(note2)
                .setFingerprint(fingerprint)
                .addContactPoint(contact1)
                .addContactPoint(contact2)
                .addContactPoint(contact3)
                .build();

        assertThat(person.getCreationTimestampMillis()).isEqualTo(creationTimestamp);
        assertThat(person.getScore()).isEqualTo(score);
        assertThat(person.getNamespace()).isEqualTo(namespace);
        assertThat(person.getId()).isEqualTo(id);
        assertThat(person.getName()).isEqualTo(name);
        assertThat(person.getGivenName()).isEqualTo(givenName);
        assertThat(person.getMiddleName()).isEqualTo(middleName);
        assertThat(person.getFamilyName()).isEqualTo(lastName);
        assertThat(person.getExternalUri().toString()).isEqualTo(externalUri.toString());
        assertThat(person.getImageUri().toString()).isEqualTo(imageUri.toString());
        assertThat(person.getNotes()).asList().containsExactly(note1, note2);
        assertThat(person.isBot()).isEqualTo(isBot);
        assertThat(person.isImportant()).isEqualTo(isImportant);
        assertThat(person.getFingerprint()).isEqualTo(fingerprint);
        assertThat(person.getAdditionalNames()).asList().isEqualTo(additionalNames);
        assertThat(person.getAdditionalNameTypes()).asList().isEqualTo(additionalNameTypes);
        assertThat(person.getAffiliations()).asList().isEqualTo(affiliations);
        assertThat(person.getRelations()).asList().isEqualTo(relations);
        assertThat(person.getContactPoints()).asList().containsExactly(contact1, contact2,
                contact3);
    }

    @Test
    @RequiresFlagsEnabled({Flags.FLAG_ENABLE_CONTACTS_INDEXER_EXTENDED_PROPERTIES,
            Flags.FLAG_ENABLE_SCHEMAS_WIPEOUT_ACCOUNT_PROPERTY_PATHS})
    public void testBuilder_extendedProperties() {
        long creationTimestamp = 12345L;
        String namespace = "namespace";
        String id = "id";
        int score = 3;
        String name = "name";
        String givenName = "givenName";
        String middleName = "middleName";
        String lastName = "lastName";
        Uri externalUri = Uri.parse("http://external.com");
        Uri imageUri = Uri.parse("http://image.com");
        byte[] fingerprint = "Hello world!".getBytes();
        List<String> affiliations = ImmutableList.of("Org1", "Org2", "Org3");
        List<String> relations = ImmutableList.of("relation1", "relation2");
        List<String> rawContactIds = ImmutableList.of("id1", "id2", "id3");
        boolean isImportant = true;
        boolean isVip = true;
        boolean isBot = true;
        boolean hasCustomRingtone = true;
        boolean sendToVoicemail = true;
        int pinnedPosition = 3;

        String note1 = "note";
        String note2 = "note2";
        ContactPoint contact1 =
                new ContactPoint.Builder(namespace, id + "1", "Home")
                        .addAddress("addr1")
                        .addPhone("phone1")
                        .addEmail("email1")
                        .addAppId("appId1")
                        .setIsSuperPrimary(true)
                        .build();
        ContactPoint contact2 =
                new ContactPoint.Builder(namespace, id + "2", "Work")
                        .addAddress("addr2")
                        .addPhone("phone2")
                        .addEmail("email2")
                        .addAppId("appId2")
                        .setIsSuperPrimary(false)
                        .build();
        ContactPoint contact3 =
                new ContactPoint.Builder(namespace, id + "3", "Other")
                        .addAddress("addr3")
                        .addPhone("phone3")
                        .addEmail("email3")
                        .addAppId("appId3")
                        .setIsSuperPrimary(false)
                        .build();
        SignificantDate date1 =
                new SignificantDate.Builder(namespace, id + "4")
                        .setRawDate("2026-01-01")
                        .setSignificantDateType(SignificantDate.TYPE_ANNIVERSARY)
                        .build();
        SignificantDate date2 =
                new SignificantDate.Builder(namespace, id + "5")
                        .setRawDate("2000-07-01")
                        .setSignificantDateType(SignificantDate.TYPE_CUSTOM)
                        .setCustomLabel("customLabel")
                        .build();
        ContactRelation structuredRelation1 =
                new ContactRelation.Builder(namespace, id + "6")
                        .setRelationName("relationName1")
                        .setRelationLabel("relationLabel1")
                        .build();
        ContactRelation structuredRelation2 =
                new ContactRelation.Builder(namespace, id + "7")
                        .setRelationName("relationName2")
                        .setRelationLabel("relationLabel2")
                        .build();
        AppSearchAccount account1 =
                new AppSearchAccount.Builder(namespace, id + "8")
                        .setAccountName("accountName1")
                        .setAccountType("accountType1")
                        .build();
        AppSearchAccount account2 =
                new AppSearchAccount.Builder(namespace, id + "9")
                        .setAccountName("accountName2")
                        .setAccountType("accountType2")
                        .build();
        List<String> additionalNames = ImmutableList.of("nickname", "phoneticName");
        @Person.NameType
        List<Long> additionalNameTypes =
                ImmutableList.of((long) Person.TYPE_NICKNAME, (long) Person.TYPE_PHONETIC_NAME);

        Person person =
                new Person.Builder(namespace, id, name)
                        .setCreationTimestampMillis(creationTimestamp)
                        .setScore(score)
                        .setGivenName(givenName)
                        .setMiddleName(middleName)
                        .setFamilyName(lastName)
                        .setExternalUri(externalUri)
                        .setImageUri(imageUri)
                        .addAdditionalName(additionalNameTypes.get(0), additionalNames.get(0))
                        .addAdditionalName(additionalNameTypes.get(1), additionalNames.get(1))
                        .addAffiliation(affiliations.get(0))
                        .addAffiliation(affiliations.get(1))
                        .addAffiliation(affiliations.get(2))
                        .addRelation(relations.get(0))
                        .addRelation(relations.get(1))
                        .addStructuredRelation(structuredRelation1)
                        .addStructuredRelation(structuredRelation2)
                        .setIsImportant(isImportant)
                        .setIsVip(isVip)
                        .setIsBot(isBot)
                        .setHasCustomRingtone(hasCustomRingtone)
                        .setSendToVoicemail(sendToVoicemail)
                        .setPinnedPosition(pinnedPosition)
                        .addNote(note1)
                        .addNote(note2)
                        .setFingerprint(fingerprint)
                        .addContactPoint(contact1)
                        .addContactPoint(contact2)
                        .addContactPoint(contact3)
                        .addSignificantDate(date1)
                        .addSignificantDate(date2)
                        .addRawContactId(rawContactIds.get(0))
                        .addRawContactId(rawContactIds.get(1))
                        .addRawContactId(rawContactIds.get(2))
                        .addAccount(account1)
                        .addAccount(account2)
                        .build();

        assertThat(person.getCreationTimestampMillis()).isEqualTo(creationTimestamp);
        assertThat(person.getScore()).isEqualTo(score);
        assertThat(person.getNamespace()).isEqualTo(namespace);
        assertThat(person.getId()).isEqualTo(id);
        assertThat(person.getName()).isEqualTo(name);
        assertThat(person.getGivenName()).isEqualTo(givenName);
        assertThat(person.getMiddleName()).isEqualTo(middleName);
        assertThat(person.getFamilyName()).isEqualTo(lastName);
        assertThat(person.getExternalUri().toString()).isEqualTo(externalUri.toString());
        assertThat(person.getImageUri().toString()).isEqualTo(imageUri.toString());
        assertThat(person.getNotes()).asList().containsExactly(note1, note2);
        assertThat(person.isBot()).isEqualTo(isBot);
        assertThat(person.isImportant()).isEqualTo(isImportant);
        assertThat(person.isVip()).isEqualTo(isVip);
        assertThat(person.hasCustomRingtone()).isEqualTo(hasCustomRingtone);
        assertThat(person.sendToVoicemail()).isEqualTo(sendToVoicemail);
        assertThat(person.getPinnedPosition()).isEqualTo(pinnedPosition);
        assertThat(person.getFingerprint()).isEqualTo(fingerprint);
        assertThat(person.getAdditionalNames()).asList().isEqualTo(additionalNames);
        assertThat(person.getAdditionalNameTypes()).asList().isEqualTo(additionalNameTypes);
        assertThat(person.getAffiliations()).asList().isEqualTo(affiliations);
        assertThat(person.getRelations()).asList().isEqualTo(relations);
        assertThat(person.getStructuredRelations())
                .asList()
                .containsExactly(structuredRelation1, structuredRelation2);
        assertThat(person.getRawContactIds()).asList().isEqualTo(rawContactIds);
        assertThat(person.getContactPoints())
                .asList()
                .containsExactly(contact1, contact2, contact3);
        assertThat(person.getSignificantDates()).asList().containsExactly(date1, date2);
        assertThat(person.getAccounts())
                .asList()
                .containsExactly(account1, account2);
    }
}