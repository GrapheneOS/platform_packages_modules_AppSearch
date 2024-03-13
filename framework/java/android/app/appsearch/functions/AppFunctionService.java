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

import android.annotation.FlaggedApi;
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Service;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.aidl.AppSearchResultParcel;
import android.app.appsearch.aidl.IAppFunctionService;
import android.app.appsearch.aidl.IAppSearchResultCallback;
import android.app.appsearch.flags.Flags;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;

import java.util.function.Consumer;

/**
 * Abstract base class to provide app functions to the system.
 *
 * <p>Include the following in the manifest:
 *
 * <pre>
 * {@literal
 * <service android:name=".YourService"
 *      android:permission="android.permission.BIND_APP_FUNCTION_SERVICE">
 *    <intent-filter>
 *      <action android:name="android.app.appsearch.functions.AppFunctionService" />
 *    </intent-filter>
 * </service>
 * }
 * </pre>
 *
 * @see AppFunctionManager
 */
@FlaggedApi(Flags.FLAG_ENABLE_APP_FUNCTIONS)
public abstract class AppFunctionService extends Service {
    private static final String TAG = "AppSearchAppFunction";

    /**
     * The {@link Intent} that must be declared as handled by the service. To be supported, the
     * service must also require the {@link android.Manifest.permission#BIND_APP_FUNCTION_SERVICE}
     * permission so that other applications can not abuse it.
     */
    @NonNull
    public static final String SERVICE_INTERFACE =
            "android.app.appsearch.functions.AppFunctionService";

    private final Binder mBinder =
            new IAppFunctionService.Stub() {
                @Override
                public void executeAppFunction(
                        @NonNull ExecuteAppFunctionRequest request,
                        @NonNull IAppSearchResultCallback callback) {
                    // TODO(b/327134039): Replace this check with the new permission
                    if (Binder.getCallingUid() != Process.SYSTEM_UID) {
                         throw new SecurityException("Can only be called by the system server");
                    }
                    SafeOneTimeAppSearchResultCallback safeCallback =
                            new SafeOneTimeAppSearchResultCallback(callback);
                    try {
                        AppFunctionService.this.onExecuteFunction(
                                request,
                                appFunctionResult ->
                                        safeCallback.onResult(
                                                new AppSearchResultParcel<>(appFunctionResult)));
                    } catch (Exception ex) {
                        // Apps should handle exceptions. But if they don't, report the error on
                        // behalf of them.
                        safeCallback.onResult(
                                new AppSearchResultParcel<>(
                                        AppSearchResult.throwableToFailedResult(ex)));
                    }
                }
            };

    @NonNull
    @Override
    public IBinder onBind(@Nullable Intent intent) {
        return mBinder;
    }

    /**
     * Called by the system to execute a specific app function.
     *
     * <p>This method is triggered when the system requests your AppFunctionService to handle a
     * particular function you have registered and made available.
     *
     * <p>This method is always triggered in the main thread. You should run heavy tasks on a worker
     * thread and dispatch the result with the given callback. You should always report back the
     * result using the callback, no matter if the execution was successful or not.
     *
     * @param request The function execution request.
     * @param callback A callback to report back the result.
     */
    @MainThread
    public abstract void onExecuteFunction(
            @NonNull ExecuteAppFunctionRequest request,
            @NonNull Consumer<AppSearchResult<ExecuteAppFunctionResponse>> callback);
}
