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
package android.app.appsearch;

import static android.app.appsearch.testutil.AppSearchTestUtils.checkIsBatchResultSuccess;
import static android.app.appsearch.testutil.AppSearchTestUtils.convertSearchResultsToDocuments;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.appsearch.AppSearchSchema.PropertyConfig;
import android.app.appsearch.AppSearchSchema.StringPropertyConfig;
import android.app.appsearch.testutil.AppSearchSessionShimImpl;
import android.content.Context;
import android.os.SystemClock;
import android.platform.test.annotations.RequiresFlagsEnabled;

import androidx.annotation.NonNull;
import androidx.test.core.app.ApplicationProvider;

import com.android.appsearch.flags.Flags;

import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListenableFuture;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;

public class AppSearchSessionInternalTest extends AppSearchSessionInternalTestBase {

    // Based on {@link GenericDocument.PARENT_TYPES_SYNTHETIC_PROPERTY}
    private static final String PARENT_TYPES_SYNTHETIC_PROPERTY = "$$__AppSearch__parentTypes";
    private final Context mContext = ApplicationProvider.getApplicationContext();

    @Override
    protected ListenableFuture<AppSearchSessionShim> createSearchSessionAsync(
            @NonNull String dbName) {
        return AppSearchSessionShimImpl.createSearchSessionAsync(
                new AppSearchManager.SearchContext.Builder(dbName).build());
    }

    @Override
    protected ListenableFuture<AppSearchSessionShim> createSearchSessionAsync(
            @NonNull String dbName, @NonNull ExecutorService executor) {
        return AppSearchSessionShimImpl.createSearchSessionAsync(
                mContext, new AppSearchManager.SearchContext.Builder(dbName).build(), executor);
    }

    // TODO(b/371610934): Remove this test once GenericDocument#setParentTypes is removed.
    @Override
    @Test
    @SuppressWarnings("deprecation")
    public void testQuery_genericDocumentWrapsParentTypeForPolymorphism() throws Exception {
        assumeTrue(mDb1.getFeatures().isFeatureSupported(Features.SCHEMA_ADD_PARENT_TYPE));
        // When SearchResult does not wrap parent information, GenericDocument should do.
        assumeFalse(mDb1.getFeatures().isFeatureSupported(Features.SEARCH_RESULT_PARENT_TYPES));

        // Schema registration
        AppSearchSchema personSchema =
                new AppSearchSchema.Builder("Person")
                        .addProperty(
                                new StringPropertyConfig.Builder("name")
                                        .setCardinality(PropertyConfig.CARDINALITY_REQUIRED)
                                        .setTokenizerType(StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                                        .setIndexingType(
                                                StringPropertyConfig.INDEXING_TYPE_PREFIXES)
                                        .build())
                        .build();
        AppSearchSchema artistSchema =
                new AppSearchSchema.Builder("Artist")
                        .addParentType("Person")
                        .addProperty(
                                new StringPropertyConfig.Builder("name")
                                        .setCardinality(PropertyConfig.CARDINALITY_REQUIRED)
                                        .setTokenizerType(StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                                        .setIndexingType(
                                                StringPropertyConfig.INDEXING_TYPE_PREFIXES)
                                        .build())
                        .addProperty(
                                new StringPropertyConfig.Builder("company")
                                        .setCardinality(PropertyConfig.CARDINALITY_REQUIRED)
                                        .setTokenizerType(StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                                        .setIndexingType(
                                                StringPropertyConfig.INDEXING_TYPE_PREFIXES)
                                        .build())
                        .build();
        AppSearchSchema musicianSchema =
                new AppSearchSchema.Builder("Musician")
                        .addParentType("Artist")
                        .addProperty(
                                new StringPropertyConfig.Builder("name")
                                        .setCardinality(PropertyConfig.CARDINALITY_REQUIRED)
                                        .setTokenizerType(StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                                        .setIndexingType(
                                                StringPropertyConfig.INDEXING_TYPE_PREFIXES)
                                        .build())
                        .addProperty(
                                new StringPropertyConfig.Builder("company")
                                        .setCardinality(PropertyConfig.CARDINALITY_REQUIRED)
                                        .setTokenizerType(StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                                        .setIndexingType(
                                                StringPropertyConfig.INDEXING_TYPE_PREFIXES)
                                        .build())
                        .build();
        AppSearchSchema messageSchema =
                new AppSearchSchema.Builder("Message")
                        .addProperty(
                                new AppSearchSchema.DocumentPropertyConfig.Builder(
                                                "receivers", "Person")
                                        .setCardinality(PropertyConfig.CARDINALITY_REPEATED)
                                        .setShouldIndexNestedProperties(true)
                                        .build())
                        .build();
        mDb1.setSchemaAsync(
                        new SetSchemaRequest.Builder()
                                .addSchemas(personSchema)
                                .addSchemas(artistSchema)
                                .addSchemas(musicianSchema)
                                .addSchemas(messageSchema)
                                .build())
                .get();

        // Index documents
        GenericDocument personDoc =
                new GenericDocument.Builder<>("namespace", "id1", "Person")
                        .setCreationTimestampMillis(1000)
                        .setPropertyString("name", "person")
                        .build();
        GenericDocument artistDoc =
                new GenericDocument.Builder<>("namespace", "id2", "Artist")
                        .setCreationTimestampMillis(1000)
                        .setPropertyString("name", "artist")
                        .setPropertyString("company", "foo")
                        .build();
        GenericDocument musicianDoc =
                new GenericDocument.Builder<>("namespace", "id3", "Musician")
                        .setCreationTimestampMillis(1000)
                        .setPropertyString("name", "musician")
                        .setPropertyString("company", "foo")
                        .build();
        GenericDocument messageDoc =
                new GenericDocument.Builder<>("namespace", "id4", "Message")
                        .setCreationTimestampMillis(1000)
                        .setPropertyDocument("receivers", artistDoc, musicianDoc)
                        .build();
        checkIsBatchResultSuccess(
                mDb1.putAsync(
                        new PutDocumentsRequest.Builder()
                                .addGenericDocuments(personDoc, artistDoc, musicianDoc, messageDoc)
                                .build()));
        GenericDocument artistDocWithParent =
                new GenericDocument.Builder<>(artistDoc)
                        .setPropertyString(PARENT_TYPES_SYNTHETIC_PROPERTY, "Person")
                        .build();
        GenericDocument musicianDocWithParent =
                new GenericDocument.Builder<>(musicianDoc)
                        .setPropertyString(PARENT_TYPES_SYNTHETIC_PROPERTY, "Artist", "Person")
                        .build();
        GenericDocument messageDocWithParent =
                new GenericDocument.Builder<>("namespace", "id4", "Message")
                        .setCreationTimestampMillis(1000)
                        .setPropertyDocument(
                                "receivers", artistDocWithParent, musicianDocWithParent)
                        .build();

        // Query to get all the documents
        SearchResultsShim searchResults =
                mDb1.search(
                        "",
                        new SearchSpec.Builder()
                                .setTermMatch(SearchSpec.TERM_MATCH_EXACT_ONLY)
                                .build());
        List<GenericDocument> documents = convertSearchResultsToDocuments(searchResults);
        assertThat(documents)
                .containsExactly(
                        personDoc,
                        artistDocWithParent,
                        musicianDocWithParent,
                        messageDocWithParent);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_SCHEMAS_WIPEOUT_ACCOUNT_PROPERTY_PATHS)
    public void testWipeoutAccount_remove() throws Exception {
        AccountManager accountManager = AccountManager.get(mContext);
        Account account =
                new Account(MockAccountService.ACCOUNT_NAME, MockAccountService.ACCOUNT_TYPE);
        accountManager.addAccountExplicitly(account, null, null);
        Account[] accounts = accountManager.getAccountsByType(MockAccountService.ACCOUNT_TYPE);
        assertThat(accounts).asList().containsExactly(account);
        AppSearchSchema email =
                new AppSearchSchema.Builder("Email")
                        .addProperty(
                                new AppSearchSchema.DocumentPropertyConfig.Builder(
                                                "account", AppSearchAccount.SCHEMA_TYPE)
                                        .setCardinality(
                                                AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                                        .setShouldIndexNestedProperties(true)
                                        .build())
                        .build();

        SetSchemaRequest request =
                new SetSchemaRequest.Builder()
                        .addSchemas(email, AppSearchAccount.SCHEMA)
                        .setSchemaTypeWipeoutAccountPropertyPaths(
                                "Email",
                                ImmutableSet.of(new PropertyPath("account")),
                                /* autoWipeout= */ true)
                        .build();

        mDb1.setSchemaAsync(request).get();

        // Put a documents with account property, should pass.
        GenericDocument document =
                new GenericDocument.Builder<>("namespace", "id1", "Email")
                        .setPropertyDocument(
                                "account",
                                new AppSearchAccount.Builder("namespace", "account1")
                                        .setAccountId("accountId")
                                        .setAccountType(MockAccountService.ACCOUNT_TYPE)
                                        .setAccountName(MockAccountService.ACCOUNT_NAME)
                                        .build())
                        .build();
        // Wait for the AppSearch background thread to receive account updates.
        // Use a simple polling mechanism: check every 100ms, for up to 2 seconds.
        AppSearchBatchResult<String, Void> putResult = null;
        for (int i = 0; i < 20; i++) {
            putResult =
                    mDb1.putAsync(
                                    new PutDocumentsRequest.Builder()
                                            .addGenericDocuments(document)
                                            .build())
                            .get();
            if (putResult.isSuccess()) {
                break;
            }

            // Wait 0.1 second to invoke on account update listener.
            SystemClock.sleep(100);
        }
        assertTrue(putResult.isSuccess());

        AppSearchBatchResult<String, GenericDocument> getResult =
                mDb1.getByDocumentIdAsync(
                                new GetByDocumentIdRequest.Builder("namespace")
                                        .addIds("id1")
                                        .build())
                        .get();
        assertTrue(getResult.isSuccess());
        assertThat(getResult.getSuccesses()).hasSize(1);
        assertThat(getResult.getSuccesses().get("id1")).isEqualTo(document);

        accountManager.removeAccountExplicitly(account);
        accounts = accountManager.getAccountsByType(MockAccountService.ACCOUNT_TYPE);
        assertThat(accounts).isEmpty();

        // Wait for the AppSearch background thread to finish data pruning.
        // Use a simple polling mechanism: check every 100ms, for up to 5 seconds.
        for (int i = 0; i < 50; i++) {
            getResult =
                    mDb1.getByDocumentIdAsync(
                                    new GetByDocumentIdRequest.Builder("namespace")
                                            .addIds("id1")
                                            .build())
                            .get();
            // Verify the reference document is removed.
            if (!getResult.isSuccess()
                    && getResult.getFailures().containsKey("id1")
                    && getResult.getFailures().get("id1").getResultCode()
                            == AppSearchResult.RESULT_NOT_FOUND) {
                break;
            }
            // Wait 0.1 second to invoke on account update listener.
            SystemClock.sleep(100);
        }
        assertThat(getResult.isSuccess()).isFalse();
        assertThat(getResult.getFailures()).containsKey("id1");
        assertThat(getResult.getFailures().get("id1").getResultCode())
                .isEqualTo(AppSearchResult.RESULT_NOT_FOUND);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_SCHEMAS_WIPEOUT_ACCOUNT_PROPERTY_PATHS)
    public void testWipeoutAccount_rename() throws Exception {
        AccountManager accountManager = AccountManager.get(mContext);
        Account account =
                new Account(MockAccountService.ACCOUNT_NAME, MockAccountService.ACCOUNT_TYPE);
        accountManager.addAccountExplicitly(account, null, null);
        Account[] accounts = accountManager.getAccountsByType(MockAccountService.ACCOUNT_TYPE);
        assertThat(accounts).asList().containsExactly(account);
        AppSearchSchema email =
                new AppSearchSchema.Builder("Email")
                        .addProperty(
                                new AppSearchSchema.DocumentPropertyConfig.Builder(
                                                "account", AppSearchAccount.SCHEMA_TYPE)
                                        .setCardinality(
                                                AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                                        .setShouldIndexNestedProperties(true)
                                        .build())
                        .build();

        SetSchemaRequest request =
                new SetSchemaRequest.Builder()
                        .addSchemas(email, AppSearchAccount.SCHEMA)
                        .setSchemaTypeWipeoutAccountPropertyPaths(
                                "Email",
                                ImmutableSet.of(new PropertyPath("account")),
                                /* autoWipeout= */ true)
                        .build();

        mDb1.setSchemaAsync(request).get();

        // Put a documents with account property, should pass.
        GenericDocument document =
                new GenericDocument.Builder<>("namespace", "id1", "Email")
                        .setPropertyDocument(
                                "account",
                                new AppSearchAccount.Builder("namespace", "account1")
                                        .setAccountId("accountId")
                                        .setAccountType(MockAccountService.ACCOUNT_TYPE)
                                        .setAccountName(MockAccountService.ACCOUNT_NAME)
                                        .build())
                        .build();

        // Wait for the AppSearch background thread to receive account updates.
        // Use a simple polling mechanism: check every 100ms, for up to 2 seconds.
        AppSearchBatchResult<String, Void> putResult = null;
        for (int i = 0; i < 20; i++) {
            putResult =
                    mDb1.putAsync(
                                    new PutDocumentsRequest.Builder()
                                            .addGenericDocuments(document)
                                            .build())
                            .get();
            if (putResult.isSuccess()) {
                break;
            }

            // Wait 0.1 second to invoke on account update listener.
            SystemClock.sleep(100);
        }
        assertTrue(putResult.isSuccess());

        // Verify the document
        AppSearchBatchResult<String, GenericDocument> getResult =
                mDb1.getByDocumentIdAsync(
                                new GetByDocumentIdRequest.Builder("namespace")
                                        .addIds("id1")
                                        .build())
                        .get();
        assertTrue(getResult.isSuccess());
        assertThat(getResult.getSuccesses()).hasSize(1);
        assertThat(getResult.getSuccesses().get("id1")).isEqualTo(document);

        // Rename account, the document should remain.
        accountManager
                .renameAccount(account, "newName", /* callback= */ null, /* handler= */ null)
                .getResult();
        Account renamedAccount = new Account("newName", MockAccountService.ACCOUNT_TYPE);
        accounts = accountManager.getAccountsByType(MockAccountService.ACCOUNT_TYPE);
        assertThat(accounts).asList().containsExactly(renamedAccount);

        // TODO(b/413089233) find a better way to verify the listener is invoked for rename than
        //  sleep. An unchanged state is ambiguous: it could mean the listener was never triggered,
        //  or it was triggered but failed to perform the expected action (not to removing the
        //  account). It means we may miss catch issues in the listener.
        getResult =
                mDb1.getByDocumentIdAsync(
                                new GetByDocumentIdRequest.Builder("namespace")
                                        .addIds("id1")
                                        .build())
                        .get();
        assertTrue(getResult.isSuccess());
        assertThat(getResult.getSuccesses()).hasSize(1);
        assertThat(getResult.getSuccesses().get("id1")).isEqualTo(document);

        // Remove the renamed account, the document should be removed.
        accountManager.removeAccountExplicitly(renamedAccount);
        accounts = accountManager.getAccountsByType(MockAccountService.ACCOUNT_TYPE);
        assertThat(accounts).isEmpty();

        // Wait for the AppSearch background thread to finish data pruning.
        // Use a simple polling mechanism: check every 100ms, for up to 5 seconds.
        for (int i = 0; i < 50; i++) {
            getResult =
                    mDb1.getByDocumentIdAsync(
                                    new GetByDocumentIdRequest.Builder("namespace")
                                            .addIds("id1")
                                            .build())
                            .get();
            // Verify the reference document is removed.
            if (!getResult.isSuccess()
                    && getResult.getFailures().containsKey("id1")
                    && getResult.getFailures().get("id1").getResultCode()
                            == AppSearchResult.RESULT_NOT_FOUND) {
                break;
            }
            // Wait 0.1 second to invoke on account update listener.
            SystemClock.sleep(100);
        }
        assertThat(getResult.isSuccess()).isFalse();
        assertThat(getResult.getFailures()).containsKey("id1");
        assertThat(getResult.getFailures().get("id1").getResultCode())
                .isEqualTo(AppSearchResult.RESULT_NOT_FOUND);
    }
}
