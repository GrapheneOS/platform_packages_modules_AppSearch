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

import static com.google.common.truth.Truth.assertThat;

import android.app.appsearch.util.DocumentIdUtil;

import org.junit.Test;

public class AppFunctionDocumentTest {

    private static final String TEST_PACKAGE_NAME = "com.example.test";
    private static final String TEST_DOCUMENT_ID = "testDocumentId";
    private static final String TEST_INDEXER_PACKAGE_NAME = "android";
    private static final String TEST_SCHEMA_TYPE = "TestSchema";

    @Test
    public void testGetSchemaNameForPackage_withSchemaType() {
        String schemaName =
                AppFunctionDocument.getSchemaNameForPackage(TEST_PACKAGE_NAME, TEST_SCHEMA_TYPE);
        assertThat(schemaName).isEqualTo(TEST_SCHEMA_TYPE + "-" + TEST_PACKAGE_NAME);
    }

    @Test
    public void testBuilder_build() {
        AppFunctionDocument document =
                new AppFunctionDocument.Builder<>(
                                TEST_PACKAGE_NAME,
                                TEST_DOCUMENT_ID,
                                TEST_INDEXER_PACKAGE_NAME,
                                TEST_SCHEMA_TYPE)
                        .build();

        assertThat(document.getPackageName()).isEqualTo(TEST_PACKAGE_NAME);
        assertThat(document.getId()).isEqualTo(TEST_PACKAGE_NAME + "/" + TEST_DOCUMENT_ID);
        assertThat(document.getSchemaType()).isEqualTo(TEST_SCHEMA_TYPE + "-" + TEST_PACKAGE_NAME);
        String expectedQualifiedId =
                DocumentIdUtil.createQualifiedId(
                        TEST_INDEXER_PACKAGE_NAME,
                        /* databaseName= */ "apps-db",
                        /* namespace= */ "apps",
                        TEST_PACKAGE_NAME);
        assertThat(document.getMobileApplicationQualifiedId()).isEqualTo(expectedQualifiedId);
    }
}
