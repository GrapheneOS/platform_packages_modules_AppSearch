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

package com.android.server.appsearch;

import static com.android.server.appsearch.external.localstorage.util.PrefixUtil.getPackageName;

import android.annotation.NonNull;
import android.app.appsearch.checker.initialization.qual.UnderInitialization;
import android.app.appsearch.checker.initialization.qual.UnknownInitialization;
import android.app.appsearch.checker.nullness.qual.RequiresNonNull;
import android.app.appsearch.exceptions.AppSearchException;
import android.app.appsearch.util.ExceptionUtil;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.appsearch.external.localstorage.AppSearchImpl;

import com.google.android.icing.proto.DocumentStorageInfoProto;
import com.google.android.icing.proto.NamespaceStorageInfoProto;
import com.google.android.icing.proto.StorageInfoProto;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Saves the storage info read from file for a user. */
public final class UserStorageInfo {
    public static final String STORAGE_INFO_FILE = "appsearch_storage";
    private static final String TAG = "AppSearchUserStorage";
    private final ReadWriteLock mReadWriteLock = new ReentrantReadWriteLock();

    @GuardedBy("mReadWriteLock")
    private final File mStorageInfoFile;

    // Saves storage usage byte size for each package under the user, keyed by package name.
    @GuardedBy("mReadWriteLock")
    private Map<String, Long> mPackageStorageSizeMap;
    // Saves storage usage byte size for all packages under the user.
    @GuardedBy("mReadWriteLock")
    private long mTotalStorageSizeBytes;

    public UserStorageInfo(@NonNull File fileParentPath) {
        Objects.requireNonNull(fileParentPath);
        mStorageInfoFile = new File(fileParentPath, STORAGE_INFO_FILE);
        readStorageInfoFromFile();
    }

    /**
     * Updates storage info file with the latest storage info queried through {@link AppSearchImpl}.
     */
    public void updateStorageInfoFile(@NonNull AppSearchImpl appSearchImpl) {
        Objects.requireNonNull(appSearchImpl);
        mReadWriteLock.writeLock().lock();
        try {
            StorageInfoProto storageInfo = appSearchImpl.getRawStorageInfoProto();
            updateStorageInfoCacheLocked(storageInfo);
            updateFileLocked(storageInfo);
        } catch (AppSearchException e) {
            Log.w(TAG, "Failed to get native storage info", e);
            ExceptionUtil.handleException(e);
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
    }

    /**
     * Updates storage info file with the latest storage info.
     */
    public void updateStorageInfoCache(@NonNull StorageInfoProto storageInfo) {
        Objects.requireNonNull(storageInfo);
        mReadWriteLock.writeLock().lock();
        try {
            updateStorageInfoCacheLocked(storageInfo);
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
    }

    @GuardedBy("mReadWriteLock")
    private void updateStorageInfoCacheLocked(@NonNull StorageInfoProto storageInfo) {
        mTotalStorageSizeBytes = storageInfo.getTotalStorageSize();
        mPackageStorageSizeMap = calculatePackageStorageInfoMap(storageInfo);
    }

    /**
     * Clears the in-memory storage info cache.
     * Resets the total storage size and clears the package storage size map.
     */
    public void dropStorageInfoCache() {
        mReadWriteLock.writeLock().lock();
        try {
            dropStorageInfoCacheLocked();
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
    }

    @GuardedBy("mReadWriteLock")
    private void dropStorageInfoCacheLocked() {
        mTotalStorageSizeBytes = 0;
        mPackageStorageSizeMap = Collections.emptyMap();
    }

    /**
     * Checks if the storage info cache is empty.
     *
     * @return {@code true} if the cache is empty, otherwise {@code false}.
     */
    public boolean isCacheEmpty() {
        mReadWriteLock.readLock().lock();
        try {
            return mTotalStorageSizeBytes == 0
                    && mPackageStorageSizeMap.isEmpty();
        } finally {
            mReadWriteLock.readLock().unlock();
        }
    }

    /**
     * Gets storage usage byte size for a package with a given package name.
     *
     * <p>Please note the storage info cached in file may be stale.
     */
    public long getSizeBytesForPackage(@NonNull String packageName) {
        Objects.requireNonNull(packageName);
        mReadWriteLock.readLock().lock();
        try {
            return mPackageStorageSizeMap.getOrDefault(packageName, 0L);
        } finally {
            mReadWriteLock.readLock().unlock();
        }
    }

    /**
     * Gets total storage usage byte size for all packages under the user.
     *
     * <p>Please note the storage info cached in file may be stale.
     */
    public long getTotalSizeBytes() {
        mReadWriteLock.readLock().lock();
        try {
            return mTotalStorageSizeBytes;
        } finally {
            mReadWriteLock.readLock().unlock();
        }
    }

    @RequiresNonNull("mStorageInfoFile")
    @VisibleForTesting
    void readStorageInfoFromFile(@UnderInitialization UserStorageInfo this) {
        if (!mStorageInfoFile.exists()) {
            dropStorageInfoCache();
            return;
        }

        mReadWriteLock.writeLock().lock();
        try (InputStream in = new FileInputStream(mStorageInfoFile)) {
            StorageInfoProto storageInfo = StorageInfoProto.parseFrom(in);
            updateStorageInfoCacheLocked(storageInfo);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Failed to read storage info from file", e);
            dropStorageInfoCacheLocked();
            ExceptionUtil.handleException(e);
        } finally {
            mReadWriteLock.writeLock().unlock();
        }
    }

    private void updateFileLocked(@NonNull StorageInfoProto storageInfo) {
        try (FileOutputStream out = new FileOutputStream(mStorageInfoFile)) {
            storageInfo.writeTo(out);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Failed to dump storage info into file", e);
            ExceptionUtil.handleException(e);
        }
    }

    /** Calculates storage usage byte size for packages from a {@link StorageInfoProto}. */
    // TODO(b/198553756): Storage cache effort has created two copies of the storage
    // calculation/interpolation logic.
    @NonNull
    @VisibleForTesting
    Map<String, Long> calculatePackageStorageInfoMap(
            @UnknownInitialization UserStorageInfo this, @NonNull StorageInfoProto storageInfo) {
        Map<String, Long> packageStorageInfoMap = new ArrayMap<>();
        if (storageInfo.hasDocumentStorageInfo()) {
            DocumentStorageInfoProto documentStorageInfo = storageInfo.getDocumentStorageInfo();
            List<NamespaceStorageInfoProto> namespaceStorageInfoList =
                    documentStorageInfo.getNamespaceStorageInfoList();

            Map<String, Integer> packageDocumentCountMap = new ArrayMap<>();
            long totalDocuments = 0;
            for (int i = 0; i < namespaceStorageInfoList.size(); i++) {
                NamespaceStorageInfoProto namespaceStorageInfo = namespaceStorageInfoList.get(i);
                String packageName = getPackageName(namespaceStorageInfo.getNamespace());
                int namespaceDocuments =
                        namespaceStorageInfo.getNumAliveDocuments()
                                + namespaceStorageInfo.getNumExpiredDocuments();
                totalDocuments += namespaceDocuments;
                packageDocumentCountMap.put(
                        packageName,
                        packageDocumentCountMap.getOrDefault(packageName, 0) + namespaceDocuments);
            }

            long totalStorageSize = storageInfo.getTotalStorageSize();
            for (Map.Entry<String, Integer> entry : packageDocumentCountMap.entrySet()) {
                // Since we don't have the exact size of all the documents, we do an estimation.
                // Note that while the total storage takes into account schema, index, etc. in
                // addition to documents, we'll only calculate the percentage based on number of
                // documents under packages.
                packageStorageInfoMap.put(
                        entry.getKey(),
                        (long) (entry.getValue() * 1.0 / totalDocuments * totalStorageSize));
            }
        }
        return Collections.unmodifiableMap(packageStorageInfoMap);
    }
}
