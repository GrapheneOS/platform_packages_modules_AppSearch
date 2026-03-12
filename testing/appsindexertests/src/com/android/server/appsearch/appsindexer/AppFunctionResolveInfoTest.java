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

import static android.app.appsearch.testutil.FrameworkFlagUtils.assumeFlagIsEnabled;
import static android.app.appsearch.testutil.FrameworkFlagUtils.isFlagEnabled;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;

import com.android.server.appsearch.appsindexer.AppFunctionResolveInfo.AppFunctionXmlInfo;
import com.android.server.appsearch.appsindexer.AppFunctionResolveInfo.XmlFile;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class AppFunctionResolveInfoTest {

    private PackageManager mPackageManager;
    private static final String PACKAGE_NAME = "com.test.package";

    @Before
    public void setUp() {
        mPackageManager = mock(PackageManager.class);
    }

    @Test
    public void testCreate_noResolveInfos_noAppLevelProperty() throws Exception {
        when(mPackageManager.getProperty(anyString(), eq(PACKAGE_NAME)))
                .thenThrow(new PackageManager.NameNotFoundException());

        AppFunctionResolveInfo info =
                AppFunctionResolveInfo.create(
                        mPackageManager, PACKAGE_NAME, Collections.emptyList());
        assertThat(info).isNull();
    }

    @Test
    public void testCreate_withResolveInfos() throws Exception {
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.serviceInfo = new ServiceInfo();
        resolveInfo.serviceInfo.packageName = PACKAGE_NAME;
        resolveInfo.serviceInfo.name = "TestService";

        AppFunctionResolveInfo info =
                AppFunctionResolveInfo.create(
                        mPackageManager, PACKAGE_NAME, Collections.singletonList(resolveInfo));
        assertThat(info).isNotNull();
        assertThat(info.getAppFunctionServiceResolveInfos()).hasSize(1);
    }

    @Test
    public void testCreate_withAppLevelProperty() throws Exception {
        PackageManager.Property property =
                new PackageManager.Property(
                        "android.app.appfunctions", "file.xml", PACKAGE_NAME, null);
        when(mPackageManager.getProperty(eq("android.app.appfunctions.v2"), eq(PACKAGE_NAME)))
                .thenThrow(new PackageManager.NameNotFoundException());
        when(mPackageManager.getProperty(eq("android.app.appfunctions"), eq(PACKAGE_NAME)))
                .thenReturn(property);

        // Even with no resolve infos, if there's app level property and the flag is enabled, it
        // should return an info.
        // If flag is disabled, it will return null.
        AppFunctionResolveInfo info =
                AppFunctionResolveInfo.create(
                        mPackageManager, PACKAGE_NAME, Collections.emptyList());
        if (isFlagEnabled(android.app.appfunctions.flags.Flags.FLAG_ENABLE_DYNAMIC_APP_FUNCTIONS)) {
            assertThat(info).isNotNull();
        } else {
            assertThat(info).isNull();
        }
    }

    @Test
    public void testGetAppFunctionXmlInfos_serviceLevelStringProperty() throws Exception {
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.serviceInfo = new ServiceInfo();
        resolveInfo.serviceInfo.packageName = PACKAGE_NAME;
        resolveInfo.serviceInfo.name = "TestService";

        ComponentName componentName = new ComponentName(PACKAGE_NAME, "TestService");

        PackageManager.Property property =
                new PackageManager.Property(
                        "android.app.appfunctions.v2",
                        "service_file.xml",
                        PACKAGE_NAME,
                        "TestService");
        when(mPackageManager.getProperty(eq("android.app.appfunctions.v2"), eq(componentName)))
                .thenReturn(property);

        AppFunctionResolveInfo info =
                new AppFunctionResolveInfo(
                        PACKAGE_NAME, Collections.singletonList(resolveInfo), null);

        List<AppFunctionXmlInfo> xmlInfos = info.getAppFunctionXmlInfos(mPackageManager);
        assertThat(xmlInfos).hasSize(1);
        assertThat(xmlInfos.get(0).getServiceName()).isEqualTo("TestService");
        assertThat(xmlInfos.get(0).getXmlFile().getXmlFilePath()).isEqualTo("service_file.xml");
        assertThat(xmlInfos.get(0).getXmlFile().getFileResourceId()).isEqualTo(Resources.ID_NULL);
    }

    @Test
    public void testGetAppFunctionXmlInfos_serviceLevelResourceIdProperty() throws Exception {
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.serviceInfo = new ServiceInfo();
        resolveInfo.serviceInfo.packageName = PACKAGE_NAME;
        resolveInfo.serviceInfo.name = "TestService";

        ComponentName componentName = new ComponentName(PACKAGE_NAME, "TestService");

        PackageManager.Property property =
                new PackageManager.Property(
                        "android.app.appfunctions.v2", 12345, true, PACKAGE_NAME, "TestService");
        when(mPackageManager.getProperty(eq("android.app.appfunctions.v2"), eq(componentName)))
                .thenReturn(property);

        AppFunctionResolveInfo info =
                new AppFunctionResolveInfo(
                        PACKAGE_NAME, Collections.singletonList(resolveInfo), null);

        List<AppFunctionXmlInfo> xmlInfos = info.getAppFunctionXmlInfos(mPackageManager);
        assertThat(xmlInfos).hasSize(1);
        assertThat(xmlInfos.get(0).getServiceName()).isEqualTo("TestService");
        assertThat(xmlInfos.get(0).getXmlFile().getXmlFilePath()).isNull();
        assertThat(xmlInfos.get(0).getXmlFile().getFileResourceId()).isEqualTo(12345);
    }

    @Test
    public void testGetAppFunctionXmlInfos_appLevelStringProperty() throws Exception {
        assumeFlagIsEnabled(android.app.appfunctions.flags.Flags.FLAG_ENABLE_DYNAMIC_APP_FUNCTIONS);
        PackageManager.Property appLevelProperty =
                new PackageManager.Property(
                        "android.app.appfunctions",
                        "app_file.xml, app_file2.xml",
                        PACKAGE_NAME,
                        null);

        AppFunctionResolveInfo info =
                new AppFunctionResolveInfo(PACKAGE_NAME, Collections.emptyList(), appLevelProperty);

        List<AppFunctionXmlInfo> xmlInfos = info.getAppFunctionXmlInfos(mPackageManager);
        assertThat(xmlInfos).hasSize(2);
        assertThat(xmlInfos.get(0).getServiceName()).isEqualTo("@null");
        assertThat(xmlInfos.get(0).getXmlFile().getXmlFilePath()).isEqualTo("app_file.xml");
        assertThat(xmlInfos.get(1).getXmlFile().getXmlFilePath()).isEqualTo("app_file2.xml");
    }

    @Test
    public void testGetAppFunctionXmlInfos_appLevelResourceIdProperty_array() throws Exception {
        assumeFlagIsEnabled(android.app.appfunctions.flags.Flags.FLAG_ENABLE_DYNAMIC_APP_FUNCTIONS);
        assumeFlagIsEnabled(
                com.android.appsearch.flags.Flags.FLAG_ENABLE_HANDLING_MULTIPLE_APP_FUNCTION_XML);

        PackageManager.Property appLevelProperty =
                new PackageManager.Property(
                        "android.app.appfunctions", 54321, true, PACKAGE_NAME, null);

        Resources resources = mock(Resources.class);
        TypedArray typedArray = mock(TypedArray.class);
        when(mPackageManager.getResourcesForApplication(PACKAGE_NAME)).thenReturn(resources);
        when(resources.getResourceTypeName(54321)).thenReturn("array");
        when(resources.obtainTypedArray(54321)).thenReturn(typedArray);
        when(typedArray.length()).thenReturn(2);
        when(typedArray.getResourceId(0, Resources.ID_NULL)).thenReturn(111);
        when(typedArray.getResourceId(1, Resources.ID_NULL)).thenReturn(222);

        AppFunctionResolveInfo info =
                new AppFunctionResolveInfo(PACKAGE_NAME, Collections.emptyList(), appLevelProperty);

        List<AppFunctionXmlInfo> xmlInfos = info.getAppFunctionXmlInfos(mPackageManager);
        assertThat(xmlInfos).hasSize(2);
        assertThat(xmlInfos.get(0).getServiceName()).isEqualTo("@null");
        assertThat(xmlInfos.get(0).getXmlFile().getXmlFilePath()).isNull();
        assertThat(xmlInfos.get(0).getXmlFile().getFileResourceId()).isEqualTo(111);
        assertThat(xmlInfos.get(1).getServiceName()).isEqualTo("@null");
        assertThat(xmlInfos.get(1).getXmlFile().getXmlFilePath()).isNull();
        assertThat(xmlInfos.get(1).getXmlFile().getFileResourceId()).isEqualTo(222);
    }

    @Test
    public void testGetAppFunctionXmlInfos_appLevelResourceIdProperty() throws Exception {
        assumeFlagIsEnabled(android.app.appfunctions.flags.Flags.FLAG_ENABLE_DYNAMIC_APP_FUNCTIONS);

        PackageManager.Property appLevelProperty =
                new PackageManager.Property(
                        "android.app.appfunctions", 54321, true, PACKAGE_NAME, null);

        Resources resources = mock(Resources.class);
        when(mPackageManager.getResourcesForApplication(PACKAGE_NAME)).thenReturn(resources);
        when(resources.getResourceTypeName(54321)).thenReturn("xml");

        AppFunctionResolveInfo info =
                new AppFunctionResolveInfo(PACKAGE_NAME, Collections.emptyList(), appLevelProperty);

        List<AppFunctionXmlInfo> xmlInfos = info.getAppFunctionXmlInfos(mPackageManager);
        assertThat(xmlInfos).hasSize(1);
        assertThat(xmlInfos.get(0).getServiceName()).isEqualTo("@null");
        assertThat(xmlInfos.get(0).getXmlFile().getXmlFilePath()).isNull();
        assertThat(xmlInfos.get(0).getXmlFile().getFileResourceId()).isEqualTo(54321);
    }

    @Test
    public void testGetAppFunctionXmlInfos_withSchemaProperty() throws Exception {
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.serviceInfo = new ServiceInfo();
        resolveInfo.serviceInfo.packageName = PACKAGE_NAME;
        resolveInfo.serviceInfo.name = "TestService";

        ComponentName componentName = new ComponentName(PACKAGE_NAME, "TestService");

        PackageManager.Property property =
                new PackageManager.Property(
                        "android.app.appfunctions",
                        "service_file.xml",
                        PACKAGE_NAME,
                        "TestService");
        when(mPackageManager.getProperty(eq("android.app.appfunctions.v2"), eq(componentName)))
                .thenThrow(new PackageManager.NameNotFoundException());
        when(mPackageManager.getProperty(eq("android.app.appfunctions"), eq(componentName)))
                .thenReturn(property);

        PackageManager.Property schemaProperty =
                new PackageManager.Property(
                        "android.app.appfunctions.schema",
                        "schema_file.xml",
                        PACKAGE_NAME,
                        "TestService");
        when(mPackageManager.getProperty(eq("android.app.appfunctions.schema"), eq(componentName)))
                .thenReturn(schemaProperty);

        AppFunctionResolveInfo info =
                new AppFunctionResolveInfo(
                        PACKAGE_NAME, Collections.singletonList(resolveInfo), null);

        List<AppFunctionXmlInfo> xmlInfos = info.getAppFunctionXmlInfos(mPackageManager);
        assertThat(xmlInfos).hasSize(1);
        assertThat(xmlInfos.get(0).hasSchemaProperty()).isTrue();
        assertThat(xmlInfos.get(0).useSchemaForParsing()).isTrue();
    }

    @Test
    public void testRunWithXmlParser_withResourceId() throws Exception {
        AppFunctionXmlInfo xmlInfo =
                new AppFunctionXmlInfo(
                        PACKAGE_NAME, new XmlFile(null, 12345), false, "TestService");

        Resources resources = mock(Resources.class);
        XmlResourceParser parser = mock(XmlResourceParser.class);
        when(mPackageManager.getResourcesForApplication(PACKAGE_NAME)).thenReturn(resources);
        when(resources.getXml(12345)).thenReturn(parser);

        boolean[] called = new boolean[1];
        xmlInfo.runWithXmlParser(
                mPackageManager,
                result -> {
                    assertThat(result).isEqualTo(parser);
                    called[0] = true;
                });
        assertThat(called[0]).isTrue();
        Mockito.verify(parser).close();
    }

    @Test
    public void testRunWithXmlParser_withFilePath() throws Exception {
        AppFunctionXmlInfo xmlInfo =
                new AppFunctionXmlInfo(
                        PACKAGE_NAME,
                        new XmlFile("test_path.xml", Resources.ID_NULL),
                        false,
                        "TestService");

        Resources resources = mock(Resources.class);
        AssetManager assetManager = mock(AssetManager.class);
        when(mPackageManager.getResourcesForApplication(PACKAGE_NAME)).thenReturn(resources);
        when(resources.getAssets()).thenReturn(assetManager);

        InputStream inputStream = Mockito.spy(new ByteArrayInputStream("<test></test>".getBytes()));
        when(assetManager.open("test_path.xml")).thenReturn(inputStream);

        boolean[] called = new boolean[1];
        xmlInfo.runWithXmlParser(
                mPackageManager,
                result -> {
                    assertThat(result).isNotNull();
                    called[0] = true;
                });
        assertThat(called[0]).isTrue();
        Mockito.verify(inputStream).close();
    }

    @Test
    public void testRunWithXmlParser_nameNotFound() throws Exception {
        AppFunctionXmlInfo xmlInfo =
                new AppFunctionXmlInfo(
                        PACKAGE_NAME, new XmlFile(null, 12345), false, "TestService");

        when(mPackageManager.getResourcesForApplication(PACKAGE_NAME))
                .thenThrow(new PackageManager.NameNotFoundException());

        boolean[] called = new boolean[1];
        xmlInfo.runWithXmlParser(mPackageManager, result -> called[0] = true);
        assertThat(called[0]).isFalse();
    }
}
