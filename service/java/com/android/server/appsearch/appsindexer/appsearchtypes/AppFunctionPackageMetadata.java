/*
 * Copyright (C) 2026 The Android Open Source Project
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


import android.app.appsearch.AppSearchSchema;
import android.os.Build;

/** Represents package-level metadata for app function. */
public class AppFunctionPackageMetadata {
    /** The schema type for the package-level metadata used by AppFunction documents. */
    public static final String SCHEMA_TYPE = "AppFunctionPackageData";

    /** Whether a parent type should be set for {@link AppFunctionPackageMetadata}. */
    public static boolean shouldSetParentType() {
        // While added to the SDK in V, addParentTypes() is safe to call on versions above U (Icing
        // added support for inheritance in the original U build, but the AppSearch API was not
        // exposed until M-2023-08).
        //
        // The fact that this code is running at all implies that the current version > M-2023-08.
        // Therefore, we know that addParentTypes is an available API.
        //
        // However, we cannot call addParentTypes() on T devices as a mainline rollback could break
        // the schema.
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
    }

    /** The parent type for {@link AppFunctionPackageMetadata}. */
    public static final AppSearchSchema PARENT_TYPE_APPSEARCH_SCHEMA =
            new AppSearchSchema.Builder(SCHEMA_TYPE).build();
}
