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

import static org.mockito.Mockito.when;

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

@RunWith(MockitoJUnitRunner.class)
public class AppFunctionStaticMetadataParserImplTest {

    private static final String TEST_PACKAGE_NAME = "com.example.app";
    private static final String TEST_INDEXER_PACKAGE_NAME = "com.android.test.indexer";
    private static final String TEST_XML_ASSET_FILE_PATH = "app_functions.xml";

    @Mock private PackageManager mPackageManager;
    @Mock private Resources mResources;
    @Mock private AssetManager mAssetManager;

    private AppFunctionStaticMetadataParser mParser;

    @Before
    public void setUp() throws Exception {
        mParser =
                new AppFunctionStaticMetadataParserImpl(
                        TEST_INDEXER_PACKAGE_NAME, /* maxAppFunctions= */ 2);

        when(mPackageManager.getResourcesForApplication(TEST_PACKAGE_NAME)).thenReturn(mResources);
        when(mResources.getAssets()).thenReturn(mAssetManager);
    }

    private void setXmlInput(String xml) throws IOException {
        InputStream inputStream = new ByteArrayInputStream(xml.getBytes());
        when(mAssetManager.open(TEST_XML_ASSET_FILE_PATH)).thenReturn(inputStream);
    }

    @Test
    public void parse_singleAppFunctionWithAllProperties() throws Exception {
        setXmlInput(
                "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n"
                        + "<version>1</version>\n"
                        + "<appfunctions>\n"
                        + "  <appfunction>\n"
                        + "    <function_id>com.example.utils#print</function_id>\n"
                        + "    <schema_name>insert_note</schema_name>\n"
                        + "    <schema_version>1</schema_version>\n"
                        + "    <schema_category>utils</schema_category>\n"
                        + "    <enabled_by_default>false</enabled_by_default>\n"
                        + "    <restrict_callers_with_execute_app_functions>true\n"
                        + "</restrict_callers_with_execute_app_functions>\n"
                        + "    <display_name_string_res>10</display_name_string_res>\n"
                        + "  </appfunction>\n"
                        + "</appfunctions>");

        List<AppFunctionStaticMetadata> appFunctions =
                mParser.parse(mPackageManager, TEST_PACKAGE_NAME, TEST_XML_ASSET_FILE_PATH);

        assertThat(appFunctions).hasSize(1);

        AppFunctionStaticMetadata appFunction1 = appFunctions.get(0);
        assertThat(appFunction1.getFunctionId()).isEqualTo("com.example.utils#print");
        assertThat(appFunction1.getPackageName()).isEqualTo(TEST_PACKAGE_NAME);
        assertThat(appFunction1.getSchemaName()).isEqualTo("insert_note");
        assertThat(appFunction1.getSchemaVersion()).isEqualTo(1);
        assertThat(appFunction1.getSchemaCategory()).isEqualTo("utils");
        assertThat(appFunction1.getEnabledByDefault()).isEqualTo(false);
        assertThat(appFunction1.getRestrictCallersWithExecuteAppFunctions()).isEqualTo(true);
        assertThat(appFunction1.getDisplayNameStringRes()).isEqualTo(10);
    }

    @Test
    public void parse_singleAppFunctionWithDefaults() throws Exception {
        setXmlInput(
                "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n"
                        + "<version>1</version>\n"
                        + "<appfunctions>\n"
                        + "  <appfunction>\n"
                        + "    <function_id>com.example.utils#print</function_id>\n"
                        + "  </appfunction>\n"
                        + "</appfunctions>");

        List<AppFunctionStaticMetadata> appFunctions =
                mParser.parse(mPackageManager, TEST_PACKAGE_NAME, TEST_XML_ASSET_FILE_PATH);

        assertThat(appFunctions).hasSize(1);

        AppFunctionStaticMetadata appFunction1 = appFunctions.get(0);
        assertThat(appFunction1.getFunctionId()).isEqualTo("com.example.utils#print");
        assertThat(appFunction1.getPackageName()).isEqualTo(TEST_PACKAGE_NAME);
        assertThat(appFunction1.getSchemaName()).isNull();
        assertThat(appFunction1.getSchemaVersion()).isEqualTo(0);
        assertThat(appFunction1.getSchemaCategory()).isNull();
        assertThat(appFunction1.getEnabledByDefault()).isEqualTo(true);
        assertThat(appFunction1.getRestrictCallersWithExecuteAppFunctions()).isEqualTo(false);
        assertThat(appFunction1.getDisplayNameStringRes()).isEqualTo(0);
    }

    @Test
    public void parse_missingFunctionId() throws Exception {
        setXmlInput(
                "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n"
                        + "<version>1</version>\n"
                        + "<appfunctions>\n"
                        + "  <appfunction>\n"
                        + "    <schema_name>insert_note</schema_name>\n"
                        + "    <schema_version>1</schema_version>\n"
                        + "    <schema_category>utils</schema_category>\n"
                        + "  </appfunction>\n"
                        + "</appfunctions>");

        List<AppFunctionStaticMetadata> appFunctions =
                mParser.parse(mPackageManager, TEST_PACKAGE_NAME, TEST_XML_ASSET_FILE_PATH);

        assertThat(appFunctions).isEmpty();
    }

    @Test
    public void parse_malformedXml() throws Exception {
        // Missing </functionId>
        setXmlInput(
                "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n"
                        + "<version>1</version>\n"
                        + "<appfunctions>\n"
                        + "  <appfunction>\n"
                        + "    <function_id>com.example.utils#print"
                        + "    <schema_name>insert_note</schema_name>\n"
                        + "    <schema_version>1</schema_version>\n"
                        + "    <schema_category>utils</schema_category>\n"
                        + "  </appfunction>\n"
                        + "</appfunctions>");

        List<AppFunctionStaticMetadata> appFunctions =
                mParser.parse(mPackageManager, TEST_PACKAGE_NAME, TEST_XML_ASSET_FILE_PATH);

        assertThat(appFunctions).isEmpty();
    }

    @Test
    public void parse_exceedMaxNumAppFunctions() throws Exception {
        // maxAppFunctions was set to be 2.
        setXmlInput(
                "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n"
                        + "<version>1</version>\n"
                        + "<appfunctions>\n"
                        + "  <appfunction>\n"
                        + "    <function_id>com.example#send_message1</function_id>\n"
                        + "  </appfunction>\n"
                        + "  <appfunction>\n"
                        + "    <function_id>com.example#send_message2</function_id>\n"
                        + "  </appfunction>\n"
                        + "  <appfunction>\n"
                        + "    <function_id>com.example#send_message3</function_id>\n"
                        + "  </appfunction>\n"
                        + "</appfunctions>");

        List<AppFunctionStaticMetadata> appFunctions =
                mParser.parse(mPackageManager, TEST_PACKAGE_NAME, TEST_XML_ASSET_FILE_PATH);

        assertThat(appFunctions).hasSize(2);
        assertThat(appFunctions.get(0).getFunctionId()).isEqualTo("com.example#send_message1");
        assertThat(appFunctions.get(1).getFunctionId()).isEqualTo("com.example#send_message2");
    }
}
