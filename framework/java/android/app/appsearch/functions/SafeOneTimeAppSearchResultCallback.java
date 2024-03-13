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

package android.app.appsearch.functions;

import android.annotation.NonNull;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.aidl.AppSearchResultParcel;
import android.app.appsearch.aidl.IAppSearchResultCallback;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.util.Objects;

/**
 * A wrapper of IAppSearchResultCallback which swallows the {@link RemoteException}.
 * This callback is intended for one-time use only. Subsequent calls to onResult() will be ignored.
 *
 * @hide
 */
public class SafeOneTimeAppSearchResultCallback {
    private static final String TAG = "AppSearchSafeCallback";

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private boolean mOnResultCalled = false;

    @NonNull
    private final IAppSearchResultCallback mCallback;

    public SafeOneTimeAppSearchResultCallback(@NonNull IAppSearchResultCallback callback) {
        mCallback = Objects.requireNonNull(callback);
    }

    public void onResult(@NonNull AppSearchResult<?> result) {
        onResult(new AppSearchResultParcel<>(result));
    }

    public void onResult(@NonNull AppSearchResultParcel<?> result) {
        synchronized(mLock) {
            if (mOnResultCalled) {
                Log.w(TAG, "Ignore subsequent calls to onResult()");
                return;
            }
            try {
                mCallback.onResult(result);
                mOnResultCalled = true;
            } catch (RemoteException ex) {
                // Failed to notify the other end. Ignore.
                Log.w(TAG, "Failed to invoke the callback", ex);
            }
        }
    }
}
