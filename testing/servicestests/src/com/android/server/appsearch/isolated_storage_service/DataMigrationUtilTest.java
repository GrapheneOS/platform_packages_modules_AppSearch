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

import static android.app.appsearch.testutil.AppSearchTestUtils.calculateDigest;
import static android.app.appsearch.testutil.AppSearchTestUtils.generateRandomBytes;

import static com.google.common.truth.Truth.assertThat;

import android.app.appsearch.AppSearchBlobHandle;
import android.app.appsearch.AppSearchEnvironmentFactory;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.AppSearchSchema.EmbeddingPropertyConfig;
import android.app.appsearch.AppSearchSchema.PropertyConfig;
import android.app.appsearch.AppSearchSchema.StringPropertyConfig;
import android.app.appsearch.EmbeddingVector;
import android.app.appsearch.FrameworkAppSearchEnvironment;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.InternalSetSchemaResponse;
import android.app.appsearch.SearchResultPage;
import android.app.appsearch.SearchSpec;
import android.app.appsearch.exceptions.AppSearchException;
import android.app.appsearch.testutil.AppSearchEmail;
import android.app.appsearch.testutil.AppSearchTestUtils;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.util.ArrayMap;

import androidx.test.core.app.ApplicationProvider;

import com.android.appsearch.flags.Flags;
import com.android.server.appsearch.external.localstorage.AppSearchConfig;
import com.android.server.appsearch.external.localstorage.AppSearchConfigImpl;
import com.android.server.appsearch.external.localstorage.AppSearchImpl;
import com.android.server.appsearch.external.localstorage.JetpackRevocableFileDescriptorStore;
import com.android.server.appsearch.external.localstorage.LocalStorageIcingOptionsConfig;
import com.android.server.appsearch.external.localstorage.OptimizeStrategy;
import com.android.server.appsearch.external.localstorage.UnlimitedLimitConfig;
import com.android.server.appsearch.external.localstorage.util.PrefixUtil;
import com.android.server.appsearch.icing.proto.BatchPutResultProto;
import com.android.server.appsearch.icing.proto.IcingSearchEngineOptions;
import com.android.server.appsearch.icing.proto.InitializeResultProto;
import com.android.server.appsearch.icing.proto.PersistType;
import com.android.server.appsearch.icing.proto.PutDocumentRequest;
import com.android.server.appsearch.icing.proto.PutResultProto;
import com.android.server.appsearch.icing.proto.ResultSpecProto;
import com.android.server.appsearch.icing.proto.SchemaProto;
import com.android.server.appsearch.icing.proto.SchemaTypeConfigProto;
import com.android.server.appsearch.icing.proto.ScoringSpecProto;
import com.android.server.appsearch.icing.proto.SearchResultProto;
import com.android.server.appsearch.icing.proto.SearchSpecProto;
import com.android.server.appsearch.icing.proto.StatusProto;
import com.android.server.appsearch.icing.proto.TermMatchType;
import com.android.server.appsearch.indexer.PersistableBundleSettingsStore;

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
import java.io.InputStream;
import java.io.OutputStream;
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

    private static class TestIcingSearchEngine extends IcingSearchEngine {
        private BatchPutResultProto mBatchPutResultProto = null;
        /**
         * @throws IllegalStateException if IcingSearchEngine fails to be created
         */
        TestIcingSearchEngine(@NonNull IcingSearchEngineOptions options) {
            super(options);
        }

        @Override
        public @NonNull BatchPutResultProto batchPut(@NonNull PutDocumentRequest documents) {
            if (mBatchPutResultProto == null) {
                return super.batchPut(documents);
            }

            return mBatchPutResultProto;
        }

        private void setBatchPutResultProto(@NonNull BatchPutResultProto batchPutResultProto) {
            mBatchPutResultProto = batchPutResultProto;
        }
    }

    private static void populateEmailsInAppSearchImpl(
            @NonNull AppSearchImpl appSearchImpl,
            @NonNull String packageName,
            @NonNull String databaseName,
            @NonNull String startId,
            int docCount)
            throws Exception {
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
                        /* setSchemaStatsBuilder= */ null,
                        /* callStatsBuilder= */ null);
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
                /* batchResultBuilder= */ null,
                /* sendChangeNotifications= */ false,
                /* logger= */ null,
                PersistType.Code.LITE,
                /* callStatsBuilder= */ null);
    }

    private static void populateEmailsWithEmbeddingsInAppSearchImpl(
            @NonNull AppSearchImpl appSearchImpl,
            @NonNull String packageName,
            @NonNull String databaseName)
            throws Exception {
        // Schema registration
        AppSearchSchema emailSchema =
                new AppSearchSchema.Builder("Email")
                        .addProperty(
                                new StringPropertyConfig.Builder("body")
                                        .setCardinality(PropertyConfig.CARDINALITY_OPTIONAL)
                                        .setTokenizerType(StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                                        .setIndexingType(
                                                StringPropertyConfig.INDEXING_TYPE_PREFIXES)
                                        .build())
                        .addProperty(
                                new EmbeddingPropertyConfig.Builder("embedding1")
                                        .setCardinality(PropertyConfig.CARDINALITY_REPEATED)
                                        .setIndexingType(
                                                EmbeddingPropertyConfig.INDEXING_TYPE_SIMILARITY)
                                        .build())
                        .addProperty(
                                new EmbeddingPropertyConfig.Builder("embedding2")
                                        .setCardinality(PropertyConfig.CARDINALITY_REPEATED)
                                        .setIndexingType(
                                                EmbeddingPropertyConfig.INDEXING_TYPE_SIMILARITY)
                                        .build())
                        .build();
        List<AppSearchSchema> schema = ImmutableList.of(emailSchema);
        InternalSetSchemaResponse internalSetSchemaResponse =
                appSearchImpl.setSchema(
                        packageName,
                        databaseName,
                        schema,
                        /* visibilityConfigs= */ Collections.emptyList(),
                        /* forceOverride= */ false,
                        /* version= */ 0,
                        /* setSchemaStatsBuilder= */ null,
                        /* callStatsBuilder= */ null);
        assertThat(internalSetSchemaResponse.isSuccess()).isTrue();

        // Index documents
        GenericDocument doc0 =
                new GenericDocument.Builder<>("namespace", "id0", "Email")
                        .setPropertyString("body", "foo")
                        .setCreationTimestampMillis(1000)
                        .setPropertyEmbedding(
                                "embedding1",
                                new EmbeddingVector(
                                        new float[] {0.1f, 0.2f, 0.3f, 0.4f, 0.5f}, "my_model_v1"))
                        .setPropertyEmbedding(
                                "embedding2",
                                new EmbeddingVector(
                                        new float[] {-0.1f, -0.2f, -0.3f, 0.4f, 0.5f},
                                        "my_model_v1"),
                                new EmbeddingVector(new float[] {0.6f, 0.7f, 0.8f}, "my_model_v2"))
                        .build();
        GenericDocument doc1 =
                new GenericDocument.Builder<>("namespace", "id1", "Email")
                        .setPropertyString("body", "bar")
                        .setCreationTimestampMillis(1000)
                        .setPropertyEmbedding(
                                "embedding1",
                                new EmbeddingVector(
                                        new float[] {-0.1f, 0.2f, -0.3f, -0.4f, 0.5f},
                                        "my_model_v1"))
                        .setPropertyEmbedding(
                                "embedding2",
                                new EmbeddingVector(new float[] {0.6f, 0.7f, -0.8f}, "my_model_v2"))
                        .build();

        List<GenericDocument> docs = new ArrayList<>();
        docs.add(doc0);
        docs.add(doc1);
        appSearchImpl.batchPutDocuments(
                packageName,
                databaseName,
                docs,
                /* batchResultBuilder= */ null,
                /* sendChangeNotifications= */ false,
                /* logger= */ null,
                PersistType.Code.LITE,
                /* callStatsBuilder= */ null);
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
                        /* callStatsBuilder= */ null,
                        /* visibilityChecker= */ null,
                        new JetpackRevocableFileDescriptorStore(mUnlimitedConfig),
                        /* icingSearchEngine= */ null,
                        ALWAYS_OPTIMIZE);
        mVmIcingSearchEngineDir = mTemporaryFolder.newFolder("vm");
        mVmIcingSearchEngine =
                new TestIcingSearchEngine(
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
                /* callStatsBuilder= */ null,
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
                /* callStatsBuilder= */ null,
                /* visibilityChecker= */ null,
                /* revocableFileDescriptorStore= */ null,
                /* icingSearchEngine= */ null,
                ALWAYS_OPTIMIZE);
        File icingVersion = new File(appSearchDir, "icing/version");
        File dataMigrationStatusFile =
                new File(appSearchDir, DataMigrationUtil.DATA_MIGRATION_STATUS_FILE);
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
        File dataMigrationStatusFile =
                new File(appSearchDir, DataMigrationUtil.DATA_MIGRATION_STATUS_FILE);
        AppSearchEnvironmentFactory.setEnvironmentInstanceForTest(
                new FrameworkAppSearchEnvironment() {
                    @Override
                    public File getAppSearchDir(
                            @NonNull Context unused, @NonNull UserHandle userHandle) {
                        return appSearchDir;
                    }
                });
        // This should create appsearchDir/icing/version file
        AppSearchImpl appSearchImpl =
                AppSearchImpl.create(
                        icingDir,
                        mUnlimitedConfig,
                        /* initStatsBuilder= */ null,
                        /* callStatsBuilder= */ null,
                        /* visibilityChecker= */ null,
                        /* revocableFileDescriptorStore= */ null,
                        /* icingSearchEngine= */ null,
                        ALWAYS_OPTIMIZE);
        int docCount = 100;
        populateEmailsInAppSearchImpl(appSearchImpl, "package1", "database1", "id", docCount);

        // migration status file doesn't exist before migration.
        assertThat(dataMigrationStatusFile.exists()).isFalse();
        assertThat(appSearchImpl.isVMEnabled()).isFalse();

        // Do data migration.
        DataMigrationStats stats =
                DataMigrationUtil.runDataMigrationForUser(
                        mContext,
                        mUserHandle,
                        appSearchImpl,
                        mVmIcingSearchEngine,
                        /* logger= */ null);
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
        PersistableBundle bundle =
                PersistableBundleSettingsStore.readBundle(dataMigrationStatusFile);
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
        SearchSpecProto searchSpec =
                SearchSpecProto.newBuilder()
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
        File dataMigrationStatusFile =
                new File(appSearchDir, DataMigrationUtil.DATA_MIGRATION_STATUS_FILE);
        AppSearchEnvironmentFactory.setEnvironmentInstanceForTest(
                new FrameworkAppSearchEnvironment() {
                    @Override
                    public File getAppSearchDir(
                            @NonNull Context unused, @NonNull UserHandle userHandle) {
                        return appSearchDir;
                    }
                });
        // This should create appsearchDir/icing/version file
        AppSearchImpl appSearchImpl =
                AppSearchImpl.create(
                        icingDir,
                        mUnlimitedConfig,
                        /* initStatsBuilder= */ null,
                        /* callStatsBuilder= */ null,
                        /* visibilityChecker= */ null,
                        /* revocableFileDescriptorStore= */ null,
                        /* icingSearchEngine= */ null,
                        ALWAYS_OPTIMIZE);

        int docCount = 100;
        populateEmailsInAppSearchImpl(appSearchImpl, "package1", "database1", "id", docCount);

        // Create status file to indicate previous failures:
        assertThat(dataMigrationStatusFile.exists()).isFalse();
        int prevRunTimes = 2;
        DataMigrationStats prevStats = new DataMigrationStats();
        prevStats.setDataMigrationRunCounter(prevRunTimes); // failed twice
        prevStats.setDataMigrationStatus(StatusProto.Code.ABORTED.getNumber());
        PersistableBundleSettingsStore.writeBundle(dataMigrationStatusFile, prevStats.getBundle());
        assertThat(dataMigrationStatusFile.exists()).isTrue();

        // Do data migration.
        DataMigrationStats stats =
                DataMigrationUtil.runDataMigrationForUser(
                        mContext,
                        mUserHandle,
                        appSearchImpl,
                        mVmIcingSearchEngine,
                        /* logger= */ null);
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
        PersistableBundle bundle =
                PersistableBundleSettingsStore.readBundle(dataMigrationStatusFile);
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
        SearchSpecProto searchSpec =
                SearchSpecProto.newBuilder()
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
    public void testDataMigrationOnEmptyStatusFile() throws Exception {
        File appSearchDir = mTemporaryFolder.newFolder("appsearch");
        File icingDir = new File(appSearchDir, "icing");
        File dataMigrationStatusFile =
                new File(appSearchDir, DataMigrationUtil.DATA_MIGRATION_STATUS_FILE);
        AppSearchEnvironmentFactory.setEnvironmentInstanceForTest(
                new FrameworkAppSearchEnvironment() {
                    @Override
                    public File getAppSearchDir(
                            @NonNull Context unused, @NonNull UserHandle userHandle) {
                        return appSearchDir;
                    }
                });
        // This should create appsearchDir/icing/version file
        AppSearchImpl appSearchImpl =
                AppSearchImpl.create(
                        icingDir,
                        mUnlimitedConfig,
                        /* initStatsBuilder= */ null,
                        /* callStatsBuilder= */ null,
                        /* visibilityChecker= */ null,
                        /* revocableFileDescriptorStore= */ null,
                        /* icingSearchEngine= */ null,
                        ALWAYS_OPTIMIZE);

        int docCount = 100;
        populateEmailsInAppSearchImpl(appSearchImpl, "package1", "database1", "id", docCount);

        // Create status file to indicate previous failures:
        assertThat(dataMigrationStatusFile.exists()).isFalse();
        // create an empty status file
        dataMigrationStatusFile.createNewFile();
        assertThat(dataMigrationStatusFile.exists()).isTrue();

        // Do data migration.
        DataMigrationStats stats =
                DataMigrationUtil.runDataMigrationForUser(
                        mContext,
                        mUserHandle,
                        appSearchImpl,
                        mVmIcingSearchEngine,
                        /* logger= */ null);
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
        PersistableBundle bundle =
                PersistableBundleSettingsStore.readBundle(dataMigrationStatusFile);
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
        SearchSpecProto searchSpec =
                SearchSpecProto.newBuilder()
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
    public void testDataMigrationRetryOnFailedPuts() throws Exception {
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
        AppSearchImpl appSearchImpl =
                AppSearchImpl.create(
                        icingDir,
                        mUnlimitedConfig,
                        /* initStatsBuilder= */ null,
                        /* callStatsBuilder= */ null,
                        /* visibilityChecker= */ null,
                        /* revocableFileDescriptorStore= */ null,
                        /* icingSearchEngine= */ null,
                        ALWAYS_OPTIMIZE);

        int docCount = 50;
        populateEmailsInAppSearchImpl(appSearchImpl, "package1", "database1", "id", docCount);

        //
        // 1st failed try to do data migration.
        //
        StatusProto.Code internalErrorCode =  StatusProto.Code.INTERNAL;
        TestIcingSearchEngine icingSearchEngine = (TestIcingSearchEngine) mVmIcingSearchEngine;
        icingSearchEngine.setBatchPutResultProto(
                BatchPutResultProto.newBuilder()
                        .setStatus(StatusProto.newBuilder().setCode(internalErrorCode).build())
                        .build());
        DataMigrationStats stats =
                DataMigrationUtil.runDataMigrationForUser(
                        mContext,
                        mUserHandle,
                        appSearchImpl,
                        icingSearchEngine,
                        /* logger= */ null);

        // check stats
        assertThat(appSearchImpl.isVMEnabled()).isFalse();
        int okStatusCode = StatusProto.Code.OK.getNumber();
        assertThat(stats.getDataMigrationStatus()).isEqualTo(internalErrorCode.getNumber());
        assertThat(stats.getVMInitStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getResetStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getSetSchemaStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getFlushStatus()).isEqualTo(-1);
        assertThat(stats.getQueryStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getPutStatus())
                .asList()
                .containsExactly(StatusProto.Code.INTERNAL.getNumber());
        assertThat(stats.getNumberOfDocsSucceeded()).isEqualTo(0);
        assertThat(stats.getNumberOfDocsFailed()).isEqualTo(0);
        assertThat(stats.getDataMigrationRunCounter()).isEqualTo(1);

        // Check IcingSearchEngine after migration
        SearchSpecProto searchSpec =
                SearchSpecProto.newBuilder()
                        .setQuery("") // an empty query will return all docs.
                        .setTermMatchType(TermMatchType.Code.PREFIX)
                        .build();
        SearchResultProto searchResult =
                icingSearchEngine.search(
                        searchSpec,
                        ScoringSpecProto.newBuilder().build(),
                        ResultSpecProto.newBuilder()
                                .setNumToScore(Integer.MAX_VALUE)
                                .setNumPerPage(Integer.MAX_VALUE)
                                .build());
        assertThat(searchResult.getResultsCount()).isEqualTo(0);

        //
        // 2nd failed try to do data migration.
        //
        BatchPutResultProto failedPuts =
                BatchPutResultProto.newBuilder()
                        .setStatus(StatusProto.newBuilder().setCode(internalErrorCode).build())
                        .addPutResultProtos(
                                PutResultProto.newBuilder()
                                        .setUri("id0")
                                        .setStatus(
                                                StatusProto.newBuilder()
                                                        .setCode(StatusProto.Code.OK)
                                                        .build())
                                        .build())
                        .addPutResultProtos(
                                PutResultProto.newBuilder()
                                        .setUri("id1")
                                        .setStatus(
                                                StatusProto.newBuilder()
                                                        .setCode(StatusProto.Code.OK)
                                                        .build())
                                        .build())
                        .addPutResultProtos(
                                PutResultProto.newBuilder()
                                        .setUri("id2")
                                        .setStatus(
                                                StatusProto.newBuilder()
                                                        .setCode(StatusProto.Code.INVALID_ARGUMENT)
                                                        .build())
                                        .build())
                        .addPutResultProtos(
                                PutResultProto.newBuilder()
                                        .setUri("id3")
                                        .setStatus(
                                                StatusProto.newBuilder()
                                                        .setCode(StatusProto.Code.ABORTED)
                                                        .build())
                                        .build())
                        .build();
        icingSearchEngine.setBatchPutResultProto(failedPuts);
        stats =
                DataMigrationUtil.runDataMigrationForUser(
                        mContext,
                        mUserHandle,
                        appSearchImpl,
                        icingSearchEngine,
                        /* logger= */ null);

        // check stats
        assertThat(appSearchImpl.isVMEnabled()).isFalse();
        assertThat(stats.getDataMigrationStatus()).isEqualTo(StatusProto.Code.ABORTED.getNumber());
        assertThat(stats.getVMInitStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getResetStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getSetSchemaStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getFlushStatus()).isEqualTo(-1);
        assertThat(stats.getQueryStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getPutStatus())
                .asList()
                .containsExactly(
                        StatusProto.Code.OK.getNumber(),
                        StatusProto.Code.INTERNAL.getNumber(),
                        StatusProto.Code.ABORTED.getNumber(),
                        StatusProto.Code.INVALID_ARGUMENT.getNumber());
        assertThat(stats.getNumberOfDocsSucceeded()).isEqualTo(2);
        assertThat(stats.getNumberOfDocsFailed()).isEqualTo(2);
        assertThat(stats.getDataMigrationRunCounter()).isEqualTo(2);

        // Check IcingSearchEngine after migration
        searchResult =
                icingSearchEngine.search(
                        searchSpec,
                        ScoringSpecProto.newBuilder().build(),
                        ResultSpecProto.newBuilder()
                                .setNumToScore(Integer.MAX_VALUE)
                                .setNumPerPage(Integer.MAX_VALUE)
                                .build());
        assertThat(searchResult.getResultsCount()).isEqualTo(0);

        //
        // 3rd try to do data migration. And this time it should succeed as we only try 3 times.
        //
        stats =
                DataMigrationUtil.runDataMigrationForUser(
                        mContext,
                        mUserHandle,
                        appSearchImpl,
                        icingSearchEngine,
                        /* logger= */ null);

        // check stats
        assertThat(appSearchImpl.isVMEnabled()).isTrue();
        assertThat(stats.getDataMigrationStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getVMInitStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getResetStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getSetSchemaStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getFlushStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getQueryStatus()).isEqualTo(okStatusCode);
        assertThat(stats.getPutStatus())
                .asList()
                .containsExactly(
                        StatusProto.Code.OK.getNumber(),
                        StatusProto.Code.INTERNAL.getNumber(),
                        StatusProto.Code.ABORTED.getNumber(),
                        StatusProto.Code.INVALID_ARGUMENT.getNumber());
        assertThat(stats.getNumberOfDocsSucceeded()).isEqualTo(2);
        assertThat(stats.getNumberOfDocsFailed()).isEqualTo(2);
        assertThat(stats.getDataMigrationRunCounter()).isEqualTo(3);

        // Check AppSearchImpl after migration
        searchResult =
                icingSearchEngine.search(
                        searchSpec,
                        ScoringSpecProto.newBuilder().build(),
                        ResultSpecProto.newBuilder()
                                .setNumToScore(Integer.MAX_VALUE)
                                .setNumPerPage(Integer.MAX_VALUE)
                                .build());
        assertThat(searchResult.getResultsCount()).isEqualTo(0);
    }

    @Test
    public void testDataMigrationVMReset() throws Exception {
        int docCount = 100;
        populateEmailsInAppSearchImpl(mAppSearchImpl, "package1", "database1", "id", docCount);

        // Do data migration.
        DataMigrationStats stats =
                DataMigrationUtil.runDataMigrationForUser(
                        mContext,
                        mUserHandle,
                        mAppSearchImpl,
                        mVmIcingSearchEngine,
                        /* logger= */ null);
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
        SearchSpecProto searchSpec =
                SearchSpecProto.newBuilder()
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
        AppSearchImpl appSearchImpl =
                AppSearchImpl.create(
                        icingDir,
                        mUnlimitedConfig,
                        /* initStatsBuilder= */ null,
                        /* callStatsBuilder= */ null,
                        /* visibilityChecker= */ null,
                        /* revocableFileDescriptorStore= */ null,
                        /* icingSearchEngine= */ null,
                        ALWAYS_OPTIMIZE);
        int newDocCount = docCount / 2;
        populateEmailsInAppSearchImpl(appSearchImpl, "package1", "database1", "id", newDocCount);

        DataMigrationUtil.runDataMigrationForUser(
                mContext, mUserHandle, appSearchImpl, mVmIcingSearchEngine, /* logger= */ null);
        assertThat(appSearchImpl.isVMEnabled()).isTrue();

        // Check AppSearchImpl after migration
        searchSpec =
                SearchSpecProto.newBuilder()
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
        File dataMigrationStatusFile =
                new File(appSearchDir, DataMigrationUtil.DATA_MIGRATION_STATUS_FILE);
        AppSearchEnvironmentFactory.setEnvironmentInstanceForTest(
                new FrameworkAppSearchEnvironment() {
                    @Override
                    public File getAppSearchDir(
                            @NonNull Context unused, @NonNull UserHandle userHandle) {
                        return appSearchDir;
                    }
                });
        // This should create appsearchDir/icing/version file
        AppSearchImpl appSearchImpl =
                AppSearchImpl.create(
                        icingDir,
                        mUnlimitedConfig,
                        /* initStatsBuilder= */ null,
                        /* callStatsBuilder= */ null,
                        /* visibilityChecker= */ null,
                        /* revocableFileDescriptorStore= */ null,
                        /* icingSearchEngine= */ null,
                        ALWAYS_OPTIMIZE);

        // migration status file doesn't exist before migration.
        assertThat(dataMigrationStatusFile.exists()).isFalse();
        assertThat(appSearchImpl.isVMEnabled()).isFalse();

        // Do data migration.
        DataMigrationStats stats =
                DataMigrationUtil.runDataMigrationForUser(
                        mContext,
                        mUserHandle,
                        appSearchImpl,
                        mVmIcingSearchEngine,
                        /* logger= */ null);

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
                        /* callStatsBuilder= */ null,
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
                        /* setSchemaStatsBuilder= */ null,
                        /* callStatsBuilder= */ null);
        assertThat(internalSetSchemaResponse.isSuccess()).isTrue();

        // Sanity check Email schema should not contain database name before migration.
        Map<String, SchemaTypeConfigProto> schemaTypesBeforeMigration =
                getRawSchemaTypesForDatabase(
                        mAppSearchImpl.rawGetSchema(), packageName, databaseName);
        assertThat(schemaTypesBeforeMigration.keySet()).containsExactlyElementsIn(List.of("Email"));
        assertThat(schemaTypesBeforeMigration.get("Email").getDatabase()).isEqualTo("");

        // Migrate the schema.
        DataMigrationUtil.runDataMigrationForUser(
                mContext, mUserHandle, mAppSearchImpl, mVmIcingSearchEngine, /* logger= */ null);
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
                        /* callStatsBuilder= */ null,
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
                        /* setSchemaStatsBuilder= */ null,
                        /* callStatsBuilder= */ null);
        assertThat(internalSetSchemaResponse1.isSuccess()).isTrue();

        // Sanity check Email schema should not contain database name before migration.
        Map<String, SchemaTypeConfigProto> schemaTypesBeforeMigration =
                getRawSchemaTypesForDatabase(
                        mAppSearchImpl.rawGetSchema(), packageName, databaseName);
        assertThat(schemaTypesBeforeMigration.keySet()).containsExactlyElementsIn(List.of("Email"));
        assertThat(schemaTypesBeforeMigration.get("Email").getDatabase()).isEqualTo("");

        // Migrate the schema.
        DataMigrationUtil.runDataMigrationForUser(
                mContext, mUserHandle, mAppSearchImpl, mVmIcingSearchEngine, /* logger= */ null);
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
                        /* setSchemaStatsBuilder= */ null,
                        /* callStatsBuilder= */ null);
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
                        /* callStatsBuilder= */ null,
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
                        /* setSchemaStatsBuilder= */ null,
                        /* callStatsBuilder= */ null);
        assertThat(internalSetSchemaResponse1.isSuccess()).isTrue();

        // Sanity check Email schema should not contain database name before migration.
        Map<String, SchemaTypeConfigProto> schemaTypesBeforeMigration =
                getRawSchemaTypesForDatabase(
                        mAppSearchImpl.rawGetSchema(), packageName, databaseName);
        assertThat(schemaTypesBeforeMigration.keySet()).containsExactlyElementsIn(List.of("Email"));
        assertThat(schemaTypesBeforeMigration.get("Email").getDatabase()).isEqualTo("");

        // Migrate the schema.
        DataMigrationUtil.runDataMigrationForUser(
                mContext, mUserHandle, mAppSearchImpl, mVmIcingSearchEngine, /* logger= */ null);
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
                        /* setSchemaStatsBuilder= */ null,
                        /* callStatsBuilder= */ null);
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

        DataMigrationStats stats =
                DataMigrationUtil.runDataMigrationForUser(
                        mContext,
                        mUserHandle,
                        mAppSearchImpl,
                        mVmIcingSearchEngine,
                        /* logger= */ null);

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
        int docNumInDest = 100;
        int docNumInSrc = 1;
        int okStatus = StatusProto.Code.OK.getNumber();
        // 1. Insert documents. AppSearchImpl is using destIcing and will insert docs there.
        insertTestDocsToAppSearchImpl(docNumInDest);
        IcingSearchEngineInterface sourceIcing = mVmIcingSearchEngine;

        // 2. Swap icing instances so that we have a reference to the icing instance that we just
        // populated.
        IcingSearchEngineInterface destIcing =
                mAppSearchImpl.swapIcingSearchEngineLocked(sourceIcing, /* isVMEnabled= */ true);

        // 3. Recreate AppSearchImpl with destIcing. This is a weird workaround - AppSearch caches
        // info including schema information. Swapping Icing instances with different schemas (as we
        // do above) makes this cache invalid. So recreating AppSearchImpl will fix the cache
        // mismatch.
        // NOTE: This is not a real issue for migration because migration ensures that the schema in
        // dest is exactly what is cached in AppSearch.
        mAppSearchImpl =
                AppSearchImpl.create(
                        mAppSearchDir,
                        mUnlimitedConfig,
                        /* initStatsBuilder= */ null,
                        /* callStatsBuilder= */ null,
                        /* visibilityChecker= */ null,
                        /* revocableFileDescriptorStore= */ null,
                        /* icingSearchEngine= */ sourceIcing,
                        ALWAYS_OPTIMIZE);

        // 4. Add documents. AppSearchImpl is using srcIcing and will insert docs there.
        insertTestDocsToAppSearchImpl(docNumInSrc);

        // 5. Confirm that destIcing has the expected number of documents.
        SearchSpecProto searchSpec =
                SearchSpecProto.newBuilder()
                        .setQuery("")
                        .setTermMatchType(TermMatchType.Code.PREFIX)
                        .build();
        SearchResultProto searchResultProto =
                destIcing.search(
                        searchSpec,
                        ScoringSpecProto.getDefaultInstance(),
                        ResultSpecProto.newBuilder()
                                .setNumPerPage(docNumInSrc + docNumInDest)
                                .build());
        assertThat(searchResultProto.getResultsCount()).isEqualTo(docNumInDest);

        // 6. Run migration from AppSearchImpl to destIcing.
        DataMigrationStats stats =
                DataMigrationUtil.runDataMigrationForUser(
                        mContext, mUserHandle, mAppSearchImpl, destIcing, /* logger= */ null);

        assertThat(stats.getDataMigrationStatus()).isEqualTo(okStatus);
        assertThat(stats.getVMInitStatus()).isEqualTo(okStatus);
        assertThat(stats.getResetStatus()).isEqualTo(okStatus);
        assertThat(stats.getSetSchemaStatus()).isEqualTo(okStatus);
        assertThat(stats.getFlushStatus()).isEqualTo(okStatus);
        assertThat(stats.getQueryStatus()).isEqualTo(okStatus);
        assertThat(stats.getPutStatus()).asList().containsExactly(okStatus);
        assertThat(stats.getNumberOfDocsSucceeded()).isEqualTo(docNumInSrc);
        assertThat(stats.getNumberOfDocsFailed()).isEqualTo(0);

        // 7. Verify that destIcing now only contains the documents that were previous in srcIcing.
        searchResultProto =
                destIcing.search(
                        searchSpec,
                        ScoringSpecProto.getDefaultInstance(),
                        ResultSpecProto.newBuilder()
                                .setNumPerPage(docNumInDest + docNumInSrc)
                                .build());
        // icing has been reset.
        assertThat(searchResultProto.getResultsCount()).isEqualTo(docNumInSrc);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_BLOB_STORE)
    public void testDataMigrationWithBlob() throws Exception {
        byte[] data = generateRandomBytes(20 * 1024); // 20 KiB
        byte[] digest = calculateDigest(data);
        AppSearchBlobHandle handle =
                AppSearchBlobHandle.createWithSha256(digest, "package", "db1", "ns");
        try (ParcelFileDescriptor writePfd =
                        mAppSearchImpl.openWriteBlob(
                                "package", "db1", handle, /* callStatsbuilder= */ null);
                OutputStream outputStream =
                        new ParcelFileDescriptor.AutoCloseOutputStream(writePfd)) {
            outputStream.write(data);
            outputStream.flush();
        }
        // commit the change and read the blob.
        mAppSearchImpl.commitBlob("package", "db1", handle, /* callStatsBuilder= */ null);

        DataMigrationStats stats =
                DataMigrationUtil.runDataMigrationForUser(
                        mContext,
                        mUserHandle,
                        mAppSearchImpl,
                        mVmIcingSearchEngine,
                        /* logger= */ null);
        assertThat(mAppSearchImpl.isVMEnabled()).isTrue();

        // check stats
        int okStatusCode = StatusProto.Code.OK.getNumber();
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

        byte[] readBytes = new byte[20 * 1024];
        try (ParcelFileDescriptor readPfd =
                        mAppSearchImpl.openReadBlob(
                                "package", "db1", handle, /* callStatsBuilder= */ null);
                InputStream inputStream = new ParcelFileDescriptor.AutoCloseInputStream(readPfd)) {
            inputStream.read(readBytes);
        }

        assertThat(readBytes).isEqualTo(data);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_BLOB_STORE)
    public void testDataMigrationWithDocHasBlob() throws Exception {
        AppSearchSchema blobSchema =
                new AppSearchSchema.Builder("Type")
                        .addProperty(
                                new AppSearchSchema.BlobHandlePropertyConfig.Builder("blob")
                                        .setCardinality(
                                                AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                                        .setDescription("this is a blob.")
                                        .build())
                        .build();
        List<AppSearchSchema> schema = ImmutableList.of(blobSchema);
        InternalSetSchemaResponse internalSetSchemaResponse =
                mAppSearchImpl.setSchema(
                        "package",
                        "db1",
                        schema,
                        /* visibilityConfigs= */ Collections.emptyList(),
                        /* forceOverride= */ false,
                        /* version= */ 0,
                        /* setSchemaStatsBuilder= */ null,
                        /* callStatsBuilder= */ null);
        assertThat(internalSetSchemaResponse.isSuccess()).isTrue();

        byte[] data = generateRandomBytes(20 * 1024); // 20 KiB
        byte[] digest = calculateDigest(data);
        AppSearchBlobHandle handle =
                AppSearchBlobHandle.createWithSha256(digest, "package", "db1", "ns");
        GenericDocument document =
                new GenericDocument.Builder<>("namespace", "id", "Type")
                        .setPropertyBlobHandle("blob", handle)
                        .build();
        List<GenericDocument> docs = new ArrayList<>();
        docs.add(document);
        mAppSearchImpl.batchPutDocuments(
                "package",
                "db1",
                docs,
                /* batchResultBuilder= */ null,
                /* sendChangeNotifications= */ false,
                /* logger= */ null,
                PersistType.Code.LITE,
                /* callStatsBuilder= */ null);

        DataMigrationStats stats =
                DataMigrationUtil.runDataMigrationForUser(
                        mContext,
                        mUserHandle,
                        mAppSearchImpl,
                        mVmIcingSearchEngine,
                        /* logger= */ null);
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
        assertThat(stats.getNumberOfDocsSucceeded()).isEqualTo(1);
        assertThat(stats.getNumberOfDocsFailed()).isEqualTo(0);
        assertThat(stats.getDataMigrationRunCounter()).isEqualTo(1);

        SearchSpecProto searchSpec =
                SearchSpecProto.newBuilder()
                        .setQuery("")
                        .setTermMatchType(TermMatchType.Code.PREFIX)
                        .build();
        SearchResultProto searchResultProto =
                mVmIcingSearchEngine.search(
                        searchSpec,
                        ScoringSpecProto.getDefaultInstance(),
                        ResultSpecProto.newBuilder().setNumPerPage(10).build());
        assertThat(searchResultProto.getResultsCount()).isEqualTo(1);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_SCHEMA_EMBEDDING_PROPERTY_CONFIG)
    public void testDataMigrationEmbeddings() throws Exception {
        populateEmailsWithEmbeddingsInAppSearchImpl(mAppSearchImpl, "package1", "database1");

        // Do data migration.
        DataMigrationStats stats =
                DataMigrationUtil.runDataMigrationForUser(
                        mContext,
                        mUserHandle,
                        mAppSearchImpl,
                        mVmIcingSearchEngine,
                        /* logger= */ null);
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
        assertThat(stats.getNumberOfDocsSucceeded()).isEqualTo(2);
        assertThat(stats.getNumberOfDocsFailed()).isEqualTo(0);
        assertThat(stats.getDataMigrationRunCounter()).isEqualTo(1);

        // Add an embedding search with dot product semantic scores:
        // - document 0: -0.5 (embedding1), 0.3 (embedding2)
        // - document 1: -0.9 (embedding1)
        EmbeddingVector searchEmbedding =
                new EmbeddingVector(new float[] {1, -1, -1, 1, -1}, "my_model_v1");
        SearchSpec searchSpec =
                new SearchSpec.Builder()
                        .setDefaultEmbeddingSearchMetricType(
                                SearchSpec.EMBEDDING_SEARCH_METRIC_TYPE_DOT_PRODUCT)
                        .addEmbeddingParameters(searchEmbedding)
                        .setRankingStrategy(
                                "sum(this.matchedSemanticScores(getEmbeddingParameter(0)))")
                        .setListFilterQueryLanguageEnabled(true)
                        .setResultCountPerPage(10)
                        .build();
        SearchResultPage resultPage =
                mAppSearchImpl.query(
                        "package1",
                        "database1",
                        "semanticSearch(getEmbeddingParameter(0), -1, 1)",
                        searchSpec,
                        /* logger= */ null,
                        /* callStatsBuilder= */ null);
        assertThat(resultPage.getResults()).hasSize(2);
    }

    private void insertTestDocsToAppSearchImpl(int docNum) throws AppSearchException {
        // Insert schema
        List<AppSearchSchema> schemas =
                ImmutableList.of(new AppSearchSchema.Builder("Type1").build());
        InternalSetSchemaResponse internalSetSchemaResponse =
                mAppSearchImpl.setSchema(
                        mContext.getPackageName(),
                        "database1",
                        schemas,
                        /* visibilityConfigs= */ Collections.emptyList(),
                        /* forceOverride= */ false,
                        /* version= */ 0,
                        /* setSchemaStatsBuilder= */ null,
                        /* callStatsBuilder= */ null);

        assertThat(internalSetSchemaResponse.isSuccess()).isTrue();

        // Insert valid docs
        List<GenericDocument> docs = new ArrayList<>();
        for (int i = 0; i < docNum; ++i) {
            GenericDocument doc =
                    new GenericDocument.Builder<>("namespace1", "id" + i, "Type1").build();
            docs.add(doc);
        }

        mAppSearchImpl.batchPutDocuments(
                mContext.getPackageName(),
                "database1",
                docs,
                /* batchResultBuilder= */ null,
                /* sendChangeNotifications= */ false,
                /* logger= */ null,
                PersistType.Code.LITE,
                /* callStatsBuilder= */ null);
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
