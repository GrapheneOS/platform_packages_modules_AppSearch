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
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;

import java.util.Objects;

/**
 * Contains information about components in a package that will be indexed by the app indexer.
 *
 * @hide
 */
public class ResolveInfos {
    @Nullable private final ResolveInfo mLaunchActivityResolveInfo;
    @Nullable private final AppFunctionResolveInfo mAppFunctionResolveInfo;

    public ResolveInfos(
            @Nullable ResolveInfo launchActivityResolveInfo,
            @Nullable AppFunctionResolveInfo appFunctionResolveInfo) {
        mLaunchActivityResolveInfo = launchActivityResolveInfo;
        mAppFunctionResolveInfo = appFunctionResolveInfo;
    }

    /**
     * Return {@link AppFunctionResolveInfo} for the packages AppFunction service. If {@code null},
     * it means this app doesn't have an app function service or app level app functions defined.
     */
    @Nullable
    public AppFunctionResolveInfo getAppFunctionResolveInfo() {
        return mAppFunctionResolveInfo;
    }

    /**
     * Return {@link ResolveInfo} for the packages launch activity. If {@code null}, it means this
     * app doesn't have a launch activity.
     */
    @Nullable
    public ResolveInfo getLaunchActivityResolveInfo() {
        return mLaunchActivityResolveInfo;
    }

    /** Builder for {@link ResolveInfos}. */
    public static class Builder {
        @Nullable private ResolveInfo mLaunchActivityResolveInfo;
        @Nullable private AppFunctionResolveInfo mAppFunctionResolveInfo;

        /** Sets the {@link ResolveInfo} for the packages launch activity. */
        @NonNull
        public Builder setLaunchActivityResolveInfo(@NonNull ResolveInfo resolveInfo) {
            mLaunchActivityResolveInfo = Objects.requireNonNull(resolveInfo);
            return this;
        }

        @NonNull
        public Builder setAppFunctionResolveInfo(
                @NonNull AppFunctionResolveInfo appFunctionResolveInfo) {
            mAppFunctionResolveInfo = Objects.requireNonNull(appFunctionResolveInfo);
            return this;
        }

        /** Builds the {@link ResolveInfos} object. */
        @NonNull
        public ResolveInfos build() {
            return new ResolveInfos(mLaunchActivityResolveInfo, mAppFunctionResolveInfo);
        }
    }
}
