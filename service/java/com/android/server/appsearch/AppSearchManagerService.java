/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static android.app.appsearch.AppSearchResult.RESULT_DENIED;
import static android.app.appsearch.AppSearchResult.RESULT_NOT_FOUND;
import static android.app.appsearch.AppSearchResult.RESULT_OK;
import static android.app.appsearch.AppSearchResult.RESULT_RATE_LIMITED;
import static android.app.appsearch.AppSearchResult.RESULT_SECURITY_ERROR;
import static android.app.appsearch.AppSearchResult.throwableToFailedResult;
import static android.os.Process.INVALID_UID;

import static com.android.server.appsearch.external.localstorage.stats.QueryStats.VISIBILITY_SCOPE_GLOBAL;
import static com.android.server.appsearch.external.localstorage.stats.QueryStats.VISIBILITY_SCOPE_LOCAL;
import static com.android.server.appsearch.util.ServiceImplHelper.invokeCallbackOnError;
import static com.android.server.appsearch.util.ServiceImplHelper.invokeCallbackOnResult;

import android.annotation.BinderThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.UserIdInt;
import android.annotation.WorkerThread;
import android.app.appsearch.AppSearchBatchResult;
import android.app.appsearch.AppSearchBlobHandle;
import android.app.appsearch.AppSearchEnvironment;
import android.app.appsearch.AppSearchEnvironmentFactory;
import android.app.appsearch.AppSearchMigrationHelper;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.CommitBlobResponse;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.GetByDocumentIdRequest;
import android.app.appsearch.GetSchemaResponse;
import android.app.appsearch.InternalSetSchemaResponse;
import android.app.appsearch.InternalVisibilityConfig;
import android.app.appsearch.OpenBlobForReadResponse;
import android.app.appsearch.OpenBlobForWriteResponse;
import android.app.appsearch.RemoveBlobResponse;
import android.app.appsearch.SearchResult;
import android.app.appsearch.SearchResultPage;
import android.app.appsearch.SearchSpec;
import android.app.appsearch.SearchSuggestionResult;
import android.app.appsearch.SetSchemaResponse;
import android.app.appsearch.SetSchemaResponse.MigrationFailure;
import android.app.appsearch.StorageInfo;
import android.app.appsearch.aidl.AppSearchAttributionSource;
import android.app.appsearch.aidl.AppSearchBatchResultParcel;
import android.app.appsearch.aidl.AppSearchResultParcel;
import android.app.appsearch.aidl.AppSearchResultParcelV2;
import android.app.appsearch.aidl.CommitBlobAidlRequest;
import android.app.appsearch.aidl.GetDocumentsAidlRequest;
import android.app.appsearch.aidl.GetNamespacesAidlRequest;
import android.app.appsearch.aidl.GetNextPageAidlRequest;
import android.app.appsearch.aidl.GetSchemaAidlRequest;
import android.app.appsearch.aidl.GetStorageInfoAidlRequest;
import android.app.appsearch.aidl.GlobalSearchAidlRequest;
import android.app.appsearch.aidl.IAppSearchBatchResultCallback;
import android.app.appsearch.aidl.IAppSearchManager;
import android.app.appsearch.aidl.IAppSearchObserverProxy;
import android.app.appsearch.aidl.IAppSearchResultCallback;
import android.app.appsearch.aidl.InitializeAidlRequest;
import android.app.appsearch.aidl.InvalidateNextPageTokenAidlRequest;
import android.app.appsearch.aidl.OpenBlobForReadAidlRequest;
import android.app.appsearch.aidl.OpenBlobForWriteAidlRequest;
import android.app.appsearch.aidl.PersistToDiskAidlRequest;
import android.app.appsearch.aidl.PutDocumentsAidlRequest;
import android.app.appsearch.aidl.PutDocumentsFromFileAidlRequest;
import android.app.appsearch.aidl.RegisterObserverCallbackAidlRequest;
import android.app.appsearch.aidl.RemoveBlobAidlRequest;
import android.app.appsearch.aidl.RemoveByDocumentIdAidlRequest;
import android.app.appsearch.aidl.RemoveByQueryAidlRequest;
import android.app.appsearch.aidl.ReportUsageAidlRequest;
import android.app.appsearch.aidl.SearchAidlRequest;
import android.app.appsearch.aidl.SearchSuggestionAidlRequest;
import android.app.appsearch.aidl.SetBlobVisibilityAidlRequest;
import android.app.appsearch.aidl.SetSchemaAidlRequest;
import android.app.appsearch.aidl.UnregisterObserverCallbackAidlRequest;
import android.app.appsearch.aidl.WriteSearchResultsToFileAidlRequest;
import android.app.appsearch.exceptions.AppSearchException;
import android.app.appsearch.safeparcel.GenericDocumentParcel;
import android.app.appsearch.stats.BaseStats;
import android.app.appsearch.stats.SchemaMigrationStats;
import android.app.appsearch.util.ExceptionUtil;
import android.app.appsearch.util.LogUtil;
import android.app.role.RoleManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageStats;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.appsearch.flags.Flags;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalManagerRegistry;
import com.android.server.SystemService;
import com.android.server.appsearch.external.localstorage.stats.CallStats;
import com.android.server.appsearch.external.localstorage.stats.OptimizeStats;
import com.android.server.appsearch.external.localstorage.stats.QueryStats;
import com.android.server.appsearch.external.localstorage.stats.SetSchemaStats;
import com.android.server.appsearch.external.localstorage.usagereporting.SearchSessionStatsExtractor;
import com.android.server.appsearch.external.localstorage.visibilitystore.CallerAccess;
import com.android.server.appsearch.external.localstorage.visibilitystore.VisibilityStore;
import com.android.server.appsearch.isolated_storage_service.IsolatedStorageServiceManager;
import com.android.server.appsearch.observer.AppSearchObserverProxy;
import com.android.server.appsearch.stats.PlatformLogger;
import com.android.server.appsearch.stats.StatsCollector;
import com.android.server.appsearch.transformer.EnterpriseSearchResultPageTransformer;
import com.android.server.appsearch.transformer.EnterpriseSearchSpecTransformer;
import com.android.server.appsearch.util.AdbDumpUtil;
import com.android.server.appsearch.util.ApiCallRecord;
import com.android.server.appsearch.util.ExecutorManager;
import com.android.server.appsearch.util.ServiceImplHelper;
import com.android.server.appsearch.visibilitystore.FrameworkCallerAccess;
import com.android.server.usage.StorageStatsManagerLocal;
import com.android.server.usage.StorageStatsManagerLocal.StorageStatsAugmenter;

import com.google.android.icing.proto.DebugInfoProto;
import com.google.android.icing.proto.DebugInfoVerbosity;
import com.google.android.icing.proto.PersistType;
import com.google.android.icing.proto.StorageInfoProto;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The main service implementation which contains AppSearch's platform functionality.
 *
 * @hide
 */
public class AppSearchManagerService extends SystemService {
    private static final String TAG = "AppSearchManagerService";
    @VisibleForTesting
    static final String SYSTEM_UI_INTELLIGENCE = "android.app.role.SYSTEM_UI_INTELLIGENCE";

    // TODO(b/401245113) make it configurable.
    // BatchPut flush conditions.
    private static final int MAX_NUMBER_OF_DOCS_BUFFERED = 50;

    /**
     * An executor for system activity not tied to any particular user.
     *
     * <p>NOTE: Never call shutdownNow(). AppSearchManagerService persists forever even as
     * individual users are added and removed -- without this pool the service will be broken. And,
     * clients waiting for callbacks will never receive anything and will hang.
     */
    private static final Executor SHARED_EXECUTOR = ExecutorManager.createDefaultExecutorService();

    private final Context mContext;
    private final ExecutorManager mExecutorManager;
    private final AppSearchEnvironment mAppSearchEnvironment;
    private final ServiceAppSearchConfig mAppSearchConfig;

    private PackageManager mPackageManager;
    private RoleManager mRoleManager;
    private ServiceImplHelper mServiceImplHelper;
    private IsolatedStorageServiceManager mIsolatedStorageServiceManager;
    private AppSearchUserInstanceManager mAppSearchUserInstanceManager;

    // Keep a reference for the lifecycle instance, so we can access other services like
    // ContactsIndexer for dumpsys purpose.
    private final AppSearchModule.Lifecycle mLifecycle;
    private final SearchSessionStatsExtractor mSearchSessionStatsExtractor;

    // Tracks the scheduled persistToDisk task, ensuring only one persistToDisk task is scheduled
    // at a time.
    //TODO(b/405169836) Consider moving the ScheduledFutures to a better place, e.g.,
    // AppSearchUserInstance.
    @GuardedBy("mPerUserPersistToDiskFutureLocked")
    private final Map<UserHandle, ScheduledFuture<?>> mPerUserPersistToDiskFutureLocked =
            new ArrayMap<>();

    public AppSearchManagerService(Context context, AppSearchModule.Lifecycle lifecycle) {
        super(context);
        mContext = Objects.requireNonNull(context);
        mLifecycle = Objects.requireNonNull(lifecycle);
        mAppSearchEnvironment = AppSearchEnvironmentFactory.getEnvironmentInstance();
        mAppSearchConfig = AppSearchComponentFactory.getConfigInstance(SHARED_EXECUTOR, mContext);
        mExecutorManager = new ExecutorManager(mAppSearchConfig);
        mSearchSessionStatsExtractor = new SearchSessionStatsExtractor();
        if (IsolatedStorageServiceManager.useIsolatedStorage(mContext, mAppSearchConfig)) {
            Log.i(TAG, "Isolated storage is enabled.");
            mIsolatedStorageServiceManager =
                    new IsolatedStorageServiceManager(
                            mContext,
                            mAppSearchConfig,
                            ExecutorManager.createDefaultScheduledExecutorService());
        } else {
            Log.i(TAG, "Isolated storage is not enabled.");
        }
    }

    @Override
    public void onStart() {
        publishBinderService(Context.APP_SEARCH_SERVICE, new Stub());
        mPackageManager = getContext().getPackageManager();
        mRoleManager = getContext().getSystemService(RoleManager.class);
        mServiceImplHelper = new ServiceImplHelper(mContext);
        mAppSearchUserInstanceManager = AppSearchUserInstanceManager.getInstance();
        registerReceivers();
        LocalManagerRegistry.getManager(StorageStatsManagerLocal.class)
                .registerStorageStatsAugmenter(new AppSearchStorageStatsAugmenter(), TAG);
        LocalManagerRegistry.addManager(LocalService.class, new LocalService());
        initializeIsolatedStorageIfNeeded();
    }

    private void initializeIsolatedStorageIfNeeded() {
        if (mIsolatedStorageServiceManager == null) {
            Log.i(TAG, "Isolated storage is not enabled, no need to initialize it");
            return;
        }
        mExecutorManager.executeLambdaForUserNoCallbackAsync(
                UserHandle.SYSTEM,
                () -> {
                    Log.i(TAG, "Initializing isolated storage service");
                    try {
                        mIsolatedStorageServiceManager.initialize();
                    } catch (AppSearchException e) {
                        Log.e(TAG, "Unable to prepare IsolatedStorageServiceManager", e);
                    }
                });
    }

    @Override
    public void onBootPhase(/* @BootPhase */ int phase) {
        if (phase == PHASE_BOOT_COMPLETED) {
            StatsCollector.getInstance(mContext, SHARED_EXECUTOR);
        }
    }

    private void registerReceivers() {
        mContext.registerReceiverForAllUsers(
                new UserActionReceiver(),
                new IntentFilter(Intent.ACTION_USER_REMOVED),
                /* broadcastPermission= */ null,
                /* scheduler= */ null);

        //TODO(b/145759910) Add a direct callback when user clears the data instead of relying on
        // broadcasts
        IntentFilter packageChangedFilter = new IntentFilter();
        packageChangedFilter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        packageChangedFilter.addAction(Intent.ACTION_PACKAGE_DATA_CLEARED);
        packageChangedFilter.addDataScheme("package");
        packageChangedFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        mContext.registerReceiverForAllUsers(
                new PackageChangedReceiver(),
                packageChangedFilter,
                /* broadcastPermission= */ null,
                /* scheduler= */ null);
    }

    private class UserActionReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(@NonNull Context context, @NonNull Intent intent) {
            Objects.requireNonNull(context);
            Objects.requireNonNull(intent);
            if (Intent.ACTION_USER_REMOVED.equals(intent.getAction())) {
                UserHandle userHandle = intent.getParcelableExtra(Intent.EXTRA_USER);
                if (userHandle == null) {
                    Log.e(TAG,
                            "Extra " + Intent.EXTRA_USER + " is missing in the intent: " + intent);
                    return;
                }
                onUserRemoved(userHandle);
            } else {
                Log.e(TAG, "Received unknown intent: " + intent);
            }
        }
    }

    private class PackageChangedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(@NonNull Context context, @NonNull Intent intent) {
            Objects.requireNonNull(context);
            Objects.requireNonNull(intent);

            String action = intent.getAction();
            if (action == null) {
                return;
            }

            switch (action) {
                case Intent.ACTION_PACKAGE_FULLY_REMOVED:
                case Intent.ACTION_PACKAGE_DATA_CLEARED:
                    Uri data = intent.getData();
                    if (data == null) {
                        Log.e(TAG, "Data is missing in the intent: " + intent);
                        return;
                    }

                    String packageName = data.getSchemeSpecificPart();
                    if (packageName == null) {
                        Log.e(TAG, "Package name is missing in the intent: " + intent);
                        return;
                    }

                    if (LogUtil.DEBUG) {
                        Log.d(TAG, "Received " + action + " broadcast on package: " + packageName);
                    }

                    int uid = intent.getIntExtra(Intent.EXTRA_UID, INVALID_UID);
                    if (uid == INVALID_UID) {
                        Log.e(TAG, "uid is missing in the intent: " + intent);
                        return;
                    }

                    handlePackageRemoved(packageName, uid);
                    break;
                default:
                    Log.e(TAG, "Received unknown intent: " + intent);
            }
        }
    }

    private void handlePackageRemoved(@NonNull String packageName, int uid) {
        UserHandle userHandle = UserHandle.getUserHandleForUid(uid);
        if (mServiceImplHelper.isUserLocked(userHandle)) {
            // We cannot access a locked user's directory and remove package data from it.
            // We should remove those uninstalled package data when the user is unlocking.
            return;
        }
        // Only clear the package's data if AppSearch exists for this user.
        if (mAppSearchEnvironment.getAppSearchDir(mContext, userHandle).exists()) {
            mExecutorManager.executeLambdaForUserNoCallbackAsync(
                    userHandle,
                    () -> {
                        try {
                            Context userContext =
                                    mAppSearchEnvironment.createContextAsUser(mContext, userHandle);
                            AppSearchUserInstance instance =
                                    mAppSearchUserInstanceManager.getOrCreateUserInstance(
                                            userContext,
                                            userHandle,
                                            mAppSearchConfig,
                                            mExecutorManager,
                                            mIsolatedStorageServiceManager);
                            instance.getAppSearchImpl().clearPackageData(packageName);
                            dispatchChangeNotifications(instance);
                            instance.getLogger().removeCacheForPackage(packageName);
                        } catch (AppSearchException | RuntimeException | IOException e) {
                            Log.e(TAG, "Unable to remove data for package: " + packageName, e);
                            ExceptionUtil.handleException(e);
                        }
                    });
        }
    }

    @Override
    public void onUserUnlocking(@NonNull TargetUser user) {
        Objects.requireNonNull(user);
        UserHandle userHandle = user.getUserHandle();
        mServiceImplHelper.setUserIsLocked(userHandle, false);

        if (mIsolatedStorageServiceManager != null) {
            SHARED_EXECUTOR.execute(
                    () ->
                            mIsolatedStorageServiceManager.onUserUnlocking(
                                    mAppSearchConfig, userHandle));
        }

        // Only schedule task if AppSearch exists for this user.
        if (mAppSearchEnvironment.getAppSearchDir(mContext, userHandle).exists()) {
            mExecutorManager.executeLambdaForUserNoCallbackAsync(
                    userHandle,
                    () -> {
                        // Try to prune garbage package data, this is to recover if user remove a
                        // package and reboot the device before we prune the package data.
                        try {
                            Context userContext = mAppSearchEnvironment
                                    .createContextAsUser(mContext, userHandle);
                            AppSearchUserInstance instance =
                                    mAppSearchUserInstanceManager.getOrCreateUserInstance(
                                            userContext,
                                            userHandle,
                                            mAppSearchConfig,
                                            mExecutorManager,
                                            mIsolatedStorageServiceManager);
                            List<PackageInfo> installedPackageInfos = userContext
                                    .getPackageManager()
                                    .getInstalledPackages(/* flags= */ 0);
                            Set<String> packagesToKeep =
                                    new ArraySet<>(installedPackageInfos.size());
                            for (int i = 0; i < installedPackageInfos.size(); i++) {
                                packagesToKeep.add(installedPackageInfos.get(i).packageName);
                            }
                            packagesToKeep.add(VisibilityStore.VISIBILITY_PACKAGE_NAME);
                            instance.getAppSearchImpl().prunePackageData(packagesToKeep);
                        } catch (AppSearchException | RuntimeException e) {
                            Log.e(TAG, "Unable to prune packages for " + user, e);
                            ExceptionUtil.handleException(e);
                        }

                        // Try to schedule fully persist job.
                        try {
                            AppSearchMaintenanceService.scheduleFullyPersistJob(mContext,
                                    userHandle.getIdentifier(),
                                    mAppSearchConfig.getCachedFullyPersistJobIntervalMillis());
                        } catch (RuntimeException e) {
                            Log.e(TAG, "Unable to schedule fully persist job for " + user, e);
                            ExceptionUtil.handleException(e);
                        }
                    });
        }
    }

    @Override
    public void onUserStopping(@NonNull TargetUser user) {
        Objects.requireNonNull(user);
        onUserStopping(
                user.getUserHandle(), /* requiresShutdown= */ false, /* removeUserData= */ false);
    }

    private void onUserStopping(
            @NonNull UserHandle userHandle, boolean requiresShutdown, boolean removeUserData) {
        Objects.requireNonNull(userHandle);
        if (LogUtil.INFO) {
            Log.i(TAG, "Shutting down AppSearch for user " + userHandle);
        }
        try {
            mServiceImplHelper.setUserIsLocked(userHandle, true);

            ScheduledFuture<?> persistToDiskFuture;
            synchronized (mPerUserPersistToDiskFutureLocked) {
                persistToDiskFuture = mPerUserPersistToDiskFutureLocked.remove(userHandle);
            }
            if (persistToDiskFuture != null) {
                // Cancel the persistToDisk task if it is scheduled but has not yet started.
                persistToDiskFuture.cancel(/* mayInterruptIfRunning= */ false);
            }

            if (mIsolatedStorageServiceManager == null
                    || !IsolatedStorageServiceManager.isUserAllowed(userHandle)
                    || requiresShutdown) {
                if (LogUtil.INFO) {
                    Log.i(TAG,
                            "Shutting down executor and closing AppSearch for user " + userHandle);
                }
                mExecutorManager.shutDownAndRemoveUserExecutor(userHandle);
                mAppSearchUserInstanceManager.closeAndRemoveUserInstance(
                        userHandle, removeUserData);
            } else {
                if (LogUtil.INFO) {
                    Log.i(TAG, "Scheduling close for AppSearch for user " + userHandle);
                }
                mExecutorManager.executeLambdaForUserNoCallbackAsync(
                        userHandle,
                        () -> {
                            if (LogUtil.INFO) {
                                Log.i(TAG, "Closing AppSearch for user " + userHandle);
                            }
                            // The user instance is removed in a locked critical section and then
                            // its
                            // AppSearchImpl is closed outside of that section. This is safe
                            // currently
                            // in a single-threaded environment but possibly dangerous in a
                            // multi-threaded environment in the case that an initialize() task
                            // manages
                            // to create a new AppSearchImpl at the same time
                            mAppSearchUserInstanceManager.closeAndRemoveUserInstance(
                                    userHandle, removeUserData);
                        });
            }

            AppSearchMaintenanceService.cancelFullyPersistJobIfScheduled(
                    mContext, userHandle.getIdentifier());
            if (LogUtil.INFO) {
                Log.i(TAG, "Removed AppSearchImpl instance for: " + userHandle);
            }
        } catch (InterruptedException | RuntimeException e) {
            Log.e(TAG, "Unable to remove data for: " + userHandle, e);
            ExceptionUtil.handleException(e);
        }
    }

    // When a user is removed, two INTENTS are received: one for userStopped and one for
    // userRemoved. When onUserStopped is called, the AppSearch instance is destroyed and
    // marked as "closed". However, during destruction we do not know if onUserStopped was
    // called because a user paused their profile or the profile is being removed. Thus we
    // cannot remove user data in onUserStopped. Create a specific onUserRemoved function
    // to ensure the user data is properly destroyed when a user profile is removed.
    private void onUserRemoved(@NonNull UserHandle userHandle) {
        Objects.requireNonNull(userHandle);
        if (LogUtil.INFO) {
            Log.i(TAG, "Removing AppSearch user and associated data for user " + userHandle);
        }
        // We can handle user removal the same way as user stopping: shut down the executor
        // and close icing. The data of AppSearch is saved in the "credential encrypted"
        // system directory of each user. That directory will be auto-deleted when a user is
        // removed, so we don't need it handle it specially.
        //
        // When isolated storage is enabled, we must explicitly delete the data in the AppSearch
        // user instance.
        onUserStopping(userHandle, /* requiresShutdown= */ true, /* removeUserData= */ true);
    }

    class LocalService {
        /** Persist all pending mutation operation to disk for the given user. */
        public void doFullyPersistForUser(@UserIdInt int userId)
                throws AppSearchException,
                        CancellationException,
                        ExecutionException,
                        InterruptedException {
            UserHandle targetUser = UserHandle.getUserHandleForUid(userId);
            AppSearchUserInstance instance =
                    mAppSearchUserInstanceManager.getUserInstance(targetUser);
            instance.getAppSearchImpl().persistToDisk(PersistType.Code.FULL);
        }
    }

    private class Stub extends IAppSearchManager.Stub {
        @Override
        public void setSchema(
                @NonNull SetSchemaAidlRequest request,
                @NonNull IAppSearchResultCallback callback) {
            Objects.requireNonNull(request);
            Objects.requireNonNull(callback);
            final long callReceivedTimestampMillis = System.currentTimeMillis();

            checkUnsupportedEmbeddingUse(request.getSchemas());

            long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            long verifyIncomingCallLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            UserHandle targetUser = mServiceImplHelper.verifyIncomingCallWithCallback(
                    request.getCallerAttributionSource(), request.getUserHandle(), callback);
            String callingPackageName = request.getCallerAttributionSource().getPackageName();
            if (targetUser == null) {
                return;  // Verification failed; verifyIncomingCall triggered callback.
            }
            if (checkCallDenied(callingPackageName, request.getDatabaseName(),
                    CallStats.CALL_TYPE_SET_SCHEMA, callback, targetUser,
                    request.getBinderCallStartTimeMillis(), totalLatencyStartTimeMillis,
                    /* numOperations= */ 1)) {
                return;
            }
            long verifyIncomingCallLatencyEndTimeMillis = SystemClock.elapsedRealtime();

            long waitExecutorStartTimeMillis = SystemClock.elapsedRealtime();
            boolean callAccepted = mExecutorManager.executeLambdaForUserAsync(
                    targetUser, callback, callingPackageName, CallStats.CALL_TYPE_SET_SCHEMA,
                    () -> {
                long waitExecutorEndTimeMillis = SystemClock.elapsedRealtime();

                @AppSearchResult.ResultCode int statusCode = AppSearchResult.RESULT_OK;
                AppSearchUserInstance instance = null;
                SetSchemaStats.Builder setSchemaStatsBuilder = new SetSchemaStats.Builder(
                        callingPackageName, request.getDatabaseName());
                int operationSuccessCount = 0;
                int operationFailureCount = 0;
                try {
                    instance = mAppSearchUserInstanceManager.getUserInstance(targetUser);
                    InternalSetSchemaResponse internalSetSchemaResponse =
                            instance.getAppSearchImpl().setSchema(
                                    callingPackageName,
                                    request.getDatabaseName(),
                                    request.getSchemas(),
                                    request.getVisibilityConfigs(),
                                    request.isForceOverride(),
                                    request.getSchemaVersion(),
                                    setSchemaStatsBuilder);
                    ++operationSuccessCount;
                    invokeCallbackOnResult(callback, AppSearchResultParcel
                            .fromInternalSetSchemaResponse(internalSetSchemaResponse));

                    // Schedule a task to dispatch change notifications. See requirements for where
                    // the method is called documented in the method description.
                    long dispatchNotificationLatencyStartTimeMillis = SystemClock.elapsedRealtime();
                    dispatchChangeNotifications(instance);
                    long dispatchNotificationLatencyEndTimeMillis = SystemClock.elapsedRealtime();

                    // setSchema will sync the schemas in the request to AppSearch, any existing
                    // schemas which are not included in the request will be deleted if we force
                    // override incompatible schemas. And all documents of these types will be
                    // deleted as well. We should checkForOptimize for these deletion.
                    long checkForOptimizeLatencyStartTimeMillis = SystemClock.elapsedRealtime();
                    checkForOptimize(targetUser, instance);
                    long checkForOptimizeLatencyEndTimeMillis = SystemClock.elapsedRealtime();

                    setSchemaStatsBuilder
                            .setVerifyIncomingCallLatencyMillis(
                                    (int) (verifyIncomingCallLatencyEndTimeMillis
                                            - verifyIncomingCallLatencyStartTimeMillis))
                            .setExecutorAcquisitionLatencyMillis(
                                    (int) (waitExecutorEndTimeMillis
                                            - waitExecutorStartTimeMillis))
                            // This operation no longer exists, so this latency is always 0
                            .setRebuildFromBundleLatencyMillis(0)
                            .setDispatchChangeNotificationsLatencyMillis(
                                    (int) (dispatchNotificationLatencyEndTimeMillis
                                            - dispatchNotificationLatencyStartTimeMillis))
                            .setOptimizeLatencyMillis(
                                    (int) (checkForOptimizeLatencyEndTimeMillis
                                            - checkForOptimizeLatencyStartTimeMillis));
                } catch (AppSearchException
                         | RuntimeException
                         | InterruptedException
                         | ExecutionException e) {
                    ++operationFailureCount;
                    AppSearchResult<Void> failedResult = throwableToFailedResult(e);
                    statusCode = failedResult.getResultCode();
                    invokeCallbackOnResult(callback, AppSearchResultParcel.fromFailedResult(
                            failedResult));
                } finally {
                    if (instance != null) {
                        int estimatedBinderLatencyMillis =
                                2 * (int) (totalLatencyStartTimeMillis -
                                        request.getBinderCallStartTimeMillis());
                        int totalLatencyMillis =
                                (int) (SystemClock.elapsedRealtime() - totalLatencyStartTimeMillis);
                        instance.getLogger().logStats(new CallStats.Builder()
                                .setPackageName(callingPackageName)
                                .setDatabase(request.getDatabaseName())
                                .setStatusCode(statusCode)
                                .setTotalLatencyMillis(totalLatencyMillis)
                                .setCallReceivedTimestampMillis(callReceivedTimestampMillis)
                                .setCallType(CallStats.CALL_TYPE_SET_SCHEMA)
                                // TODO(b/173532925) check the existing binder call latency chart
                                // is good enough for us:
                                // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                                .setEstimatedBinderLatencyMillis(estimatedBinderLatencyMillis)
                                .setNumOperationsSucceeded(operationSuccessCount)
                                .setNumOperationsFailed(operationFailureCount)
                                .setLaunchVMEnabled(instance.isVMEnabled())
                                .build());
                        instance.getLogger().logStats(setSchemaStatsBuilder
                                .setStatusCode(statusCode)
                                .setSchemaMigrationCallType(request.getSchemaMigrationCallType())
                                .setTotalLatencyMillis(totalLatencyMillis)
                                .setLaunchVMEnabled(instance.isVMEnabled())
                                .build());

                        SetSchemaStats setSchemaStats =
                                setSchemaStatsBuilder
                                    .setStatusCode(statusCode)
                                    .setSchemaMigrationCallType(request
                                        .getSchemaMigrationCallType())
                                    .setTotalLatencyMillis(totalLatencyMillis)
                                    .setLaunchVMEnabled(instance.isVMEnabled())
                                    .build();
                        if (setSchemaStats.getNewTypeCount() > 0
                                || setSchemaStats.getDeletedTypeCount() > 0
                                || setSchemaStats.getCompatibleTypeChangeCount() > 0
                                || setSchemaStats.getIndexIncompatibleTypeChangeCount() > 0
                                || setSchemaStats.getBackwardsIncompatibleTypeChangeCount() > 0) {
                            // Drop the cache only if there has been a change in this setSchema call
                            dropStorageInfoCacheForUser(targetUser);
                        }
                    }
                }
            });
            if (!callAccepted) {
                logRateLimitedOrCallDeniedCallStats(callingPackageName, request.getDatabaseName(),
                        CallStats.CALL_TYPE_SET_SCHEMA, targetUser,
                        request.getBinderCallStartTimeMillis(), totalLatencyStartTimeMillis,
                        /*numOperations=*/ 1, RESULT_RATE_LIMITED);
            }
        }

        @Override
        public void getSchema(
                @NonNull GetSchemaAidlRequest request,
                @NonNull IAppSearchResultCallback callback) {
            Objects.requireNonNull(request);
            Objects.requireNonNull(callback);
            final long callReceivedTimestampMillis = System.currentTimeMillis();

            long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            UserHandle targetUser = mServiceImplHelper.verifyIncomingCallWithCallback(
                    request.getCallerAttributionSource(), request.getUserHandle(), callback);
            String callingPackageName = request.getCallerAttributionSource().getPackageName();
            if (targetUser == null) {
                return;  // Verification failed; verifyIncomingCall triggered callback.
            }
            // Get the enterprise user for enterprise calls
            UserHandle userToQuery = mServiceImplHelper.getUserToQuery(request.isForEnterprise(),
                    targetUser);
            if (userToQuery == null) {
                // Return an empty response if we tried to and couldn't get the enterprise user
                invokeCallbackOnResult(callback, AppSearchResultParcel.fromGetSchemaResponse(
                        new GetSchemaResponse.Builder().build()));
                return;
            }
            boolean global = isGlobalCall(callingPackageName, request.getTargetPackageName(),
                    request.isForEnterprise());
            // We deny based on the calling package and calling database names. If the calling
            // package does not match the target package, then the call is global and the target
            // database is not a calling database.
            String callingDatabaseName = global ? null : request.getDatabaseName();
            int callType = global ? CallStats.CALL_TYPE_GLOBAL_GET_SCHEMA
                    : CallStats.CALL_TYPE_GET_SCHEMA;
            if (checkCallDenied(callingPackageName, callingDatabaseName, callType, callback,
                    targetUser, request.getBinderCallStartTimeMillis(), totalLatencyStartTimeMillis,
                    /* numOperations= */ 1)) {
                return;
            }
            boolean callAccepted = mExecutorManager.executeLambdaForUserAsync(targetUser,
                    callback, callingPackageName, callType, () -> {
                @AppSearchResult.ResultCode int statusCode = AppSearchResult.RESULT_OK;
                AppSearchUserInstance instance = null;
                int operationSuccessCount = 0;
                int operationFailureCount = 0;
                try {
                    instance = mAppSearchUserInstanceManager.getUserInstance(userToQuery);

                    boolean callerHasSystemAccess = instance.getVisibilityChecker()
                            .doesCallerHaveSystemAccess(callingPackageName);
                    GetSchemaResponse response =
                            instance.getAppSearchImpl().getSchema(
                                    request.getTargetPackageName(),
                                    request.getDatabaseName(),
                                    new FrameworkCallerAccess(request.getCallerAttributionSource(),
                                            callerHasSystemAccess, request.isForEnterprise()));
                    ++operationSuccessCount;
                    invokeCallbackOnResult(callback, AppSearchResultParcel
                            .fromGetSchemaResponse(response)
                    );
                } catch (AppSearchException
                         | RuntimeException
                         | InterruptedException
                         | ExecutionException e) {
                    ++operationFailureCount;
                    AppSearchResult<Void> failedResult = throwableToFailedResult(e);
                    statusCode = failedResult.getResultCode();
                    invokeCallbackOnResult(callback, AppSearchResultParcel.fromFailedResult(
                            failedResult));
                } finally {
                    if (instance != null) {
                        int estimatedBinderLatencyMillis =
                                2 * (int) (totalLatencyStartTimeMillis
                                        - request.getBinderCallStartTimeMillis());
                        int totalLatencyMillis =
                                (int) (SystemClock.elapsedRealtime() - totalLatencyStartTimeMillis);
                        instance.getLogger().logStats(new CallStats.Builder()
                                .setPackageName(callingPackageName)
                                .setDatabase(request.getDatabaseName())
                                .setStatusCode(statusCode)
                                .setTotalLatencyMillis(totalLatencyMillis)
                                .setCallReceivedTimestampMillis(callReceivedTimestampMillis)
                                .setCallType(callType)
                                // TODO(b/173532925) check the existing binder call latency chart
                                // is good enough for us:
                                // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                                .setEstimatedBinderLatencyMillis(estimatedBinderLatencyMillis)
                                .setNumOperationsSucceeded(operationSuccessCount)
                                .setNumOperationsFailed(operationFailureCount)
                                .setLaunchVMEnabled(instance.isVMEnabled())
                                .build());
                    }
                }
            });
            if (!callAccepted) {
                logRateLimitedOrCallDeniedCallStats(callingPackageName, callingDatabaseName,
                        callType, targetUser, request.getBinderCallStartTimeMillis(),
                        totalLatencyStartTimeMillis, /*numOperations=*/ 1, RESULT_RATE_LIMITED);
            }
        }

        @Override
        public void getNamespaces(
                @NonNull GetNamespacesAidlRequest request,
                @NonNull IAppSearchResultCallback callback) {
            Objects.requireNonNull(request);
            Objects.requireNonNull(callback);
            final long callReceivedTimestampMillis = System.currentTimeMillis();

            long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            UserHandle targetUser = mServiceImplHelper.verifyIncomingCallWithCallback(
                    request.getCallerAttributionSource(), request.getUserHandle(), callback);
            String callingPackageName = request.getCallerAttributionSource().getPackageName();
            if (targetUser == null) {
                return;  // Verification failed; verifyIncomingCall triggered callback.
            }
            if (checkCallDenied(callingPackageName, request.getDatabaseName(),
                    CallStats.CALL_TYPE_GET_NAMESPACES, callback, targetUser,
                    request.getBinderCallStartTimeMillis(), totalLatencyStartTimeMillis,
                    /* numOperations= */ 1)) {
                return;
            }
            boolean callAccepted = mExecutorManager.executeLambdaForUserAsync(targetUser,
                    callback, callingPackageName, CallStats.CALL_TYPE_GET_NAMESPACES, () -> {
                @AppSearchResult.ResultCode int statusCode = AppSearchResult.RESULT_OK;
                AppSearchUserInstance instance = null;
                int operationSuccessCount = 0;
                int operationFailureCount = 0;
                try {
                    instance = mAppSearchUserInstanceManager.getUserInstance(targetUser);
                    List<String> namespaces =
                            instance.getAppSearchImpl().getNamespaces(
                                    callingPackageName, request.getDatabaseName());
                    ++operationSuccessCount;
                    invokeCallbackOnResult(callback, AppSearchResultParcel
                            .fromStringList(namespaces)
                    );
                } catch (AppSearchException
                         | RuntimeException
                         | InterruptedException
                         | ExecutionException e) {
                    ++operationFailureCount;
                    AppSearchResult<Void> failedResult = throwableToFailedResult(e);
                    statusCode = failedResult.getResultCode();
                    invokeCallbackOnResult(callback, AppSearchResultParcel.fromFailedResult(
                            failedResult));
                } finally {
                    if (instance != null) {
                        int estimatedBinderLatencyMillis =
                                2 * (int) (totalLatencyStartTimeMillis
                                        - request.getBinderCallStartTimeMillis());
                        int totalLatencyMillis =
                                (int) (SystemClock.elapsedRealtime() - totalLatencyStartTimeMillis);
                        instance.getLogger().logStats(new CallStats.Builder()
                                .setPackageName(callingPackageName)
                                .setDatabase(request.getDatabaseName())
                                .setStatusCode(statusCode)
                                .setTotalLatencyMillis(totalLatencyMillis)
                                .setCallReceivedTimestampMillis(callReceivedTimestampMillis)
                                .setCallType(CallStats.CALL_TYPE_GET_NAMESPACES)
                                // TODO(b/173532925) check the existing binder call latency chart
                                // is good enough for us:
                                // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                                .setEstimatedBinderLatencyMillis(estimatedBinderLatencyMillis)
                                .setNumOperationsSucceeded(operationSuccessCount)
                                .setNumOperationsFailed(operationFailureCount)
                                .setLaunchVMEnabled(instance.isVMEnabled())
                                .build());
                    }
                }
            });
            if (!callAccepted) {
                logRateLimitedOrCallDeniedCallStats(callingPackageName, request.getDatabaseName(),
                        CallStats.CALL_TYPE_GET_NAMESPACES, targetUser,
                        request.getBinderCallStartTimeMillis(), totalLatencyStartTimeMillis,
                        /*numOperations=*/ 1, RESULT_RATE_LIMITED);
            }
        }

        @Override
        public void putDocuments(
                @NonNull PutDocumentsAidlRequest request,
                @NonNull IAppSearchBatchResultCallback callback) {
            Objects.requireNonNull(request);
            Objects.requireNonNull(callback);
            final long callReceivedTimestampMillis = System.currentTimeMillis();

            long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            UserHandle targetUser = mServiceImplHelper.verifyIncomingCallWithCallback(
                    request.getCallerAttributionSource(), request.getUserHandle(), callback);
            String callingPackageName = request.getCallerAttributionSource().getPackageName();
            if (targetUser == null) {
                return;  // Verification failed; verifyIncomingCall triggered callback.
            }
            if (checkCallDenied(callingPackageName, request.getDatabaseName(),
                    CallStats.CALL_TYPE_PUT_DOCUMENTS, callback, targetUser,
                    request.getBinderCallStartTimeMillis(), totalLatencyStartTimeMillis,
                    /* numOperations= */ request.getDocumentsParcel().getTotalDocumentCount())) {
                return;
            }
            boolean callAccepted = mExecutorManager.executeLambdaForUserAsync(targetUser,
                    callback, callingPackageName, CallStats.CALL_TYPE_PUT_DOCUMENTS, () -> {
                @AppSearchResult.ResultCode int statusCode = RESULT_OK;
                AppSearchUserInstance instance = null;
                int operationSuccessCount = 0;
                int operationFailureCount = 0;
                // initialize later.
                // This is only used for logging stats.
                List<GenericDocument> takenActionGenericDocuments = null;

                try {
                    AppSearchBatchResult.Builder<String, Void> resultBuilder =
                            new AppSearchBatchResult.Builder<>();
                    instance = mAppSearchUserInstanceManager.getUserInstance(targetUser);
                    List<GenericDocumentParcel> documentParcels =
                            request.getDocumentsParcel().getDocumentParcels();
                    List<GenericDocumentParcel> takenActionDocumentParcels =
                            request.getDocumentsParcel().getTakenActionGenericDocumentParcels();
                    // Write GenericDocument of taken actions
                    if (!takenActionDocumentParcels.isEmpty()) {
                        takenActionGenericDocuments =
                                new ArrayList<>(takenActionDocumentParcels.size());
                    }

                    // Write GenericDocument of general documents
                    if (!Flags.enableBatchPut()) {
                        for (int i = 0; i < documentParcels.size(); i++) {
                            GenericDocument document = new GenericDocument(documentParcels.get(i));
                            try {
                                instance.getAppSearchImpl().putDocument(
                                        callingPackageName,
                                        request.getDatabaseName(),
                                        document,
                                        /* sendChangeNotifications= */ true,
                                        instance.getLogger());
                                resultBuilder.setSuccess(document.getId(), /* value= */ null);
                                ++operationSuccessCount;
                            } catch (AppSearchException | RuntimeException e) {
                                // We don't rethrow here, so we can keep trying with the
                                // following documents.
                                AppSearchResult<Void> result = throwableToFailedResult(e);
                                resultBuilder.setResult(document.getId(), result);
                                // Since we can only include one status code in the atom,
                                // for failures, we would just save the one for the last failure
                                statusCode = result.getResultCode();
                                ++operationFailureCount;
                            }
                        }

                        for (int i = 0; i < takenActionDocumentParcels.size(); i++) {
                            GenericDocument document =
                                    new GenericDocument(takenActionDocumentParcels.get(i));
                            takenActionGenericDocuments.add(document);
                            try {
                                instance.getAppSearchImpl().putDocument(
                                        callingPackageName,
                                        request.getDatabaseName(),
                                        document,
                                        /* sendChangeNotifications= */ true,
                                        instance.getLogger());
                                resultBuilder.setSuccess(document.getId(), /* value= */ null);
                                ++operationSuccessCount;
                            } catch (AppSearchException | RuntimeException e) {
                                // We don't rethrow here, so we can keep trying with the
                                // following documents.
                                AppSearchResult<Void> result = throwableToFailedResult(e);
                                resultBuilder.setResult(document.getId(), result);
                                // Since we can only include one status code in the atom,
                                // for failures, we would just save the one for the last failure
                                statusCode = result.getResultCode();
                                ++operationFailureCount;
                            }
                        }

                        // Now that the batch has been written, persist the newly written data.
                        if (Flags.enableDelayedPersistToDisk()) {
                            schedulePersistToDisk(targetUser,
                                    mAppSearchConfig.getLightweightPersistType(),
                                    mAppSearchConfig.getCachedPersistDelayMillis());
                        } else {
                            instance.getAppSearchImpl().persistToDisk(
                                    mAppSearchConfig.getLightweightPersistType());
                        }
                    } else {
                        if (!documentParcels.isEmpty() || !takenActionDocumentParcels.isEmpty()) {
                            // List to hold the current batch.
                            List<GenericDocument> currentBatch = new ArrayList<>();
                            // The lock is held in AppSearchImpl.batchPutDocuments. To avoid holding
                            // it for too long, we divide the documents into smaller batches. We
                            // flush whenever we reach MAX_NUMBER_OF_DOCS_BUFFERED.
                            // We also need to limit the # of bytes we send to the
                            // isolated_storage_service, and it is currently done in AppSearchImpl
                            // as it is easier to get the byte size from the proto directly.
                            for (int i = 0; i < documentParcels.size(); i++) {
                                if (currentBatch.size() >= MAX_NUMBER_OF_DOCS_BUFFERED) {
                                    instance.getAppSearchImpl().batchPutDocuments(
                                            callingPackageName,
                                            request.getDatabaseName(),
                                            currentBatch,
                                            resultBuilder,
                                            /* sendChangeNotifications=*/ true,
                                            instance.getLogger(),
                                            PersistType.Code.UNKNOWN);
                                    currentBatch.clear();
                                }
                                currentBatch.add(new GenericDocument(documentParcels.get(i)));
                            }
                            for (int i = 0; i < takenActionDocumentParcels.size(); i++) {
                                if (currentBatch.size() >= MAX_NUMBER_OF_DOCS_BUFFERED) {
                                    instance.getAppSearchImpl().batchPutDocuments(
                                            callingPackageName,
                                            request.getDatabaseName(),
                                            currentBatch,
                                            resultBuilder,
                                            /* sendChangeNotifications=*/ true,
                                            instance.getLogger(),
                                            PersistType.Code.UNKNOWN);
                                    currentBatch.clear();
                                }
                                GenericDocument document = new GenericDocument(
                                        takenActionDocumentParcels.get(i));
                                takenActionGenericDocuments.add(document);
                                currentBatch.add(document);
                            }
                            // flush the last batch with
                            // mAppSearchConfig.getLightweightPersistType().
                            instance.getAppSearchImpl().batchPutDocuments(
                                    callingPackageName,
                                    request.getDatabaseName(),
                                    currentBatch,
                                    resultBuilder,
                                    /* sendChangeNotifications=*/ true,
                                    instance.getLogger(),
                                    PersistType.Code.UNKNOWN);
                            schedulePersistToDisk(targetUser,
                                    mAppSearchConfig.getLightweightPersistType(),
                                    mAppSearchConfig.getCachedPersistDelayMillis());
                        }
                    }

                    // For batch put, we need to set the right operations metrics from
                    // the batchResult.
                    AppSearchBatchResult<String, Void> batchResult = resultBuilder.build();
                    if (Flags.enableBatchPut()) {
                        // reports the success/failure count. For batchPut, those two are not set
                        // at this point.
                        operationSuccessCount += batchResult.getSuccesses().size();

                        // Handle failures.
                        Map<String, AppSearchResult<Void>> failures = batchResult.getFailures();
                        operationFailureCount += failures.size();
                        // Previously we use the last failure to set the status code,
                        // now we use the first id we get from the map.
                        for (String id : failures.keySet()) {
                            statusCode = failures.get(id).getResultCode();
                            break;
                        }
                    }

                    invokeCallbackOnResult(callback, AppSearchBatchResultParcel
                            .fromStringToVoid(batchResult));

                    // Schedule a task to dispatch change notifications. See requirements for where
                    // the method is called documented in the method description.
                    dispatchChangeNotifications(instance);

                    // The existing documents with same ID will be deleted, so there may be some
                    // resources that could be released after optimize().
                    checkForOptimize(
                            targetUser,
                            instance,
                            /* mutateBatchSize= */
                            request.getDocumentsParcel().getTotalDocumentCount());
                } catch (AppSearchException
                         | RuntimeException
                         | InterruptedException
                         | ExecutionException e) {
                    ++operationFailureCount;
                    AppSearchResult<Void> failedResult = throwableToFailedResult(e);
                    statusCode = failedResult.getResultCode();
                    invokeCallbackOnError(callback, failedResult);
                } finally {
                    // TODO(b/261959320) add outstanding latency fields in AppSearch stats
                    if (instance != null) {
                        dropStorageInfoCacheForUser(targetUser);
                        int estimatedBinderLatencyMillis =
                                2 * (int) (totalLatencyStartTimeMillis
                                        - request.getBinderCallStartTimeMillis());
                        int totalLatencyMillis =
                                (int) (SystemClock.elapsedRealtime() - totalLatencyStartTimeMillis);
                        instance.getLogger().logStats(new CallStats.Builder()
                                .setPackageName(callingPackageName)
                                .setDatabase(request.getDatabaseName())
                                .setStatusCode(statusCode)
                                .setTotalLatencyMillis(totalLatencyMillis)
                                .setCallReceivedTimestampMillis(callReceivedTimestampMillis)
                                .setCallType(CallStats.CALL_TYPE_PUT_DOCUMENTS)
                                // TODO(b/173532925) check the existing binder call latency chart
                                // is good enough for us:
                                // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                                .setEstimatedBinderLatencyMillis(estimatedBinderLatencyMillis)
                                .setNumOperationsSucceeded(operationSuccessCount)
                                .setNumOperationsFailed(operationFailureCount)
                                .setLaunchVMEnabled(instance.isVMEnabled())
                                .build());

                        // Extract metrics from taken action generic documents and add log.
                        if (takenActionGenericDocuments != null
                                && !takenActionGenericDocuments.isEmpty()) {
                            instance.getLogger()
                                    .logStats(mSearchSessionStatsExtractor.extract(
                                            callingPackageName,
                                            request.getDatabaseName(),
                                            takenActionGenericDocuments,
                                            instance.isVMEnabled()));
                        }
                    }
                }
            });
            if (!callAccepted) {
                logRateLimitedOrCallDeniedCallStats(callingPackageName, request.getDatabaseName(),
                        CallStats.CALL_TYPE_PUT_DOCUMENTS, targetUser,
                        request.getBinderCallStartTimeMillis(),
                        totalLatencyStartTimeMillis,
                        /* numOperations= */
                        request.getDocumentsParcel().getTotalDocumentCount(), RESULT_RATE_LIMITED);
            }
        }

        @Override
        public void getDocuments(
                @NonNull GetDocumentsAidlRequest request,
                @NonNull IAppSearchBatchResultCallback callback) {
            Objects.requireNonNull(request);
            Objects.requireNonNull(callback);
            final long callReceivedTimestampMillis = System.currentTimeMillis();

            long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            UserHandle targetUser = mServiceImplHelper.verifyIncomingCallWithCallback(
                    request.getCallerAttributionSource(), request.getUserHandle(), callback);
            String callingPackageName = request.getCallerAttributionSource().getPackageName();
            if (targetUser == null) {
                return;  // Verification failed; verifyIncomingCall triggered callback.
            }
            // Get the enterprise user for enterprise calls
            UserHandle userToQuery = mServiceImplHelper.getUserToQuery(
                    request.isForEnterprise(), targetUser);
            if (userToQuery == null) {
                if (Flags.enableEnterpriseEmptyBatchResultFix()) {
                    // Return a batch result with RESULT_NOT_FOUND for each document id if we tried
                    // to and couldn't get the enterprise user
                    AppSearchBatchResult.Builder<String, GenericDocumentParcel> resultBuilder =
                            new AppSearchBatchResult.Builder<>();
                    String namespace = request.getGetByDocumentIdRequest().getNamespace();
                    for (String id : request.getGetByDocumentIdRequest().getIds()) {
                        resultBuilder.setFailure(id, RESULT_NOT_FOUND,
                                "Document (" + namespace + ", " + id + ") not found.");
                    }
                    invokeCallbackOnResult(callback, AppSearchBatchResultParcel
                            .fromStringToGenericDocumentParcel(resultBuilder.build()));
                } else {
                    // Return an empty batch result if we tried to and couldn't get the enterprise
                    // user
                    invokeCallbackOnResult(callback, AppSearchBatchResultParcel
                            .fromStringToGenericDocumentParcel(new AppSearchBatchResult
                                    .Builder<String, GenericDocumentParcel>().build()));
                }
                return;
            }
            // TODO(b/319315074): consider removing local getDocument and just use globalGetDocument
            //  instead; this would simplify the code and assure us that enterprise calls definitely
            //  go through visibility checks
            boolean global = isGlobalCall(callingPackageName, request.getTargetPackageName(),
                    request.isForEnterprise());
            // We deny based on the calling package and calling database names. If the calling
            // package does not match the target package, then the call is global and the target
            // database is not a calling database.
            String callingDatabaseName = global ? null : request.getDatabaseName();
            int callType = global ? CallStats.CALL_TYPE_GLOBAL_GET_DOCUMENT_BY_ID
                    : CallStats.CALL_TYPE_GET_DOCUMENTS;
            if (checkCallDenied(callingPackageName, callingDatabaseName, callType, callback,
                    targetUser, request.getBinderCallStartTimeMillis(), totalLatencyStartTimeMillis,
                    /* numOperations= */ request.getGetByDocumentIdRequest().getIds().size())) {
                return;
            }
            boolean callAccepted = mExecutorManager.executeLambdaForUserAsync(targetUser,
                    callback, callingPackageName, callType, () -> {
                @AppSearchResult.ResultCode int statusCode = RESULT_OK;
                AppSearchUserInstance instance = null;
                int operationSuccessCount = 0;
                int operationFailureCount = 0;
                try {
                    instance = mAppSearchUserInstanceManager.getUserInstance(userToQuery);
                    AppSearchBatchResult.Builder<String, GenericDocumentParcel> resultBuilder =
                            new AppSearchBatchResult.Builder<>();

                    if (!Flags.enableBatchGet()) {
                        for (String id : request.getGetByDocumentIdRequest().getIds()) {
                            try {
                                GenericDocument document;
                                if (global) {
                                    boolean callerHasSystemAccess = instance.getVisibilityChecker()
                                            .doesCallerHaveSystemAccess(
                                                    request.getCallerAttributionSource()
                                                            .getPackageName());
                                    Map<String, List<String>> typePropertyPaths =
                                            request.getGetByDocumentIdRequest().getProjections();
                                    if (request.isForEnterprise()) {
                                        EnterpriseSearchSpecTransformer.transformPropertiesMap(
                                                typePropertyPaths);
                                    }
                                    document = instance.getAppSearchImpl().globalGetDocument(
                                            request.getTargetPackageName(),
                                            request.getDatabaseName(),
                                            request.getGetByDocumentIdRequest().getNamespace(),
                                            id,
                                            typePropertyPaths,
                                            new FrameworkCallerAccess(
                                                    request.getCallerAttributionSource(),
                                                    callerHasSystemAccess,
                                                    request.isForEnterprise()));
                                    if (request.isForEnterprise()) {
                                        document =
                                                EnterpriseSearchResultPageTransformer
                                                        .transformDocument(
                                                                request.getTargetPackageName(),
                                                                request.getDatabaseName(),
                                                                document);
                                    }
                                } else {
                                    document = instance.getAppSearchImpl().getDocument(
                                            request.getTargetPackageName(),
                                            request.getDatabaseName(),
                                            request.getGetByDocumentIdRequest().getNamespace(),
                                            id,
                                            request.getGetByDocumentIdRequest().getProjections());
                                }
                                ++operationSuccessCount;
                                resultBuilder.setSuccess(id, document.getDocumentParcel());
                            } catch (AppSearchException | RuntimeException e) {
                                // Since we can only include one status code in the atom,
                                // for failures, we would just save the one for the last failure
                                // Also, we don't rethrow here, so we can keep trying for
                                // the following ones.
                                AppSearchResult<GenericDocumentParcel> result =
                                        throwableToFailedResult(e);
                                resultBuilder.setResult(id, result);
                                statusCode = result.getResultCode();
                                ++operationFailureCount;
                            }
                        }
                    } else if (!request.getGetByDocumentIdRequest().getIds().isEmpty()) {
                        AppSearchBatchResult<String, GenericDocument> getDocumentsResult;
                        if (global) {
                            boolean callerHasSystemAccess = instance.getVisibilityChecker()
                                    .doesCallerHaveSystemAccess(
                                            request.getCallerAttributionSource()
                                                    .getPackageName());
                            GetByDocumentIdRequest getByDocumentIdRequest =
                                    request.getGetByDocumentIdRequest();
                            if (request.isForEnterprise()) {
                                // Rebuild a GetByDocumentIdRequest with
                                // a modified typePropertyPaths
                                GetByDocumentIdRequest.Builder builder =
                                        new GetByDocumentIdRequest.Builder(
                                                getByDocumentIdRequest.getNamespace());
                                builder.addIds(getByDocumentIdRequest.getIds());
                                Map<String, List<String>> typePropertyPaths =
                                        getByDocumentIdRequest.getProjections();
                                EnterpriseSearchSpecTransformer.transformPropertiesMap(
                                        typePropertyPaths);
                                for (Map.Entry<String, List<String>> entry :
                                        typePropertyPaths.entrySet()) {
                                    builder.addProjection(entry.getKey(), entry.getValue());
                                }
                                getByDocumentIdRequest = builder.build();
                            }
                            getDocumentsResult = instance.getAppSearchImpl().batchGetDocuments(
                                    request.getTargetPackageName(),
                                    request.getDatabaseName(),
                                    getByDocumentIdRequest,
                                    new FrameworkCallerAccess(
                                            request.getCallerAttributionSource(),
                                            callerHasSystemAccess,
                                            request.isForEnterprise()));
                        } else {
                            getDocumentsResult = instance.getAppSearchImpl().batchGetDocuments(
                                    request.getTargetPackageName(),
                                    request.getDatabaseName(),
                                    request.getGetByDocumentIdRequest(),
                                    /*callerAccess=*/ null);
                        }

                        // Build the result to be returned.
                        for (Map.Entry<String, GenericDocument> entry :
                                getDocumentsResult.getSuccesses().entrySet()) {
                            GenericDocument document = entry.getValue();
                            if (request.isForEnterprise()) {
                                document = EnterpriseSearchResultPageTransformer.transformDocument(
                                        request.getTargetPackageName(),
                                        request.getDatabaseName(),
                                        document);
                            }
                            ++operationSuccessCount;
                            resultBuilder.setSuccess(entry.getKey(), document.getDocumentParcel());
                        }

                        for (Map.Entry<String, AppSearchResult<GenericDocument>> entry :
                                getDocumentsResult.getFailures().entrySet()) {
                            AppSearchResult<GenericDocument> failure = entry.getValue();
                            ++operationFailureCount;
                            statusCode = failure.getResultCode();
                            // We can't use the result from Icing directly as the type is
                            // AppSearchResult<GenericDocument>,
                            // not AppSearchResult<GenericDocumentParcel>
                            resultBuilder.setResult(entry.getKey(),
                                    AppSearchResult.newFailedResult(failure));
                        }
                    }

                    invokeCallbackOnResult(callback, AppSearchBatchResultParcel
                            .fromStringToGenericDocumentParcel(resultBuilder.build()));
                } catch (RuntimeException | InterruptedException | ExecutionException e) {
                    ++operationFailureCount;
                    AppSearchResult<Void> failedResult = throwableToFailedResult(e);
                    statusCode = failedResult.getResultCode();
                    invokeCallbackOnError(callback, failedResult);
                } finally {
                    // TODO(b/261959320) add outstanding latency fields in AppSearch stats
                    if (instance != null) {
                        int estimatedBinderLatencyMillis =
                                2 * (int) (totalLatencyStartTimeMillis -
                                        request.getBinderCallStartTimeMillis());
                        int totalLatencyMillis =
                                (int) (SystemClock.elapsedRealtime() - totalLatencyStartTimeMillis);
                        instance.getLogger().logStats(new CallStats.Builder()
                                .setPackageName(callingPackageName)
                                .setDatabase(request.getDatabaseName())
                                .setStatusCode(statusCode)
                                .setTotalLatencyMillis(totalLatencyMillis)
                                .setCallReceivedTimestampMillis(callReceivedTimestampMillis)
                                .setCallType(callType)
                                // TODO(b/173532925) check the existing binder call latency chart
                                // is good enough for us:
                                // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                                .setEstimatedBinderLatencyMillis(estimatedBinderLatencyMillis)
                                .setNumOperationsSucceeded(operationSuccessCount)
                                .setNumOperationsFailed(operationFailureCount)
                                .setLaunchVMEnabled(instance.isVMEnabled())
                                .build());
                    }
                }
            });
            if (!callAccepted) {
                logRateLimitedOrCallDeniedCallStats(callingPackageName, callingDatabaseName,
                        callType, targetUser, request.getBinderCallStartTimeMillis(),
                        totalLatencyStartTimeMillis,
                        /* numOperations= */ request.getGetByDocumentIdRequest().getIds().size(),
                        RESULT_RATE_LIMITED);

            }
        }

        @Override
        public void openBlobForWrite(
                OpenBlobForWriteAidlRequest request, @NonNull IAppSearchResultCallback callback) {
            Objects.requireNonNull(request);
            Objects.requireNonNull(callback);
            final long callReceivedTimestampMillis = System.currentTimeMillis();
            long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            UserHandle targetUser =
                    mServiceImplHelper.verifyIncomingCallWithCallback(
                            request.getCallerAttributionSource(),
                            request.getUserHandle(),
                            callback);
            String callingPackageName = request.getCallerAttributionSource().getPackageName();
            String callingDatabaseName = request.getCallingDatabaseName();
            if (targetUser == null) {
                return; // Verification failed; verifyIncomingCall triggered callback.
            }
            if (checkCallDenied(callingPackageName, callingDatabaseName,
                    CallStats.CALL_TYPE_OPEN_WRITE_BLOB, callback, targetUser,
                    request.getBinderCallStartTimeMillis(), totalLatencyStartTimeMillis,
                    /* numOperations= */ request.getBlobHandles().size())) {
                return;
            }
            boolean callAccepted = mExecutorManager.executeLambdaForUserAsync(targetUser, callback,
                    callingPackageName, CallStats.CALL_TYPE_OPEN_WRITE_BLOB, () -> {
                @AppSearchResult.ResultCode int statusCode = RESULT_OK;
                AppSearchUserInstance instance = null;
                int operationSuccessCount = 0;
                int operationFailureCount = 0;
                try {
                    AppSearchBatchResult.Builder<AppSearchBlobHandle, ParcelFileDescriptor>
                            resultBuilder = new AppSearchBatchResult.Builder<>();
                    instance = mAppSearchUserInstanceManager.getUserInstance(targetUser);
                    List<AppSearchBlobHandle> blobHandles = request.getBlobHandles();
                    for (int i = 0; i < blobHandles.size(); i++) {
                        AppSearchBlobHandle blobHandle = blobHandles.get(i);
                        try {
                            ParcelFileDescriptor pfd = instance.getAppSearchImpl()
                                    .openWriteBlob(
                                            callingPackageName,
                                            callingDatabaseName,
                                            blobHandle);
                            resultBuilder.setSuccess(blobHandle, pfd);
                        } catch (AppSearchException | IOException e) {
                            AppSearchResult<ParcelFileDescriptor> result =
                                    throwableToFailedResult(e);
                            resultBuilder.setResult(blobHandle, result);
                            statusCode = result.getResultCode();
                            ++operationFailureCount;
                        }
                    }
                    OpenBlobForWriteResponse response =
                            new OpenBlobForWriteResponse(resultBuilder.build());
                    invokeCallbackOnResult(callback,
                            AppSearchResultParcelV2.fromOpenBlobForWriteResponse(response));
                } catch (RuntimeException | InterruptedException | ExecutionException e) {
                    ++operationFailureCount;
                    AppSearchResult<Void> failedResult = throwableToFailedResult(e);
                    statusCode = failedResult.getResultCode();
                    invokeCallbackOnResult(callback,
                            AppSearchResultParcelV2.fromFailedResult(failedResult));
                } finally {
                    if (instance != null) {
                        dropStorageInfoCacheForUser(targetUser);
                        int estimatedBinderLatencyMillis =
                                2 * (int) (totalLatencyStartTimeMillis -
                                        request.getBinderCallStartTimeMillis());
                        int totalLatencyMillis =
                                (int) (SystemClock.elapsedRealtime()
                                        - totalLatencyStartTimeMillis);
                        instance.getLogger().logStats(new CallStats.Builder()
                                .setPackageName(callingPackageName)
                                .setDatabase(request.getCallingDatabaseName())
                                .setCallType(CallStats.CALL_TYPE_OPEN_WRITE_BLOB)
                                .setStatusCode(statusCode)
                                .setCallReceivedTimestampMillis(callReceivedTimestampMillis)
                                .setTotalLatencyMillis(totalLatencyMillis)
                                // TODO(b/173532925) check the existing binder call latency chart
                                // is good enough for us:
                                // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                                .setEstimatedBinderLatencyMillis(
                                        estimatedBinderLatencyMillis)
                                .setNumOperationsSucceeded(operationSuccessCount)
                                .setNumOperationsFailed(operationFailureCount)
                                .setLaunchVMEnabled(instance.isVMEnabled())
                                .build());
                    }
                }
            });
            if (!callAccepted) {
                logRateLimitedOrCallDeniedCallStats(
                        callingPackageName,
                        /* callingDatabaseName= */ null,
                        CallStats.CALL_TYPE_OPEN_WRITE_BLOB,
                        targetUser,
                        request.getBinderCallStartTimeMillis(),
                        totalLatencyStartTimeMillis,
                        request.getBlobHandles().size(),
                        RESULT_RATE_LIMITED);
            }
        }

        @Override
        public void removeBlob(
                RemoveBlobAidlRequest request, @NonNull IAppSearchResultCallback callback) {
            Objects.requireNonNull(request);
            Objects.requireNonNull(callback);
            final long callReceivedTimestampMillis = System.currentTimeMillis();

            long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            UserHandle targetUser =
                    mServiceImplHelper.verifyIncomingCallWithCallback(
                            request.getCallerAttributionSource(),
                            request.getUserHandle(),
                            callback);
            String callingPackageName = request.getCallerAttributionSource().getPackageName();
            String callingDatabaseName = request.getCallingDatabaseName();
            if (targetUser == null) {
                return; // Verification failed; verifyIncomingCall triggered callback.
            }
            if (checkCallDenied(callingPackageName, callingDatabaseName,
                    CallStats.CALL_TYPE_REMOVE_BLOB, callback, targetUser,
                    request.getBinderCallStartTimeMillis(), totalLatencyStartTimeMillis,
                    /* numOperations= */ request.getBlobHandles().size())) {
                return;
            }
            boolean callAccepted = mExecutorManager.executeLambdaForUserAsync(targetUser, callback,
                    callingPackageName, CallStats.CALL_TYPE_REMOVE_BLOB, () -> {
                @AppSearchResult.ResultCode int statusCode = RESULT_OK;
                AppSearchUserInstance instance = null;
                int operationSuccessCount = 0;
                int operationFailureCount = 0;
                try {
                    AppSearchBatchResult.Builder<AppSearchBlobHandle, Void>
                            resultBuilder = new AppSearchBatchResult.Builder<>();
                    instance = mAppSearchUserInstanceManager.getUserInstance(targetUser);
                    List<AppSearchBlobHandle> blobHandles = request.getBlobHandles();
                    for (int i = 0; i < blobHandles.size(); i++) {
                        AppSearchBlobHandle blobHandle = blobHandles.get(i);
                        try {
                            instance.getAppSearchImpl()
                                    .removeBlob(
                                            callingPackageName,
                                            callingDatabaseName,
                                            blobHandle);
                            resultBuilder.setSuccess(blobHandle, null);
                        } catch (AppSearchException | IOException e) {
                            AppSearchResult<Void> result =
                                    throwableToFailedResult(e);
                            resultBuilder.setResult(blobHandle, result);
                            statusCode = result.getResultCode();
                            ++operationFailureCount;
                        }
                    }
                    RemoveBlobResponse response = new RemoveBlobResponse(resultBuilder.build());
                    invokeCallbackOnResult(callback,
                            AppSearchResultParcelV2.fromRemoveBlobResponseParcel(response));
                } catch (RuntimeException | InterruptedException | ExecutionException e) {
                    ++operationFailureCount;
                    AppSearchResult<Void> failedResult = throwableToFailedResult(e);
                    statusCode = failedResult.getResultCode();
                    invokeCallbackOnResult(callback,
                            AppSearchResultParcelV2.fromFailedResult(failedResult));
                } finally {
                    if (instance != null) {
                        dropStorageInfoCacheForUser(targetUser);
                        int estimatedBinderLatencyMillis =
                                2 * (int) (totalLatencyStartTimeMillis -
                                        request.getBinderCallStartTimeMillis());
                        int totalLatencyMillis =
                                (int) (SystemClock.elapsedRealtime()
                                        - totalLatencyStartTimeMillis);
                        instance.getLogger().logStats(new CallStats.Builder()
                                .setPackageName(callingPackageName)
                                .setDatabase(request.getCallingDatabaseName())
                                .setCallType(CallStats.CALL_TYPE_REMOVE_BLOB)
                                .setStatusCode(statusCode)
                                .setCallReceivedTimestampMillis(callReceivedTimestampMillis)
                                .setTotalLatencyMillis(totalLatencyMillis)
                                // TODO(b/173532925) check the existing binder call latency chart
                                // is good enough for us:
                                // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                                .setEstimatedBinderLatencyMillis(
                                        estimatedBinderLatencyMillis)
                                .setNumOperationsSucceeded(operationSuccessCount)
                                .setNumOperationsFailed(operationFailureCount)
                                .setLaunchVMEnabled(instance.isVMEnabled())
                                .build());
                    }
                }
            });
            if (!callAccepted) {
                logRateLimitedOrCallDeniedCallStats(
                        callingPackageName,
                        /* callingDatabaseName= */ null,
                        CallStats.CALL_TYPE_REMOVE_BLOB,
                        targetUser,
                        request.getBinderCallStartTimeMillis(),
                        totalLatencyStartTimeMillis,
                        request.getBlobHandles().size(),
                        RESULT_RATE_LIMITED);
            }
        }

        @Override
        public void commitBlob(
                CommitBlobAidlRequest request, @NonNull IAppSearchResultCallback callback) {
            Objects.requireNonNull(request);
            Objects.requireNonNull(callback);
            final long callReceivedTimestampMillis = System.currentTimeMillis();

            long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            UserHandle targetUser =
                    mServiceImplHelper.verifyIncomingCallWithCallback(
                            request.getCallerAttributionSource(),
                            request.getUserHandle(),
                            callback);
            String callingPackageName = request.getCallerAttributionSource().getPackageName();
            String callingDatabaseName = request.getCallingDatabaseName();
            if (targetUser == null) {
                return; // Verification failed; verifyIncomingCall triggered callback.
            }
            if (checkCallDenied(callingPackageName, callingDatabaseName,
                    CallStats.CALL_TYPE_COMMIT_BLOB, callback, targetUser,
                    request.getBinderCallStartTimeMillis(), totalLatencyStartTimeMillis,
                    /* numOperations= */ request.getBlobHandles().size())) {
                return;
            }
            boolean callAccepted = mExecutorManager.executeLambdaForUserAsync(targetUser, callback,
                    callingPackageName, CallStats.CALL_TYPE_COMMIT_BLOB, () -> {
                @AppSearchResult.ResultCode int statusCode = RESULT_OK;
                AppSearchUserInstance instance = null;
                int operationSuccessCount = 0;
                int operationFailureCount = 0;
                try {
                    AppSearchBatchResult.Builder<AppSearchBlobHandle, Void>
                            resultBuilder = new AppSearchBatchResult.Builder<>();
                    instance = mAppSearchUserInstanceManager.getUserInstance(targetUser);
                    List<AppSearchBlobHandle> blobHandles = request.getBlobHandles();
                    for (int i = 0; i < blobHandles.size(); i++) {
                        AppSearchBlobHandle blobHandle = blobHandles.get(i);
                        try {
                            instance.getAppSearchImpl()
                                    .commitBlob(
                                            callingPackageName,
                                            callingDatabaseName,
                                            blobHandle);
                            resultBuilder.setSuccess(blobHandle, null);
                        } catch (AppSearchException | IOException e) {
                            AppSearchResult<Void> result =
                                    throwableToFailedResult(e);
                            resultBuilder.setResult(blobHandle, result);
                            statusCode = result.getResultCode();
                            ++operationFailureCount;
                        }
                    }
                    CommitBlobResponse response =
                            new CommitBlobResponse(resultBuilder.build());
                    invokeCallbackOnResult(callback,
                            AppSearchResultParcelV2.fromCommitBlobResponseParcel(response));
                } catch (RuntimeException | InterruptedException | ExecutionException e) {
                    ++operationFailureCount;
                    AppSearchResult<Void> failedResult = throwableToFailedResult(e);
                    statusCode = failedResult.getResultCode();
                    invokeCallbackOnResult(callback,
                            AppSearchResultParcelV2.fromFailedResult(failedResult));
                } finally {
                    if (instance != null) {
                        dropStorageInfoCacheForUser(targetUser);
                        int estimatedBinderLatencyMillis =
                                2 * (int) (totalLatencyStartTimeMillis -
                                        request.getBinderCallStartTimeMillis());
                        int totalLatencyMillis =
                                (int) (SystemClock.elapsedRealtime()
                                        - totalLatencyStartTimeMillis);
                        instance.getLogger().logStats(new CallStats.Builder()
                                .setPackageName(callingPackageName)
                                .setDatabase(request.getCallingDatabaseName())
                                .setCallType(CallStats.CALL_TYPE_COMMIT_BLOB)
                                .setStatusCode(statusCode)
                                .setCallReceivedTimestampMillis(callReceivedTimestampMillis)
                                .setTotalLatencyMillis(totalLatencyMillis)
                                // TODO(b/173532925) check the existing binder call latency chart
                                // is good enough for us:
                                // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                                .setEstimatedBinderLatencyMillis(
                                        estimatedBinderLatencyMillis)
                                .setNumOperationsSucceeded(operationSuccessCount)
                                .setNumOperationsFailed(operationFailureCount)
                                .setLaunchVMEnabled(instance.isVMEnabled())
                                .build());
                    }

                }
            });
            if (!callAccepted) {
                logRateLimitedOrCallDeniedCallStats(
                        callingPackageName,
                        /* callingDatabaseName= */ null,
                        CallStats.CALL_TYPE_COMMIT_BLOB,
                        targetUser,
                        request.getBinderCallStartTimeMillis(),
                        totalLatencyStartTimeMillis,
                        request.getBlobHandles().size(),
                        RESULT_RATE_LIMITED);
            }
        }

        @Override
        public void openBlobForRead(
                OpenBlobForReadAidlRequest request, @NonNull IAppSearchResultCallback callback) {
            Objects.requireNonNull(request);
            Objects.requireNonNull(callback);
            final long callReceivedTimestampMillis = System.currentTimeMillis();

            long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            UserHandle targetUser =
                    mServiceImplHelper.verifyIncomingCallWithCallback(
                            request.getCallerAttributionSource(),
                            request.getUserHandle(),
                            callback);
            String callingPackageName = request.getCallerAttributionSource().getPackageName();
            String callingDatabaseName = request.getCallingDatabaseName();
            boolean global = callingDatabaseName == null;
            int callType = global ? CallStats.CALL_TYPE_GLOBAL_OPEN_READ_BLOB
                    : CallStats.CALL_TYPE_OPEN_READ_BLOB;
            if (targetUser == null) {
                return; // Verification failed; verifyIncomingCall triggered callback.
            }
            if (checkCallDenied(callingPackageName, callingDatabaseName, callType, callback,
                    targetUser, request.getBinderCallStartTimeMillis(), totalLatencyStartTimeMillis,
                    /* numOperations= */ request.getBlobHandles().size())) {
                return;
            }
            boolean callAccepted = mExecutorManager.executeLambdaForUserAsync(targetUser, callback,
                    callingPackageName, callType, () -> {
                @AppSearchResult.ResultCode int statusCode = RESULT_OK;
                AppSearchUserInstance instance = null;
                int operationSuccessCount = 0;
                int operationFailureCount = 0;
                try {
                    AppSearchBatchResult.Builder<AppSearchBlobHandle, ParcelFileDescriptor>
                            resultBuilder = new AppSearchBatchResult.Builder<>();
                    instance = mAppSearchUserInstanceManager.getUserInstance(targetUser);
                    List<AppSearchBlobHandle> blobHandles = request.getBlobHandles();
                    for (int i = 0; i < blobHandles.size(); i++) {
                        AppSearchBlobHandle blobHandle = blobHandles.get(i);
                        try {
                            ParcelFileDescriptor pfd;
                            if (global) {
                                boolean callerHasSystemAccess = instance.getVisibilityChecker()
                                        .doesCallerHaveSystemAccess(callingPackageName);
                                CallerAccess callerAccess = new FrameworkCallerAccess(
                                        request.getCallerAttributionSource(),
                                        callerHasSystemAccess,
                                        /*isForEnterprise=*/ false);
                                pfd = instance.getAppSearchImpl().globalOpenReadBlob(
                                        blobHandle, callerAccess);
                            } else {
                                pfd = instance.getAppSearchImpl().openReadBlob(callingPackageName,
                                        callingDatabaseName, blobHandle);
                            }
                            resultBuilder.setSuccess(blobHandle, pfd);
                        } catch (AppSearchException | IOException e) {
                            AppSearchResult<ParcelFileDescriptor> result =
                                    throwableToFailedResult(e);
                            resultBuilder.setResult(blobHandle, result);
                            statusCode = result.getResultCode();
                            ++operationFailureCount;
                        }
                    }
                    OpenBlobForReadResponse response =
                            new OpenBlobForReadResponse(resultBuilder.build());
                    invokeCallbackOnResult(callback,
                            AppSearchResultParcelV2.fromOpenBlobForReadResponse(response));
                } catch (RuntimeException | InterruptedException | ExecutionException e) {
                    ++operationFailureCount;
                    AppSearchResult<Void> failedResult = throwableToFailedResult(e);
                    statusCode = failedResult.getResultCode();
                    invokeCallbackOnResult(callback,
                            AppSearchResultParcelV2.fromFailedResult(failedResult));
                } finally {
                    if (instance != null) {
                        int estimatedBinderLatencyMillis =
                                2 * (int) (totalLatencyStartTimeMillis -
                                        request.getBinderCallStartTimeMillis());
                        int totalLatencyMillis =
                                (int) (SystemClock.elapsedRealtime()
                                        - totalLatencyStartTimeMillis);
                        instance.getLogger().logStats(new CallStats.Builder()
                                .setPackageName(callingPackageName)
                                .setDatabase(request.getCallingDatabaseName())
                                .setStatusCode(statusCode)
                                .setCallType(callType)
                                .setCallReceivedTimestampMillis(callReceivedTimestampMillis)
                                .setTotalLatencyMillis(totalLatencyMillis)
                                // TODO(b/173532925) check the existing binder call latency chart
                                // is good enough for us:
                                // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                                .setEstimatedBinderLatencyMillis(
                                        estimatedBinderLatencyMillis)
                                .setNumOperationsSucceeded(operationSuccessCount)
                                .setNumOperationsFailed(operationFailureCount)
                                .setLaunchVMEnabled(instance.isVMEnabled())
                                .build());
                    }
                }
            });
            if (!callAccepted) {
                logRateLimitedOrCallDeniedCallStats(
                        callingPackageName,
                        /* callingDatabaseName= */ null,
                        callType,
                        targetUser,
                        request.getBinderCallStartTimeMillis(),
                        totalLatencyStartTimeMillis,
                        request.getBlobHandles().size(),
                        RESULT_RATE_LIMITED);
            }
        }

        @Override
        public void setBlobVisibility(
                SetBlobVisibilityAidlRequest request, @NonNull IAppSearchResultCallback callback) {
            Objects.requireNonNull(request);
            Objects.requireNonNull(callback);
            final long callReceivedTimestampMillis = System.currentTimeMillis();

            long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            UserHandle targetUser =
                    mServiceImplHelper.verifyIncomingCallWithCallback(
                            request.getCallerAttributionSource(),
                            request.getUserHandle(),
                            callback);
            String callingPackageName = request.getCallerAttributionSource().getPackageName();
            String callingDatabaseName = request.getCallingDatabaseName();
            if (targetUser == null) {
                return; // Verification failed; verifyIncomingCall triggered callback.
            }
            if (checkCallDenied(callingPackageName, callingDatabaseName,
                    CallStats.CALL_TYPE_SET_BLOB_VISIBILITY, callback, targetUser,
                    request.getBinderCallStartTimeMillis(), totalLatencyStartTimeMillis,
                    /* numOperations= */ 1)) {
                return;
            }
            boolean callAccepted = mExecutorManager.executeLambdaForUserAsync(targetUser, callback,
                    callingPackageName, CallStats.CALL_TYPE_SET_BLOB_VISIBILITY, () -> {
                @AppSearchResult.ResultCode int statusCode = RESULT_OK;
                AppSearchUserInstance instance = null;
                int operationSuccessCount = 0;
                int operationFailureCount = 0;
                try {
                    instance = mAppSearchUserInstanceManager.getUserInstance(targetUser);
                    List<InternalVisibilityConfig> visibilityConfigs =
                            request.getVisibilityConfigs();
                    instance.getAppSearchImpl().setBlobNamespaceVisibility(
                            callingPackageName,
                            callingDatabaseName,
                            visibilityConfigs);
                    ++operationSuccessCount;
                    invokeCallbackOnResult(callback, AppSearchResultParcelV2.fromVoid());
                } catch (AppSearchException | RuntimeException | InterruptedException
                         | ExecutionException e) {
                    ++operationFailureCount;
                    AppSearchResult<Void> failedResult = throwableToFailedResult(e);
                    statusCode = failedResult.getResultCode();
                    invokeCallbackOnResult(callback,
                            AppSearchResultParcelV2.fromFailedResult(failedResult));
                } finally {
                    if (instance != null) {
                        int estimatedBinderLatencyMillis =
                                2 * (int) (totalLatencyStartTimeMillis -
                                        request.getBinderCallStartTimeMillis());
                        int totalLatencyMillis =
                                (int) (SystemClock.elapsedRealtime()
                                        - totalLatencyStartTimeMillis);
                        instance.getLogger().logStats(new CallStats.Builder()
                                .setPackageName(callingPackageName)
                                .setDatabase(request.getCallingDatabaseName())
                                .setCallType(CallStats.CALL_TYPE_SET_BLOB_VISIBILITY)
                                .setStatusCode(statusCode)
                                .setCallReceivedTimestampMillis(callReceivedTimestampMillis)
                                .setTotalLatencyMillis(totalLatencyMillis)
                                // TODO(b/173532925) check the existing binder call latency chart
                                // is good enough for us:
                                // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                                .setEstimatedBinderLatencyMillis(
                                        estimatedBinderLatencyMillis)
                                .setNumOperationsSucceeded(operationSuccessCount)
                                .setNumOperationsFailed(operationFailureCount)
                                .setLaunchVMEnabled(instance.isVMEnabled())
                                .build());
                    }
                }
            });
            if (!callAccepted) {
                logRateLimitedOrCallDeniedCallStats(
                        callingPackageName,
                        /* callingDatabaseName= */ null,
                        CallStats.CALL_TYPE_SET_BLOB_VISIBILITY,
                        targetUser,
                        request.getBinderCallStartTimeMillis(),
                        totalLatencyStartTimeMillis,
                        /* numOperations= */ 1,
                        RESULT_RATE_LIMITED);
            }
        }

        @Override
        public void search(
                @NonNull SearchAidlRequest request,
                @NonNull IAppSearchResultCallback callback) {
            Objects.requireNonNull(request);
            Objects.requireNonNull(callback);
            final long callReceivedTimestampMillis = System.currentTimeMillis();

            checkUnsupportedEmbeddingUse(request.getSearchSpec());

            long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            UserHandle targetUser = mServiceImplHelper.verifyIncomingCallWithCallback(
                    request.getCallerAttributionSource(), request.getUserHandle(), callback);
            String callingPackageName = request.getCallerAttributionSource().getPackageName();
            if (targetUser == null) {
                return;  // Verification failed; verifyIncomingCall triggered callback.
            }
            if (checkCallDenied(callingPackageName, request.getDatabaseName(),
                    CallStats.CALL_TYPE_SEARCH, callback, targetUser,
                    request.getBinderCallStartTimeMillis(), totalLatencyStartTimeMillis,
                    /* numOperations= */ 1)) {
                return;
            }
            boolean callAccepted = mExecutorManager.executeLambdaForUserAsync(targetUser,
                    callback, callingPackageName, CallStats.CALL_TYPE_SEARCH, () -> {
                @AppSearchResult.ResultCode int statusCode = RESULT_OK;
                AppSearchUserInstance instance = null;
                int operationSuccessCount = 0;
                int operationFailureCount = 0;
                try {
                    instance = mAppSearchUserInstanceManager.getUserInstance(targetUser);
                    SearchResultPage searchResultPage = instance.getAppSearchImpl().query(
                            callingPackageName,
                            request.getDatabaseName(),
                            request.getSearchExpression(),
                            request.getSearchSpec(),
                            instance.getLogger());
                    ++operationSuccessCount;
                    invokeCallbackOnResult(
                            callback,
                            AppSearchResultParcel.fromSearchResultPage(searchResultPage));
                } catch (AppSearchException | RuntimeException | InterruptedException
                         | ExecutionException e) {
                    ++operationFailureCount;
                    AppSearchResult<Void> failedResult = throwableToFailedResult(e);
                    statusCode = failedResult.getResultCode();
                    invokeCallbackOnResult(callback, AppSearchResultParcel.fromFailedResult(
                            failedResult));
                } finally {
                    if (instance != null) {
                        int estimatedBinderLatencyMillis = 2 * (int) (totalLatencyStartTimeMillis
                                - request.getBinderCallStartTimeMillis());
                        int totalLatencyMillis =
                                (int) (SystemClock.elapsedRealtime() - totalLatencyStartTimeMillis);
                        instance.getLogger().logStats(new CallStats.Builder()
                                .setPackageName(callingPackageName)
                                .setDatabase(request.getDatabaseName())
                                .setStatusCode(statusCode)
                                .setTotalLatencyMillis(totalLatencyMillis)
                                .setCallReceivedTimestampMillis(callReceivedTimestampMillis)
                                .setCallType(CallStats.CALL_TYPE_SEARCH)
                                // TODO(b/173532925) check the existing binder call latency chart
                                // is good enough for us:
                                // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                                .setEstimatedBinderLatencyMillis(estimatedBinderLatencyMillis)
                                .setNumOperationsSucceeded(operationSuccessCount)
                                .setNumOperationsFailed(operationFailureCount)
                                .setLaunchVMEnabled(instance.isVMEnabled())
                                .build());
                    }
                }
            });
            if (!callAccepted) {
                logRateLimitedOrCallDeniedCallStats(callingPackageName, request.getDatabaseName(),
                        CallStats.CALL_TYPE_SEARCH, targetUser,
                        request.getBinderCallStartTimeMillis(), totalLatencyStartTimeMillis,
                        /* numOperations= */ 1, RESULT_RATE_LIMITED);
            }
        }

        @Override
        public void globalSearch(
                @NonNull GlobalSearchAidlRequest request,
                @NonNull IAppSearchResultCallback callback) {
            Objects.requireNonNull(request);
            Objects.requireNonNull(callback);
            final long callReceivedTimestampMillis = System.currentTimeMillis();

            checkUnsupportedEmbeddingUse(request.getSearchSpec());

            long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            UserHandle targetUser = mServiceImplHelper.verifyIncomingCallWithCallback(
                    request.getCallerAttributionSource(), request.getUserHandle(), callback);
            String callingPackageName = request.getCallerAttributionSource().getPackageName();
            if (targetUser == null) {
                return;  // Verification failed; verifyIncomingCall triggered callback.
            }
            // Get the enterprise user for enterprise calls
            UserHandle userToQuery = mServiceImplHelper.getUserToQuery(request.isForEnterprise(),
                    targetUser);
            if (userToQuery == null) {
                // Return an empty result if we tried to and couldn't get the enterprise user
                invokeCallbackOnResult(callback,
                        AppSearchResultParcel.fromSearchResultPage(new SearchResultPage()));
                return;
            }
            if (checkCallDenied(callingPackageName, /* callingDatabaseName= */ null,
                    CallStats.CALL_TYPE_GLOBAL_SEARCH, callback, targetUser,
                    request.getBinderCallStartTimeMillis(), totalLatencyStartTimeMillis,
                    /* numOperations= */ 1)) {
                return;
            }
            boolean callAccepted = mExecutorManager.executeLambdaForUserAsync(targetUser,
                    callback, callingPackageName, CallStats.CALL_TYPE_GLOBAL_SEARCH, () -> {
                @AppSearchResult.ResultCode int statusCode = RESULT_OK;
                AppSearchUserInstance instance = null;
                int operationSuccessCount = 0;
                int operationFailureCount = 0;
                try {
                    instance = mAppSearchUserInstanceManager.getUserInstance(userToQuery);
                    boolean callerHasSystemAccess = instance.getVisibilityChecker()
                            .doesCallerHaveSystemAccess(callingPackageName);
                    SearchSpec querySearchSpec = request.isForEnterprise()
                            ? EnterpriseSearchSpecTransformer.transformSearchSpec(
                            request.getSearchSpec()) : request.getSearchSpec();
                    SearchResultPage searchResultPage = instance.getAppSearchImpl().globalQuery(
                            request.getSearchExpression(),
                            querySearchSpec,
                            new FrameworkCallerAccess(request.getCallerAttributionSource(),
                                    callerHasSystemAccess, request.isForEnterprise()),
                            instance.getLogger());
                    if (request.isForEnterprise()) {
                        searchResultPage =
                                EnterpriseSearchResultPageTransformer.transformSearchResultPage(
                                        searchResultPage);
                    }
                    ++operationSuccessCount;
                    invokeCallbackOnResult(
                            callback,
                            AppSearchResultParcel.fromSearchResultPage(searchResultPage));
                } catch (AppSearchException | RuntimeException | InterruptedException
                         | ExecutionException e) {
                    ++operationFailureCount;
                    AppSearchResult<Void> failedResult = throwableToFailedResult(e);
                    statusCode = failedResult.getResultCode();
                    invokeCallbackOnResult(callback, AppSearchResultParcel.fromFailedResult(
                            failedResult));
                } finally {
                    if (instance != null) {
                        int estimatedBinderLatencyMillis = 2 * (int) (totalLatencyStartTimeMillis
                                - request.getBinderCallStartTimeMillis());
                        int totalLatencyMillis =
                                (int) (SystemClock.elapsedRealtime() - totalLatencyStartTimeMillis);
                        instance.getLogger().logStats(new CallStats.Builder()
                                .setPackageName(callingPackageName)
                                .setStatusCode(statusCode)
                                .setTotalLatencyMillis(totalLatencyMillis)
                                .setCallReceivedTimestampMillis(callReceivedTimestampMillis)
                                .setCallType(CallStats.CALL_TYPE_GLOBAL_SEARCH)
                                // TODO(b/173532925) check the existing binder call latency chart
                                // is good enough for us:
                                // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                                .setEstimatedBinderLatencyMillis(estimatedBinderLatencyMillis)
                                .setNumOperationsSucceeded(operationSuccessCount)
                                .setNumOperationsFailed(operationFailureCount)
                                .setLaunchVMEnabled(instance.isVMEnabled())
                                .build());
                    }
                }
            });
            if (!callAccepted) {
                logRateLimitedOrCallDeniedCallStats(callingPackageName,
                        /* callingDatabaseName= */ null, CallStats.CALL_TYPE_GLOBAL_SEARCH,
                        targetUser, request.getBinderCallStartTimeMillis(),
                        totalLatencyStartTimeMillis, /* numOperations= */ 1, RESULT_RATE_LIMITED);
            }
        }

        @Override
        public void getNextPage(
                @NonNull GetNextPageAidlRequest request,
                @NonNull IAppSearchResultCallback callback) {
            Objects.requireNonNull(request);
            Objects.requireNonNull(callback);
            final long callReceivedTimestampMillis = System.currentTimeMillis();

            long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            UserHandle targetUser = mServiceImplHelper.verifyIncomingCallWithCallback(
                    request.getCallerAttributionSource(), request.getUserHandle(), callback);
            String callingPackageName = request.getCallerAttributionSource().getPackageName();
            if (targetUser == null) {
                return;  // Verification failed; verifyIncomingCall triggered callback.
            }
            // Get the enterprise user for enterprise calls
            UserHandle userToQuery = mServiceImplHelper.getUserToQuery(request.isForEnterprise(),
                    targetUser);
            if (userToQuery == null) {
                // Return an empty result if we tried to and couldn't get the enterprise user
                invokeCallbackOnResult(callback,
                        AppSearchResultParcel.fromSearchResultPage(new SearchResultPage()));
                return;
            }
            // Enterprise session calls are considered global for CallStats logging
            boolean global = request.getDatabaseName() == null || request.isForEnterprise();
            int callType = global ? CallStats.CALL_TYPE_GLOBAL_GET_NEXT_PAGE
                    : CallStats.CALL_TYPE_GET_NEXT_PAGE;
            if (checkCallDenied(callingPackageName, request.getDatabaseName(), callType, callback,
                    targetUser, request.getBinderCallStartTimeMillis(), totalLatencyStartTimeMillis,
                    /* numOperations= */ 1)) {
                return;
            }
            boolean callAccepted = mExecutorManager.executeLambdaForUserAsync(targetUser,
                    callback, callingPackageName, callType, () -> {
                @AppSearchResult.ResultCode int statusCode = AppSearchResult.RESULT_OK;
                AppSearchUserInstance instance = null;
                int operationSuccessCount = 0;
                int operationFailureCount = 0;
                QueryStats.Builder statsBuilder;
                if (global) {
                    statsBuilder = new QueryStats.Builder(VISIBILITY_SCOPE_GLOBAL,
                            callingPackageName)
                            .setJoinType(request.getJoinType());
                } else {
                    statsBuilder = new QueryStats.Builder(VISIBILITY_SCOPE_LOCAL,
                            callingPackageName)
                            .setDatabase(request.getDatabaseName())
                            .setJoinType(request.getJoinType());
                }
                try {
                    instance = mAppSearchUserInstanceManager.getUserInstance(userToQuery);
                    SearchResultPage searchResultPage =
                            instance.getAppSearchImpl().getNextPage(callingPackageName,
                                    request.getNextPageToken(), statsBuilder);
                    if (request.isForEnterprise()) {
                        searchResultPage =
                                EnterpriseSearchResultPageTransformer.transformSearchResultPage(
                                        searchResultPage);
                    }
                    ++operationSuccessCount;
                    invokeCallbackOnResult(
                            callback,
                            AppSearchResultParcel.fromSearchResultPage(searchResultPage));
                } catch (AppSearchException | RuntimeException | InterruptedException
                         | ExecutionException e) {
                    ++operationFailureCount;
                    AppSearchResult<Void> failedResult = throwableToFailedResult(e);
                    statusCode = failedResult.getResultCode();
                    invokeCallbackOnResult(callback, AppSearchResultParcel.fromFailedResult(
                            failedResult));
                } finally {
                    if (instance != null) {
                        int estimatedBinderLatencyMillis = 2 * (int) (totalLatencyStartTimeMillis
                                - request.getBinderCallStartTimeMillis());
                        int totalLatencyMillis =
                                (int) (SystemClock.elapsedRealtime() - totalLatencyStartTimeMillis);
                        CallStats.Builder builder = new CallStats.Builder()
                                .setPackageName(callingPackageName)
                                .setDatabase(request.getDatabaseName())
                                .setStatusCode(statusCode)
                                .setTotalLatencyMillis(totalLatencyMillis)
                                .setCallReceivedTimestampMillis(callReceivedTimestampMillis)
                                .setCallType(callType)
                                // TODO(b/173532925) check the existing binder call latency chart
                                // is good enough for us:
                                // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                                .setEstimatedBinderLatencyMillis(estimatedBinderLatencyMillis)
                                .setNumOperationsSucceeded(operationSuccessCount)
                                .setNumOperationsFailed(operationFailureCount)
                                .setLaunchVMEnabled(instance.isVMEnabled());
                        instance.getLogger().logStats(builder.build());
                        instance.getLogger().logStats(statsBuilder.build());
                    }
                }
            });
            if (!callAccepted) {
                logRateLimitedOrCallDeniedCallStats(callingPackageName, request.getDatabaseName(),
                        callType, targetUser, request.getBinderCallStartTimeMillis(),
                        totalLatencyStartTimeMillis, /* numOperations= */ 1, RESULT_RATE_LIMITED);
            }
        }

        @Override
        public void invalidateNextPageToken(@NonNull InvalidateNextPageTokenAidlRequest request) {
            Objects.requireNonNull(request);
            final long callReceivedTimestampMillis = System.currentTimeMillis();

            long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            try {
                UserHandle targetUser = mServiceImplHelper.verifyIncomingCall(
                        request.getCallerAttributionSource(), request.getUserHandle());
                // Get the enterprise user for enterprise calls
                UserHandle userToQuery = mServiceImplHelper.getUserToQuery(
                        request.isForEnterprise(), targetUser);
                if (userToQuery == null) {
                    // Return if we tried to and couldn't get the enterprise user
                    return;
                }
                String callingPackageName = request.getCallerAttributionSource().getPackageName();
                if (checkCallDenied(callingPackageName, /* callingDatabaseName= */ null,
                        CallStats.CALL_TYPE_INVALIDATE_NEXT_PAGE_TOKEN, targetUser,
                        request.getBinderCallStartTimeMillis(), totalLatencyStartTimeMillis,
                        /* numOperations= */ 1)) {
                    return;
                }
                boolean callAccepted = mExecutorManager.executeLambdaForUserNoCallbackAsync(
                        targetUser, callingPackageName,
                        CallStats.CALL_TYPE_INVALIDATE_NEXT_PAGE_TOKEN, () -> {
                    @AppSearchResult.ResultCode int statusCode = AppSearchResult.RESULT_OK;
                    AppSearchUserInstance instance = null;
                    int operationSuccessCount = 0;
                    int operationFailureCount = 0;
                    try {
                        instance = mAppSearchUserInstanceManager.getUserInstance(userToQuery);
                        instance.getAppSearchImpl().invalidateNextPageToken(
                                callingPackageName, request.getNextPageToken());
                        operationSuccessCount++;
                    } catch (AppSearchException | RuntimeException | InterruptedException
                             | ExecutionException e) {
                        ++operationFailureCount;
                        statusCode = throwableToFailedResult(e).getResultCode();
                        Log.e(TAG, "Unable to invalidate the query page token", e);
                        ExceptionUtil.handleException(e);
                    } finally {
                        if (instance != null) {
                            int estimatedBinderLatencyMillis =
                                    2 * (int) (totalLatencyStartTimeMillis
                                            - request.getBinderCallStartTimeMillis());
                            int totalLatencyMillis =
                                    (int) (SystemClock.elapsedRealtime()
                                            - totalLatencyStartTimeMillis);
                            instance.getLogger().logStats(new CallStats.Builder()
                                    .setPackageName(callingPackageName)
                                    .setStatusCode(statusCode)
                                    .setTotalLatencyMillis(totalLatencyMillis)
                                    .setCallReceivedTimestampMillis(callReceivedTimestampMillis)
                                    .setCallType(CallStats.CALL_TYPE_INVALIDATE_NEXT_PAGE_TOKEN)
                                    // TODO(b/173532925) check the existing binder call latency
                                    //  chart
                                    // is good enough for us:
                                    // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                                    .setEstimatedBinderLatencyMillis(estimatedBinderLatencyMillis)
                                    .setNumOperationsSucceeded(operationSuccessCount)
                                    .setNumOperationsFailed(operationFailureCount)
                                    .setLaunchVMEnabled(instance.isVMEnabled())
                                    .build());
                        }
                    }
                });
                if (!callAccepted) {
                    logRateLimitedOrCallDeniedCallStats(
                            callingPackageName, /* callingDatabaseName= */ null,
                            CallStats.CALL_TYPE_INVALIDATE_NEXT_PAGE_TOKEN, targetUser,
                            request.getBinderCallStartTimeMillis(), totalLatencyStartTimeMillis,
                            /* numOperations= */ 1, RESULT_RATE_LIMITED);
                }
            } catch (RuntimeException e) {
                Log.e(TAG, "Unable to invalidate the query page token", e);
                ExceptionUtil.handleException(e);
            }
        }

        @Override
        public void writeSearchResultsToFile(
                @NonNull WriteSearchResultsToFileAidlRequest request,
                @NonNull IAppSearchResultCallback callback) {
            Objects.requireNonNull(request);
            Objects.requireNonNull(callback);
            final long callReceivedTimestampMillis = System.currentTimeMillis();

            checkUnsupportedEmbeddingUse(request.getSearchSpec());

            long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            UserHandle targetUser = mServiceImplHelper.verifyIncomingCallWithCallback(
                    request.getCallerAttributionSource(), request.getUserHandle(), callback);
            String callingPackageName = request.getCallerAttributionSource().getPackageName();
            if (targetUser == null) {
                return;  // Verification failed; verifyIncomingCall triggered callback.
            }
            if (checkCallDenied(callingPackageName, request.getDatabaseName(),
                    CallStats.CALL_TYPE_WRITE_SEARCH_RESULTS_TO_FILE, callback, targetUser,
                    request.getBinderCallStartTimeMillis(), totalLatencyStartTimeMillis,
                    /* numOperations= */ 1)) {
                return;
            }
            boolean callAccepted = mExecutorManager.executeLambdaForUserAsync(targetUser,
                    callback, callingPackageName, CallStats.CALL_TYPE_WRITE_SEARCH_RESULTS_TO_FILE,
                    () -> {
                @AppSearchResult.ResultCode int statusCode = AppSearchResult.RESULT_OK;
                AppSearchUserInstance instance = null;
                int operationSuccessCount = 0;
                int operationFailureCount = 0;
                try {
                    instance = mAppSearchUserInstanceManager.getUserInstance(targetUser);
                    // we don't need to append the file. The file is always brand new.
                    try (DataOutputStream outputStream = new DataOutputStream(new FileOutputStream(
                            request.getParcelFileDescriptor().getFileDescriptor()))) {
                        SearchResultPage searchResultPage = instance.getAppSearchImpl().query(
                                callingPackageName,
                                request.getDatabaseName(),
                                request.getSearchExpression(),
                                request.getSearchSpec(),
                                /* logger= */ null);
                        while (!searchResultPage.getResults().isEmpty()) {
                            for (int i = 0; i < searchResultPage.getResults().size(); i++) {
                                AppSearchMigrationHelper.writeDocumentToOutputStream(
                                        outputStream,
                                        searchResultPage.getResults().get(i).getGenericDocument());
                            }
                            operationSuccessCount += searchResultPage.getResults().size();
                            // TODO(b/173532925): Implement logging for statsBuilder
                            searchResultPage = instance.getAppSearchImpl().getNextPage(
                                    callingPackageName,
                                    searchResultPage.getNextPageToken(),
                                    /* sStatsBuilder= */ null);
                        }
                    }
                    invokeCallbackOnResult(callback, AppSearchResultParcel.fromVoid());
                } catch (AppSearchException | IOException | RuntimeException
                         | InterruptedException | ExecutionException e) {
                    ++operationFailureCount;
                    AppSearchResult<Void> failedResult = throwableToFailedResult(e);
                    statusCode = failedResult.getResultCode();
                    invokeCallbackOnResult(callback, AppSearchResultParcel.fromFailedResult(
                            failedResult));
                } finally {
                    if (instance != null) {
                        int estimatedBinderLatencyMillis = 2 * (int) (totalLatencyStartTimeMillis
                                - request.getBinderCallStartTimeMillis());
                        int totalLatencyMillis =
                                (int) (SystemClock.elapsedRealtime() - totalLatencyStartTimeMillis);
                        instance.getLogger().logStats(new CallStats.Builder()
                                .setPackageName(callingPackageName)
                                .setDatabase(request.getDatabaseName())
                                .setStatusCode(statusCode)
                                .setTotalLatencyMillis(totalLatencyMillis)
                                .setCallReceivedTimestampMillis(callReceivedTimestampMillis)
                                .setCallType(CallStats.CALL_TYPE_WRITE_SEARCH_RESULTS_TO_FILE)
                                // TODO(b/173532925) check the existing binder call latency chart
                                // is good enough for us:
                                // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                                .setEstimatedBinderLatencyMillis(estimatedBinderLatencyMillis)
                                .setNumOperationsSucceeded(operationSuccessCount)
                                .setNumOperationsFailed(operationFailureCount)
                                .setLaunchVMEnabled(instance.isVMEnabled())
                                .build());
                    }
                }
            });
            if (!callAccepted) {
                logRateLimitedOrCallDeniedCallStats(callingPackageName, request.getDatabaseName(),
                        CallStats.CALL_TYPE_WRITE_SEARCH_RESULTS_TO_FILE, targetUser,
                        request.getBinderCallStartTimeMillis(), totalLatencyStartTimeMillis,
                        /* numOperations= */ 1, RESULT_RATE_LIMITED);
            }
        }

        @Override
        public void putDocumentsFromFile(
                @NonNull PutDocumentsFromFileAidlRequest request,
                @NonNull IAppSearchResultCallback callback) {
            Objects.requireNonNull(request);
            Objects.requireNonNull(callback);
            final long callReceivedTimestampMillis = System.currentTimeMillis();

            long callStatsTotalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            UserHandle targetUser = mServiceImplHelper.verifyIncomingCallWithCallback(
                    request.getCallerAttributionSource(), request.getUserHandle(), callback);
            String callingPackageName = request.getCallerAttributionSource().getPackageName();
            if (targetUser == null) {
                return;  // Verification failed; verifyIncomingCall triggered callback.
            }
            // Since we don't read from the given file, we don't know the number of documents so we
            // just set numOperations to 1 instead
            if (checkCallDenied(callingPackageName, request.getDatabaseName(),
                    CallStats.CALL_TYPE_PUT_DOCUMENTS_FROM_FILE, callback, targetUser,
                    request.getBinderCallStartTimeMillis(), callStatsTotalLatencyStartTimeMillis,
                    /* numOperations= */ 1)) {
                return;
            }
            boolean callAccepted = mExecutorManager.executeLambdaForUserAsync(targetUser,
                    callback, callingPackageName, CallStats.CALL_TYPE_PUT_DOCUMENTS_FROM_FILE,
                    () -> {
                @AppSearchResult.ResultCode int statusCode = AppSearchResult.RESULT_OK;
                AppSearchUserInstance instance = null;
                int operationSuccessCount = 0;
                int operationFailureCount = 0;
                SchemaMigrationStats.Builder schemaMigrationStatsBuilder = new SchemaMigrationStats
                        .Builder(request.getSchemaMigrationStats());
                try {
                    instance = mAppSearchUserInstanceManager.getUserInstance(targetUser);

                    GenericDocument document;
                    ArrayList<MigrationFailure> migrationFailures = new ArrayList<>();
                    try (DataInputStream inputStream = new DataInputStream(new FileInputStream(
                            request.getParcelFileDescriptor().getFileDescriptor()))) {
                        while (true) {
                            try {
                                document = AppSearchMigrationHelper
                                        .readDocumentFromInputStream(inputStream);
                            } catch (EOFException e) {
                                // nothing wrong, we just finish the reading.
                                break;
                            }
                            try {
                                // Per this method's documentation, individual document change
                                // notifications are not dispatched.
                                instance.getAppSearchImpl().putDocument(
                                        callingPackageName,
                                        request.getDatabaseName(),
                                        document,
                                        /* sendChangeNotifications= */ false,
                                        /* logger= */ null);
                                ++operationSuccessCount;
                            } catch (AppSearchException | RuntimeException e) {
                                // We don't rethrow here, so we can still keep going with the
                                // following documents.
                                ++operationFailureCount;
                                AppSearchResult<Void> failedResult = throwableToFailedResult(e);
                                statusCode = failedResult.getResultCode();
                                migrationFailures.add(new SetSchemaResponse.MigrationFailure(
                                        document.getNamespace(),
                                        document.getId(),
                                        document.getSchemaType(),
                                        failedResult));
                            }
                        }
                    }
                    // Now that the batch has been written, persist the newly written data.
                    if (Flags.enableDelayedPersistToDisk()) {
                        schedulePersistToDisk(targetUser,
                                mAppSearchConfig.getLightweightPersistType(),
                                mAppSearchConfig.getCachedPersistDelayMillis());
                    } else {
                        instance.getAppSearchImpl().persistToDisk(
                                mAppSearchConfig.getLightweightPersistType());
                    }

                    schemaMigrationStatsBuilder
                            .setTotalSuccessMigratedDocumentCount(operationSuccessCount)
                            .setMigrationFailureCount(migrationFailures.size());
                    invokeCallbackOnResult(callback, AppSearchResultParcel
                            .fromMigrationFailuresList(migrationFailures));
                } catch (AppSearchException | IOException | RuntimeException
                         | InterruptedException | ExecutionException e) {
                    ++operationFailureCount;
                    AppSearchResult<Void> failedResult = throwableToFailedResult(e);
                    statusCode = failedResult.getResultCode();
                    invokeCallbackOnResult(callback, AppSearchResultParcel.fromFailedResult(
                            failedResult));
                } finally {
                    if (instance != null) {
                        dropStorageInfoCacheForUser(targetUser);
                        long latencyEndTimeMillis =
                                SystemClock.elapsedRealtime();
                        int estimatedBinderLatencyMillis =
                                2 * (int) (callStatsTotalLatencyStartTimeMillis
                                        - request.getBinderCallStartTimeMillis());
                        int callStatsTotalLatencyMillis =
                                (int) (latencyEndTimeMillis - callStatsTotalLatencyStartTimeMillis);
                        // totalLatencyStartTimeMillis is captured in the SDK side, and
                        // put migrate documents is the last step of migration process.
                        // This should includes whole schema migration process.
                        // Like get old schema, first and second set schema, query old
                        // documents, transform documents and save migrated documents.
                        int totalLatencyMillis = (int) (latencyEndTimeMillis
                                - request.getTotalLatencyStartTimeMillis());
                        int saveDocumentLatencyMillis = (int) (latencyEndTimeMillis
                                - request.getBinderCallStartTimeMillis());
                        instance.getLogger().logStats(new CallStats.Builder()
                                .setPackageName(callingPackageName)
                                .setDatabase(request.getDatabaseName())
                                .setStatusCode(statusCode)
                                .setTotalLatencyMillis(callStatsTotalLatencyMillis)
                                .setCallReceivedTimestampMillis(callReceivedTimestampMillis)
                                .setCallType(CallStats.CALL_TYPE_PUT_DOCUMENTS_FROM_FILE)
                                // TODO(b/173532925) check the existing binder call latency chart
                                // is good enough for us:
                                // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                                .setEstimatedBinderLatencyMillis(estimatedBinderLatencyMillis)
                                .setNumOperationsSucceeded(operationSuccessCount)
                                .setNumOperationsFailed(operationFailureCount)
                                .setLaunchVMEnabled(instance.isVMEnabled())
                                .build());
                        long enabledFeatures = new BaseStats.Builder<>()
                                .setLaunchVMEnabled(true).build().getEnabledFeatures();
                        instance.getLogger().logStats(schemaMigrationStatsBuilder
                                .setStatusCode(statusCode)
                                .setTotalLatencyMillis(totalLatencyMillis)
                                .setSaveDocumentLatencyMillis(saveDocumentLatencyMillis)
                                .setEnabledFeatures(enabledFeatures)
                                .build());
                    }
                }
            });
            if (!callAccepted) {
                logRateLimitedOrCallDeniedCallStats(callingPackageName, request.getDatabaseName(),
                        CallStats.CALL_TYPE_PUT_DOCUMENTS_FROM_FILE, targetUser,
                        request.getBinderCallStartTimeMillis(),
                        callStatsTotalLatencyStartTimeMillis, /* numOperations= */ 1,
                        RESULT_RATE_LIMITED);
            }
        }

        @Override
        public void searchSuggestion(
                @NonNull SearchSuggestionAidlRequest request,
                @NonNull IAppSearchResultCallback callback) {
            Objects.requireNonNull(request);
            Objects.requireNonNull(callback);
            final long callReceivedTimestampMillis = System.currentTimeMillis();

            long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            UserHandle targetUser = mServiceImplHelper.verifyIncomingCallWithCallback(
                    request.getCallerAttributionSource(), request.getUserHandle(), callback);
            String callingPackageName = request.getCallerAttributionSource().getPackageName();
            if (targetUser == null) {
                return;  // Verification failed; verifyIncomingCall triggered callback.
            }
            if (checkCallDenied(callingPackageName, request.getDatabaseName(),
                    CallStats.CALL_TYPE_SEARCH_SUGGESTION, callback, targetUser,
                    request.getBinderCallStartTimeMillis(), totalLatencyStartTimeMillis,
                    /* numOperations= */ 1)) {
                return;
            }
            boolean callAccepted = mExecutorManager.executeLambdaForUserAsync(targetUser,
                    callback, callingPackageName, CallStats.CALL_TYPE_SEARCH_SUGGESTION,
                    () -> {
                @AppSearchResult.ResultCode int statusCode = AppSearchResult.RESULT_OK;
                AppSearchUserInstance instance = null;
                int operationSuccessCount = 0;
                int operationFailureCount = 0;
                try {
                    instance = mAppSearchUserInstanceManager.getUserInstance(targetUser);
                    // TODO(b/173532925): Implement logging for statsBuilder
                    List<SearchSuggestionResult> searchSuggestionResults =
                            instance.getAppSearchImpl().searchSuggestion(
                                    callingPackageName,
                                    request.getDatabaseName(),
                                    request.getSuggestionQueryExpression(),
                                    request.getSearchSuggestionSpec());
                    ++operationSuccessCount;
                    invokeCallbackOnResult(
                            callback, AppSearchResultParcel
                                    .fromSearchSuggestionResultList(searchSuggestionResults));
                } catch (AppSearchException | RuntimeException | InterruptedException
                         | ExecutionException e) {
                    ++operationFailureCount;
                    AppSearchResult<Void> failedResult = throwableToFailedResult(e);
                    statusCode = failedResult.getResultCode();
                    invokeCallbackOnResult(callback, AppSearchResultParcel.fromFailedResult(
                            failedResult));
                } finally {
                    if (instance != null) {
                        int estimatedBinderLatencyMillis =
                                2 * (int) (totalLatencyStartTimeMillis
                                        - request.getBinderCallStartTimeMillis());
                        int totalLatencyMillis =
                                (int) (SystemClock.elapsedRealtime() - totalLatencyStartTimeMillis);
                        instance.getLogger().logStats(new CallStats.Builder()
                                .setPackageName(callingPackageName)
                                .setDatabase(request.getDatabaseName())
                                .setStatusCode(statusCode)
                                .setTotalLatencyMillis(totalLatencyMillis)
                                .setCallReceivedTimestampMillis(callReceivedTimestampMillis)
                                .setCallType(CallStats.CALL_TYPE_SEARCH_SUGGESTION)
                                // TODO(b/173532925) check the existing binder call latency chart
                                // is good enough for us:
                                // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                                .setEstimatedBinderLatencyMillis(estimatedBinderLatencyMillis)
                                .setNumOperationsSucceeded(operationSuccessCount)
                                .setNumOperationsFailed(operationFailureCount)
                                .setLaunchVMEnabled(instance.isVMEnabled())
                                .build());
                    }
                }
            });
            if (!callAccepted) {
                logRateLimitedOrCallDeniedCallStats(callingPackageName, request.getDatabaseName(),
                        CallStats.CALL_TYPE_SEARCH_SUGGESTION, targetUser,
                        request.getBinderCallStartTimeMillis(), totalLatencyStartTimeMillis,
                        /* numOperations= */ 1, RESULT_RATE_LIMITED);
            }
        }

        @Override
        public void reportUsage(
                @NonNull ReportUsageAidlRequest request,
                @NonNull IAppSearchResultCallback callback) {
            Objects.requireNonNull(request);
            Objects.requireNonNull(callback);
            final long callReceivedTimestampMillis = System.currentTimeMillis();

            long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            UserHandle targetUser = mServiceImplHelper.verifyIncomingCallWithCallback(
                    request.getCallerAttributionSource(), request.getUserHandle(), callback);
            String callingPackageName = request.getCallerAttributionSource().getPackageName();
            if (targetUser == null) {
                return;  // Verification failed; verifyIncomingCall triggered callback.
            }
            // We deny based on the calling package and calling database names. If the API call is
            // intended for system usage, then the call is global, and the target database is not a
            // calling database.
            String callingDatabaseName = request.isSystemUsage()
                    ? null : request.getDatabaseName();
            int callType = request.isSystemUsage() ? CallStats.CALL_TYPE_REPORT_SYSTEM_USAGE
                    : CallStats.CALL_TYPE_REPORT_USAGE;
            if (checkCallDenied(callingPackageName, callingDatabaseName, callType, callback,
                    targetUser, request.getBinderCallStartTimeMillis(), totalLatencyStartTimeMillis,
                    /* numOperations= */ 1)) {
                return;
            }
            boolean callAccepted = mExecutorManager.executeLambdaForUserAsync(targetUser,
                    callback, callingPackageName, CallStats.CALL_TYPE_REPORT_USAGE,
                    () -> {
                @AppSearchResult.ResultCode int statusCode = AppSearchResult.RESULT_OK;
                AppSearchUserInstance instance = null;
                int operationSuccessCount = 0;
                int operationFailureCount = 0;
                try {
                    instance = mAppSearchUserInstanceManager.getUserInstance(targetUser);
                    if (request.isSystemUsage()) {
                        if (!instance.getVisibilityChecker().doesCallerHaveSystemAccess(
                                callingPackageName)) {
                            throw new AppSearchException(RESULT_SECURITY_ERROR,
                                    callingPackageName
                                            + " does not have access to report system usage");
                        }
                    } else {
                        if (!callingPackageName.equals(request.getTargetPackageName())) {
                            throw new AppSearchException(RESULT_SECURITY_ERROR,
                                    "Cannot report usage to different package: "
                                            + request.getTargetPackageName() + " from package: "
                                            + callingPackageName);
                        }
                    }

                    instance.getAppSearchImpl().reportUsage(request.getTargetPackageName(),
                            request.getDatabaseName(),
                            request.getReportUsageRequest().getNamespace(),
                            request.getReportUsageRequest().getDocumentId(),
                            request.getReportUsageRequest().getUsageTimestampMillis(),
                            request.isSystemUsage());
                    ++operationSuccessCount;
                    invokeCallbackOnResult(callback, AppSearchResultParcel.fromVoid());
                } catch (AppSearchException | RuntimeException | InterruptedException
                         | ExecutionException e) {
                    ++operationFailureCount;
                    AppSearchResult<Void> failedResult = throwableToFailedResult(e);
                    statusCode = failedResult.getResultCode();
                    invokeCallbackOnResult(callback, AppSearchResultParcel.fromFailedResult(
                            failedResult));
                } finally {
                    if (instance != null) {
                        dropStorageInfoCacheForUser(targetUser);
                        int estimatedBinderLatencyMillis =
                                2 * (int) (totalLatencyStartTimeMillis -
                                        request.getBinderCallStartTimeMillis());
                        int totalLatencyMillis =
                                (int) (SystemClock.elapsedRealtime() - totalLatencyStartTimeMillis);
                        instance.getLogger().logStats(new CallStats.Builder()
                                .setPackageName(callingPackageName)
                                .setDatabase(request.getDatabaseName())
                                .setStatusCode(statusCode)
                                .setTotalLatencyMillis(totalLatencyMillis)
                                .setCallReceivedTimestampMillis(callReceivedTimestampMillis)
                                .setCallType(callType)
                                // TODO(b/173532925) check the existing binder call latency chart
                                // is good enough for us:
                                // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                                .setEstimatedBinderLatencyMillis(estimatedBinderLatencyMillis)
                                .setNumOperationsSucceeded(operationSuccessCount)
                                .setNumOperationsFailed(operationFailureCount)
                                .setLaunchVMEnabled(instance.isVMEnabled())
                                .build());
                    }
                }
            });
            if (!callAccepted) {
                logRateLimitedOrCallDeniedCallStats(callingPackageName, callingDatabaseName,
                        callType, targetUser, request.getBinderCallStartTimeMillis(),
                        totalLatencyStartTimeMillis,
                        /* numOperations= */ 1, RESULT_RATE_LIMITED);
            }
        }

        @Override
        public void removeByDocumentId(
                @NonNull RemoveByDocumentIdAidlRequest request,
                @NonNull IAppSearchBatchResultCallback callback) {
            Objects.requireNonNull(request);
            Objects.requireNonNull(callback);
            final long callReceivedTimestampMillis = System.currentTimeMillis();

            long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            UserHandle targetUser = mServiceImplHelper.verifyIncomingCallWithCallback(
                    request.getCallerAttributionSource(), request.getUserHandle(), callback);
            String callingPackageName = request.getCallerAttributionSource().getPackageName();
            if (targetUser == null) {
                return;  // Verification failed; verifyIncomingCall triggered callback.
            }
            if (checkCallDenied(callingPackageName, request.getDatabaseName(),
                    CallStats.CALL_TYPE_REMOVE_DOCUMENTS_BY_ID, callback, targetUser,
                    request.getBinderCallStartTimeMillis(), totalLatencyStartTimeMillis,
                    /* numOperations= */ request.getRemoveByDocumentIdRequest().getIds().size())) {
                return;
            }
            boolean callAccepted = mExecutorManager.executeLambdaForUserAsync(targetUser,
                    callback, callingPackageName, CallStats.CALL_TYPE_REMOVE_DOCUMENTS_BY_ID,
                    () -> {
                @AppSearchResult.ResultCode int statusCode = RESULT_OK;
                AppSearchUserInstance instance = null;
                int operationSuccessCount = 0;
                int operationFailureCount = 0;
                try {
                    AppSearchBatchResult.Builder<String, Void> resultBuilder =
                            new AppSearchBatchResult.Builder<>();
                    instance = mAppSearchUserInstanceManager.getUserInstance(targetUser);
                    for (String id : request.getRemoveByDocumentIdRequest().getIds()) {
                        try {
                            instance.getAppSearchImpl().remove(
                                    callingPackageName,
                                    request.getDatabaseName(),
                                    request.getRemoveByDocumentIdRequest().getNamespace(),
                                    id,
                                    /* removeStatsBuilder= */ null);
                            ++operationSuccessCount;
                            resultBuilder.setSuccess(id, /*result= */ null);
                        } catch (AppSearchException | RuntimeException e) {
                            // We don't rethrow here, so we can still keep trying for the following
                            // ones.
                            AppSearchResult<Void> result = throwableToFailedResult(e);
                            resultBuilder.setResult(id, result);
                            // Since we can only include one status code in the atom,
                            // for failures, we would just save the one for the last failure
                            statusCode = result.getResultCode();
                            ++operationFailureCount;
                        }
                    }
                    // Now that the batch has been written, persist the newly written data.
                    if (Flags.enableDelayedPersistToDisk()) {
                        schedulePersistToDisk(targetUser,
                                mAppSearchConfig.getLightweightPersistType(),
                                mAppSearchConfig.getCachedPersistDelayMillis());
                    } else {
                        instance.getAppSearchImpl().persistToDisk(
                                mAppSearchConfig.getLightweightPersistType());
                    }
                    invokeCallbackOnResult(callback, AppSearchBatchResultParcel.fromStringToVoid(
                            resultBuilder.build()));

                    // Schedule a task to dispatch change notifications. See requirements for where
                    // the method is called documented in the method description.
                    dispatchChangeNotifications(instance);

                    checkForOptimize(targetUser, instance,
                            request.getRemoveByDocumentIdRequest().getIds().size());
                } catch (AppSearchException | RuntimeException | InterruptedException
                         | ExecutionException e) {
                    ++operationFailureCount;
                    AppSearchResult<Void> failedResult = throwableToFailedResult(e);
                    statusCode = failedResult.getResultCode();
                    invokeCallbackOnError(callback, failedResult);
                } finally {
                    // TODO(b/261959320) add outstanding latency fields in AppSearch stats
                    if (instance != null) {
                        dropStorageInfoCacheForUser(targetUser);
                        int estimatedBinderLatencyMillis =
                                2 * (int) (totalLatencyStartTimeMillis
                                        - request.getBinderCallStartTimeMillis());
                        int totalLatencyMillis =
                                (int) (SystemClock.elapsedRealtime() - totalLatencyStartTimeMillis);
                        instance.getLogger().logStats(new CallStats.Builder()
                                .setPackageName(callingPackageName)
                                .setDatabase(request.getDatabaseName())
                                .setStatusCode(statusCode)
                                .setTotalLatencyMillis(totalLatencyMillis)
                                .setCallReceivedTimestampMillis(callReceivedTimestampMillis)
                                .setCallType(CallStats.CALL_TYPE_REMOVE_DOCUMENTS_BY_ID)
                                // TODO(b/173532925) check the existing binder call latency chart
                                // is good enough for us:
                                // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                                .setEstimatedBinderLatencyMillis(estimatedBinderLatencyMillis)
                                .setNumOperationsSucceeded(operationSuccessCount)
                                .setNumOperationsFailed(operationFailureCount)
                                .setLaunchVMEnabled(instance.isVMEnabled())
                                .build());
                    }
                }
            });
            if (!callAccepted) {
                logRateLimitedOrCallDeniedCallStats(callingPackageName, request.getDatabaseName(),
                        CallStats.CALL_TYPE_REMOVE_DOCUMENTS_BY_ID, targetUser,
                        request.getBinderCallStartTimeMillis(), totalLatencyStartTimeMillis,
                        /* numOperations= */ request.getRemoveByDocumentIdRequest().getIds().size(),
                        RESULT_RATE_LIMITED);
            }
        }

        @Override
        public void removeByQuery(
                @NonNull RemoveByQueryAidlRequest request,
                @NonNull IAppSearchResultCallback callback) {
            Objects.requireNonNull(request);
            Objects.requireNonNull(callback);
            final long callReceivedTimestampMillis = System.currentTimeMillis();

            checkUnsupportedEmbeddingUse(request.getSearchSpec());

            long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            UserHandle targetUser = mServiceImplHelper.verifyIncomingCallWithCallback(
                    request.getCallerAttributionSource(), request.getUserHandle(), callback);
            String callingPackageName = request.getCallerAttributionSource().getPackageName();
            if (targetUser == null) {
                return;  // Verification failed; verifyIncomingCall triggered callback.
            }
            if (checkCallDenied(callingPackageName, request.getDatabaseName(),
                    CallStats.CALL_TYPE_REMOVE_DOCUMENTS_BY_SEARCH, callback, targetUser,
                    request.getBinderCallStartTimeMillis(), totalLatencyStartTimeMillis,
                    /* numOperations= */ 1)) {
                return;
            }
            boolean callAccepted = mExecutorManager.executeLambdaForUserAsync(targetUser,
                    callback, callingPackageName, CallStats.CALL_TYPE_REMOVE_DOCUMENTS_BY_SEARCH,
                    () -> {
                @AppSearchResult.ResultCode int statusCode = RESULT_OK;
                AppSearchUserInstance instance = null;
                int operationSuccessCount = 0;
                int operationFailureCount = 0;
                try {
                    instance = mAppSearchUserInstanceManager.getUserInstance(targetUser);
                    instance.getAppSearchImpl().removeByQuery(
                            callingPackageName,
                            request.getDatabaseName(),
                            request.getQueryExpression(),
                            request.getSearchSpec(),
                            /* removeStatsBuilder= */ null);
                    // Now that the batch has been written, persist the newly written data.
                    if (Flags.enableDelayedPersistToDisk()) {
                        schedulePersistToDisk(targetUser,
                                mAppSearchConfig.getLightweightPersistType(),
                                mAppSearchConfig.getCachedPersistDelayMillis());
                    } else {
                        instance.getAppSearchImpl().persistToDisk(
                                mAppSearchConfig.getLightweightPersistType());
                    }
                    ++operationSuccessCount;
                    invokeCallbackOnResult(callback, AppSearchResultParcel.fromVoid());

                    // Schedule a task to dispatch change notifications. See requirements for where
                    // the method is called documented in the method description.
                    dispatchChangeNotifications(instance);

                    checkForOptimize(targetUser, instance);
                } catch (AppSearchException | RuntimeException | InterruptedException
                         | ExecutionException e) {
                    ++operationFailureCount;
                    AppSearchResult<Void> failedResult = throwableToFailedResult(e);
                    statusCode = failedResult.getResultCode();
                    invokeCallbackOnResult(callback, AppSearchResultParcel.fromFailedResult(
                            failedResult));
                } finally {
                    // TODO(b/261959320) add outstanding latency fields in AppSearch stats
                    if (instance != null) {
                        dropStorageInfoCacheForUser(targetUser);
                        int estimatedBinderLatencyMillis =
                                2 * (int) (totalLatencyStartTimeMillis
                                        - request.getBinderCallStartTimeMillis());
                        int totalLatencyMillis =
                                (int) (SystemClock.elapsedRealtime() - totalLatencyStartTimeMillis);
                        instance.getLogger().logStats(new CallStats.Builder()
                                .setPackageName(callingPackageName)
                                .setDatabase(request.getDatabaseName())
                                .setStatusCode(statusCode)
                                .setTotalLatencyMillis(totalLatencyMillis)
                                .setCallReceivedTimestampMillis(callReceivedTimestampMillis)
                                .setCallType(CallStats.CALL_TYPE_REMOVE_DOCUMENTS_BY_SEARCH)
                                // TODO(b/173532925) check the existing binder call latency chart
                                // is good enough for us:
                                // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                                .setEstimatedBinderLatencyMillis(estimatedBinderLatencyMillis)
                                .setNumOperationsSucceeded(operationSuccessCount)
                                .setNumOperationsFailed(operationFailureCount)
                                .setLaunchVMEnabled(instance.isVMEnabled())
                                .build());
                    }
                }
            });
            if (!callAccepted) {
                logRateLimitedOrCallDeniedCallStats(callingPackageName, request.getDatabaseName(),
                        CallStats.CALL_TYPE_REMOVE_DOCUMENTS_BY_SEARCH, targetUser,
                        request.getBinderCallStartTimeMillis(), totalLatencyStartTimeMillis,
                        /* numOperations= */ 1, RESULT_RATE_LIMITED);
            }
        }

        @Override
        public void getStorageInfo(
                @NonNull GetStorageInfoAidlRequest request,
                @NonNull IAppSearchResultCallback callback) {
            Objects.requireNonNull(request);
            Objects.requireNonNull(callback);
            final long callReceivedTimestampMillis = System.currentTimeMillis();

            long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            UserHandle targetUser = mServiceImplHelper.verifyIncomingCallWithCallback(
                    request.getCallerAttributionSource(), request.getUserHandle(), callback);
            String callingPackageName = request.getCallerAttributionSource().getPackageName();
            if (targetUser == null) {
                return;  // Verification failed; verifyIncomingCall triggered callback.
            }
            if (checkCallDenied(callingPackageName, request.getDatabaseName(),
                    CallStats.CALL_TYPE_GET_STORAGE_INFO, callback, targetUser,
                    request.getBinderCallStartTimeMillis(), totalLatencyStartTimeMillis,
                    /* numOperations= */ 1)) {
                return;
            }
            boolean callAccepted = mExecutorManager.executeLambdaForUserAsync(targetUser,
                    callback, callingPackageName, CallStats.CALL_TYPE_GET_STORAGE_INFO, () -> {
                @AppSearchResult.ResultCode int statusCode = AppSearchResult.RESULT_OK;
                AppSearchUserInstance instance = null;
                int operationSuccessCount = 0;
                int operationFailureCount = 0;
                try {
                    instance = mAppSearchUserInstanceManager.getUserInstance(targetUser);
                    StorageInfo storageInfo = instance.getAppSearchImpl().getStorageInfoForDatabase(
                            callingPackageName, request.getDatabaseName());
                    ++operationSuccessCount;
                    invokeCallbackOnResult(
                            callback, AppSearchResultParcel.fromStorageInfo(storageInfo));
                } catch (AppSearchException | RuntimeException | InterruptedException
                         | ExecutionException e) {
                    ++operationFailureCount;
                    AppSearchResult<Void> failedResult = throwableToFailedResult(e);
                    statusCode = failedResult.getResultCode();
                    invokeCallbackOnResult(callback, AppSearchResultParcel.fromFailedResult(
                            failedResult));
                } finally {
                    if (instance != null) {
                        int estimatedBinderLatencyMillis =
                                2 * (int) (totalLatencyStartTimeMillis
                                        - request.getBinderCallStartTimeMillis());
                        int totalLatencyMillis =
                                (int) (SystemClock.elapsedRealtime() - totalLatencyStartTimeMillis);
                        instance.getLogger().logStats(new CallStats.Builder()
                                .setPackageName(callingPackageName)
                                .setDatabase(request.getDatabaseName())
                                .setStatusCode(statusCode)
                                .setTotalLatencyMillis(totalLatencyMillis)
                                .setCallReceivedTimestampMillis(callReceivedTimestampMillis)
                                .setCallType(CallStats.CALL_TYPE_GET_STORAGE_INFO)
                                // TODO(b/173532925) check the existing binder call latency chart
                                // is good enough for us:
                                // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                                .setEstimatedBinderLatencyMillis(estimatedBinderLatencyMillis)
                                .setNumOperationsSucceeded(operationSuccessCount)
                                .setNumOperationsFailed(operationFailureCount)
                                .setLaunchVMEnabled(instance.isVMEnabled())
                                .build());
                    }
                }
            });
            if (!callAccepted) {
                logRateLimitedOrCallDeniedCallStats(callingPackageName, request.getDatabaseName(),
                        CallStats.CALL_TYPE_GET_STORAGE_INFO, targetUser,
                        request.getBinderCallStartTimeMillis(), totalLatencyStartTimeMillis,
                        /* numOperations= */ 1, RESULT_RATE_LIMITED);
            }
        }

        @Override
        public void persistToDisk(@NonNull PersistToDiskAidlRequest request) {
            Objects.requireNonNull(request);
            final long callReceivedTimestampMillis = System.currentTimeMillis();

            long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            try {
                UserHandle targetUser = mServiceImplHelper.verifyIncomingCall(
                        request.getCallerAttributionSource(), request.getUserHandle());
                String callingPackageName = request.getCallerAttributionSource().getPackageName();
                if (checkCallDenied(callingPackageName, /* callingDatabaseName= */ null,
                        CallStats.CALL_TYPE_FLUSH, targetUser,
                        request.getBinderCallStartTimeMillis(), totalLatencyStartTimeMillis,
                        /* numOperations= */ 1)) {
                    return;
                }
                boolean callAccepted = mExecutorManager.executeLambdaForUserNoCallbackAsync(
                        targetUser, callingPackageName, CallStats.CALL_TYPE_FLUSH, () -> {
                    @AppSearchResult.ResultCode int statusCode = RESULT_OK;
                    AppSearchUserInstance instance = null;
                    int operationSuccessCount = 0;
                    int operationFailureCount = 0;
                    try {
                        if (Flags.enableNoOpManualPersist()) {
                            Log.w(TAG,
                                    "Received persistToDisk call. Skipping since "
                                            + "AppSearchManagerService manages its own "
                                            + "persistence schedule");
                        } else {
                            instance = mAppSearchUserInstanceManager.getUserInstance(targetUser);
                            instance.getAppSearchImpl().persistToDisk(PersistType.Code.FULL);
                        }
                        ++operationSuccessCount;
                    } catch (AppSearchException | RuntimeException | InterruptedException
                             | ExecutionException e) {
                        ++operationFailureCount;
                        statusCode = throwableToFailedResult(e).getResultCode();
                        // We will print two error messages if we rethrow, but I would rather keep
                        // this print statement here, so we know where the actual exception
                        // comes from.
                        Log.e(TAG, "Unable to persist the data to disk", e);
                        ExceptionUtil.handleException(e);
                    } finally {
                        if (instance != null) {
                            int estimatedBinderLatencyMillis =
                                    2 * (int) (totalLatencyStartTimeMillis
                                            - request.getBinderCallStartTimeMillis());
                            int totalLatencyMillis =
                                    (int) (SystemClock.elapsedRealtime()
                                            - totalLatencyStartTimeMillis);
                            instance.getLogger().logStats(new CallStats.Builder()
                                    .setPackageName(callingPackageName)
                                    .setStatusCode(statusCode)
                                    .setTotalLatencyMillis(totalLatencyMillis)
                                    .setCallReceivedTimestampMillis(callReceivedTimestampMillis)
                                    .setCallType(CallStats.CALL_TYPE_FLUSH)
                                    // TODO(b/173532925) check the existing binder call latency
                                    // chart is good enough for us:
                                    // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                                    .setEstimatedBinderLatencyMillis(estimatedBinderLatencyMillis)
                                    .setNumOperationsSucceeded(operationSuccessCount)
                                    .setNumOperationsFailed(operationFailureCount)
                                    .setLaunchVMEnabled(instance.isVMEnabled())
                                    .build());
                        }
                    }
                });
                if (!callAccepted) {
                    logRateLimitedOrCallDeniedCallStats(
                            callingPackageName, /* callingDatabaseName= */ null,
                            CallStats.CALL_TYPE_FLUSH, targetUser,
                            request.getBinderCallStartTimeMillis(), totalLatencyStartTimeMillis,
                            /* numOperations= */ 1, RESULT_RATE_LIMITED);
                }
            } catch (RuntimeException e) {
                Log.e(TAG, "Unable to persist the data to disk", e);
                ExceptionUtil.handleException(e);
            }
        }

        @Override
        public AppSearchResultParcel<Void> registerObserverCallback(
                @NonNull RegisterObserverCallbackAidlRequest request,
                @NonNull IAppSearchObserverProxy observerProxyStub) {
            Objects.requireNonNull(request);
            Objects.requireNonNull(observerProxyStub);
            final long callReceivedTimestampMillis = System.currentTimeMillis();

            @AppSearchResult.ResultCode int statusCode = AppSearchResult.RESULT_OK;
            AppSearchUserInstance instance = null;
            String callingPackageName = null;
            int operationSuccessCount = 0;
            int operationFailureCount = 0;
            long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            // Note: registerObserverCallback is performed on the binder thread, unlike most
            // AppSearch APIs
            try {
                UserHandle targetUser = mServiceImplHelper.verifyIncomingCall(
                        request.getCallerAttributionSource(), request.getUserHandle());
                callingPackageName = request.getCallerAttributionSource().getPackageName();
                if (checkCallDenied(callingPackageName, /* callingDatabaseName= */ null,
                        CallStats.CALL_TYPE_REGISTER_OBSERVER_CALLBACK, targetUser,
                        request.getBinderCallStartTimeMillis(), totalLatencyStartTimeMillis,
                        /* numOperations= */ 1)) {
                    return AppSearchResultParcel.fromFailedResult(AppSearchResult.newFailedResult(
                            RESULT_DENIED, null));
                }
                long callingIdentity = Binder.clearCallingIdentity();
                try {
                    instance = mAppSearchUserInstanceManager.getUserInstance(targetUser);

                    // Prepare a new ObserverProxy linked to this binder.
                    AppSearchObserverProxy observerProxy =
                            new AppSearchObserverProxy(observerProxyStub);

                    // Watch for client disconnection, unregistering the observer if it happens.
                    final AppSearchUserInstance finalInstance = instance;
                    observerProxyStub.asBinder().linkToDeath(
                            () -> finalInstance.getAppSearchImpl()
                                    .unregisterObserverCallback(
                                            request.getTargetPackageName(), observerProxy),
                            /* flags= */ 0);

                    // Register the observer.
                    boolean callerHasSystemAccess = instance.getVisibilityChecker()
                            .doesCallerHaveSystemAccess(callingPackageName);
                    instance.getAppSearchImpl().registerObserverCallback(
                            new FrameworkCallerAccess(request.getCallerAttributionSource(),
                                    callerHasSystemAccess, /*isForEnterprise=*/ false),
                            request.getTargetPackageName(),
                            request.getObserverSpec(),
                            mExecutorManager.getOrCreateUserExecutor(targetUser),
                            new AppSearchObserverProxy(observerProxyStub));
                    ++operationSuccessCount;
                    return AppSearchResultParcel.fromVoid();
                } finally {
                    Binder.restoreCallingIdentity(callingIdentity);
                }
            } catch (RemoteException
                     | RuntimeException
                     | InterruptedException
                     | ExecutionException e) {
                ++operationFailureCount;
                AppSearchResult<Void> failedResult = throwableToFailedResult(e);
                statusCode = failedResult.getResultCode();
                return AppSearchResultParcel.fromFailedResult(failedResult);
            } finally {
                if (instance != null) {
                    int estimatedBinderLatencyMillis =
                            2 * (int) (totalLatencyStartTimeMillis
                                    - request.getBinderCallStartTimeMillis());
                    int totalLatencyMillis =
                            (int) (SystemClock.elapsedRealtime() - totalLatencyStartTimeMillis);
                    instance.getLogger().logStats(new CallStats.Builder()
                            .setPackageName(callingPackageName)
                            .setStatusCode(statusCode)
                            .setTotalLatencyMillis(totalLatencyMillis)
                            .setCallReceivedTimestampMillis(callReceivedTimestampMillis)
                            .setCallType(CallStats.CALL_TYPE_REGISTER_OBSERVER_CALLBACK)
                            // TODO(b/173532925) check the existing binder call latency chart
                            // is good enough for us:
                            // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                            .setEstimatedBinderLatencyMillis(estimatedBinderLatencyMillis)
                            .setNumOperationsSucceeded(operationSuccessCount)
                            .setNumOperationsFailed(operationFailureCount)
                            .setLaunchVMEnabled(instance.isVMEnabled())
                            .build());
                }
            }
        }

        @Override
        public AppSearchResultParcel<Void> unregisterObserverCallback(
                @NonNull UnregisterObserverCallbackAidlRequest request,
                @NonNull IAppSearchObserverProxy observerProxyStub) {
            Objects.requireNonNull(request);
            Objects.requireNonNull(observerProxyStub);
            final long callReceivedTimestampMillis = System.currentTimeMillis();

            @AppSearchResult.ResultCode int statusCode = AppSearchResult.RESULT_OK;
            AppSearchUserInstance instance = null;
            int operationSuccessCount = 0;
            int operationFailureCount = 0;
            long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            // Note: unregisterObserverCallback is performed on the binder thread, unlike most
            // AppSearch APIs
            try {
                UserHandle targetUser = mServiceImplHelper.verifyIncomingCall(
                        request.getCallerAttributionSource(), request.getUserHandle());
                String callingPackageName = request.getCallerAttributionSource().getPackageName();
                if (checkCallDenied(callingPackageName, /* callingDatabaseName= */ null,
                        CallStats.CALL_TYPE_UNREGISTER_OBSERVER_CALLBACK, targetUser,
                        request.getBinderCallStartTimeMillis(), totalLatencyStartTimeMillis,
                        /* numOperations= */ 1)) {
                    return AppSearchResultParcel.fromFailedResult(AppSearchResult.newFailedResult(
                            RESULT_DENIED, null));
                }
                long callingIdentity = Binder.clearCallingIdentity();
                try {
                    instance = mAppSearchUserInstanceManager.getUserInstance(targetUser);
                    instance.getAppSearchImpl().unregisterObserverCallback(
                            request.getObservedPackage(),
                            new AppSearchObserverProxy(observerProxyStub));
                    ++operationSuccessCount;
                    return AppSearchResultParcel.fromVoid();
                } finally {
                    Binder.restoreCallingIdentity(callingIdentity);
                }
            } catch (RuntimeException | InterruptedException | ExecutionException e) {
                ++operationFailureCount;
                AppSearchResult<Void> failedResult = throwableToFailedResult(e);
                statusCode = failedResult.getResultCode();
                return AppSearchResultParcel.fromFailedResult(failedResult);
            } finally {
                if (instance != null) {
                    int estimatedBinderLatencyMillis =
                            2 * (int) (totalLatencyStartTimeMillis
                                    - request.getBinderCallStartTimeMillis());
                    int totalLatencyMillis =
                            (int) (SystemClock.elapsedRealtime() - totalLatencyStartTimeMillis);
                    String callingPackageName = request.getCallerAttributionSource()
                            .getPackageName();
                    instance.getLogger().logStats(new CallStats.Builder()
                            .setPackageName(callingPackageName)
                            .setStatusCode(statusCode)
                            .setTotalLatencyMillis(totalLatencyMillis)
                            .setCallReceivedTimestampMillis(callReceivedTimestampMillis)
                            .setCallType(CallStats.CALL_TYPE_UNREGISTER_OBSERVER_CALLBACK)
                            // TODO(b/173532925) check the existing binder call latency chart
                            // is good enough for us:
                            // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                            .setEstimatedBinderLatencyMillis(estimatedBinderLatencyMillis)
                            .setNumOperationsSucceeded(operationSuccessCount)
                            .setNumOperationsFailed(operationFailureCount)
                            .setLaunchVMEnabled(instance.isVMEnabled())
                            .build());
                }
            }
        }

        @Override
        public void initialize(
                @NonNull InitializeAidlRequest request,
                @NonNull IAppSearchResultCallback callback) {
            Objects.requireNonNull(request);
            Objects.requireNonNull(callback);
            final long callReceivedTimestampMillis = System.currentTimeMillis();

            long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            UserHandle targetUser = mServiceImplHelper.verifyIncomingCallWithCallback(
                    request.getCallerAttributionSource(), request.getUserHandle(), callback);
            String callingPackageName = request.getCallerAttributionSource().getPackageName();
            if (targetUser == null) {
                return;  // Verification failed; verifyIncomingCall triggered callback.
            }
            if (mAppSearchConfig.getCachedDenylist().checkDeniedPackage(callingPackageName,
                    CallStats.CALL_TYPE_INITIALIZE)) {
                // Note: can't log CallStats here since UserInstance isn't guaranteed to (and most
                // likely does not) exist
                invokeCallbackOnResult(callback, AppSearchResultParcel.fromFailedResult(
                        AppSearchResult.newFailedResult(RESULT_DENIED, null)));
                return;
            }
            mExecutorManager.executeLambdaForUserAsync(targetUser, callback, callingPackageName,
                    CallStats.CALL_TYPE_INITIALIZE, () -> {
                @AppSearchResult.ResultCode int statusCode = RESULT_OK;
                AppSearchUserInstance instance = null;
                int operationSuccessCount = 0;
                int operationFailureCount = 0;
                try {
                    Context targetUserContext = mAppSearchEnvironment
                            .createContextAsUser(mContext, request.getUserHandle());
                    instance = mAppSearchUserInstanceManager.getOrCreateUserInstance(
                            targetUserContext,
                            targetUser,
                            mAppSearchConfig,
                            mExecutorManager,
                            mIsolatedStorageServiceManager);
                    ++operationSuccessCount;
                    invokeCallbackOnResult(callback, AppSearchResultParcel.fromVoid());
                } catch (AppSearchException | RuntimeException e) {
                    ++operationFailureCount;
                    AppSearchResult<Void> failedResult = throwableToFailedResult(e);
                    statusCode = failedResult.getResultCode();
                    invokeCallbackOnResult(callback, AppSearchResultParcel.fromFailedResult(
                            failedResult));
                } finally {
                    if (instance != null) {
                        int estimatedBinderLatencyMillis =
                                2 * (int) (totalLatencyStartTimeMillis
                                        - request.getBinderCallStartTimeMillis());
                        int totalLatencyMillis =
                                (int) (SystemClock.elapsedRealtime() - totalLatencyStartTimeMillis);
                        instance.getLogger().logStats(new CallStats.Builder()
                                .setPackageName(callingPackageName)
                                .setStatusCode(statusCode)
                                .setTotalLatencyMillis(totalLatencyMillis)
                                .setCallReceivedTimestampMillis(callReceivedTimestampMillis)
                                .setCallType(CallStats.CALL_TYPE_INITIALIZE)
                                // TODO(b/173532925) check the existing binder call latency chart
                                // is good enough for us:
                                // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                                .setEstimatedBinderLatencyMillis(estimatedBinderLatencyMillis)
                                .setNumOperationsSucceeded(operationSuccessCount)
                                .setNumOperationsFailed(operationFailureCount)
                                .setLaunchVMEnabled(instance.isVMEnabled())
                                .build());
                    }
                }
            });
        }

        @BinderThread
        private void dumpContactsIndexer(@NonNull PrintWriter pw, boolean verbose) {
            Objects.requireNonNull(pw);
            UserHandle currentUser = UserHandle.getUserHandleForUid(Binder.getCallingUid());
            try {
                pw.println("ContactsIndexer stats for " + currentUser);
                mLifecycle.dumpContactsIndexerForUser(currentUser, pw, verbose);
            } catch (Exception e) {
                String errorMessage =
                        "Unable to dump the internal contacts indexer state for the user: "
                                + currentUser;
                Log.e(TAG, errorMessage, e);
                pw.println(errorMessage);
            }
        }

        @BinderThread
        private void dumpAppsIndexer(@NonNull PrintWriter pw) {
            Objects.requireNonNull(pw);
            UserHandle currentUser = UserHandle.getUserHandleForUid(Binder.getCallingUid());
            try {
                pw.println("AppsIndexer stats for " + currentUser);
                mLifecycle.dumpAppsIndexerForUser(currentUser, pw);
            } catch (Exception e) {
                String errorMessage =
                        "Unable to dump the internal app indexer state for the user: "
                                + currentUser;
                Log.e(TAG, errorMessage, e);
                pw.println(errorMessage);
            }
        }

        @BinderThread
        private void dumpAppOpenEventIndexer(@NonNull PrintWriter pw) {
            Objects.requireNonNull(pw);
            UserHandle currentUser = UserHandle.getUserHandleForUid(Binder.getCallingUid());
            try {
                pw.println("AppOpenEventIndexer stats for " + currentUser);
                mLifecycle.dumpAppOpenEventIndexerForUser(currentUser, pw);
            } catch (Exception e) {
                String errorMessage =
                        "Unable to dump the internal app open event indexer state for the user: "
                                + currentUser;
                Log.e(TAG, errorMessage, e);
                pw.println(errorMessage);
            }
        }

        @BinderThread
        private void dumpAppSearch(@NonNull PrintWriter pw, boolean verbose) {
            Objects.requireNonNull(pw);

            UserHandle currentUser = UserHandle.getUserHandleForUid(Binder.getCallingUid());
            try {
                AppSearchUserInstance instance = mAppSearchUserInstanceManager.getUserInstance(
                        currentUser);

                // Print out the recorded last called APIs.
                List<ApiCallRecord> lastCalledApis = instance.getLogger().getLastCalledApis();
                if (!lastCalledApis.isEmpty()) {
                    pw.println("Last Called APIs:");
                    for (int i = 0; i < lastCalledApis.size(); i++) {
                        pw.println(lastCalledApis.get(i));
                    }
                    pw.println();
                }

                DebugInfoProto debugInfo = instance.getAppSearchImpl().getRawDebugInfoProto(
                        verbose ? DebugInfoVerbosity.Code.DETAILED
                                : DebugInfoVerbosity.Code.BASIC);
                // TODO(b/229778472) Consider showing the original names of namespaces and types
                //  for a specific package if the package name is passed as a parameter from users.
                debugInfo = AdbDumpUtil.desensitizeDebugInfo(debugInfo);
                pw.println(debugInfo.getIndexInfo().getIndexStorageInfo());
                pw.println();
                pw.println("lite_index_info:");
                pw.println(debugInfo.getIndexInfo().getLiteIndexInfo());
                pw.println();
                pw.println("main_index_info:");
                pw.println(debugInfo.getIndexInfo().getMainIndexInfo());
                pw.println();
                pw.println(debugInfo.getDocumentInfo());
                pw.println();
                pw.println(debugInfo.getSchemaInfo());
            } catch (Exception e) {
                String errorMessage =
                        "Unable to dump the internal state for the user: " + currentUser;
                Log.e(TAG, errorMessage, e);
                pw.println(errorMessage);
            }
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                    != PackageManager.PERMISSION_GRANTED) {
                pw.println("Permission Denial: can't dump AppSearchManagerService from pid="
                        + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid()
                        + " due to missing android.permission.DUMP permission");
                return;
            }
            boolean verbose = false;
            if (args != null) {
                for (int i = 0; i < args.length; i++) {
                    String arg = args[i];

                    if (Objects.equals(arg, "-h")) {
                        pw.println(
                                "Dumps the internal state of AppSearch platform storage and "
                                        + "AppSearch Contacts Indexer for the current user.");
                        pw.println("-v, verbose mode");
                        return;
                    } else if (Objects.equals(arg, "-v") || Objects.equals(arg, "-a")) {
                        // "-a" is included when adb dumps all services e.g. in adb bugreport so we
                        // want to run in verbose mode when this happens
                        verbose = true;
                    } else if (arg.startsWith("--query")) {
                        if (Binder.getCallingUid() != Process.ROOT_UID) {
                            pw.println("Query requires root.");
                            return;
                        }
                        if (!"eng".equals(Build.TYPE) && !"userdebug".equals(Build.TYPE)) {
                            pw.println("Query requires eng or userdebug build.");
                            return;
                        }

                        String query = arg.substring("--query".length(), arg.length());
                        if (query.startsWith("=")) {
                            query = query.substring(1, query.length());
                        }
                        if (query.startsWith("\"") && query.endsWith("\"") && query.length() > 1) {
                            query = query.substring(1, query.length() - 1);
                        }

                        for (int j = i + 1; j < args.length; j++) {
                            if (query.length() > 0) query += " ";
                            query += args[j];
                        }

                        pw.println("Dump with query: \"" + query + "\"");
                        runCommandLineQuery(pw, query);
                        return;
                    } else {
                        pw.println(String.format("AppSearch unrecognized arg: \"%s\".", arg));
                        // TODO: we do not return here, though it's probably okay, it
                        // may require more testing.
                    }
                }
            }
            dumpAppSearch(pw, verbose);
            dumpContactsIndexer(pw, verbose);
            dumpAppsIndexer(pw);
            dumpAppOpenEventIndexer(pw);
        }
    }

    @BinderThread
    @SuppressLint("WrongThread")
    private void runCommandLineQuery(@NonNull PrintWriter pw, @NonNull String query) {
        Objects.requireNonNull(pw);
        UserHandle currentUser = UserHandle.getUserHandleForUid(Binder.getCallingUid());
        try {
            AppSearchUserInstance instance =
                    mAppSearchUserInstanceManager.getUserInstance(currentUser);
            SearchSpec querySearchSpec =
                    new SearchSpec.Builder()
                            .setTermMatch(SearchSpec.TERM_MATCH_PREFIX)
                            .setResultCountPerPage(25)
                            .build();
            List<QueryStats> statsList = new ArrayList<>();
            PlatformLogger logger =
                    new PlatformLogger(mContext, mAppSearchConfig) {
                        @Override
                        public void logStats(@NonNull QueryStats stats) {
                            statsList.add(stats);
                        }
                    };
            SearchResultPage searchResultPage =
                    instance.getAppSearchImpl()
                            .globalQuery(
                                    query,
                                    querySearchSpec,
                                    new FrameworkCallerAccess(
                                            new AppSearchAttributionSource(
                                                    mContext.getPackageName(),
                                                    Process.myUid(),
                                                    Process.myPid()),
                                            /* callerHasSystemAccess= */ true,
                                            /* sForEnterprise= */ false),
                                    logger);
            while (!searchResultPage.getResults().isEmpty()) {
                printResultPage(pw, searchResultPage);
                QueryStats.Builder statsBuilder =
                        new QueryStats.Builder(VISIBILITY_SCOPE_GLOBAL, mContext.getPackageName());
                searchResultPage =
                        instance.getAppSearchImpl()
                                .getNextPage(
                                        mContext.getPackageName(),
                                        searchResultPage.getNextPageToken(),
                                        statsBuilder);
                statsList.add(statsBuilder.build());
            }
            printStats(pw, statsList);
        } catch (Exception e) {
            pw.println("Encountered exception: " + e);

            // smoreland@ says - can't find this in logcat output when hit ???
            Log.e(TAG, "Encountered exception ", e);
        }
    }

    private void printResultPage(PrintWriter pw, SearchResultPage page) {
        pw.println("Printing result page: ");
        for (SearchResult result : page.getResults()) {
            pw.println(
                    String.format(
                            "Result={pkg=%s, db=%s, ns=%s, id=%s}",
                            result.getPackageName(),
                            result.getDatabaseName(),
                            result.getGenericDocument().getNamespace(),
                            result.getGenericDocument().getId()));
        }
    }

    private void printStats(PrintWriter pw, List<QueryStats> stats) {
        pw.println("Printing search stats: ");
        for (QueryStats s : stats) {
            pw.println(s);
        }
    }

    private class AppSearchStorageStatsAugmenter implements StorageStatsAugmenter {
        private static final int STORAGE_STATS_RETRIEVAL_TIMEOUT_MS = 100;
        // This lock exists to block incessant calls from StorageStatsManager so that they don't
        // acquire AppSearch's internal lock and starve other requests.
        private final ReentrantLock mStorageStatsCacheRefreshLock = new ReentrantLock();

        @Override
        public void augmentStatsForPackageForUser(
                @NonNull PackageStats stats,
                @NonNull String packageName,
                @NonNull UserHandle userHandle,
                boolean canCallerAccessAllStats) {
            Objects.requireNonNull(stats);
            Objects.requireNonNull(packageName);
            Objects.requireNonNull(userHandle);

            try {
                if (mServiceImplHelper.isUserLocked(userHandle)) {
                    // No need to get stats for locked user.
                    return;
                }
                AppSearchUserInstance instance =
                        mAppSearchUserInstanceManager.getUserInstanceOrNull(userHandle);
                if (instance == null || Flags.enableStorageInfoCache()) {
                    Context userContext =
                            mAppSearchEnvironment.createContextAsUser(mContext, userHandle);
                    UserStorageInfo userStorageInfo =
                            mAppSearchUserInstanceManager
                                .getOrCreateUserStorageInfoInstance(
                                    userContext, userHandle);

                    refreshCachedStorageInfoIfNecessary(instance, userStorageInfo);
                    stats.dataSize += userStorageInfo.getSizeBytesForPackage(packageName);
                } else {
                    stats.dataSize +=
                            instance.getAppSearchImpl()
                                .getStorageInfoForPackages(
                                    new ArraySet<>(Collections.singleton(packageName)))
                                .getSizeBytes();
                }
            } catch (AppSearchException | InterruptedException | RuntimeException e) {
                Log.e(
                        TAG,
                        "Unable to augment storage stats for "
                                + userHandle
                                + " packageName "
                                + packageName,
                        e);
                ExceptionUtil.handleException(e);
            }
        }

        @Override
        public void augmentStatsForUid(
                @NonNull PackageStats stats, int uid, boolean canCallerAccessAllStats) {
            Objects.requireNonNull(stats);

            UserHandle userHandle = UserHandle.getUserHandleForUid(uid);
            try {
                if (mServiceImplHelper.isUserLocked(userHandle)) {
                    // No need to get stats for locked user.
                    return;
                }
                String[] packagesForUid = mPackageManager.getPackagesForUid(uid);
                if (packagesForUid == null) {
                    return;
                }
                AppSearchUserInstance instance =
                        mAppSearchUserInstanceManager.getUserInstanceOrNull(userHandle);
                if (instance == null || Flags.enableStorageInfoCache()) {
                    Context userContext =
                            mAppSearchEnvironment.createContextAsUser(mContext, userHandle);
                    UserStorageInfo userStorageInfo =
                            mAppSearchUserInstanceManager
                                .getOrCreateUserStorageInfoInstance(
                                    userContext, userHandle);

                    refreshCachedStorageInfoIfNecessary(instance, userStorageInfo);
                    for (int i = 0; i < packagesForUid.length; i++) {
                        stats.dataSize += userStorageInfo.getSizeBytesForPackage(
                            packagesForUid[i]);
                    }
                } else {
                    Set<String> packageNames = new ArraySet<>(packagesForUid);
                    stats.dataSize +=
                            instance.getAppSearchImpl()
                                .getStorageInfoForPackages(packageNames)
                                .getSizeBytes();
                }
            } catch (AppSearchException | InterruptedException | RuntimeException e) {
                Log.e(TAG, "Unable to augment storage stats for uid " + uid, e);
                ExceptionUtil.handleException(e);
            }
        }

        @Override
        public void augmentStatsForUser(
                @NonNull PackageStats stats, @NonNull UserHandle userHandle) {
            // TODO(b/179160886): this implementation could incur many jni calls and a lot of
            //  in-memory processing from getStorageInfoForPackage. Instead, we can just compute the
            //  size of the icing dir (or use the overall StorageInfo without interpolating it).
            Objects.requireNonNull(stats);
            Objects.requireNonNull(userHandle);

            try {
                if (mServiceImplHelper.isUserLocked(userHandle)) {
                    // No need to get stats for locked user.
                    return;
                }
                AppSearchUserInstance instance =
                        mAppSearchUserInstanceManager.getUserInstanceOrNull(userHandle);
                if (instance == null || Flags.enableStorageInfoCache()) {
                    Context userContext =
                            mAppSearchEnvironment.createContextAsUser(mContext, userHandle);
                    UserStorageInfo userStorageInfo =
                            mAppSearchUserInstanceManager
                                .getOrCreateUserStorageInfoInstance(
                                    userContext, userHandle);

                    refreshCachedStorageInfoIfNecessary(instance, userStorageInfo);
                    stats.dataSize += userStorageInfo.getTotalSizeBytes();
                } else {
                    List<PackageInfo> packagesForUser =
                            mPackageManager.getInstalledPackagesAsUser(
                                    /* flags= */ 0, userHandle.getIdentifier());
                    if (packagesForUser != null) {
                        Set<String> packageNames = new ArraySet<>();
                        for (int i = 0; i < packagesForUser.size(); i++) {
                            String packageName = packagesForUser.get(i).packageName;
                            packageNames.add(packageName);
                        }
                        stats.dataSize +=
                                instance.getAppSearchImpl()
                                        .getStorageInfoForPackages(packageNames)
                                        .getSizeBytes();
                    }
                }
            } catch (AppSearchException | InterruptedException | RuntimeException e) {
                Log.e(TAG, "Unable to augment storage stats for " + userHandle, e);
                ExceptionUtil.handleException(e);
            }
        }

        private void refreshCachedStorageInfoIfNecessary(
                AppSearchUserInstance instance, UserStorageInfo userStorageInfo)
                throws AppSearchException, InterruptedException {
            if (instance == null) {
                return;
            }
            // If the instance is not null, then it's possible that the cache
            // has been invalidated after creation.
            if (!userStorageInfo.isCacheEmpty()) {
                return;
            }
            boolean lockAcquired = false;
            try {
                lockAcquired =
                        mStorageStatsCacheRefreshLock.tryLock(
                                STORAGE_STATS_RETRIEVAL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (lockAcquired) {
                    // If we were blocked while acquiring the lock, then it's likely that the
                    // blocking thread has updated the cache. Therefore, we should check again to
                    // ensure that we really do need to retrieve storage info from AppSearch.
                    if (userStorageInfo.isCacheEmpty()) {
                        StorageInfoProto storageInfo =
                                instance.getAppSearchImpl().getRawStorageInfoProto();
                        userStorageInfo.updateStorageInfoCache(storageInfo);
                    }
                } else {
                    Log.w(TAG, "StorageStatsAugmenter timed out waiting on cache refresh.");
                }
            } finally {
                if (lockAcquired) {
                    mStorageStatsCacheRefreshLock.unlock();
                }
            }
        }
    }

    /**
     * Dispatches change notifications if there are any to dispatch.
     *
     * <p>This method is async; notifications are dispatched onto their own registered executors.
     *
     * <p>IMPORTANT: You must always call this within the background task that contains the
     * operation that mutated the index. If you called it outside of that task, it could start
     * before the task completes, causing notifications to be missed.
     */
    @WorkerThread
    private void dispatchChangeNotifications(@NonNull AppSearchUserInstance instance) {
        instance.getAppSearchImpl().dispatchAndClearChangeNotifications();
    }

    @WorkerThread
    private void checkForOptimize(
            @NonNull UserHandle targetUser,
            @NonNull AppSearchUserInstance instance,
            int mutateBatchSize) {
        if (mServiceImplHelper.isUserLocked(targetUser)) {
            // We shouldn't schedule any task to locked user.
            return;
        }
        mExecutorManager.executeLambdaForUserNoCallbackAsync(
                targetUser,
                () -> {
                    long totalLatencyStartMillis = SystemClock.elapsedRealtime();
                    OptimizeStats.Builder builder = new OptimizeStats.Builder();
                    try {
                        instance.getAppSearchImpl().checkForOptimize(mutateBatchSize, builder);
                    } catch (Exception e) {
                        Log.w(TAG, "Error occurred when check for optimize", e);
                    } finally {
                        OptimizeStats oStats = builder.setTotalLatencyMillis(
                                (int) (SystemClock.elapsedRealtime() - totalLatencyStartMillis))
                                .build();
                        if (oStats.getOriginalDocumentCount() > 0) {
                            if (oStats.getDeletedDocumentCount()
                                    + oStats.getExpiredDocumentCount() > 0) {
                                dropStorageInfoCacheForUser(targetUser);
                            }
                            instance.getLogger().logStats(oStats);
                        }
                    }
                });
    }

    @WorkerThread
    private void checkForOptimize(
            @NonNull UserHandle targetUser,
            @NonNull AppSearchUserInstance instance) {
        if (mServiceImplHelper.isUserLocked(targetUser)) {
            // We shouldn't schedule any task to locked user.
            return;
        }
        mExecutorManager.executeLambdaForUserNoCallbackAsync(
                targetUser,
                () -> {
                    long totalLatencyStartMillis = SystemClock.elapsedRealtime();
                    OptimizeStats.Builder builder = new OptimizeStats.Builder();
                    try {
                        instance.getAppSearchImpl().checkForOptimize(builder);
                    } catch (Exception e) {
                        Log.w(TAG, "Error occurred when check for optimize", e);
                    } finally {
                        OptimizeStats oStats = builder.setTotalLatencyMillis(
                                (int) (SystemClock.elapsedRealtime() - totalLatencyStartMillis))
                                .build();
                        if (oStats.getOriginalDocumentCount() > 0) {
                            // see if optimize has been run by checking originalDocumentCount
                            if (oStats.getDeletedDocumentCount()
                                    + oStats.getExpiredDocumentCount() > 0) {
                                dropStorageInfoCacheForUser(targetUser);
                            }
                            instance.getLogger().logStats(oStats);
                        }
                    }
                });
    }

    @WorkerThread
    private void schedulePersistToDisk(
            @NonNull UserHandle targetUser,
            @NonNull PersistType.Code persistType,
            long delayMs) {
        if (mServiceImplHelper.isUserLocked(targetUser)) {
            // We shouldn't schedule any task to locked user.
            return;
        }
        synchronized (mPerUserPersistToDiskFutureLocked) {
            ScheduledFuture<?> persistToDiskFuture =
                    mPerUserPersistToDiskFutureLocked.get(targetUser);
            if (persistToDiskFuture == null || persistToDiskFuture.isDone()) {
                persistToDiskFuture = mExecutorManager.scheduleLambdaForUserNoCallbackAsync(
                        targetUser, () -> {
                            if (mServiceImplHelper.isUserLocked(targetUser)) {
                                // Skip the persistToDisk call if the user is locked.
                                return;
                            }
                            try {
                                AppSearchUserInstance instance =
                                        mAppSearchUserInstanceManager.getUserInstance(targetUser);
                                instance.getAppSearchImpl().persistToDisk(persistType);
                            } catch (Exception e) {
                                Log.w(TAG, "Unable to persist the data to disk", e);
                            }
                        }, delayMs, TimeUnit.MILLISECONDS);
                mPerUserPersistToDiskFutureLocked.put(targetUser, persistToDiskFuture);
            }
        }
    }

    /**
     * An API call is considered global if the calling package and target package names do not
     * match.
     * <p>
     * Enterprise session calls do not necessarily have access to same-package data; therefore, even
     * if the calling and target packages are the same, enterprise session calls must always be
     * global to go through the proper visibility checks. (Enterprise session calls are also always
     * considered global for CallStats logging.)
     */
    private boolean isGlobalCall(@NonNull String callingPackageName,
            @NonNull String targetPackageName, boolean isForEnterprise) {
        return !callingPackageName.equals(targetPackageName) || isForEnterprise;
    }

    /**
     * Logs rate-limited or denied calls to CallStats.
     */
    private void logRateLimitedOrCallDeniedCallStats(@NonNull String callingPackageName,
            @Nullable String callingDatabaseName, @CallStats.CallType int apiType,
            @NonNull UserHandle targetUser, long binderCallStartTimeMillis,
            long totalLatencyStartTimeMillis, int numOperations,
            @AppSearchResult.ResultCode int statusCode) {
        Objects.requireNonNull(callingPackageName);
        Objects.requireNonNull(targetUser);
        int estimatedBinderLatencyMillis =
                2 * (int) (totalLatencyStartTimeMillis - binderCallStartTimeMillis);
        int totalLatencyMillis =
                (int) (SystemClock.elapsedRealtime() - totalLatencyStartTimeMillis);
        try {
            mAppSearchUserInstanceManager.getUserInstance(targetUser).getLogger().logStats(
                    new CallStats.Builder()
                            .setPackageName(callingPackageName)
                            .setDatabase(callingDatabaseName)
                            .setStatusCode(statusCode)
                            .setTotalLatencyMillis(totalLatencyMillis)
                            .setCallType(apiType)
                            .setEstimatedBinderLatencyMillis(estimatedBinderLatencyMillis)
                            .setNumOperationsFailed(numOperations)
                            .build());
        } catch (ExecutionException | InterruptedException | CancellationException e) {
            Log.e(TAG, "Failed to get logger for user: " + targetUser
                    + " due to " + e.getMessage());
        }
    }

    private void checkUnsupportedEmbeddingUse(@NonNull List<AppSearchSchema> schemas) {
        // Embedding support currently only allowed on Baklava+. This is because embedding
        // properties are a rollback compatibility issue. Therefore, we cannot allow it to be used
        // on devices that could be rolled back to a pre-Embedding binary until we have landed
        // rollback compatibility work.
        if (isAtLeastBaklava()) {
            return;
        }
        for (int i = 0; i < schemas.size(); ++i) {
            AppSearchSchema schema = schemas.get(i);
            List<AppSearchSchema.PropertyConfig> properties = schema.getProperties();
            for (int j = 0; j < properties.size(); ++j) {
                AppSearchSchema.PropertyConfig property = properties.get(j);
                if (property instanceof AppSearchSchema.EmbeddingPropertyConfig) {
                    throw new UnsupportedOperationException(
                            "SCHEMA_EMBEDDING_PROPERTY_CONFIG is not available on this AppSearch "
                                    + "implementation.");
                }
            }
        }
    }

    private void checkUnsupportedEmbeddingUse(@NonNull SearchSpec spec) {
        // Embedding support currently only allowed on Baklava+. This is because embedding
        // properties are a rollback compatibility issue. Therefore, we cannot allow it to be used
        // on devices that could be rolled back to a pre-Embedding binary until we have landed
        // rollback compatibility work.
        if (isAtLeastBaklava()) {
            return;
        }
        if (!spec.getEmbeddingParameters().isEmpty()) {
            throw new UnsupportedOperationException(
                    "SCHEMA_EMBEDDING_PROPERTY_CONFIG is not available on this AppSearch "
                            + "implementation.");
        }
        if (spec.getJoinSpec() != null) {
            checkUnsupportedEmbeddingUse(spec.getJoinSpec().getNestedSearchSpec());
        }
    }

    private static boolean isAtLeastBaklava() {
        return Build.VERSION.SDK_INT >= 36
                || (Build.VERSION.SDK_INT == 35 && Build.VERSION.CODENAME.equals("Baklava"));
    }

    /**
     * Checks if an API call for a given calling package and calling database should be denied
     * according to the denylist. If the call is denied, also logs the denial through CallStats.
     *
     * @return true if the given api call should be denied for the given calling package and calling
     * database; otherwise false
     */
    private boolean checkCallDenied(@NonNull String callingPackageName,
            @Nullable String callingDatabaseName, @CallStats.CallType int apiType,
            @NonNull UserHandle targetUser, long binderCallStartTimeMillis,
            long totalLatencyStartTimeMillis, int numOperations) {
        Denylist denylist = mAppSearchConfig.getCachedDenylist();
        boolean denied = callingDatabaseName == null ? denylist.checkDeniedPackage(
                callingPackageName, apiType) : denylist.checkDeniedPackageDatabase(
                callingPackageName, callingDatabaseName, apiType);
        if (denied) {
            logRateLimitedOrCallDeniedCallStats(callingPackageName, callingDatabaseName, apiType,
                    targetUser, binderCallStartTimeMillis, totalLatencyStartTimeMillis,
                    numOperations, RESULT_DENIED);
        }
        return denied;
    }

    /**
     * Checks if an API call for a given calling package and calling database should be denied
     * according to the denylist. If the call is denied, also logs the denial through CallStats and
     * invokes the given {@link IAppSearchResultCallback} with a failed result.
     *
     * @return true if the given api call should be denied for the given calling package and calling
     * database; otherwise false
     */
    private boolean checkCallDenied(@NonNull String callingPackageName,
            @Nullable String callingDatabaseName, @CallStats.CallType int apiType,
            @NonNull IAppSearchResultCallback callback, @NonNull UserHandle targetUser,
            long binderCallStartTimeMillis, long totalLatencyStartTimeMillis, int numOperations) {
        if (checkCallDenied(callingPackageName, callingDatabaseName, apiType, targetUser,
                binderCallStartTimeMillis, totalLatencyStartTimeMillis, numOperations)) {
            invokeCallbackOnResult(callback, AppSearchResultParcel.fromFailedResult(
                    AppSearchResult.newFailedResult(RESULT_DENIED, null)));
            return true;
        }
        return false;
    }

    /**
     * Checks if an API call for a given calling package and calling database should be denied
     * according to the denylist. If the call is denied, also logs the denial through CallStats and
     * invokes the given {@link IAppSearchBatchResultCallback} with a failed result.
     *
     * @return true if the given api call should be denied for the given calling package and calling
     * database; otherwise false
     */
    private boolean checkCallDenied(@NonNull String callingPackageName,
            @Nullable String callingDatabaseName, @CallStats.CallType int apiType,
            @NonNull IAppSearchBatchResultCallback callback, @NonNull UserHandle targetUser,
            long binderCallStartTimeMillis, long totalLatencyStartTimeMillis, int numOperations) {
        if (checkCallDenied(callingPackageName, callingDatabaseName, apiType, targetUser,
                binderCallStartTimeMillis, totalLatencyStartTimeMillis, numOperations)) {
            invokeCallbackOnError(callback, AppSearchResult.newFailedResult(RESULT_DENIED, null));
            return true;
        }
        return false;
    }

    /**
     * Drops the cached storage information for a specific user.
     * This method retrieves the {@link UserStorageInfo} instance associated with the specified user
     * and clears the cached storage data to ensure that subsequent calls reflect any changes or
     * updates in storage usage.
     */
    private void dropStorageInfoCacheForUser(@NonNull UserHandle targetUser) {
        if (!Flags.enableStorageInfoCache()) {
            return;
        }

        Context targetContext =
                mAppSearchEnvironment.createContextAsUser(mContext, targetUser);
        UserStorageInfo userStorageInfo =
                mAppSearchUserInstanceManager
                    .getOrUserStorageInfoInstanceOrNull(targetContext, targetUser);
        if (userStorageInfo != null) {
            userStorageInfo.dropStorageInfoCache();
        }
    }
}
