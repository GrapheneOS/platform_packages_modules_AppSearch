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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.UserHandle;
import android.util.Log;

import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * Utility class for establishing temporary connections to services and executing
 * operations with a defined timeout. This class ensures that services are unbound
 * after the operation or if a timeout occurs.
 *
 * @hide
 */
public class ServiceCallHelper<T> {
    private static final String TAG = "AppSearchAppFunction";

    @NonNull
    private final Context mContext;
    @NonNull
    private final Function<IBinder, T> mInterfaceConverter;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Executor mExecutor;

    /**
     * @context                   The system context.
     * @param interfaceConverter  A function responsible for converting an IBinder object into
     *                            the desired service interface.
     * @param executor            An Executor instance to dispatch callback.
     */

    public ServiceCallHelper(
            @NonNull Context context,
            @NonNull Function<IBinder, T> interfaceConverter,
            @NonNull Executor executor) {
        mContext = context;
        mInterfaceConverter = interfaceConverter;
        mExecutor = executor;
    }

    /**
     * Initiates service binding and executes a provided method when the service connects.
     * Unbinds the service after execution or upon timeout. Returns the result of the
     * bindService API.
     *
     * <p>When the service connection was made successfully, it's the caller responsibility to
     * report the usage is completed and can be unbound by calling
     * {@link ServiceUsageCompleteListener#onCompleted()}.
     *
     * <p>This method includes a timeout mechanism to prevent the system from being stuck in a
     * state where a service is bound indefinitely (for example, if the binder method never
     * returns). This helps ensure that the calling app does not remain alive unnecessarily.
     *
     * @param intent          An Intent object that describes the service that should be bound.
     * @param bindFlags       Flags used to control the binding process See
     *                        {@link Context#bindService}.
     * @param timeoutInMillis The maximum time in milliseconds to wait for the service connection.
     * @param userHandle      The UserHandle of the user for which the service should be bound.
     * @param callback        A callback to be invoked for various events. See
     *                        {@link RunServiceCallCallback}.
     */
    public boolean runServiceCall(
            @NonNull Intent intent,
            int bindFlags,
            long timeoutInMillis,
            @NonNull UserHandle userHandle,
            @NonNull RunServiceCallCallback<T> callback) {
        OneOffServiceConnection serviceConnection =
                new OneOffServiceConnection(
                        intent,
                        bindFlags,
                        timeoutInMillis,
                        userHandle,
                        callback);

        return serviceConnection.bindAndRun();
    }

    private class OneOffServiceConnection implements ServiceConnection,
            ServiceUsageCompleteListener {
        private final Intent mIntent;
        private final int mFlags;
        private final long mTimeoutMillis;
        private final UserHandle mUserHandle;
        private final RunServiceCallCallback<T> mCallback;
        private final Runnable mTimeoutCallback;

        OneOffServiceConnection(
                @NonNull Intent intent,
                int flags,
                long timeoutMillis,
                @NonNull UserHandle userHandle,
                @NonNull RunServiceCallCallback<T> callback) {
            mIntent = intent;
            mFlags = flags;
            mTimeoutMillis = timeoutMillis;
            mCallback = callback;
            mTimeoutCallback = () -> mExecutor.execute(() -> {
                safeUnbind();
                mCallback.onTimedOut();
            });
            mUserHandle = userHandle;
        }

        public boolean bindAndRun() {
            boolean bindServiceResult = mContext.bindServiceAsUser(
                    mIntent,
                    this,
                    mFlags,
                    mUserHandle);

            if (bindServiceResult) {
                mHandler.postDelayed(mTimeoutCallback, mTimeoutMillis);
            } else {
                safeUnbind();
            }

            return bindServiceResult;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            T serviceInterface = mInterfaceConverter.apply(service);

            mExecutor.execute(() -> mCallback.onServiceConnected(serviceInterface, this));
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            safeUnbind();
            mExecutor.execute(mCallback::onFailedToConnect);
        }

        @Override
        public void onBindingDied(ComponentName name) {
            safeUnbind();
            mExecutor.execute(mCallback::onFailedToConnect);
        }

        @Override
        public void onNullBinding(ComponentName name) {
            safeUnbind();
            mExecutor.execute(mCallback::onFailedToConnect);
        }

        private void safeUnbind() {
            try {
                mHandler.removeCallbacks(mTimeoutCallback);
                mContext.unbindService(this);
            } catch (Exception ex) {
                Log.w(TAG, "Failed to unbind", ex);
            }
        }

        @Override
        public void onCompleted() {
            safeUnbind();
        }
    }

    /**
     * An interface for clients to signal that they have finished using a bound service.
     */
    public interface ServiceUsageCompleteListener {
        /**
         * Called when a client has finished using a bound service. This indicates that
         * the service can be safely unbound.
         */
        void onCompleted();
    }

    public interface RunServiceCallCallback<T> {
        /**
         * Called when the service connection has been established. Uses
         * {@code serviceUsageCompleteListener} to report finish using the connected service.
         */
        void onServiceConnected(
                @NonNull T service,
                @NonNull ServiceUsageCompleteListener serviceUsageCompleteListener);

        /** Called when the service connection was failed to establish. */
        void onFailedToConnect();

        /**
         * Called when the whole operation(i.e. binding and the service call) takes longer than
         * allowed.
         */
        void onTimedOut();
    }
}
