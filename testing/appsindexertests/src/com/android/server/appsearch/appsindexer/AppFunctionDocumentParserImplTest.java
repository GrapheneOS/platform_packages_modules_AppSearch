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

import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.when;

import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.GenericDocument;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Build;
import android.platform.test.annotations.RequiresFlagsEnabled;

import androidx.annotation.NonNull;

import com.android.server.appsearch.appsindexer.appsearchtypes.AppFunctionDocument;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
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

    private static final String TEST_PRINT_APPFUNCTION_XML =
            "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n"
                    + "<appfunctions>\n"
                    + "  <AppFunctionStaticMetadata>\n"
                    + "    <id>com.example.utils#print</id>\n"
                    + "    <functionId>com.example.utils#print</functionId>\n"
                    + "    <enabledByDefault>true</enabledByDefault>\n"
                    + "    <schemaVersion>10</schemaVersion>\n"
                    + "  </AppFunctionStaticMetadata>\n"
                    + "</appfunctions>";

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
    public void parseIntoMapForGivenSchemas_singleAppFunctionWithPrimitiveProperties()
            throws Exception {
        XmlPullParser xmlPullParser = getXmlPullParser(TEST_PRINT_APPFUNCTION_XML);

        Map<String, AppFunctionDocument> appFunctions =
                mParser.parseIntoMapForGivenSchemas(
                        mPackageManager, TEST_PACKAGE_NAME, xmlPullParser, TEST_SCHEMAS, "");

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
    @RequiresFlagsEnabled({android.app.appfunctions.flags.Flags.FLAG_ENABLE_DYNAMIC_APP_FUNCTIONS})
    public void parseIntoMapForGivenSchemas_withNotNullServiceName_servicePropertyIsSet()
            throws Exception {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA);
        XmlPullParser xmlPullParser = getXmlPullParser(TEST_PRINT_APPFUNCTION_XML);
        final String testXmlService = "com.android.TestAppFunctionsService";

        Map<String, AppFunctionDocument> appFunctions =
                mParser.parseIntoMapForGivenSchemas(
                        mPackageManager,
                        TEST_PACKAGE_NAME,
                        xmlPullParser,
                        TEST_SCHEMAS,
                        testXmlService);

        assertThat(appFunctions).hasSize(1);
        assertThat(appFunctions).containsKey("com.example.app/com.example.utils#print");
        GenericDocument actualAppFunction =
                appFunctions.get("com.example.app/com.example.utils#print");
        assertThat(actualAppFunction.getPropertyString("serviceName")).isEqualTo(testXmlService);
    }

    @NonNull
    private static XmlPullParser getXmlPullParser(String xml) throws XmlPullParserException {
        XmlPullParser xmlPullParser = XmlPullParserFactory.newInstance().newPullParser();
        xmlPullParser.setInput(new InputStreamReader(new ByteArrayInputStream(xml.getBytes())));
        return xmlPullParser;
    }

    @Test
    public void parseIntoMapForGivenSchemas_multipleAppFunctions() throws Exception {
        XmlPullParser xmlPullParser =
                getXmlPullParser(
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
                        mPackageManager, TEST_PACKAGE_NAME, xmlPullParser, TEST_SCHEMAS, "");

        assertThat(appFunctions).hasSize(2);
        assertThat(appFunctions).containsKey("com.example.app/com.example.utils#print");
        assertThat(appFunctions).containsKey("com.example.app/com.example.utils#print");
    }

    @Test
    public void parseIntoMapForGivenSchemas_malformedXml_returnsEmptyMap() throws Exception {
        XmlPullParser xmlPullParser =
                getXmlPullParser(
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
                        mPackageManager, TEST_PACKAGE_NAME, xmlPullParser, TEST_SCHEMAS, "");

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
        XmlPullParser xmlPullParser =
                getXmlPullParser(
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
                        mPackageManager, TEST_PACKAGE_NAME, xmlPullParser, TEST_SCHEMAS, "");

        assertThat(appFunctions).hasSize(2);
        assertThat(appFunctions).containsKey("com.example.app/com.example.utils#print1");
        assertThat(appFunctions).containsKey("com.example.app/com.example.utils#print2");
    }

    @Test
    public void parseIntoMapForGivenSchemas_singleAppFunctionWithDocumentProperties()
            throws Exception {
        XmlPullParser xmlPullParser =
                getXmlPullParser(
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
                        mPackageManager, TEST_PACKAGE_NAME, xmlPullParser, TEST_SCHEMAS, "");

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
        XmlPullParser xmlPullParser =
                getXmlPullParser(
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
                        mPackageManager, TEST_PACKAGE_NAME, xmlPullParser, TEST_SCHEMAS, "");

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
        XmlPullParser xmlPullParser =
                getXmlPullParser(
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
                        mPackageManager, TEST_PACKAGE_NAME, xmlPullParser, TEST_SCHEMAS, "");

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
        XmlPullParser xmlPullParser =
                getXmlPullParser(
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
                        mPackageManager, TEST_PACKAGE_NAME, xmlPullParser, TEST_SCHEMAS, "");

        assertThat(appFunctions).isEmpty();
    }

    @Test
    public void parseIntoMapForGivenSchemas_singleAppFunctionWithRepeatedProperties()
            throws Exception {
        XmlPullParser xmlPullParser =
                getXmlPullParser(
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
                        mPackageManager, TEST_PACKAGE_NAME, xmlPullParser, TEST_SCHEMAS, "");

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
        XmlPullParser xmlPullParser =
                getXmlPullParser(
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
                        mPackageManager, TEST_PACKAGE_NAME, xmlPullParser, TEST_SCHEMAS, "");

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
    public void parseIntoMapForGivenSchemas_xmlTagWithStartingOrOnlyUnderscores_noFunctionParsed()
            throws Exception {
        XmlPullParser xmlPullParser =
                getXmlPullParser(
                        "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n"
                                + "<appfunctions>\n"
                                + "  <AppFunctionStaticMetadata>\n"
                                + "    <id>com.example.utils#print</id>\n"
                                + "    <functionId>com.example.utils#invalid</functionId>\n"
                                + "    <___>test</___>\n"
                                + "    <_schema_version_>test</_schema_version_>\n"
                                + "  </AppFunctionStaticMetadata>\n"
                                + "  <AppFunctionStaticMetadata>\n"
                                + "    <id>com.example.utils#print</id>\n"
                                + "    <functionId>com.example.utils#print</functionId>\n"
                                + "  </AppFunctionStaticMetadata>\n"
                                + "</appfunctions>");

        Map<String, AppFunctionDocument> appFunctions =
                mParser.parseIntoMapForGivenSchemas(
                        mPackageManager, TEST_PACKAGE_NAME, xmlPullParser, TEST_SCHEMAS, "");

        assertThat(appFunctions).isEmpty();
    }

    @Test
    public void parseIntoMapForGivenSchemas_unknownProperties_noFunctionParsed() throws Exception {
        XmlPullParser xmlPullParser =
                getXmlPullParser(
                        "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n"
                                + "<appfunctions>\n"
                                + "  <AppFunctionStaticMetadata>\n"
                                + "    <id>com.example.utils#print</id>\n"
                                + "    <functionId>com.example.utils#invalid</functionId>\n"
                                + "    <unknownProperty>"
                                + "        <id>nestedId</id>\n"
                                + "        <functionId>nestedFunctionId</functionId>\n"
                                + "    </unknownProperty>\n"
                                + "  </AppFunctionStaticMetadata>\n"
                                + "  <AppFunctionStaticMetadata>\n"
                                + "    <id>com.example.utils#print</id>\n"
                                + "    <functionId>com.example.utils#print</functionId>\n"
                                + "  </AppFunctionStaticMetadata>\n"
                                + "</appfunctions>");

        Map<String, AppFunctionDocument> appFunctions =
                mParser.parseIntoMapForGivenSchemas(
                        mPackageManager, TEST_PACKAGE_NAME, xmlPullParser, TEST_SCHEMAS, "");

        assertThat(appFunctions).isEmpty();
    }
}
