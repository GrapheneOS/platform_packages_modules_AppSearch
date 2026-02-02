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

package com.android.server.appsearch.appsindexer.appsearchtypes;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.app.appsearch.AppSearchSchema;

import android.platform.test.annotations.RequiresFlagsEnabled;
import org.junit.Test;

public class AppFunctionStaticMetadataTest {
    @Test
    public void testAppFunction() {
        String functionId = "com.example.message#send_message";
        String schemaName = "send_message";
        String schemaCategory = "messaging";
        int stringResId = 3;
        long schemaVersion = 2;
        boolean enabledByDefault = false;
        boolean restrictCallersWithExecuteAppFunctions = false;
        String packageName = "com.example.message";

        AppFunctionStaticMetadata appFunction =
                new AppFunctionStaticMetadata.Builder(packageName, functionId, "android")
                        .setSchemaName(schemaName)
                        .setSchemaVersion(schemaVersion)
                        .setSchemaCategory(schemaCategory)
                        .setEnabledByDefault(enabledByDefault)
                        .setRestrictCallersWithExecuteAppFunctions(
                                restrictCallersWithExecuteAppFunctions)
                        .setDisplayNameStringRes(stringResId)
                        .setScope(AppFunctionStaticMetadata.SCOPE_GLOBAL)
                        .build();
        assertThat(appFunction.getFunctionId()).isEqualTo(functionId);
        assertThat(appFunction.getPackageName()).isEqualTo(packageName);
        assertThat(appFunction.getSchemaName()).isEqualTo(schemaName);
        assertThat(appFunction.getSchemaVersion()).isEqualTo(schemaVersion);
        assertThat(appFunction.getRestrictCallersWithExecuteAppFunctions())
                .isEqualTo(restrictCallersWithExecuteAppFunctions);
        assertThat(appFunction.getSchemaCategory()).isEqualTo(schemaCategory);
        assertThat(appFunction.getEnabledByDefault()).isEqualTo(enabledByDefault);
        assertThat(appFunction.getDisplayNameStringRes()).isEqualTo(stringResId);
        assertThat(appFunction.getMobileApplicationQualifiedId())
                .isEqualTo("android$apps-db/apps#com.example.message");
        assertThat(appFunction.getScope())
                .isEqualTo(AppFunctionStaticMetadata.SCOPE_GLOBAL);
    }

    @Test
    public void testChildSchema() {
        AppSearchSchema appSearchSchema =
                AppFunctionStaticMetadata.createAppFunctionSchemaForPackage("com.xyz");

        if (AppFunctionStaticMetadata.shouldSetParentType()) {
            assertThat(appSearchSchema.getParentTypes())
                    .containsExactly(AppFunctionStaticMetadata.SCHEMA_TYPE);
        }
    }

    @Test
    public void testParentSchema() {
        assertThat(AppFunctionStaticMetadata.PARENT_TYPE_APPSEARCH_SCHEMA.getSchemaType())
                .isEqualTo(AppFunctionStaticMetadata.SCHEMA_TYPE);
    }

    @Test
    @RequiresFlagsEnabled(android.app.appfunctions.flags.Flags.FLAG_ENABLE_DYNAMIC_APP_FUNCTIONS)
    public void testScope_global_succeeds() {
        AppFunctionStaticMetadata appFunction =
                new AppFunctionStaticMetadata.Builder("pkg", "id", "android")
                        .setScope(AppFunctionStaticMetadata.SCOPE_GLOBAL)
                        .build();

        assertThat(appFunction.getScope())
                .isEqualTo(AppFunctionStaticMetadata.SCOPE_GLOBAL);
    }

    @Test
    @RequiresFlagsEnabled(android.app.appfunctions.flags.Flags.FLAG_ENABLE_DYNAMIC_APP_FUNCTIONS)
    public void testScope_activity_appLevel_succeeds() {
        AppFunctionStaticMetadata appFunction =
                new AppFunctionStaticMetadata.Builder("pkg", "id", "android")
                        .setScope(AppFunctionStaticMetadata.SCOPE_ACTIVITY)
                        .setServiceName(AppFunctionStaticMetadata.APPLICATION_LEVEL_SERVICE_NAME)
                        .build();

        assertThat(appFunction.getScope())
                .isEqualTo(AppFunctionStaticMetadata.SCOPE_ACTIVITY);
        assertThat(appFunction.getServiceName())
                .isEqualTo(AppFunctionStaticMetadata.APPLICATION_LEVEL_SERVICE_NAME);
    }

    @Test
    @RequiresFlagsEnabled(android.app.appfunctions.flags.Flags.FLAG_ENABLE_DYNAMIC_APP_FUNCTIONS)
    public void testScope_invalid_throws() {
        AppFunctionStaticMetadata.Builder builder =
                new AppFunctionStaticMetadata.Builder("pkg", "id", "android")
                        .setScope("invalid");

        assertThrows(IllegalStateException.class, builder::build);
    }

    @Test
    @RequiresFlagsEnabled(android.app.appfunctions.flags.Flags.FLAG_ENABLE_DYNAMIC_APP_FUNCTIONS)
    public void testScope_notSet_serviceBased_defaultsToGlobal() {
        AppFunctionStaticMetadata appFunction =
                new AppFunctionStaticMetadata.Builder("pkg", "id", "android")
                        .setServiceName("some.service.Name")
                        .build();

        assertThat(appFunction.getScope())
                .isEqualTo(AppFunctionStaticMetadata.SCOPE_GLOBAL);
        assertThat(appFunction.getServiceName())
                .isEqualTo("some.service.Name");
    }

    @Test
    @RequiresFlagsEnabled(android.app.appfunctions.flags.Flags.FLAG_ENABLE_DYNAMIC_APP_FUNCTIONS)
    public void testScope_notSet_appLevel_throws() {
        AppFunctionStaticMetadata.Builder builder =
                new AppFunctionStaticMetadata.Builder("pkg", "id", "android")
                        .setServiceName(AppFunctionStaticMetadata.APPLICATION_LEVEL_SERVICE_NAME);

        assertThrows(IllegalStateException.class, builder::build);
    }
}
