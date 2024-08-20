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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.ResolveInfo;

import java.util.Objects;

/**
 * Contains information about components in a package that will be indexed by the app indexer.
 *
 * @hide
 */
public class ResolveInfos {
    @Nullable private ResolveInfo mAppFunctionServiceInfo;
    @Nullable private ResolveInfo mLaunchActivityResolveInfo;

    public ResolveInfos(
            @Nullable ResolveInfo appFunctionServiceInfo,
            @Nullable ResolveInfo launchActivityResolveInfo) {
        mAppFunctionServiceInfo = appFunctionServiceInfo;
        mLaunchActivityResolveInfo = launchActivityResolveInfo;
    }

    /**
     * Return {@link ResolveInfo} for the packages AppFunction service. If {@code null}, it means
     * this app doesn't have an app function service.
     */
    @Nullable
    public ResolveInfo getAppFunctionServiceInfo() {
        return mAppFunctionServiceInfo;
    }

    /**
     * Return {@link ResolveInfo} for the packages launch activity. If {@code null}, it means this
     * app doesn't have a launch activity.
     */
    @Nullable
    public ResolveInfo getLaunchActivityResolveInfo() {
        return mLaunchActivityResolveInfo;
    }

    public static class Builder {
        @Nullable private ResolveInfo mAppFunctionServiceInfo;
        @Nullable private ResolveInfo mLaunchActivityResolveInfo;

        /** Sets the {@link ResolveInfo} for the packages AppFunction service */
        @NonNull
        public Builder setAppFunctionServiceResolveInfo(@NonNull ResolveInfo resolveInfo) {
            mAppFunctionServiceInfo = Objects.requireNonNull(resolveInfo);
            return this;
        }

        /** Sets the {@link ResolveInfo} for the packages launch activity. */
        @NonNull
        public Builder setLaunchActivityResolveInfo(@NonNull ResolveInfo resolveInfo) {
            mLaunchActivityResolveInfo = Objects.requireNonNull(resolveInfo);
            return this;
        }

        /** Builds the {@link ResolveInfos} object. */
        @NonNull
        public ResolveInfos build() {
            return new ResolveInfos(mAppFunctionServiceInfo, mLaunchActivityResolveInfo);
        }
    }
}
