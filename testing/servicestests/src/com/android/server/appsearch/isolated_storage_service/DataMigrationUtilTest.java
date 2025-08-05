/*
 * Copyright (C) 2025 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import android.app.appsearch.AppSearchEnvironmentFactory;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.FrameworkAppSearchEnvironment;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.InternalSetSchemaResponse;
import android.app.appsearch.exceptions.AppSearchException;
import android.app.appsearch.testutil.AppSearchEmail;
import android.app.appsearch.testutil.AppSearchTestUtils;
import android.content.Context;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.util.ArrayMap;

import androidx.test.core.app.ApplicationProvider;

import com.android.server.appsearch.external.localstorage.AppSearchConfig;
import com.android.server.appsearch.external.localstorage.AppSearchConfigImpl;
import com.android.server.appsearch.external.localstorage.AppSearchImpl;
import com.android.server.appsearch.external.localstorage.LocalStorageIcingOptionsConfig;
import com.android.server.appsearch.external.localstorage.OptimizeStrategy;
import com.android.server.appsearch.external.localstorage.UnlimitedLimitConfig;
import com.android.server.appsearch.external.localstorage.util.PrefixUtil;
import com.android.server.appsearch.icing.proto.IcingSearchEngineOptions;
import com.android.server.appsearch.icing.proto.InitializeResultProto;
import com.android.server.appsearch.icing.proto.PersistType;
import com.android.server.appsearch.icing.proto.ResultSpecProto;
import com.android.server.appsearch.icing.proto.SchemaProto;
import com.android.server.appsearch.icing.proto.SchemaTypeConfigProto;
import com.android.server.appsearch.icing.proto.ScoringSpecProto;
import com.android.server.appsearch.icing.proto.SearchResultProto;
import com.android.server.appsearch.icing.proto.SearchSpecProto;
import com.android.server.appsearch.icing.proto.StatusProto;
import com.android.server.appsearch.icing.proto.TermMatchType;
import com.android.server.appsearch.indexer.IndexerSettings;

import com.google.android.icing.IcingSearchEngine;
import com.google.android.icing.IcingSearchEngineInterface;
import com.google.common.collect.ImmutableList;

import org.jspecify.annotations.NonNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@SuppressWarnings("GuardedBy")
public class DataMigrationUtilTest {
    /**
     * Always trigger optimize in this class. OptimizeStrategy will be tested in its own test class.
     */
    private static final OptimizeStrategy ALWAYS_OPTIMIZE = optimizeInfo -> true;

    @Rule public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    @Rule public final RuleChain mRuleChain = AppSearchTestUtils.createCommonTestRules();

    private Context mContext;

    private UserHandle mUserHandle;

    // Objects for source AppSearchImpl
    private File mAppSearchDir;
    private AppSearchImpl mAppSearchImpl;

    // Objects for dest IcingSearchEngineInterface
    private File mVmIcingSearchEngineDir;
    private IcingSearchEngineInterface mVmIcingSearchEngine;

    private AppSearchConfig mUnlimitedConfig =
            new AppSearchConfigImpl(
                    new UnlimitedLimitConfig(), new LocalStorageIcingOptionsConfig());
    private AppSearchConfig mAppSearchConfigWithDatabaseSchemaDisabled =
            new AppSearchConfigImpl(
                        new UnlimitedLimitConfig(), new LocalStorageIcingOptionsConfig()) {
        @Override
        public @NonNull IcingSearchEngineOptions toIcingSearchEngineOptions(
                @NonNull String baseDir, boolean isVMEnabled) {
            IcingSearchEngineOptions.Builder builder =
                    IcingSearchEngineOptions.newBuilder(
                            super.toIcingSearchEngineOptions(baseDir, isVMEnabled));
            // Turn off schema database.
            builder.setEnableSchemaDatabase(false);
            return builder.build();
        }
    };

    private static void populateEmailsInAppSearchImpl(@NonNull AppSearchImpl appSearchImpl,
            @NonNull String packageName, @NonNull String databaseName,
            @NonNull String startId, int docCount) throws Exception {
        // Insert schema
        List<AppSearchSchema> schema = ImmutableList.of(AppSearchEmail.SCHEMA);
        InternalSetSchemaResponse internalSetSchemaResponse =
                appSearchImpl.setSchema(
                        packageName,
                        databaseName,
                        schema,
                        /* visibilityConfigs= */ Collections.emptyList(),
                        /* forceOverride= */ false,
                        /* version= */ 0,
                        /* setSchemaStatsBuilder= */ null);
        assertThat(internalSetSchemaResponse.isSuccess()).isTrue();
        // Insert 10 documents
        List<GenericDocument> docs = new ArrayList<>();
        for (int i = 0; i < docCount; i++) {
            AppSearchEmail email =
                    new AppSearchEmail.Builder("namespace", startId + i)
                            .setFrom("from@example.com")
                            .setTo("to1@example.com", "to2@example.com")
                            .setSubject("testPut example")
                            .setBody("This is the body of the testPut email")
                            .build();
            docs.add(email);
        }
        appSearchImpl.batchPutDocuments(
                packageName,
                databaseName,
                docs,
                /*batchResultBuilder=*/ null,
                /* sendChangeNotifications= */ false,
                /* logger= */ null,
                PersistType.Code.LITE);
    }


    @Before
    public void setUp() throws Exception {
        mContext = ApplicationProvider.getApplicationContext();
        mUserHandle = mContext.getUser();
        mAppSearchDir = mTemporaryFolder.newFolder("test");
        mAppSearchImpl =
                AppSearchImpl.create(
                        mAppSearchDir,
                        mUnlimitedConfig,
                        /* initStatsBuilder= */ null,
                        /* visibilityChecker= */ null,
                        /* revocableFileDescriptorStore= */ null,
                        /* icingSearchEngine= */ null,
                        ALWAYS_OPTIMIZE);
        mVmIcingSearchEngineDir = mTemporaryFolder.newFolder("vm");
        mVmIcingSearchEngine =
                new IcingSearchEngine(
                        (new LocalStorageIcingOptionsConfig())
                                .toIcingSearchEngineOptions(
                                        mVmIcingSearchEngineDir.getAbsolutePath(),
                                        /* isVmEnabled= */ true));
        InitializeResultProto initializeResultProto = mVmIcingSearchEngine.initialize();
        assertThat(initializeResultProto.getStatus().getCode()).isEqualTo(StatusProto.Code.OK);
    }

    @After
    public void tearDown() {
        mAppSearchImpl.close();
        mVmIcingSearchEngine.close();
    }


    @Test
    public void testNeedDataMigration() throws Exception {
        File appSearchDir = mTemporaryFolder.newFolder("appsearch");
        File icingDir = new File(appSearchDir, "icing");
        AppSearchEnvironmentFactory.setEnvironmentInstanceForTest(
                new FrameworkAppSearchEnvironment() {
                    @Override
                    public File getAppSearchDir(
                            @NonNull Context unused, @NonNull UserHandle userHandle) {
                        return appSearchDir;
                    }
                });
        // This should create appsearchDir/icing/version file
        AppSearchImpl.create(
                icingDir,
                mUnlimitedConfig,
                /* initStatsBuilder= */ null,
                /* visibilityChecker= */ null,
                /* revocableFileDescriptorStore= */ null,
                /* icingSearchEngine= */ null,
                ALWAYS_OPTIMIZE);
        File icingVersion = new File(appSearchDir, "icing/version");

        assertThat(icingVersion.exists()).isTrue();
        assertThat(DataMigrationUtil.needDataMigration(mContext, mUserHandle)).isTrue();
    }

    @Test
    public void testNeedDataMigrationEvenIfStatusFileExist() throws Exception {
        File appSearchDir = mTemporaryFolder.newFolder("appsearch");
        File icingDir = new File(appSearchDir, "icing");
        AppSearchEnvironmentFactory.setEnvironmentInstanceForTest(
                new FrameworkAppSearchEnvironment() {
                    @Override
                    public File getAppSearchDir(
                            @NonNull Context unused, @NonNull UserHandle userHandle) {
                        return appSearchDir;
                    }
                });
        // This should create appsearchDir/icing/version file
        AppSearchImpl.create(
                icingDir,
                mUnlimitedConfig,
                /* initStatsBuilder= */ null,
                /* visibilityChecker= */ null,
                /* revocableFileDescriptorStore= */ null,
                /* icingSearchEngine= */ null,
                ALWAYS_OPTIMIZE);
        File icingVersion = new File(appSearchDir, "icing/version");
        File dataMigrationStatusFile = new File(appSearchDir,
                DataMigrationUtil.DATA_MIGRATION_STATUS_FILE);
        dataMigrationStatusFile.createNewFile();

        assertThat(dataMigrationStatusFile.exists()).isTrue();
        assertThat(icingVersion.exists()).isTrue();
        assertThat(DataMigrationUtil.needDataMigration(mContext, mUserHandle)).isTrue();
    }

    @Test
    public void testNeedDataMigrationNotNeeded() throws Exception {
        File appSearchDir = mTemporaryFolder.newFolder("appsearch");
        File icingDir = mTemporaryFolder.newFolder("appsearch/icing");
        AppSearchEnvironmentFactory.setEnvironmentInstanceForTest(
                new FrameworkAppSearchEnvironment() {
                    @Override
                    public File getAppSearchDir(
                            @NonNull Context unused, @NonNull UserHandle userHandle) {
                        return appSearchDir;
                    }
                });


        File icingVersion = new File(appSearchDir, "icing/version");

        assertThat(icingDir.exists()).isTrue();
        assertThat(icingVersion.exists()).isFalse();
        assertThat(DataMigrationUtil.needDataMigration(mContext, mUserHandle)).isFalse();
    }

    @Test
    public void testDataMigrationSucceed() throws Exception {
        File appSearchDir = mTemporaryFolder.newFolder("appsearch");
        File icingDir = new File(appSearchDir, "icing");
        File dataMigrationStatusFile = new File(appSearchDir,
                DataMigrationUtil.DATA_MIGRATION_STATUS_FILE);
        AppSearchEnvironmentFactory.setEnvironmentInstanceForTest(
                new FrameworkAppSearchEnvironment() {
                    @Override
                    public File getAppSearchDir(
                            @NonNull Context unused, @NonNull UserHandle userHandle) {
                        return appSearchDir;
                    }
                });
        // This should create appsearchDir/icing/version file
        AppSearchImpl appSearchImpl = AppSearchImpl.create(
                icingDir,
                mUnlimitedConfig,
                /* initStatsBuilder= */ null,
                /* visibilityChecker= */ null,
                /* revocableFileDescriptorStore= */ null,
                /* icingSearchEngine= */ null,
                ALWAYS_OPTIMIZE);
        int docCount = 100;
        populateEmailsInAppSearchImpl(appSearchImpl, "package1",
                "database1", "id", docCount);

        // migration status file doesn't exist before migration.
        assertThat(dataMigrationStatusFile.exists()).isFalse();
        assertThat(appSearchImpl.isVMEnabled()).isFalse();

        // Do data migration.
        DataMigrationStats stats = DataMigrationUtil.runDataMigrationForUser(
                mContext, mUserHandle, appSearchImpl, mVmIcingSearchEngine, /*logger=*/ null);
        assertThat(appSearchImpl.isVMEnabled()).isTrue();

        // check stats
        int okStatusCode = StatusProto.Code.OK.getNumber();
        assertThat(stats.getDataMigrationStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getVMInitStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getResetStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getSetSchemaStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getFlushStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getQueryStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getPutStatus()).asList().containsExactly(okStatusCode);
        assertThat(stats.getNumberOfDocsSucceeded()).isEqualTo(docCount);
        assertThat(stats.getNumberOfDocsFailed()).isEqualTo(0);
        assertThat(stats.getDataMigrationRunCounter()).isEqualTo(1);

        // check dumpsys file
        assertThat(dataMigrationStatusFile.exists()).isTrue();
        PersistableBundle bundle = IndexerSettings.readBundle(dataMigrationStatusFile);
        stats.setBundle(bundle);
        assertThat(stats.getDataMigrationStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getVMInitStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getResetStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getSetSchemaStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getFlushStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getQueryStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getPutStatus()).asList().containsExactly(okStatusCode);
        assertThat(stats.getNumberOfDocsSucceeded()).isEqualTo(docCount);
        assertThat(stats.getNumberOfDocsFailed()).isEqualTo(0);
        assertThat(stats.getDataMigrationRunCounter()).isEqualTo(1);

        // Check AppSearchImpl after migration
        SearchSpecProto searchSpec = SearchSpecProto.newBuilder()
                        .setQuery("") // an empty query will return all docs.
                        .setTermMatchType(TermMatchType.Code.PREFIX)
                        .build();
        SearchResultProto searchResult =
                appSearchImpl.rawSearch(
                        searchSpec,
                        ScoringSpecProto.newBuilder().build(),
                        ResultSpecProto.newBuilder()
                                .setNumToScore(Integer.MAX_VALUE)
                                .setNumPerPage(Integer.MAX_VALUE)
                                .build());
        assertThat(searchResult.getResultsCount()).isEqualTo(docCount);

        // check the vm instance
        searchResult =
                mVmIcingSearchEngine.search(
                        searchSpec,
                        ScoringSpecProto.newBuilder().build(),
                        ResultSpecProto.newBuilder()
                                .setNumToScore(Integer.MAX_VALUE)
                                .setNumPerPage(Integer.MAX_VALUE)
                                .build());
        assertThat(searchResult.getResultsCount()).isEqualTo(docCount);
    }

    @Test
    public void testDataMigrationOnPrevFailures() throws Exception {
        File appSearchDir = mTemporaryFolder.newFolder("appsearch");
        File icingDir = new File(appSearchDir, "icing");
        File dataMigrationStatusFile = new File(appSearchDir,
                DataMigrationUtil.DATA_MIGRATION_STATUS_FILE);
        AppSearchEnvironmentFactory.setEnvironmentInstanceForTest(
                new FrameworkAppSearchEnvironment() {
                    @Override
                    public File getAppSearchDir(
                            @NonNull Context unused, @NonNull UserHandle userHandle) {
                        return appSearchDir;
                    }
                });
        // This should create appsearchDir/icing/version file
        AppSearchImpl appSearchImpl = AppSearchImpl.create(
                icingDir,
                mUnlimitedConfig,
                /* initStatsBuilder= */ null,
                /* visibilityChecker= */ null,
                /* revocableFileDescriptorStore= */ null,
                /* icingSearchEngine= */ null,
                ALWAYS_OPTIMIZE);

        int docCount = 100;
        populateEmailsInAppSearchImpl(appSearchImpl, "package1",
                "database1", "id", docCount);

        // Create status file to indicate previous failures:
        assertThat(dataMigrationStatusFile.exists()).isFalse();
        int prevRunTimes = 2;
        DataMigrationStats prevStats = new DataMigrationStats();
        prevStats.setDataMigrationRunCounter(prevRunTimes); // failed twice
        prevStats.setDataMigrationStatus(StatusProto.Code.ABORTED.getNumber());
        IndexerSettings.writeBundle(dataMigrationStatusFile, prevStats.getBundle());
        assertThat(dataMigrationStatusFile.exists()).isTrue();

        // Do data migration.
        DataMigrationStats stats = DataMigrationUtil.runDataMigrationForUser(
                mContext, mUserHandle, appSearchImpl, mVmIcingSearchEngine, /*logger=*/ null);
        assertThat(appSearchImpl.isVMEnabled()).isTrue();

        // check stats
        int okStatusCode = StatusProto.Code.OK.getNumber();
        assertThat(stats.getDataMigrationStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getVMInitStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getResetStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getSetSchemaStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getFlushStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getQueryStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getPutStatus()).asList().containsExactly(okStatusCode);
        assertThat(stats.getNumberOfDocsSucceeded()).isEqualTo(docCount);
        assertThat(stats.getNumberOfDocsFailed()).isEqualTo(0);
        assertThat(stats.getDataMigrationRunCounter()).isEqualTo(prevRunTimes + 1);

        // check dumpsys file
        assertThat(dataMigrationStatusFile.exists()).isTrue();
        PersistableBundle bundle = IndexerSettings.readBundle(dataMigrationStatusFile);
        stats.setBundle(bundle);
        assertThat(stats.getDataMigrationStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getVMInitStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getResetStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getSetSchemaStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getFlushStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getQueryStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getPutStatus()).asList().containsExactly(okStatusCode);
        assertThat(stats.getNumberOfDocsSucceeded()).isEqualTo(docCount);
        assertThat(stats.getNumberOfDocsFailed()).isEqualTo(0);
        assertThat(stats.getDataMigrationRunCounter()).isEqualTo(prevRunTimes + 1);

        // Check AppSearchImpl after migration
        SearchSpecProto searchSpec = SearchSpecProto.newBuilder()
                .setQuery("") // an empty query will return all docs.
                .setTermMatchType(TermMatchType.Code.PREFIX)
                .build();
        SearchResultProto searchResult =
                appSearchImpl.rawSearch(
                        searchSpec,
                        ScoringSpecProto.newBuilder().build(),
                        ResultSpecProto.newBuilder()
                                .setNumToScore(Integer.MAX_VALUE)
                                .setNumPerPage(Integer.MAX_VALUE)
                                .build());
        assertThat(searchResult.getResultsCount()).isEqualTo(docCount);

        // check the vm instance
        searchResult =
                mVmIcingSearchEngine.search(
                        searchSpec,
                        ScoringSpecProto.newBuilder().build(),
                        ResultSpecProto.newBuilder()
                                .setNumToScore(Integer.MAX_VALUE)
                                .setNumPerPage(Integer.MAX_VALUE)
                                .build());
        assertThat(searchResult.getResultsCount()).isEqualTo(docCount);
    }

    @Test
    public void testDataMigrationVMReset() throws Exception {
        int docCount = 100;
        populateEmailsInAppSearchImpl(mAppSearchImpl, "package1",
                "database1", "id", docCount);

        // Do data migration.
        DataMigrationStats stats = DataMigrationUtil.runDataMigrationForUser(
                mContext, mUserHandle, mAppSearchImpl, mVmIcingSearchEngine, /*logger=*/ null);
        assertThat(mAppSearchImpl.isVMEnabled()).isTrue();

        // check stats
        int okStatusCode = StatusProto.Code.OK.getNumber();
        assertThat(stats.getDataMigrationStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getVMInitStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getResetStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getSetSchemaStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getFlushStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getQueryStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getPutStatus()).asList().containsExactly(okStatusCode);
        assertThat(stats.getNumberOfDocsSucceeded()).isEqualTo(docCount);
        assertThat(stats.getNumberOfDocsFailed()).isEqualTo(0);
        assertThat(stats.getDataMigrationRunCounter()).isEqualTo(1);

        // Check AppSearchImpl after migration
        SearchSpecProto searchSpec = SearchSpecProto.newBuilder()
                .setQuery("") // an empty query will return all docs.
                .setTermMatchType(TermMatchType.Code.PREFIX)
                .build();

        // check the vm instance
        SearchResultProto searchResult =
                mVmIcingSearchEngine.search(
                        searchSpec,
                        ScoringSpecProto.newBuilder().build(),
                        ResultSpecProto.newBuilder()
                                .setNumToScore(Integer.MAX_VALUE)
                                .setNumPerPage(Integer.MAX_VALUE)
                                .build());
        assertThat(searchResult.getResultsCount()).isEqualTo(docCount);

        // Create a new AppSearchInstance
        File appSearchDir = mTemporaryFolder.newFolder("appsearch");
        File icingDir = new File(appSearchDir, "icing");
        // This should create appsearchDir/icing/version file
        AppSearchImpl appSearchImpl = AppSearchImpl.create(
                icingDir,
                mUnlimitedConfig,
                /* initStatsBuilder= */ null,
                /* visibilityChecker= */ null,
                /* revocableFileDescriptorStore= */ null,
                /* icingSearchEngine= */ null,
                ALWAYS_OPTIMIZE);
        int newDocCount = docCount / 2;
        populateEmailsInAppSearchImpl(appSearchImpl, "package1",
                "database1", "id", newDocCount);

        stats = DataMigrationUtil.runDataMigrationForUser(
                mContext, mUserHandle, appSearchImpl, mVmIcingSearchEngine, /*logger=*/ null);
        assertThat(appSearchImpl.isVMEnabled()).isTrue();

        // Check AppSearchImpl after migration
        searchSpec = SearchSpecProto.newBuilder()
                .setQuery("") // an empty query will return all docs.
                .setTermMatchType(TermMatchType.Code.PREFIX)
                .build();

        // check the vm instance
        searchResult =
                mVmIcingSearchEngine.search(
                        searchSpec,
                        ScoringSpecProto.newBuilder().build(),
                        ResultSpecProto.newBuilder()
                                .setNumToScore(Integer.MAX_VALUE)
                                .setNumPerPage(Integer.MAX_VALUE)
                                .build());
        assertThat(searchResult.getResultsCount()).isEqualTo(newDocCount);
    }

    @Test
    public void testDataMigrationSucceedOnEmptySource() throws Exception {
        File appSearchDir = mTemporaryFolder.newFolder("appsearch");
        File icingDir = new File(appSearchDir, "icing");
        File dataMigrationStatusFile = new File(appSearchDir,
                DataMigrationUtil.DATA_MIGRATION_STATUS_FILE);
        AppSearchEnvironmentFactory.setEnvironmentInstanceForTest(
                new FrameworkAppSearchEnvironment() {
                    @Override
                    public File getAppSearchDir(
                            @NonNull Context unused, @NonNull UserHandle userHandle) {
                        return appSearchDir;
                    }
                });
        // This should create appsearchDir/icing/version file
        AppSearchImpl appSearchImpl = AppSearchImpl.create(
                icingDir,
                mUnlimitedConfig,
                /* initStatsBuilder= */ null,
                /* visibilityChecker= */ null,
                /* revocableFileDescriptorStore= */ null,
                /* icingSearchEngine= */ null,
                ALWAYS_OPTIMIZE);

        // migration status file doesn't exist before migration.
        assertThat(dataMigrationStatusFile.exists()).isFalse();
        assertThat(appSearchImpl.isVMEnabled()).isFalse();

        // Do data migration.
        DataMigrationStats stats = DataMigrationUtil.runDataMigrationForUser(
                mContext, mUserHandle, appSearchImpl, mVmIcingSearchEngine, /*logger=*/ null);

        int okStatusCode = StatusProto.Code.OK.getNumber();
        assertThat(appSearchImpl.isVMEnabled()).isTrue();
        assertThat(dataMigrationStatusFile.exists()).isTrue();
        assertThat(stats.getDataMigrationStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getVMInitStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getResetStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getSetSchemaStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getFlushStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getQueryStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getPutStatus()).isNull();
        assertThat(stats.getNumberOfDocsSucceeded()).isEqualTo(0);
        assertThat(stats.getNumberOfDocsFailed()).isEqualTo(0);
        assertThat(stats.getDataMigrationRunCounter()).isEqualTo(1);
    }

    @Test
    public void migrateFromDbScopedSchemaOperationsDisabled_shouldEnableDbSchemaAfterMigration()
            throws Exception {
        mAppSearchImpl =
                AppSearchImpl.create(
                        mAppSearchDir,
                        mAppSearchConfigWithDatabaseSchemaDisabled,
                        /* initStatsBuilder= */ null,
                        /* visibilityChecker= */ null,
                        /* revocableFileDescriptorStore= */ null,
                        /* icingSearchEngine= */ null,
                        ALWAYS_OPTIMIZE);
        assertThat(mAppSearchImpl.useDatabaseScopedSchemaOperations()).isFalse();

        String packageName = "package";
        String databaseName = "database";
        String targetPrefix = PrefixUtil.createPrefix(packageName, databaseName);
        // Set a schema before migration.
        List<AppSearchSchema> schemas =
                Collections.singletonList(new AppSearchSchema.Builder("Email").build());
        InternalSetSchemaResponse internalSetSchemaResponse =
                mAppSearchImpl.setSchema(
                        packageName,
                        databaseName,
                        schemas,
                        /* visibilityConfigs= */ Collections.emptyList(),
                        /* forceOverride= */ false,
                        /* version= */ 0,
                        /* setSchemaStatsBuilder= */ null);
        assertThat(internalSetSchemaResponse.isSuccess()).isTrue();

        // Sanity check Email schema should not contain database name before migration.
        Map<String, SchemaTypeConfigProto> schemaTypesBeforeMigration =
                getRawSchemaTypesForDatabase(
                        mAppSearchImpl.rawGetSchema(), packageName, databaseName);
        assertThat(schemaTypesBeforeMigration.keySet()).containsExactlyElementsIn(List.of("Email"));
        assertThat(schemaTypesBeforeMigration.get("Email").getDatabase()).isEqualTo("");

        // Migrate the schema.
        DataMigrationStats migrationStats = DataMigrationUtil.runDataMigrationForUser(
                mContext, mUserHandle, mAppSearchImpl, mVmIcingSearchEngine, /*logger=*/ null);
        assertThat(mAppSearchImpl.useDatabaseScopedSchemaOperations()).isTrue();

        Map<String, SchemaTypeConfigProto> schemaTypesAfterMigration =
                getRawSchemaTypesForDatabase(
                        mAppSearchImpl.rawGetSchema(), packageName, databaseName);
        assertThat(schemaTypesAfterMigration.keySet()).containsExactlyElementsIn(List.of("Email"));
        assertThat(schemaTypesAfterMigration.get("Email").getDatabase()).isEqualTo(targetPrefix);
    }

    @Test
    public void migrateFromDbScopedSchemaOperationsDisabled_setNewSchemaAfterMigration()
            throws Exception {
        mAppSearchImpl =
                AppSearchImpl.create(
                        mAppSearchDir,
                        mAppSearchConfigWithDatabaseSchemaDisabled,
                        /* initStatsBuilder= */ null,
                        /* visibilityChecker= */ null,
                        /* revocableFileDescriptorStore= */ null,
                        /* icingSearchEngine= */ null,
                        ALWAYS_OPTIMIZE);
        assertThat(mAppSearchImpl.useDatabaseScopedSchemaOperations()).isFalse();

        String packageName = "package";
        String databaseName = "database";
        String targetPrefix = PrefixUtil.createPrefix(packageName, databaseName);
        // Set a schema before migration.
        List<AppSearchSchema> schemas =
                Collections.singletonList(new AppSearchSchema.Builder("Email").build());
        InternalSetSchemaResponse internalSetSchemaResponse1 =
                mAppSearchImpl.setSchema(
                        packageName,
                        databaseName,
                        schemas,
                        /* visibilityConfigs= */ Collections.emptyList(),
                        /* forceOverride= */ false,
                        /* version= */ 0,
                        /* setSchemaStatsBuilder= */ null);
        assertThat(internalSetSchemaResponse1.isSuccess()).isTrue();

        // Sanity check Email schema should not contain database name before migration.
        Map<String, SchemaTypeConfigProto> schemaTypesBeforeMigration =
                getRawSchemaTypesForDatabase(
                        mAppSearchImpl.rawGetSchema(), packageName, databaseName);
        assertThat(schemaTypesBeforeMigration.keySet()).containsExactlyElementsIn(List.of("Email"));
        assertThat(schemaTypesBeforeMigration.get("Email").getDatabase()).isEqualTo("");

        // Migrate the schema.
        DataMigrationStats migrationStats = DataMigrationUtil.runDataMigrationForUser(
                mContext, mUserHandle, mAppSearchImpl, mVmIcingSearchEngine, /*logger=*/ null);
        assertThat(mAppSearchImpl.useDatabaseScopedSchemaOperations()).isTrue();

        // Set another new schema after migration.
        List<AppSearchSchema> schemas2 =
                Arrays.asList(
                        new AppSearchSchema.Builder("Email").build(),
                        new AppSearchSchema.Builder("Message").build());
        InternalSetSchemaResponse internalSetSchemaResponse2 =
                mAppSearchImpl.setSchema(
                        packageName,
                        databaseName,
                        schemas2,
                        /* visibilityConfigs= */ Collections.emptyList(),
                        /* forceOverride= */ false,
                        /* version= */ 0,
                        /* setSchemaStatsBuilder= */ null);
        assertThat(internalSetSchemaResponse2.isSuccess()).isTrue();

        Map<String, SchemaTypeConfigProto> schemaTypesAfterMigration =
                getRawSchemaTypesForDatabase(
                        mAppSearchImpl.rawGetSchema(), packageName, databaseName);
        assertThat(schemaTypesAfterMigration.keySet())
                .containsExactlyElementsIn(List.of("Email", "Message"));
        assertThat(schemaTypesAfterMigration.get("Email").getDatabase()).isEqualTo(targetPrefix);
        assertThat(schemaTypesAfterMigration.get("Message").getDatabase()).isEqualTo(targetPrefix);
    }

    @Test
    public void migrateFromDbScopedSchemaOperationsDisabled_setSameSchemaAfterMigration()
            throws Exception {
        mAppSearchImpl =
                AppSearchImpl.create(
                        mAppSearchDir,
                        mAppSearchConfigWithDatabaseSchemaDisabled,
                        /* initStatsBuilder= */ null,
                        /* visibilityChecker= */ null,
                        /* revocableFileDescriptorStore= */ null,
                        /* icingSearchEngine= */ null,
                        ALWAYS_OPTIMIZE);
        assertThat(mAppSearchImpl.useDatabaseScopedSchemaOperations()).isFalse();

        String packageName = "package";
        String databaseName = "database";
        String targetPrefix = PrefixUtil.createPrefix(packageName, databaseName);
        // Set a schema before migration.
        List<AppSearchSchema> schemas =
                Collections.singletonList(new AppSearchSchema.Builder("Email").build());
        InternalSetSchemaResponse internalSetSchemaResponse1 =
                mAppSearchImpl.setSchema(
                        packageName,
                        databaseName,
                        schemas,
                        /* visibilityConfigs= */ Collections.emptyList(),
                        /* forceOverride= */ false,
                        /* version= */ 0,
                        /* setSchemaStatsBuilder= */ null);
        assertThat(internalSetSchemaResponse1.isSuccess()).isTrue();

        // Sanity check Email schema should not contain database name before migration.
        Map<String, SchemaTypeConfigProto> schemaTypesBeforeMigration =
                getRawSchemaTypesForDatabase(
                        mAppSearchImpl.rawGetSchema(), packageName, databaseName);
        assertThat(schemaTypesBeforeMigration.keySet()).containsExactlyElementsIn(List.of("Email"));
        assertThat(schemaTypesBeforeMigration.get("Email").getDatabase()).isEqualTo("");

        // Migrate the schema.
        DataMigrationStats migrationStats = DataMigrationUtil.runDataMigrationForUser(
                mContext, mUserHandle, mAppSearchImpl, mVmIcingSearchEngine, /*logger=*/ null);
        assertThat(mAppSearchImpl.useDatabaseScopedSchemaOperations()).isTrue();

        // Set the same schema after migration.
        InternalSetSchemaResponse internalSetSchemaResponse2 =
                mAppSearchImpl.setSchema(
                        packageName,
                        databaseName,
                        schemas,
                        /* visibilityConfigs= */ Collections.emptyList(),
                        /* forceOverride= */ false,
                        /* version= */ 0,
                        /* setSchemaStatsBuilder= */ null);
        assertThat(internalSetSchemaResponse2.isSuccess()).isTrue();

        Map<String, SchemaTypeConfigProto> schemaTypesAfterMigration =
                getRawSchemaTypesForDatabase(
                        mAppSearchImpl.rawGetSchema(), packageName, databaseName);
        assertThat(schemaTypesAfterMigration.keySet()).containsExactlyElementsIn(List.of("Email"));
        assertThat(schemaTypesAfterMigration.get("Email").getDatabase()).isEqualTo(targetPrefix);
    }

    @Test
    public void verifyStatsFromDataMigration() throws Exception {
        int docNum = 100;
        insertTestDocsToAppSearchImpl(100);
        int okStatus = StatusProto.Code.OK.getNumber();

        DataMigrationStats stats = DataMigrationUtil.runDataMigrationForUser(mContext,
                mUserHandle,
                mAppSearchImpl,
                mVmIcingSearchEngine,
                /*logger=*/ null);

        assertThat(stats.getDataMigrationStatus()).isEqualTo(okStatus);
        assertThat(stats.getVMInitStatus()).isEqualTo(okStatus);
        assertThat(stats.getResetStatus()).isEqualTo(okStatus);
        assertThat(stats.getSetSchemaStatus()).isEqualTo(okStatus);
        assertThat(stats.getFlushStatus()).isEqualTo(okStatus);
        assertThat(stats.getQueryStatus()).isEqualTo(okStatus);
        assertThat(stats.getPutStatus()).asList().containsExactly(okStatus);
        assertThat(stats.getNumberOfDocsSucceeded()).isEqualTo(docNum);
        assertThat(stats.getNumberOfDocsFailed()).isEqualTo(0);
    }

    @Test
    public void verifyVMResetBeforeMigration() throws Exception {
        int docNumInVM = 100;
        int docNumInHost = 1;
        int okStatus = StatusProto.Code.OK.getNumber();
        insertTestDocsToAppSearchImpl(docNumInVM);
        // After that, mVmIcingSearchEngine contains data, and mAppSearchImpl is empty.
        mVmIcingSearchEngine = mAppSearchImpl.swapIcingSearchEngineLocked(
                mVmIcingSearchEngine,
                /*isVMEnabled=*/ true);
        insertTestDocsToAppSearchImpl(docNumInHost);

        SearchSpecProto searchSpec =
                SearchSpecProto.newBuilder()
                        .setQuery("")
                        .setTermMatchType(TermMatchType.Code.PREFIX)
                        .build();
        SearchResultProto searchResultProto =
                mVmIcingSearchEngine.search(
                        searchSpec,
                        ScoringSpecProto.getDefaultInstance(),
                        ResultSpecProto.newBuilder().setNumPerPage(
                                docNumInVM + docNumInHost).build());
        assertThat(searchResultProto.getResultsCount()).isEqualTo(docNumInVM);

        DataMigrationStats stats = DataMigrationUtil.runDataMigrationForUser(mContext,
                mUserHandle,
                mAppSearchImpl,
                mVmIcingSearchEngine,
                /*logger=*/ null);

        assertThat(stats.getDataMigrationStatus()).isEqualTo(okStatus);
        assertThat(stats.getVMInitStatus()).isEqualTo(okStatus);
        assertThat(stats.getResetStatus()).isEqualTo(okStatus);
        assertThat(stats.getSetSchemaStatus()).isEqualTo(okStatus);
        assertThat(stats.getFlushStatus()).isEqualTo(okStatus);
        assertThat(stats.getQueryStatus()).isEqualTo(okStatus);
        assertThat(stats.getPutStatus()).asList().containsExactly(okStatus);
        assertThat(stats.getNumberOfDocsSucceeded()).isEqualTo(docNumInHost);
        assertThat(stats.getNumberOfDocsFailed()).isEqualTo(0);

        searchResultProto =
                mVmIcingSearchEngine.search(
                        searchSpec,
                        ScoringSpecProto.getDefaultInstance(),
                        ResultSpecProto.newBuilder().setNumPerPage(
                                docNumInVM + docNumInHost).build());
        // icing has been reset.
        assertThat(searchResultProto.getResultsCount()).isEqualTo(docNumInHost);
    }

    private void insertTestDocsToAppSearchImpl(int docNum) throws AppSearchException {
        // Insert schema
        List<AppSearchSchema> schemas =
                ImmutableList.of(
                        new AppSearchSchema.Builder("Type1").build());
        InternalSetSchemaResponse internalSetSchemaResponse =
                mAppSearchImpl.setSchema(
                        mContext.getPackageName(),
                        "database1",
                        schemas,
                        /* visibilityConfigs= */ Collections.emptyList(),
                        /* forceOverride= */ false,
                        /* version= */ 0,
                        /* setSchemaStatsBuilder= */ null);

        assertThat(internalSetSchemaResponse.isSuccess()).isTrue();

        // Insert valid docs
        List<GenericDocument> docs = new ArrayList<>();
        for (int i = 0; i < docNum; ++i) {
            GenericDocument doc =
                    new GenericDocument.Builder<>(
                            "namespace1", "id" + i, "Type1").build();
            docs.add(doc);
        }

        mAppSearchImpl.batchPutDocuments(
                mContext.getPackageName(),
                "database1",
                docs,
                /*batchResultBuilder=*/ null,
                /* sendChangeNotifications= */ false,
                /* logger= */ null,
                PersistType.Code.LITE);
    }

    /**
     * Helper function to get {@link SchemaTypeConfigProto} for the given package and database.
     *
     * @return a map for schema type name (with prefix removed) to {@link SchemaTypeConfigProto}.
     */
    private static Map<String, SchemaTypeConfigProto> getRawSchemaTypesForDatabase(
            SchemaProto schemaProto, String packageName, String databaseName)
            throws AppSearchException {
        String targetPrefix = PrefixUtil.createPrefix(packageName, databaseName);

        Map<String, SchemaTypeConfigProto> result = new ArrayMap<>();
        for (int i = 0; i < schemaProto.getTypesCount(); ++i) {
            SchemaTypeConfigProto typeConfig = schemaProto.getTypes(i);
            String typePrefix = PrefixUtil.getPrefix(typeConfig.getSchemaType());
            String typeName = PrefixUtil.removePrefix(typeConfig.getSchemaType());
            if (typePrefix.equals(targetPrefix)) {
                result.put(typeName, typeConfig);
            }
        }
        return result;
    }
}
