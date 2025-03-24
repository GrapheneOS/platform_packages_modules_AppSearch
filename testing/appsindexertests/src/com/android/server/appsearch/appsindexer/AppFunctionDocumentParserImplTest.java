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

import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.GenericDocument;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Resources;

import com.android.server.appsearch.appsindexer.appsearchtypes.AppFunctionDocument;
import com.android.server.appsearch.appsindexer.appsearchtypes.AppFunctionStaticMetadata;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RunWith(MockitoJUnitRunner.class)
public class AppFunctionDocumentParserImplTest {

    private static final String TEST_PACKAGE_NAME = "com.example.app";
    private static final String TEST_INDEXER_PACKAGE_NAME = "com.android.test.indexer";
    private static final String TEST_XML_ASSET_FILE_PATH = "app_functions.xml";
    private static final Map<String, AppSearchSchema> TEST_SCHEMAS =
            Map.of(
                    "AppFunctionStaticMetadata-com.example.app",
                    new AppSearchSchema.Builder("AppFunctionStaticMetadata-com.example.app")
                            .addProperty(
                                    new AppSearchSchema.StringPropertyConfig.Builder("functionId")
                                            .build())
                            .addProperty(
                                    new AppSearchSchema.BooleanPropertyConfig.Builder(
                                                    "enabledByDefault")
                                            .build())
                            .addProperty(
                                    new AppSearchSchema.LongPropertyConfig.Builder("schemaVersion")
                                            .build())
                            .addProperty(
                                    new AppSearchSchema.DocumentPropertyConfig.Builder(
                                                    "appFunctionParameterMetadata",
                                                    "AppFunctionParameterMetadata-com.example.app")
                                            .build())
                            .build(),
                    "AppFunctionParameterMetadata-com.example.app",
                    new AppSearchSchema.Builder("AppFunctionParameterMetadata-com.example.app")
                            .addProperty(
                                    new AppSearchSchema.StringPropertyConfig.Builder(
                                                    "parameterName")
                                            .build())
                            .addProperty(
                                    new AppSearchSchema.DocumentPropertyConfig.Builder(
                                                    "selfReference",
                                                    "AppFunctionParameterMetadata-com.example.app")
                                            .build())
                            .build());

    @Mock private PackageManager mPackageManager;
    @Mock private Resources mResources;
    @Mock private AssetManager mAssetManager;

    private AppFunctionDocumentParser mParser;

    @Before
    public void setUp() throws Exception {
        mParser =
                new AppFunctionDocumentParserImpl(
                        TEST_INDEXER_PACKAGE_NAME, new TestAppsIndexerConfig());

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
    public void parse_ignoresUnknownProperties() throws Exception {
        setXmlInput(
                "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n"
                        + "<version>1</version>\n"
                        + "<appfunctions>\n"
                        + "  <appfunction>\n"
                        + "    <function_id>com.example.utils#print</function_id>\n"
                        + "    <parameters><name>test</name></parameters>\n"
                        + "    <unknown_property>test</unknown_property>\n"
                        + "  </appfunction>\n"
                        + "</appfunctions>");

        List<AppFunctionStaticMetadata> appFunctions =
                mParser.parse(mPackageManager, TEST_PACKAGE_NAME, TEST_XML_ASSET_FILE_PATH);

        assertThat(appFunctions).hasSize(1);
        // Only contain known properties from XML or properties populated by default.
        assertThat(appFunctions.get(0).getPropertyNames())
                .containsExactly(
                        "functionId",
                        "packageName",
                        "enabledByDefault",
                        "mobileApplicationQualifiedId");
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
        mParser =
                new AppFunctionDocumentParserImpl(
                        TEST_INDEXER_PACKAGE_NAME,
                        new TestAppsIndexerConfig() {
                            @Override
                            public int getMaxAppFunctionsPerPackage() {
                                return 2;
                            }
                        });
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

    @Test
    public void parseIntoMapForGivenSchemas_singleAppFunctionWithPrimitiveProperties()
            throws Exception {
        setXmlInput(
                "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n"
                        + "<appfunctions>\n"
                        + "  <AppFunctionStaticMetadata>\n"
                        + "    <id>com.example.utils#print</id>\n"
                        + "    <functionId>com.example.utils#print</functionId>\n"
                        + "    <enabledByDefault>true</enabledByDefault>\n"
                        + "    <schemaVersion>10</schemaVersion>\n"
                        + "  </AppFunctionStaticMetadata>\n"
                        + "</appfunctions>");

        Map<String, AppFunctionDocument> appFunctions =
                mParser.parseIntoMapForGivenSchemas(
                        mPackageManager, TEST_PACKAGE_NAME, TEST_XML_ASSET_FILE_PATH, TEST_SCHEMAS);

        assertThat(appFunctions).hasSize(1);
        assertThat(appFunctions).containsKey("com.example.app/com.example.utils#print");
        GenericDocument actualAppFunction =
                appFunctions.get("com.example.app/com.example.utils#print");
        assertThat(actualAppFunction.getNamespace()).isEqualTo("app_functions");
        assertThat(actualAppFunction.getId()).isEqualTo("com.example.app/com.example.utils#print");
        assertThat(actualAppFunction.getSchemaType())
                .isEqualTo("AppFunctionStaticMetadata-com.example.app");
        assertThat(actualAppFunction.getPropertyString("functionId"))
                .isEqualTo("com.example.utils#print");
        assertThat(actualAppFunction.getPropertyBoolean("enabledByDefault")).isEqualTo(true);
        assertThat(actualAppFunction.getPropertyLong("schemaVersion")).isEqualTo(10);
        assertThat(actualAppFunction.getPropertyString("packageName")).isEqualTo(TEST_PACKAGE_NAME);
        assertThat(actualAppFunction.getPropertyString("mobileApplicationQualifiedId"))
                .isEqualTo("com.android.test.indexer$apps-db/apps#com.example.app");
    }

    @Test
    public void parseIntoMapForGivenSchemas_multipleAppFunctions() throws Exception {
        setXmlInput(
                "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n"
                        + "<appfunctions>\n"
                        + "  <AppFunctionStaticMetadata>\n"
                        + "    <id>com.example.utils#print</id>\n"
                        + "    <functionId>com.example.utils#print1</functionId>\n"
                        + "    <enabledByDefault>true</enabledByDefault>\n"
                        + "    <schemaVersion>10</schemaVersion>\n"
                        + "  </AppFunctionStaticMetadata>\n"
                        + "  <AppFunctionStaticMetadata>\n"
                        + "    <id>com.example.utils#print2</id>\n"
                        + "    <functionId>com.example.utils#print2</functionId>\n"
                        + "    <enabledByDefault>true</enabledByDefault>\n"
                        + "    <schemaVersion>10</schemaVersion>\n"
                        + "  </AppFunctionStaticMetadata>\n"
                        + "</appfunctions>");

        Map<String, AppFunctionDocument> appFunctions =
                mParser.parseIntoMapForGivenSchemas(
                        mPackageManager, TEST_PACKAGE_NAME, TEST_XML_ASSET_FILE_PATH, TEST_SCHEMAS);

        assertThat(appFunctions).hasSize(2);
        assertThat(appFunctions).containsKey("com.example.app/com.example.utils#print");
        assertThat(appFunctions).containsKey("com.example.app/com.example.utils#print");
    }

    @Test
    public void parseIntoMapForGivenSchemas_malformedXml_returnsEmptyMap() throws Exception {
        setXmlInput(
                "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n"
                        + "<appfunctions>\n"
                        + "  <AppFunctionStaticMetadata>\n"
                        + "    <functionId>com.example.utils#print</functionId>\n"
                        + "    <enabledByDefault>true</enabledByDefault>\n"
                        + "    <schemaVersion>10</schemaVersion>\n"
                        + "  </AppFunctionStaticMetadata>\n"
                        + "  <AppFunctionStaticMetadata>\n"
                        + "    <functionId>com.example.utils#print2</functionId>\n"
                        + "    <enabledByDefault>true</enabledByDefault>\n"
                        + "    <schemaVersion>10</schemaVersion>\n"
                        + "</appfunctions>");

        Map<String, AppFunctionDocument> appFunctions =
                mParser.parseIntoMapForGivenSchemas(
                        mPackageManager, TEST_PACKAGE_NAME, TEST_XML_ASSET_FILE_PATH, TEST_SCHEMAS);

        assertThat(appFunctions).isEmpty();
    }

    @Test
    public void parseIntoMapForGivenSchemas_exceedMaxNumAppFunctions_parsesOnlyMaxNumAppFunctions()
            throws Exception {
        mParser =
                new AppFunctionDocumentParserImpl(
                        TEST_INDEXER_PACKAGE_NAME,
                        new TestAppsIndexerConfig() {
                            @Override
                            public int getMaxAppFunctionsPerPackage() {
                                return 2;
                            }
                        });
        setXmlInput(
                "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n"
                        + "<appfunctions>\n"
                        + "  <AppFunctionStaticMetadata>\n"
                        + "    <id>com.example.utils#print1</id>\n"
                        + "    <functionId>com.example.utils#print1</functionId>\n"
                        + "    <enabledByDefault>true</enabledByDefault>\n"
                        + "    <schemaVersion>10</schemaVersion>\n"
                        + "  </AppFunctionStaticMetadata>\n"
                        + "  <AppFunctionStaticMetadata>\n"
                        + "    <id>com.example.utils#print2</id>\n"
                        + "    <functionId>com.example.utils#print2</functionId>\n"
                        + "    <enabledByDefault>true</enabledByDefault>\n"
                        + "    <schemaVersion>10</schemaVersion>\n"
                        + "  </AppFunctionStaticMetadata>\n"
                        + "  <AppFunctionStaticMetadata>\n"
                        + "    <id>com.example.utils#print3</id>\n"
                        + "    <functionId>com.example.utils#print3</functionId>\n"
                        + "    <enabledByDefault>true</enabledByDefault>\n"
                        + "    <schemaVersion>10</schemaVersion>\n"
                        + "  </AppFunctionStaticMetadata>\n"
                        + "</appfunctions>");

        Map<String, AppFunctionDocument> appFunctions =
                mParser.parseIntoMapForGivenSchemas(
                        mPackageManager, TEST_PACKAGE_NAME, TEST_XML_ASSET_FILE_PATH, TEST_SCHEMAS);

        assertThat(appFunctions).hasSize(2);
        assertThat(appFunctions).containsKey("com.example.app/com.example.utils#print1");
        assertThat(appFunctions).containsKey("com.example.app/com.example.utils#print2");
    }

    @Test
    public void parseIntoMapForGivenSchemas_singleAppFunctionWithDocumentProperties()
            throws Exception {
        setXmlInput(
                "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n"
                        + "<appfunctions>\n"
                        + "  <AppFunctionStaticMetadata>\n"
                        + "    <id>com.example.utils#print</id>\n"
                        + "    <functionId>com.example.utils#print</functionId>\n"
                        + "    <appFunctionParameterMetadata>\n"
                        + "      <id>com.example.utils#print/appFunctionParameterMetadata-0"
                        + "</id>\n"
                        + "      <parameterName>test</parameterName>\n"
                        + "    </appFunctionParameterMetadata>\n"
                        + "  </AppFunctionStaticMetadata>\n"
                        + "</appfunctions>");

        Map<String, AppFunctionDocument> appFunctions =
                mParser.parseIntoMapForGivenSchemas(
                        mPackageManager, TEST_PACKAGE_NAME, TEST_XML_ASSET_FILE_PATH, TEST_SCHEMAS);

        assertThat(appFunctions).hasSize(1);
        assertThat(appFunctions).containsKey("com.example.app/com.example.utils#print");
        GenericDocument actualAppFunction =
                appFunctions.get("com.example.app/com.example.utils#print");
        assertThat(actualAppFunction.getPropertyString("functionId"))
                .isEqualTo("com.example.utils#print");
        assertThat(
                        actualAppFunction.getPropertyString(
                                "appFunctionParameterMetadata.parameterName"))
                .isEqualTo("test");
        assertThat(actualAppFunction.getPropertyDocument("appFunctionParameterMetadata").getId())
                .isEqualTo(
                        "com.example.app/com.example.utils#print/appFunctionParameterMetadata-0");
    }

    @Test
    public void parseIntoMapForGivenSchemas_singleAppFunctionWithSelfReferencingSchema()
            throws Exception {
        setXmlInput(
                "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n"
                        + "<appfunctions>\n"
                        + "  <AppFunctionStaticMetadata>\n"
                        + "    <id>com.example.utils#print</id>\n"
                        + "    <functionId>com.example.utils#print</functionId>\n"
                        + "    <appFunctionParameterMetadata>\n"
                        + "      <id>com.example.utils#print/appFunctionParameterMetadata-0"
                        + "</id>\n"
                        + "      <parameterName>test</parameterName>\n"
                        + "    <selfReference>\n"
                        + "      <id>com.example.utils#print/appFunctionParameterMetadata-1"
                        + "</id>\n"
                        + "      <parameterName>selfReferencingParam</parameterName>\n"
                        + "    </selfReference>\n"
                        + "    </appFunctionParameterMetadata>\n"
                        + "  </AppFunctionStaticMetadata>\n"
                        + "</appfunctions>");

        Map<String, AppFunctionDocument> appFunctions =
                mParser.parseIntoMapForGivenSchemas(
                        mPackageManager, TEST_PACKAGE_NAME, TEST_XML_ASSET_FILE_PATH, TEST_SCHEMAS);

        assertThat(appFunctions).hasSize(1);
        assertThat(appFunctions).containsKey("com.example.app/com.example.utils#print");
        GenericDocument actualAppFunction =
                appFunctions.get("com.example.app/com.example.utils#print");
        assertThat(actualAppFunction.getPropertyString("functionId"))
                .isEqualTo("com.example.utils#print");
        assertThat(
                        actualAppFunction.getPropertyString(
                                "appFunctionParameterMetadata.parameterName"))
                .isEqualTo("test");
        assertThat(actualAppFunction.getPropertyDocument("appFunctionParameterMetadata").getId())
                .isEqualTo(
                        "com.example.app/com.example.utils#print/appFunctionParameterMetadata-0");
        assertThat(
                        actualAppFunction.getPropertyString(
                                "appFunctionParameterMetadata.selfReference.parameterName"))
                .isEqualTo("selfReferencingParam");
    }

    @Test
    public void parseIntoMapForGivenSchemas_multipleTypesOfRootDocuments() throws Exception {
        setXmlInput(
                "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n"
                        + "<appfunctions>\n"
                        + "  <AppFunctionStaticMetadata>\n"
                        + "    <id>com.example.utils#print</id>\n"
                        + "    <functionId>com.example.utils#print</functionId>\n"
                        + "  </AppFunctionStaticMetadata>\n"
                        + "  <AppFunctionParameterMetadata>\n"
                        + "    <id>com.example.utils#printParameterMetadata</id>\n"
                        + "    <parameterName>message</parameterName>\n"
                        + "  </AppFunctionParameterMetadata>\n"
                        + "</appfunctions>");

        Map<String, AppFunctionDocument> appFunctions =
                mParser.parseIntoMapForGivenSchemas(
                        mPackageManager, TEST_PACKAGE_NAME, TEST_XML_ASSET_FILE_PATH, TEST_SCHEMAS);

        assertThat(appFunctions).hasSize(2);
        assertThat(appFunctions).containsKey("com.example.app/com.example.utils#print");
        GenericDocument actualAppFunction =
                appFunctions.get("com.example.app/com.example.utils#print");
        assertThat(actualAppFunction.getPropertyString("functionId"))
                .isEqualTo("com.example.utils#print");
        GenericDocument appFunctionParameterMetadataDocument =
                appFunctions.get("com.example.app/com.example.utils#printParameterMetadata");
        assertThat(appFunctionParameterMetadataDocument.getPropertyString("parameterName"))
                .isEqualTo("message");
        assertThat(appFunctionParameterMetadataDocument.getId())
                .isEqualTo("com.example.app/com.example.utils#printParameterMetadata");
    }

    @Test
    public void
            parseIntoMapForGivenSchemas_singleFunctionWithDocumentProperties_missingIdInNestedDoc()
                    throws Exception {
        setXmlInput(
                "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n"
                        + "<appfunctions>\n"
                        + "  <AppFunctionStaticMetadata>\n"
                        + "    <id>com.example.utils#print</id>\n"
                        + "    <functionId>com.example.utils#print</functionId>\n"
                        + "    <appFunctionParameterMetadata>\n"
                        + "      <parameterName>test</parameterName>\n"
                        + "    </appFunctionParameterMetadata>\n"
                        + "  </AppFunctionStaticMetadata>\n"
                        + "</appfunctions>");

        Map<String, AppFunctionDocument> appFunctions =
                mParser.parseIntoMapForGivenSchemas(
                        mPackageManager, TEST_PACKAGE_NAME, TEST_XML_ASSET_FILE_PATH, TEST_SCHEMAS);

        assertThat(appFunctions).isEmpty();
    }

    @Test
    public void parseIntoMapForGivenSchemas_singleAppFunctionWithRepeatedProperties()
            throws Exception {
        setXmlInput(
                "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n"
                        + "<appfunctions>\n"
                        + "  <AppFunctionStaticMetadata>\n"
                        + "    <id>com.example.utils#print</id>\n"
                        + "    <functionId>com.example.utils#print</functionId>\n"
                        + "    <appFunctionParameterMetadata>\n"
                        + "      <id>com.example.utils#print/appFunctionParameterMetadata-0"
                        + "</id>\n"
                        + "      <parameterName>test1</parameterName>\n"
                        + "      <parameterName>test2</parameterName>\n"
                        + "    </appFunctionParameterMetadata>\n"
                        + "    <appFunctionParameterMetadata>\n"
                        + "      <id>com.example.utils#print/appFunctionParameterMetadata-1"
                        + "</id>\n"
                        + "      <parameterName>test3</parameterName>\n"
                        + "    </appFunctionParameterMetadata>\n"
                        + "  </AppFunctionStaticMetadata>\n"
                        + "</appfunctions>");

        Map<String, AppFunctionDocument> appFunctions =
                mParser.parseIntoMapForGivenSchemas(
                        mPackageManager, TEST_PACKAGE_NAME, TEST_XML_ASSET_FILE_PATH, TEST_SCHEMAS);

        assertThat(appFunctions).hasSize(1);
        assertThat(appFunctions).containsKey("com.example.app/com.example.utils#print");
        GenericDocument actualAppFunction =
                appFunctions.get("com.example.app/com.example.utils#print");
        assertThat(actualAppFunction.getPropertyString("functionId"))
                .isEqualTo("com.example.utils#print");
        assertThat(
                        Arrays.asList(
                                actualAppFunction.getPropertyStringArray(
                                        "appFunctionParameterMetadata[0].parameterName")))
                .containsExactly("test1", "test2");
        assertThat(
                        Arrays.asList(
                                actualAppFunction.getPropertyStringArray(
                                        "appFunctionParameterMetadata[1].parameterName")))
                .containsExactly("test3");
    }

    @Test
    public void parseIntoMapForGivenSchemas_validXmlWithUnderscores_worksWithDynamicSchemas()
            throws Exception {
        setXmlInput(
                "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n"
                        + "<appfunctions>\n"
                        + "  <appfunction>\n"
                        + "    <id>com.example.utils#print</id>\n"
                        + "    <function__id>com.example.utils#print</function__id>\n"
                        + "    <enabled_by_default>true</enabled_by_default>\n"
                        + "    <scHema_veRsion>10</scHema_veRsion>\n"
                        + "  </appfunction>\n"
                        + "</appfunctions>");

        Map<String, AppFunctionDocument> appFunctions =
                mParser.parseIntoMapForGivenSchemas(
                        mPackageManager, TEST_PACKAGE_NAME, TEST_XML_ASSET_FILE_PATH, TEST_SCHEMAS);

        assertThat(appFunctions).hasSize(1);
        assertThat(appFunctions).containsKey("com.example.app/com.example.utils#print");
        GenericDocument actualAppFunction =
                appFunctions.get("com.example.app/com.example.utils#print");
        assertThat(actualAppFunction.getNamespace()).isEqualTo("app_functions");
        assertThat(actualAppFunction.getId()).isEqualTo("com.example.app/com.example.utils#print");
        assertThat(actualAppFunction.getSchemaType())
                .isEqualTo("AppFunctionStaticMetadata-com.example.app");
        assertThat(actualAppFunction.getPropertyString("functionId"))
                .isEqualTo("com.example.utils#print");
        assertThat(actualAppFunction.getPropertyBoolean("enabledByDefault")).isEqualTo(true);
        assertThat(actualAppFunction.getPropertyLong("schemaVersion")).isEqualTo(10);
    }

    @Test
    public void parseIntoMapForGivenSchemas_xmlTagWithStartingOrOnlyUnderscores_propertiesIgnored()
            throws Exception {
        setXmlInput(
                "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n"
                        + "<appfunctions>\n"
                        + "  <AppFunctionStaticMetadata>\n"
                        + "    <id>com.example.utils#print</id>\n"
                        + "    <functionId>com.example.utils#print</functionId>\n"
                        + "    <___>test</___>\n"
                        + "    <_schema_version_>test</_schema_version_>\n"
                        + "  </AppFunctionStaticMetadata>\n"
                        + "</appfunctions>");

        Map<String, AppFunctionDocument> appFunctions =
                mParser.parseIntoMapForGivenSchemas(
                        mPackageManager, TEST_PACKAGE_NAME, TEST_XML_ASSET_FILE_PATH, TEST_SCHEMAS);

        assertThat(appFunctions).hasSize(1);
        assertThat(appFunctions).containsKey("com.example.app/com.example.utils#print");
        GenericDocument actualAppFunction =
                appFunctions.get("com.example.app/com.example.utils#print");
        assertThat(actualAppFunction.getNamespace()).isEqualTo("app_functions");
        assertThat(actualAppFunction.getId()).isEqualTo("com.example.app/com.example.utils#print");
        assertThat(actualAppFunction.getSchemaType())
                .isEqualTo("AppFunctionStaticMetadata-com.example.app");
        assertThat(actualAppFunction.getPropertyString("functionId"))
                .isEqualTo("com.example.utils#print");
        assertThat(actualAppFunction.getPropertyNames())
                .containsExactly("functionId", "packageName", "mobileApplicationQualifiedId");
    }
}
