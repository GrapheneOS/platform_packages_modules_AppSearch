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

import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import android.app.appsearch.AppSearchSchema.PropertyConfig;
import android.app.appsearch.AppSearchSchema.StringPropertyConfig;
import android.app.appsearch.testutil.AppSearchSessionShimImpl;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.test.core.app.ApplicationProvider;

import com.google.common.util.concurrent.ListenableFuture;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;

public class AppSearchSessionInternalTest extends AppSearchSessionInternalTestBase {

    // Based on {@link GenericDocument.PARENT_TYPES_SYNTHETIC_PROPERTY}
    private static final String PARENT_TYPES_SYNTHETIC_PROPERTY = "$$__AppSearch__parentTypes";

    @Override
    protected ListenableFuture<AppSearchSessionShim> createSearchSessionAsync(
            @NonNull String dbName) {
        return AppSearchSessionShimImpl.createSearchSessionAsync(
                new AppSearchManager.SearchContext.Builder(dbName).build());
    }

    @Override
    protected ListenableFuture<AppSearchSessionShim> createSearchSessionAsync(
            @NonNull String dbName, @NonNull ExecutorService executor) {
        Context context = ApplicationProvider.getApplicationContext();
        return AppSearchSessionShimImpl.createSearchSessionAsync(
                context, new AppSearchManager.SearchContext.Builder(dbName).build(), executor);
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
}
