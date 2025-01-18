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

package com.android.server.appsearch;

import android.annotation.NonNull;
import android.content.Context;
import android.os.ParcelFileDescriptor;

import com.android.server.appsearch.external.localstorage.AppSearchRevocableFileDescriptor;
import com.android.server.appsearch.external.localstorage.RevocableFileDescriptorStore;

import java.io.IOException;
import java.util.Objects;

/**
 * The Framework implementation of {@link RevocableFileDescriptorStore}.
 *
 * <p>We need to pass {@link ParcelFileDescriptor} cross binder to the SDK side in the framework.
 * When sending a {@link ParcelFileDescriptor} cross the binder, the system will generate a
 * duplicate {@link ParcelFileDescriptor} and pass that dup to the other process. AppSearch will
 * lose the control of that dup. We need wrap the {@link ParcelFileDescriptor} to a {@link
 * FrameworkRevocableFileDescriptor} to give us the ability the control the sending {@link
 * ParcelFileDescriptor}.
 */
public class FrameworkRevocableFileDescriptorStore extends RevocableFileDescriptorStore {

    @NonNull private final Context mContext;

    public FrameworkRevocableFileDescriptorStore(
            @NonNull Context context, @NonNull ServiceAppSearchConfig config) {
        super(config);
        mContext = Objects.requireNonNull(context);
    }

    @NonNull
    @Override
    public AppSearchRevocableFileDescriptor wrapToRevocableFileDescriptor(
            @NonNull ParcelFileDescriptor parcelFileDescriptor, int mode) throws IOException {
        return new FrameworkRevocableFileDescriptor(
                mContext, parcelFileDescriptor.getFileDescriptor(), mode);
    }
}
