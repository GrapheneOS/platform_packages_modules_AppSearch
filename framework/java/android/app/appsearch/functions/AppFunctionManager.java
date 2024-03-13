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

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.UserHandleAware;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.SearchSessionUtil;
import android.app.appsearch.aidl.AppSearchAttributionSource;
import android.app.appsearch.aidl.AppSearchResultParcel;
import android.app.appsearch.aidl.ExecuteAppFunctionAidlRequest;
import android.app.appsearch.aidl.IAppSearchManager;
import android.app.appsearch.aidl.IAppSearchResultCallback;
import android.app.appsearch.flags.Flags;
import android.content.Context;
import android.os.RemoteException;
import android.os.SystemClock;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Provides app functions related functionalities.
 *
 * <p>App function is a specific piece of functionality that an app offers to the system. These
 * functionalities can be integrated into various system features.
 */
@FlaggedApi(Flags.FLAG_ENABLE_APP_FUNCTIONS)
public class AppFunctionManager {
    private final IAppSearchManager mService;
    private final Context mContext;

    /** @hide */
    public AppFunctionManager(@NonNull Context context, @NonNull IAppSearchManager service) {
        mContext = Objects.requireNonNull(context);
        mService = Objects.requireNonNull(service);
    }

    /**
     * Executes an app function provided by {@link AppFunctionService} through the system.
     *
     * @param request The request.
     * @param executor Executor on which to invoke the callback.
     * @param callback A callback to receive the function execution result.
     */
    @UserHandleAware
    public void executeAppFunction(
            @NonNull ExecuteAppFunctionRequest request,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<AppSearchResult<ExecuteAppFunctionResponse>> callback) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(callback);

        ExecuteAppFunctionAidlRequest aidlRequest = new ExecuteAppFunctionAidlRequest(
                request,
                AppSearchAttributionSource.createAttributionSource(mContext),
                mContext.getUser(),
                SystemClock.elapsedRealtime());
        try {
            mService.executeAppFunction(
                    aidlRequest,
                    new IAppSearchResultCallback.Stub() {
                        @Override
                        public void onResult(AppSearchResultParcel result) {
                            SearchSessionUtil.safeExecute(
                                    executor, callback, () -> callback.accept(result.getResult()));
                        }
                    });
        } catch (RemoteException ex) {
            ex.rethrowFromSystemServer();
        }
    }
}
