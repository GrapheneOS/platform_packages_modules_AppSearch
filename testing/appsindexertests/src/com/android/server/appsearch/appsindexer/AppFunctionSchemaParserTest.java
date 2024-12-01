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
package com.android.server.appsearch.appsindexer;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import static org.mockito.Mockito.when;

import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.AppSearchSchema.PropertyConfig;
import android.app.appsearch.AppSearchSchema.StringPropertyConfig;
import android.app.appsearch.AppSearchSchema.LongPropertyConfig;
import android.app.appsearch.AppSearchSchema.BooleanPropertyConfig;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Resources;

import com.android.server.appsearch.appsindexer.appsearchtypes.AppFunctionStaticMetadata;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

@RunWith(MockitoJUnitRunner.class)
public class AppFunctionSchemaParserTest {
    private static final String TEST_PACKAGE_NAME = "com.example.app";
    private static final String TEST_XML_ASSET_FILE_PATH = "appfn_schema.xsd";
    private static final int MAX_ALLOWED_NESTING = 2;

    @Mock private PackageManager mPackageManager;
    @Mock private Resources mResources;
    @Mock private AssetManager mAssetManager;

    private AppFunctionSchemaParser mParser;

    @Before
    public void setUp() throws Exception {
        mParser = new AppFunctionSchemaParser(MAX_ALLOWED_NESTING);

        when(mPackageManager.getResourcesForApplication(TEST_PACKAGE_NAME)).thenReturn(mResources);
        when(mResources.getAssets()).thenReturn(mAssetManager);
    }

    @Test
    public void parse_singleType_withNoAttributes() throws Exception {
        String xsd =
                "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">"
                        + "    <xs:documentType name=\"TestType\">"
                        + "        <xs:element name=\"name\" type=\"xs:string\" />"
                        + "        <xs:element name=\"age\" type=\"xs:int\" />"
                        + "        <xs:element name=\"isActive\" type=\"xs:boolean\" />"
                        + "    </xs:documentType>"
                        + "</xs:schema>";
        setXmlInput(xsd);

        Map<String, AppSearchSchema> schemas =
                mParser.parseAndCreateSchemas(
                        mPackageManager, TEST_PACKAGE_NAME, TEST_XML_ASSET_FILE_PATH);

        assertThat(schemas).hasSize(1);
        assertThat(schemas.get("TestType"))
                .isEqualTo(
                        new AppSearchSchema.Builder("TestType")
                                .addProperty(new StringPropertyConfig.Builder("name").build())
                                .addProperty(new LongPropertyConfig.Builder("age").build())
                                .addProperty(new BooleanPropertyConfig.Builder("isActive").build())
                                .build());
    }

    @Test
    public void parse_singleType_withAttributes() throws Exception {
        String xsd =
                "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">"
                        + "    <xs:documentType name=\"TestType\">"
                        + "        <xs:element name=\"name\" type=\"xs:string\" indexingType=\""
                        + StringPropertyConfig.INDEXING_TYPE_EXACT_TERMS
                        + "\" tokenizerType=\""
                        + StringPropertyConfig.TOKENIZER_TYPE_VERBATIM
                        + "\" />"
                        + "        <xs:element name=\"age\" type=\"xs:int\" indexingType=\""
                        + LongPropertyConfig.INDEXING_TYPE_RANGE
                        + "\" />"
                        + "        <xs:element name=\"isActive\" type=\"xs:boolean\" cardinality=\""
                        + PropertyConfig.CARDINALITY_REQUIRED
                        + "\" />"
                        + "    </xs:documentType>"
                        + "</xs:schema>";
        setXmlInput(xsd);

        Map<String, AppSearchSchema> schemas =
                mParser.parseAndCreateSchemas(
                        mPackageManager, TEST_PACKAGE_NAME, TEST_XML_ASSET_FILE_PATH);

        assertThat(schemas).hasSize(1);
        assertThat(schemas.get("TestType"))
                .isEqualTo(
                        new AppSearchSchema.Builder("TestType")
                                .addProperty(
                                        new StringPropertyConfig.Builder("name")
                                                .setIndexingType(
                                                        StringPropertyConfig
                                                                .INDEXING_TYPE_EXACT_TERMS)
                                                .setTokenizerType(
                                                        StringPropertyConfig
                                                                .TOKENIZER_TYPE_VERBATIM)
                                                .build())
                                .addProperty(
                                        new LongPropertyConfig.Builder("age")
                                                .setIndexingType(
                                                        LongPropertyConfig.INDEXING_TYPE_RANGE)
                                                .build())
                                .addProperty(
                                        new BooleanPropertyConfig.Builder("isActive")
                                                .setCardinality(PropertyConfig.CARDINALITY_REQUIRED)
                                                .build())
                                .build());
    }

    @Test
    public void parse_multipleNestedTypes() throws Exception {
        String xsd =
                "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">"
                        + "    <xs:documentType name=\"OuterType\">"
                        + "        <xs:element name=\"inner\" type=\"appfn:InnerType\" />"
                        + "    </xs:documentType>"
                        + "    <xs:documentType name=\"InnerType\">"
                        + "        <xs:element name=\"value\" type=\"xs:string\" />"
                        + "    </xs:documentType>"
                        + "</xs:schema>";
        setXmlInput(xsd);

        Map<String, AppSearchSchema> schemas =
                mParser.parseAndCreateSchemas(
                        mPackageManager, TEST_PACKAGE_NAME, TEST_XML_ASSET_FILE_PATH);

        assertThat(schemas).hasSize(2);
        assertThat(schemas.get("InnerType"))
                .isEqualTo(
                        new AppSearchSchema.Builder("InnerType")
                                .addProperty(new StringPropertyConfig.Builder("value").build())
                                .build());
        assertThat(schemas.get("OuterType"))
                .isEqualTo(
                        new AppSearchSchema.Builder("OuterType")
                                .addProperty(
                                        new AppSearchSchema.DocumentPropertyConfig.Builder(
                                                        "inner", "InnerType")
                                                .setShouldIndexNestedProperties(true)
                                                .build())
                                .build());
    }

    @Test
    public void parse_exceedMaxAllowedDocumentTypes() throws Exception {
        String xsd =
                "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">"
                        + "    <xs:documentType name=\"OuterType\">"
                        + "        <xs:element name=\"inner\" type=\"appfn:InnerType\" />"
                        + "    </xs:documentType>"
                        + "    <xs:documentType name=\"InnerType\">"
                        + "        <xs:element name=\"inner2\" type=\"appfn:Inner2Type\" />"
                        + "    </xs:documentType>"
                        + "    <xs:documentType name=\"Inner2Type\">"
                        + "        <xs:element name=\"value\" type=\"xs:string\" />"
                        + "    </xs:documentType>"
                        + "</xs:schema>";
        setXmlInput(xsd);

        Exception e =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                mParser.parseAndCreateSchemas(
                                        mPackageManager,
                                        TEST_PACKAGE_NAME,
                                        TEST_XML_ASSET_FILE_PATH));
        assertThat(e).hasMessageThat().contains("Exceeded max allowed document types: 2");
    }

    @Test
    public void parse_unsupportedType_throwsException() throws Exception {
        String xsd =
                "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">"
                        + "    <xs:documentType name=\"TestType\">"
                        + "        <xs:element name=\"name\" type=\"xs:unsupportedType\" />"
                        + "    </xs:documentType>"
                        + "</xs:schema>";
        setXmlInput(xsd);

        Exception e =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                mParser.parseAndCreateSchemas(
                                        mPackageManager,
                                        TEST_PACKAGE_NAME,
                                        TEST_XML_ASSET_FILE_PATH));
        assertThat(e).hasMessageThat().contains("Unsupported type: xs:unsupportedType");
    }

    private void setXmlInput(String xml) throws IOException {
        InputStream inputStream = new ByteArrayInputStream(xml.getBytes());
        when(mAssetManager.open(TEST_XML_ASSET_FILE_PATH)).thenReturn(inputStream);
    }
}
