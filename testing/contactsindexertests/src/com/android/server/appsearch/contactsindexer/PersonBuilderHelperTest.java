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

package com.android.server.appsearch.contactsindexer;

import static com.google.common.truth.Truth.assertThat;

import android.app.appsearch.AppSearchAccount;
import android.app.appsearch.testutil.AppSearchTestUtils;
import android.net.Uri;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;

import com.android.appsearch.flags.Flags;
import com.android.server.appsearch.contactsindexer.appsearchtypes.ContactPoint;
import com.android.server.appsearch.contactsindexer.appsearchtypes.ContactRelation;
import com.android.server.appsearch.contactsindexer.appsearchtypes.Person;
import com.android.server.appsearch.contactsindexer.appsearchtypes.SignificantDate;

import com.google.common.collect.ImmutableList;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;

import java.util.Arrays;
import java.util.List;

public class PersonBuilderHelperTest {
    @Rule public final RuleChain mRuleChain = AppSearchTestUtils.createCommonTestRules();

    @Test
    public void testFingerprintGeneration_creationTimestampAndFingerprintNotIncluded() {
        long creationTimestamp = 12345L;
        long creationTimestamp2 = 12346L;
        String namespace = "namespace";
        String id = "id";
        String name = "name";
        String givenName = "givenName";
        String middleName = "middleName";
        String lastName = "lastName";
        Uri externalUri = Uri.parse("http://external.com");
        Uri imageUri = Uri.parse("http://image.com");
        byte[] fingerprint = "Hello world!".getBytes();
        byte[] fingerprint2 = "Hello world!!!".getBytes();
        List<String> additionalNames = Arrays.asList("name1", "name2");
        List<String> affiliations = Arrays.asList("Org1", "Org2", "Org3");
        List<String> relations = Arrays.asList("relation1", "relation2");
        boolean isImportant = true;
        boolean isBot = true;
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
        Person.Builder personBuilder = new Person.Builder(namespace, id, name)
                .setGivenName(givenName)
                .setMiddleName(middleName)
                .setFamilyName(lastName)
                .setExternalUri(externalUri)
                .setImageUri(imageUri)
                .addAdditionalName(Person.TYPE_NICKNAME, additionalNames.get(0))
                .addAdditionalName(Person.TYPE_PHONETIC_NAME, additionalNames.get(1))
                .addAffiliation(affiliations.get(0))
                .addAffiliation(affiliations.get(1))
                .addAffiliation(affiliations.get(2))
                .addRelation(relations.get(0))
                .addRelation(relations.get(1))
                .setIsImportant(isImportant)
                .setIsBot(isBot)
                .setFingerprint(fingerprint)
                .addContactPoint(contact1)
                .addContactPoint(contact2);

        Person person = new PersonBuilderHelper(id, personBuilder).setCreationTimestampMillis(
                creationTimestamp).buildPerson();
        Person personSame = new PersonBuilderHelper(id, personBuilder).setCreationTimestampMillis(
                creationTimestamp).buildPerson();
        // same content except for creationTimestamp and fingerprint.
        Person personWithDifferentTsAndFingerprint = new PersonBuilderHelper(id,
                personBuilder.setFingerprint(
                        fingerprint2)).setCreationTimestampMillis(creationTimestamp2).buildPerson();

        // Fingerprint is not empty.
        assertThat(person.getFingerprint()).hasLength(16);
        // Fingerprint would be reset by the generated value
        assertThat(person.getFingerprint()).isNotEqualTo(fingerprint);
        assertThat(personWithDifferentTsAndFingerprint.getFingerprint()).isNotEqualTo(fingerprint2);
        assertThat(person.getFingerprint()).isEqualTo(personSame.getFingerprint());
        assertThat(person.getFingerprint()).isEqualTo(
                personWithDifferentTsAndFingerprint.getFingerprint());
        assertThat(person.getCreationTimestampMillis()).isEqualTo(creationTimestamp);
        assertThat(personSame.getCreationTimestampMillis()).isEqualTo(creationTimestamp);
        assertThat(personWithDifferentTsAndFingerprint.getCreationTimestampMillis()).isEqualTo(
                creationTimestamp2);
    }

    // Test the fingerprinting function.
    @Test
    public void testFingerprintGeneration_forEachPropertyType_string() {
        long creationTimestamp = 12345L;
        String namespace = "namespace";
        String id = "id";
        String name = "name";
        String givenName = "givenName";
        Uri externalUri = Uri.parse("http://external.com");
        Uri externalUriDiff = Uri.parse("http://external2.com");
        Person.Builder personBuilder = new Person.Builder(namespace, id, name)
                .setCreationTimestampMillis(creationTimestamp)
                .setGivenName(givenName)
                .setExternalUri(externalUri);

        Person person = new PersonBuilderHelper(id, personBuilder).setCreationTimestampMillis(
                0).buildPerson();
        Person personSame = new PersonBuilderHelper(id, personBuilder).setCreationTimestampMillis(
                0).buildPerson();
        Person personNameDiff = new PersonBuilderHelper(id,
                personBuilder.setGivenName(name + "diff")).setCreationTimestampMillis(
                0).buildPerson();
        Person personUriDiff = new PersonBuilderHelper(id,
                personBuilder.setExternalUri(externalUriDiff)).setCreationTimestampMillis(
                0).buildPerson();

        assertThat(person.getFingerprint()).isEqualTo(personSame.getFingerprint());
        assertThat(person.getFingerprint()).isNotEqualTo(personNameDiff.getFingerprint());
        assertThat(person.getFingerprint()).isNotEqualTo(personUriDiff.getFingerprint());
    }

    @Test
    public void testFingerprintGeneration_forEachPropertyType_boolean() {
        long creationTimestamp = 12345L;
        String namespace = "namespace";
        String id = "id";
        String name = "name";
        Person.Builder personBuilder = new Person.Builder(namespace, id, name)
                .setIsBot(true);

        Person personIsBotTrue = new PersonBuilderHelper(id,
                personBuilder).setCreationTimestampMillis(creationTimestamp).buildPerson();
        Person personIsBotFalse = new PersonBuilderHelper(id,
                personBuilder.setIsBot(false)).setCreationTimestampMillis(
                creationTimestamp).buildPerson();

        assertThat(personIsBotTrue.getFingerprint()).isNotEqualTo(
                personIsBotFalse.getFingerprint());
    }

    @Test
    public void testFingerprintGeneration_forEachPropertyType_stringArray() {
        String namespace = "namespace";
        String id = "id";
        String name = "name";
        List<String> additionalNames = Arrays.asList("name1", "name2");
        List<String> additionalNames2 = Arrays.asList("name1", "name3");
        Person.Builder personBuilder = new Person.Builder(namespace, id, name)
                .addAdditionalName(Person.TYPE_NICKNAME, additionalNames.get(0))
                .addAdditionalName(Person.TYPE_PHONETIC_NAME, additionalNames.get(1));
        Person.Builder personBuilder2 = new Person.Builder(namespace, id, name)
                .addAdditionalName(Person.TYPE_NICKNAME, additionalNames2.get(0))
                .addAdditionalName(Person.TYPE_PHONETIC_NAME, additionalNames2.get(1));
        // one additionalName type is different from personBuilder above.
        Person.Builder personBuilder3 = new Person.Builder(namespace, id, name)
                .addAdditionalName(Person.TYPE_UNKNOWN, additionalNames.get(0))
                .addAdditionalName(Person.TYPE_PHONETIC_NAME, additionalNames.get(1));

        Person person = new PersonBuilderHelper(id, personBuilder).setCreationTimestampMillis(
                0).buildPerson();
        Person person2 = new PersonBuilderHelper(id, personBuilder2).setCreationTimestampMillis(
                0).buildPerson();
        Person person3 = new PersonBuilderHelper(id, personBuilder3).setCreationTimestampMillis(
                0).buildPerson();

        assertThat(person.getFingerprint()).isNotEqualTo(person2.getFingerprint());
        assertThat(person.getFingerprint()).isNotEqualTo(person3.getFingerprint());
    }

    @Test
    public void testFingerprintGeneration_forEachPropertyType_DocumentArray() {
        long creationTimestamp = 12345L;
        String namespace = "namespace";
        String id = "id";
        String name = "name";
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
        ContactPoint contact2diff = new ContactPoint.Builder(namespace, id + "2", "Work")
                .addAddress("addr2diff")
                .addPhone("phone2")
                .addEmail("email2")
                .addAppId("appId2")
                .build();
        Person.Builder personBuilder = new Person.Builder(namespace, id, name)
                .setCreationTimestampMillis(creationTimestamp)
                .addContactPoint(contact1)
                .addContactPoint(contact2);
        Person.Builder personBuilder2 = new Person.Builder(namespace, id, name)
                .setCreationTimestampMillis(creationTimestamp)
                .addContactPoint(contact1)
                .addContactPoint(contact2diff);

        Person person = new PersonBuilderHelper(id, personBuilder).setCreationTimestampMillis(
                0).setCreationTimestampMillis(0).buildPerson();
        Person person2 = new PersonBuilderHelper(id, personBuilder2).setCreationTimestampMillis(
                0).setCreationTimestampMillis(0).buildPerson();

        // Fingerprint would be reset by the generated value
        assertThat(person.getFingerprint()).isNotEqualTo(person2.getFingerprint());
    }

    @Test
    public void testFingerprintGeneration_sameValueForDifferentProperties_differentFingerprint() {
        String namespace = "namespace";
        String id = "id";
        String name = "same";
        Person.Builder personBuilder = new Person.Builder(namespace, id, name).setGivenName(name);
        Person.Builder personBuilder2 = new Person.Builder(namespace, id, name).setFamilyName(name);

        Person person = new PersonBuilderHelper(id, personBuilder).setCreationTimestampMillis(
                0).buildPerson();
        Person person2 = new PersonBuilderHelper(id, personBuilder2).setCreationTimestampMillis(
                0).buildPerson();

        assertThat(person.getFingerprint()).isNotEqualTo(person2.getFingerprint());
    }

    @Test
    public void testScore_setCorrectly() {
        String namespace = "namespace";
        String id = "id";
        String name = "name";
        List<String> additionalNames = Arrays.asList("name1", "name2");
        Person.Builder personBuilder = new Person.Builder(namespace, id, name)
                .addAdditionalName(Person.TYPE_NICKNAME, additionalNames.get(0))
                .addAdditionalName(Person.TYPE_PHONETIC_NAME, additionalNames.get(1));
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
        personBuilder.addContactPoint(contact1)
                .addContactPoint(contact2)
                .addContactPoint(contact3);

        Person person = new PersonBuilderHelper(id, personBuilder).setCreationTimestampMillis(
                0).setCreationTimestampMillis(0).buildPerson();

        // Score should be set as base(1) + # of contactPoints + # of additionalNames.
        assertThat(person.getScore()).isEqualTo(6);
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_ENABLE_CONTACTS_INDEXER_EXTENDED_PROPERTIES)
    public void testGenerateFingerprintStringForPerson() {
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
                .setCreationTimestampMillis(creationTimestamp)
                .addAddress("addr1")
                .addPhone("phone1")
                .addEmail("email1")
                .addAppId("appId1")
                .build();
        ContactPoint contact2 = new ContactPoint.Builder(namespace, id + "2", "Work")
                .setCreationTimestampMillis(creationTimestamp)
                .addAddress("addr2")
                .addPhone("phone2")
                .addEmail("email2")
                .addAppId("appId2")
                .build();
        ContactPoint contact3 = new ContactPoint.Builder(namespace, id + "3", "Other")
                .setCreationTimestampMillis(creationTimestamp)
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

        // Different from GenericDocument.toString, we will a get string representation without
        // any indentation for Person.
        String expected = "properties: {\n"
                + "\"additionalNameTypes\": [1, 2],\n"
                + "\"additionalNames\": [\"nickname\", \"phoneticName\"],\n"
                + "\"affiliations\": [\"Org1\", \"Org2\", \"Org3\"],\n"
                + "\"contactPoints\": [\n"
                + "properties: {\n"
                + "\"address\": [\"addr1\"],\n"
                + "\"appId\": [\"appId1\"],\n"
                + "\"email\": [\"email1\"],\n"
                + "\"label\": [\"Home\"],\n"
                + "\"telephone\": [\"phone1\"]\n"
                + "},\n"
                + "\n"
                + "properties: {\n"
                + "\"address\": [\"addr2\"],\n"
                + "\"appId\": [\"appId2\"],\n"
                + "\"email\": [\"email2\"],\n"
                + "\"label\": [\"Work\"],\n"
                + "\"telephone\": [\"phone2\"]\n"
                + "},\n"
                + "\n"
                + "properties: {\n"
                + "\"address\": [\"addr3\"],\n"
                + "\"appId\": [\"appId3\"],\n"
                + "\"email\": [\"email3\"],\n"
                + "\"label\": [\"Other\"],\n"
                + "\"telephone\": [\"phone3\"]\n"
                + "}\n"
                + "],\n"
                + "\"externalUri\": [\"http://external.com\"],\n"
                + "\"familyName\": [\"lastName\"],\n"
                + "\"fingerprint\": [[72, 101, 108, 108, 111, 32, 119, 111, 114, 108, "
                + "100, 33]],\n"
                + "\"givenName\": [\"givenName\"],\n"
                + "\"imageUri\": [\"http://image.com\"],\n"
                + "\"isBot\": [true],\n"
                + "\"isImportant\": [true],\n"
                + "\"middleName\": [\"middleName\"],\n"
                + "\"name\": [\"name\"],\n"
                + "\"notes\": [\"note\", \"note2\"],\n"
                + "\"relations\": [\"relation1\", \"relation2\"]\n"
                + "}";

        assertThat(PersonBuilderHelper.generateFingerprintStringForPerson(person)).isEqualTo(
                expected);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_CONTACTS_INDEXER_EXTENDED_PROPERTIES)
    public void testGenerateFingerprintStringForPerson_extendedProperties() {
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

        // Different from GenericDocument.toString, we will a get string representation without
        // any indentation for Person.
        String expected =
                "properties: {\n"
                        + "\"accounts\": [\n"
                        + "properties: {\n"
                        + "\"accountName\": [\"accountName1\"],\n"
                        + "\"accountType\": [\"accountType1\"]\n"
                        + "},\n"
                        + "\n"
                        + "properties: {\n"
                        + "\"accountName\": [\"accountName2\"],\n"
                        + "\"accountType\": [\"accountType2\"]\n"
                        + "}\n"
                        + "],\n"
                        + "\"additionalNameTypes\": [1, 2],\n"
                        + "\"additionalNames\": [\"nickname\", \"phoneticName\"],\n"
                        + "\"affiliations\": [\"Org1\", \"Org2\", \"Org3\"],\n"
                        + "\"contactPoints\": [\n"
                        + "properties: {\n"
                        + "\"address\": [\"addr1\"],\n"
                        + "\"appId\": [\"appId1\"],\n"
                        + "\"email\": [\"email1\"],\n"
                        + "\"isSuperPrimary\": [true],\n"
                        + "\"label\": [\"Home\"],\n"
                        + "\"telephone\": [\"phone1\"]\n"
                        + "},\n"
                        + "\n"
                        + "properties: {\n"
                        + "\"address\": [\"addr2\"],\n"
                        + "\"appId\": [\"appId2\"],\n"
                        + "\"email\": [\"email2\"],\n"
                        + "\"isSuperPrimary\": [false],\n"
                        + "\"label\": [\"Work\"],\n"
                        + "\"telephone\": [\"phone2\"]\n"
                        + "},\n"
                        + "\n"
                        + "properties: {\n"
                        + "\"address\": [\"addr3\"],\n"
                        + "\"appId\": [\"appId3\"],\n"
                        + "\"email\": [\"email3\"],\n"
                        + "\"isSuperPrimary\": [false],\n"
                        + "\"label\": [\"Other\"],\n"
                        + "\"telephone\": [\"phone3\"]\n"
                        + "}\n"
                        + "],\n"
                        + "\"externalUri\": [\"http://external.com\"],\n"
                        + "\"familyName\": [\"lastName\"],\n"
                        + "\"fingerprint\": [[72, 101, 108, 108, 111, 32, 119, 111, 114, 108, "
                        + "100, 33]],\n"
                        + "\"givenName\": [\"givenName\"],\n"
                        + "\"hasCustomRingtone\": [true],\n"
                        + "\"imageUri\": [\"http://image.com\"],\n"
                        + "\"isBot\": [true],\n"
                        + "\"isImportant\": [true],\n"
                        + "\"isVip\": [true],\n"
                        + "\"middleName\": [\"middleName\"],\n"
                        + "\"name\": [\"name\"],\n"
                        + "\"notes\": [\"note\", \"note2\"],\n"
                        + "\"pinnedPosition\": [3],\n"
                        + "\"rawContactIds\": [\"id1\", \"id2\", \"id3\"],\n"
                        + "\"relations\": [\"relation1\", \"relation2\"],\n"
                        + "\"sendToVoicemail\": [true],\n"
                        + "\"significantDates\": [\n"
                        + "properties: {\n"
                        + "\"rawDate\": [\"2026-01-01\"],\n"
                        + "\"type\": [1]\n"
                        + "},\n"
                        + "\n"
                        + "properties: {\n"
                        + "\"customLabel\": [\"customLabel\"],\n"
                        + "\"rawDate\": [\"2000-07-01\"],\n"
                        + "\"type\": [0]\n"
                        + "}\n"
                        + "],\n"
                        + "\"structuredRelations\": [\n"
                        + "properties: {\n"
                        + "\"relationLabel\": [\"relationLabel1\"],\n"
                        + "\"relationName\": [\"relationName1\"]\n"
                        + "},\n"
                        + "\n"
                        + "properties: {\n"
                        + "\"relationLabel\": [\"relationLabel2\"],\n"
                        + "\"relationName\": [\"relationName2\"]\n"
                        + "}\n"
                        + "]\n"
                        + "}";

        assertThat(PersonBuilderHelper.generateFingerprintStringForPerson(person))
                .isEqualTo(expected);
    }
}
