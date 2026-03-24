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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.GenericDocument;

import java.util.Objects;

/**
 * Represents the state of the AppFunctionService for a given {@link MobileApplication}.
 *
 * <p>The service is identified by the {@link #getId()} of the document.
 *
 * @hide
 */
public class AppFunctionServiceState extends GenericDocument {
    private static final String TAG = "AppSearchAppFunctionServiceState";

    public static final String SCHEMA_TYPE = "AppFunctionServiceState";

    public static final String APP_FUNCTION_SERVICE_STATE_PROPERTY_IS_ENABLED = "isEnabled";

    public static final AppSearchSchema SCHEMA =
            new AppSearchSchema.Builder(SCHEMA_TYPE)
                    .addProperty(
                            new AppSearchSchema.BooleanPropertyConfig.Builder(
                                            APP_FUNCTION_SERVICE_STATE_PROPERTY_IS_ENABLED)
                                    .setCardinality(
                                            AppSearchSchema.PropertyConfig.CARDINALITY_REQUIRED)
                                    .build())
                    .build();

    /** Constructs a {@link AppFunctionServiceState}. */
    public AppFunctionServiceState(@NonNull GenericDocument document) {
        super(document);
    }

    /** Returns whether the AppFunctionService is enabled for the app. */
    public boolean isEnabled() {
        return getPropertyBoolean(APP_FUNCTION_SERVICE_STATE_PROPERTY_IS_ENABLED);
    }

    /** Builder for {@link AppFunctionServiceState}. */
    public static final class Builder extends GenericDocument.Builder<Builder> {
        public Builder(@NonNull String serviceName) {
            super(
                    MobileApplication.APPS_NAMESPACE,
                    serviceName,
                    SCHEMA_TYPE);
        }

        /** Sets whether the AppFunctionService is enabled for the app. */
        @NonNull
        public Builder setEnabled(boolean isEnabled) {
            setPropertyBoolean(APP_FUNCTION_SERVICE_STATE_PROPERTY_IS_ENABLED, isEnabled);
            return this;
        }

        /** Constructs a {@link AppFunctionServiceState}. */
        public Builder(@NonNull GenericDocument document) {
            super(document);
        }

        @Override
        public AppFunctionServiceState build() {
            return new AppFunctionServiceState(super.build());
        }
    }
}
