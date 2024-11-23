/*
 * Copyright 2023-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.ai.vectorstore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.core.io.Resource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SimpleVectorStoreTests {

	@TempDir(cleanup = CleanupMode.ON_SUCCESS)
	Path tempDir;

	private SimpleVectorStore vectorStore;

	private EmbeddingModel mockEmbeddingModel;

	@BeforeEach
	void setUp() {
		mockEmbeddingModel = mock(EmbeddingModel.class);
		when(mockEmbeddingModel.dimensions()).thenReturn(3);
		when(mockEmbeddingModel.embed(any(String.class))).thenReturn(new float[] { 0.1f, 0.2f, 0.3f });
		when(mockEmbeddingModel.embed(any(Document.class))).thenReturn(new float[] { 0.1f, 0.2f, 0.3f });

		vectorStore = new SimpleVectorStore(mockEmbeddingModel);
	}

	@Test
	void shouldAddAndRetrieveDocument() {
		Document doc = Document.builder()
			.withId("1")
			.withContent("test content")
			.withMetadata(Map.of("key", "value"))
			.build();

		vectorStore.add(List.of(doc));

		List<Document> results = vectorStore.similaritySearch("test content");
		assertThat(results).hasSize(1).first().satisfies(result -> {
			assertThat(result.getId()).isEqualTo("1");
			assertThat(result.getContent()).isEqualTo("test content");
			assertThat(result.getMetadata()).containsEntry("key", "value");
		});
	}

	@Test
	void shouldAddMultipleDocuments() {
		List<Document> docs = Arrays.asList(Document.builder().withId("1").withContent("first").build(),
				Document.builder().withId("2").withContent("second").build());

		vectorStore.add(docs);

		List<Document> results = vectorStore.similaritySearch("first");
		assertThat(results).hasSize(2).extracting(Document::getId).containsExactlyInAnyOrder("1", "2");
	}

	@Test
	void shouldHandleEmptyDocumentList() {
		assertThatThrownBy(() -> vectorStore.add(Collections.emptyList())).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Documents list cannot be empty");
	}

	@Test
	void shouldHandleNullDocumentList() {
		assertThatThrownBy(() -> vectorStore.add(null)).isInstanceOf(NullPointerException.class)
			.hasMessage("Documents list cannot be null");
	}

	@Test
	void shouldDeleteDocuments() {
		Document doc = Document.builder().withId("1").withContent("test content").build();

		vectorStore.add(List.of(doc));
		assertThat(vectorStore.similaritySearch("test")).hasSize(1);

		vectorStore.delete(List.of("1"));
		assertThat(vectorStore.similaritySearch("test")).isEmpty();
	}

	@Test
	void shouldHandleDeleteOfNonexistentDocument() {
		vectorStore.delete(List.of("nonexistent-id"));
		// Should not throw exception and return true
		assertThat(vectorStore.delete(List.of("nonexistent-id")).get()).isTrue();
	}

	@Test
	void shouldPerformSimilaritySearchWithThreshold() {
		// Configure mock to return different embeddings for different queries
		when(mockEmbeddingModel.embed("query")).thenReturn(new float[] { 0.9f, 0.9f, 0.9f });

		Document doc = Document.builder().withId("1").withContent("test content").build();

		vectorStore.add(List.of(doc));

		SearchRequest request = SearchRequest.query("query").withSimilarityThreshold(0.99f).withTopK(5);

		List<Document> results = vectorStore.similaritySearch(request);
		assertThat(results).isEmpty();
	}

	@Test
	void shouldSaveAndLoadVectorStore() throws IOException {
		Document doc = Document.builder()
			.withId("1")
			.withContent("test content")
			.withMetadata(new HashMap<>(Map.of("key", "value")))
			.build();

		vectorStore.add(List.of(doc));

		File saveFile = tempDir.resolve("vector-store.json").toFile();
		vectorStore.save(saveFile);

		SimpleVectorStore loadedStore = new SimpleVectorStore(mockEmbeddingModel);
		loadedStore.load(saveFile);

		List<Document> results = loadedStore.similaritySearch("test content");
		assertThat(results).hasSize(1).first().satisfies(result -> {
			assertThat(result.getId()).isEqualTo("1");
			assertThat(result.getContent()).isEqualTo("test content");
			assertThat(result.getMetadata()).containsEntry("key", "value");
		});
	}

	@Test
	void shouldHandleLoadFromInvalidResource() throws IOException {
		Resource mockResource = mock(Resource.class);
		when(mockResource.getInputStream()).thenThrow(new IOException("Resource not found"));

		assertThatThrownBy(() -> vectorStore.load(mockResource)).isInstanceOf(RuntimeException.class)
			.hasCauseInstanceOf(IOException.class)
			.hasMessageContaining("Resource not found");
	}

	@Test
	void shouldHandleSaveToInvalidLocation() {
		File invalidFile = new File("/invalid/path/file.json");

		assertThatThrownBy(() -> vectorStore.save(invalidFile)).isInstanceOf(RuntimeException.class)
			.hasCauseInstanceOf(IOException.class);
	}

	@Test
	void shouldHandleConcurrentOperations() throws InterruptedException {
		int numThreads = 10;
		Thread[] threads = new Thread[numThreads];

		for (int i = 0; i < numThreads; i++) {
			final String id = String.valueOf(i);
			threads[i] = new Thread(() -> {
				Document doc = Document.builder().withId(id).withContent("content " + id).build();
				vectorStore.add(List.of(doc));
			});
			threads[i].start();
		}

		for (Thread thread : threads) {
			thread.join();
		}

		SearchRequest request = SearchRequest.query("test").withTopK(numThreads);

		List<Document> results = vectorStore.similaritySearch(request);

		assertThat(results).hasSize(numThreads);

		// Verify all documents were properly added
		Set<String> resultIds = results.stream().map(Document::getId).collect(Collectors.toSet());

		Set<String> expectedIds = new java.util.HashSet<>();
		for (int i = 0; i < numThreads; i++) {
			expectedIds.add(String.valueOf(i));
		}

		assertThat(resultIds).containsExactlyInAnyOrderElementsOf(expectedIds);

		// Verify content integrity
		results.forEach(doc -> assertThat(doc.getContent()).isEqualTo("content " + doc.getId()));
	}

	@Test
	void shouldRejectInvalidSimilarityThreshold() {
		assertThatThrownBy(() -> SearchRequest.query("test").withSimilarityThreshold(2.0f))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Similarity threshold must be in [0,1] range.");
	}

	@Test
	void shouldRejectNegativeTopK() {
		assertThatThrownBy(() -> SearchRequest.query("test").withTopK(-1)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("TopK should be positive.");
	}

	@Test
	void shouldHandleCosineSimilarityEdgeCases() {
		float[] zeroVector = new float[] { 0f, 0f, 0f };
		float[] normalVector = new float[] { 1f, 1f, 1f };

		assertThatThrownBy(() -> SimpleVectorStore.EmbeddingMath.cosineSimilarity(zeroVector, normalVector))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Vectors cannot have zero norm");
	}

	@Test
	void shouldHandleVectorLengthMismatch() {
		float[] vector1 = new float[] { 1f, 2f };
		float[] vector2 = new float[] { 1f, 2f, 3f };

		assertThatThrownBy(() -> SimpleVectorStore.EmbeddingMath.cosineSimilarity(vector1, vector2))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Vectors lengths must be equal");
	}

	@Test
	void shouldHandleNullVectors() {
		float[] vector = new float[] { 1f, 2f, 3f };

		assertThatThrownBy(() -> SimpleVectorStore.EmbeddingMath.cosineSimilarity(null, vector))
			.isInstanceOf(RuntimeException.class)
			.hasMessage("Vectors must not be null");

		assertThatThrownBy(() -> SimpleVectorStore.EmbeddingMath.cosineSimilarity(vector, null))
			.isInstanceOf(RuntimeException.class)
			.hasMessage("Vectors must not be null");
	}

}