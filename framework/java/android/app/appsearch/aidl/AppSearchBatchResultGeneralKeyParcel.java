/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.app.appsearch.aidl;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.appsearch.AppSearchBatchResult;
import android.app.appsearch.AppSearchBlobHandle;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.ParcelableUtil;
import android.app.appsearch.safeparcel.AbstractSafeParcelable;
import android.app.appsearch.safeparcel.SafeParcelable;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;

import com.android.appsearch.flags.Flags;

import java.util.Map;
import java.util.Objects;

/**
 * Parcelable wrapper around {@link AppSearchBatchResult}.
 *
 * <p>{@link AppSearchBatchResult} can contain any type of key and value, including non-parcelable
 * values. For the specific case of sending {@link AppSearchBatchResult} across Binder, this class
 * wraps an {@link AppSearchBatchResult} and provides parcelability of the whole structure.
 *
 * @param <KeyType> The type of the keys for which the results will be reported. We are passing the
 *     class name of the KeyType to parcelable. Rename the class name of KeyType maybe have backward
 *     compatibility issue for GmsCore.
 * @param <ValueType> The type of result object for successful calls. Must be a parcelable type.
 * @hide
 */
@SafeParcelable.Class(
        creator = "AppSearchBatchResultGeneralKeyParcelCreator",
        creatorIsFinal = false)
public final class AppSearchBatchResultGeneralKeyParcel<KeyType, ValueType>
        extends AbstractSafeParcelable {

    @NonNull
    // Provide ClassLoader when read from bundle in getResult() method
    @SuppressWarnings("rawtypes")
    public static final Parcelable.Creator<AppSearchBatchResultGeneralKeyParcel> CREATOR =
            new AppSearchBatchResultGeneralKeyParcelCreator() {
                @Override
                public AppSearchBatchResultGeneralKeyParcel createFromParcel(Parcel in) {
                    byte[] dataBlob = Objects.requireNonNull(ParcelableUtil.readBlob(in));
                    Parcel unmarshallParcel = Parcel.obtain();
                    try {
                        unmarshallParcel.unmarshall(dataBlob, 0, dataBlob.length);
                        unmarshallParcel.setDataPosition(0);
                        String keyClassName = unmarshallParcel.readString();
                        Bundle keyBundle = unmarshallParcel.readBundle(getClass().getClassLoader());
                        Bundle valueBundle =
                                unmarshallParcel.readBundle(getClass().getClassLoader());
                        return new AppSearchBatchResultGeneralKeyParcel(
                                keyClassName, keyBundle, valueBundle);
                    } finally {
                        unmarshallParcel.recycle();
                    }
                }
            };

    @Field(id = 1)
    @NonNull
    final String mKeyClassName;

    // Map stores keys of AppSearchBatchResult. The key will be index number. Associated with
    // mAppSearchResultBundle
    @Field(id = 2)
    @NonNull
    final Bundle mKeyBundle;

    // Map stores AppSearchResultParcel Value. The key will be index number. Associated with
    // mKeyBundle
    @Field(id = 3)
    @NonNull
    final Bundle mAppSearchResultValueBundle;

    @Nullable private AppSearchBatchResult<KeyType, ValueType> mResultCached;

    @Constructor
    AppSearchBatchResultGeneralKeyParcel(
            @Param(id = 1) String keyClassName,
            @Param(id = 2) Bundle keyBundle,
            @Param(id = 3) Bundle appSearchResultValueBundle) {
        mKeyClassName = keyClassName;
        mKeyBundle = keyBundle;
        mAppSearchResultValueBundle = appSearchResultValueBundle;
    }

    /**
     * Creates a new {@link AppSearchBatchResultParcel} from the given {@link AppSearchBatchResult}
     * results which has {@link AppSearchBlobHandle} as keys and {@link ParcelFileDescriptor} as
     * values.
     */
    @SuppressWarnings("unchecked")
    @NonNull
    @FlaggedApi(Flags.FLAG_ENABLE_BLOB_STORE)
    public static AppSearchBatchResultGeneralKeyParcel<AppSearchBlobHandle, ParcelFileDescriptor>
            fromBlobHandleToPfd(
                    @NonNull
                            AppSearchBatchResult<AppSearchBlobHandle, ParcelFileDescriptor>
                                    result) {
        Bundle keyAppSearchResultBundle = new Bundle();
        Bundle valueAppSearchResultBundle = new Bundle();
        int i = 0;
        for (Map.Entry<AppSearchBlobHandle, AppSearchResult<ParcelFileDescriptor>> entry :
                result.getAll().entrySet()) {
            AppSearchResultParcel<ParcelFileDescriptor> valueAppSearchResultParcel;
            // Create result from value in success case and errorMessage in failure case.
            if (entry.getValue().isSuccess()) {
                valueAppSearchResultParcel =
                        AppSearchResultParcel.fromParcelFileDescriptor(
                                entry.getValue().getResultValue());
            } else {
                valueAppSearchResultParcel =
                        AppSearchResultParcel.fromFailedResult(entry.getValue());
            }
            keyAppSearchResultBundle.putParcelable(String.valueOf(i), entry.getKey());
            valueAppSearchResultBundle.putParcelable(String.valueOf(i), valueAppSearchResultParcel);
            ++i;
        }
        return new AppSearchBatchResultGeneralKeyParcel<>(
                AppSearchBlobHandle.class.getName(),
                keyAppSearchResultBundle,
                valueAppSearchResultBundle);
    }

    /**
     * Creates a new {@link AppSearchBatchResultParcel} from the given {@link AppSearchBatchResult}
     * results which has {@link AppSearchBlobHandle} as keys and {@code Void} as values.
     */
    @NonNull
    @FlaggedApi(Flags.FLAG_ENABLE_BLOB_STORE)
    public static AppSearchBatchResultGeneralKeyParcel<AppSearchBlobHandle, Void>
            fromBlobHandleToVoid(@NonNull AppSearchBatchResult<AppSearchBlobHandle, Void> result) {
        Bundle keyAppSearchResultBundle = new Bundle();
        Bundle valueAppSearchResultBundle = new Bundle();
        int i = 0;
        for (Map.Entry<AppSearchBlobHandle, AppSearchResult<Void>> entry :
                result.getAll().entrySet()) {
            AppSearchResultParcel<ParcelFileDescriptor> valueAppSearchResultParcel;
            // Create result from value in success case and errorMessage in failure case.
            if (entry.getValue().isSuccess()) {
                valueAppSearchResultParcel = AppSearchResultParcel.fromVoid();
            } else {
                valueAppSearchResultParcel =
                        AppSearchResultParcel.fromFailedResult(entry.getValue());
            }
            keyAppSearchResultBundle.putParcelable(String.valueOf(i), entry.getKey());
            valueAppSearchResultBundle.putParcelable(String.valueOf(i), valueAppSearchResultParcel);
            ++i;
        }
        return new AppSearchBatchResultGeneralKeyParcel<>(
                AppSearchBlobHandle.class.getName(),
                keyAppSearchResultBundle,
                valueAppSearchResultBundle);
    }

    /**
     * Gets the {@link AppSearchBatchResult} out of this {@link
     * AppSearchBatchResultGeneralKeyParcel}.
     */
    @NonNull
    @SuppressWarnings("unchecked")
    public AppSearchBatchResult<KeyType, ValueType> getResult() {
        if (mResultCached == null) {
            AppSearchBatchResult.Builder<KeyType, ValueType> builder =
                    new AppSearchBatchResult.Builder<>();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    java.lang.Class<?> clazz = java.lang.Class.forName(mKeyClassName);
                    for (String key : mKeyBundle.keySet()) {
                        builder.setResult(
                                (KeyType) mKeyBundle.getParcelable(key, clazz),
                                mAppSearchResultValueBundle
                                        .getParcelable(key, AppSearchResultParcel.class)
                                        .getResult());
                    }
                } catch (ClassNotFoundException e) {
                    // Impossible, the key type name should always match the KeyType.
                    throw new RuntimeException("Class not found: " + e.getMessage(), e);
                }
            } else {
                for (String key : mKeyBundle.keySet()) {
                    builder.setResult(
                            mKeyBundle.getParcelable(key),
                            ((AppSearchResultParcel) mAppSearchResultValueBundle.getParcelable(key))
                                    .getResult());
                }
            }

            mResultCached = builder.build();
        }
        return mResultCached;
    }

    /** @hide */
    @Override
    @SuppressWarnings("unchecked")
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        byte[] bytes;
        // Create a parcel object to serialize results. So that we can use Parcel.writeBlob() to
        // send data. WriteBlob() could take care of whether to pass data via binder directly or
        // Android shared memory if the data is large.
        Parcel data = Parcel.obtain();
        try {
            data.writeString(mKeyClassName);
            data.writeBundle(mKeyBundle);
            data.writeBundle(mAppSearchResultValueBundle);
            bytes = data.marshall();
        } finally {
            data.recycle();
        }
        ParcelableUtil.writeBlob(dest, bytes);
    }
}
