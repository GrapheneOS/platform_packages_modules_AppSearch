/*
 * Copyright 2025 The Android Open Source Project
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
package com.android.server.appsearch.isolated_storage_service;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.PersistableBundle;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Arrays;

/**
 * Holds stats for data migration.
 *
 * @hide
 */
public class DataMigrationStats {
    private static final String DATA_MIGRATION_RUN_COUNTER = "data_migration_run_counter";

    private static final String DATA_MIGRATION_STATUS = "data_migration_status";
    private static final String DATA_MIGRATION_VM_INIT_STATUS = "data_migration_vm_init_status";
    private static final String DATA_MIGRATION_RESET_STATUS = "data_migration_reset_status";
    private static final String DATA_MIGRATION_SET_SCHEMA_STATUS =
            "data_migration_set_schema_status";
    private static final String DATA_MIGRATION_FLUSH_STATUS = "data_migration_flush_status";
    private static final String DATA_MIGRATION_QUERY_STATUS = "data_migration_query_status";

    private static final String DATA_MIGRATION_PUT_STATUS = "data_migration_put_status";

    private static final String DATA_MIGRATION_NUMBER_OF_DOCS_SUCCEEDED =
            "data_migration_number_of_docs_succeeded";
    private static final String DATA_MIGRATION_NUMBER_OF_DOCS_FAILED =
            "data_migration_number_of_docs_failed";

    @VisibleForTesting
    static final int VALUE_NOT_SET = -1;

    private PersistableBundle mBundle = new PersistableBundle();

    @Override
    public String toString() {
        // TODO(b/430610163) Switch to StringBuilder.
        String str = "Number of Data Migration runs: " + getDataMigrationRunCounter() + ".\n"
                + "Data Migration status: " + getDataMigrationStatus() + ".\n"
                + "VM initialization Status: " + getVMInitStatus() + ".\n"
                + "Reset Status: " + getResetStatus() + ".\n"
                + "SetSchema status: " + getSetSchemaStatus() + ".\n"
                + "Flush status: " + getFlushStatus() + ".\n"
                + "Query status: " + getQueryStatus() + ".\n"
                + "Put status: " + Arrays.toString(getPutStatus()) + ".\n"
                + "# of docs succeeded: " + getNumberOfDocsSucceeded() + ".\n"
                + "# of docs failed: " + getNumberOfDocsFailed() + ".\n";

        return str;
    }

    /** Gets the number of times data migration has been run.
     *
     * <p>Normally it should be either 0(data migration not needed) or 1(success). If it is more
     * than 1, it means previous migration has failed.
     */
    public int getDataMigrationRunCounter() {
        return mBundle.getInt(DATA_MIGRATION_RUN_COUNTER, VALUE_NOT_SET);
    }

    /** Gets the overall data migration status. */
    public int getDataMigrationStatus() {
        return mBundle.getInt(DATA_MIGRATION_STATUS, VALUE_NOT_SET);
    }

    /** Gets the VM initialization status. */
    public int getVMInitStatus() {
        return mBundle.getInt(DATA_MIGRATION_VM_INIT_STATUS, VALUE_NOT_SET);
    }

    /**
     * Gets the reset status.
     *
     * <p>Reset is not always called so this could be {@code STATUS_NOT_SET}.
     */
    public int getResetStatus() {
        return mBundle.getInt(DATA_MIGRATION_RESET_STATUS, VALUE_NOT_SET);
    }

    /** Gets setSchema status. */
    public int getSetSchemaStatus() {
        return mBundle.getInt(DATA_MIGRATION_SET_SCHEMA_STATUS, VALUE_NOT_SET);
    }

    /** Gets Flush(PersistToDisk) status. */
    public int getFlushStatus() {
        return mBundle.getInt(DATA_MIGRATION_FLUSH_STATUS, VALUE_NOT_SET);
    }

    /** Gets Query status. */
    public int getQueryStatus() {
        return mBundle.getInt(DATA_MIGRATION_QUERY_STATUS, VALUE_NOT_SET);
    }

    /**
     * Gets Put status.
     *
     * <p>As we are doing multiple puts, we could have multiple status code.
     *
     * <p>Right now, we only save unique ones.
     */
    @Nullable
    public int[] getPutStatus() {
        return mBundle.getIntArray(DATA_MIGRATION_PUT_STATUS);
    }

    /** Gets the number of docs succeeded to be put. */
    public long getNumberOfDocsSucceeded() {
        return mBundle.getLong(DATA_MIGRATION_NUMBER_OF_DOCS_SUCCEEDED, 0);
    }

    /** Gets the number of docs failed to be put. */
    public long getNumberOfDocsFailed() {
        return mBundle.getLong(DATA_MIGRATION_NUMBER_OF_DOCS_FAILED, 0);
    }

    /** Gets the internal bundle. */
    @NonNull
    public PersistableBundle getBundle() {
        return mBundle;
    }

    /** Sets the internal bundle. */
    // This is only used for better print in dumpsys. As we read the bundle from disk and use this
    // class to print.
    public void setBundle(@NonNull PersistableBundle bundle) {
        mBundle = bundle;
    }

    /** Sets the number of times data migration has been run. */
    public void setDataMigrationRunCounter(int counter) {
        mBundle.putInt(DATA_MIGRATION_RUN_COUNTER, counter);
    }

    /** Sets the overall data migration status. */
    public void setDataMigrationStatus(int statusCode) {
        mBundle.putInt(DATA_MIGRATION_STATUS, statusCode);
    }

    /** Sets the VM initialization status. */
    public void setVMInitStatus(int statusCode) {
        mBundle.putInt(DATA_MIGRATION_VM_INIT_STATUS, statusCode);
    }

    /** Sets the reset status. */
    public void setResetStatus(int statusCode) {
        mBundle.putInt(DATA_MIGRATION_RESET_STATUS, statusCode);
    }

    /** Sets the setSchema status. */
    public void setSetSchemaStatus(int statusCode) {
        mBundle.putInt(DATA_MIGRATION_SET_SCHEMA_STATUS, statusCode);
    }

    /** Sets the Flush(PersistToDisk) status. */
    public void setFlushStatus(int statusCode) {
        mBundle.putInt(DATA_MIGRATION_FLUSH_STATUS, statusCode);
    }

    /** Sets the Query status. */
    public void setQueryStatus(int statusCode) {
        mBundle.putInt(DATA_MIGRATION_QUERY_STATUS, statusCode);
    }

    /** Sets Put(s) status. */
    public void setPutStatus(int[] statusCode) {
        mBundle.putIntArray(DATA_MIGRATION_PUT_STATUS, statusCode);
    }

    /** Sets the number of docs succeeded to be put. */
    public void setNumberOfDocsSucceeded(long numOfDocSucceeded) {
        mBundle.putLong(DATA_MIGRATION_NUMBER_OF_DOCS_SUCCEEDED, numOfDocSucceeded);
    }

    /** Sets the number of docs failed to be put. */
    public void setNumberOfDocsFailed(long numOfDocFailed) {
        mBundle.putLong(DATA_MIGRATION_NUMBER_OF_DOCS_FAILED, numOfDocFailed);
    }
}
