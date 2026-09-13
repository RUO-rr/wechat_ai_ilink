---
title: LangChain4j 文档：EmbeddingStore 与 EmbeddingStoreIngestor（灌库与检索）
source: https://docs.langchain4j.dev/tutorials/rag
fetched: 2026-09-14
lang: en
---
### Embedding Store
The `EmbeddingStore` interface represents a store for `Embedding`s, also known as vector database.
It allows for the storage and efficient search of similar (close in the embedding space) `Embedding`s.

Currently supported embedding stores can be found [here](/integrations/embedding-stores).

`EmbeddingStore` can store `Embedding`s alone or together with the corresponding `TextSegment`:
- It can store only `Embedding`, by ID. Original embedded data can be stored elsewhere and correlated using the ID.
- It can store both `Embedding` and the original data that has been embedded (usually `TextSegment`).

<details>
<summary>Useful methods</summary>

- `EmbeddingStore.add(Embedding)` adds a given `Embedding` to the store and returns a random ID
- `EmbeddingStore.add(String id, Embedding)` adds a given `Embedding` with a specified ID to the store
- `EmbeddingStore.add(Embedding, TextSegment)` adds a given `Embedding` with an associated `TextSegment` to the store and returns a random ID
- `EmbeddingStore.addAll(List<Embedding>)` adds a list of given `Embedding`s to the store and returns a list of random IDs
- `EmbeddingStore.addAll(List<Embedding>, List<TextSegment>)` adds a list of given `Embedding`s with associated `TextSegment`s to the store and returns a list of random IDs
- `EmbeddingStore.addAll(List<String> ids, List<Embedding>, List<TextSegment>)` adds a list of given `Embedding`s with associated IDs and `TextSegment`s to the store
- `EmbeddingStore.search(EmbeddingSearchRequest)` searches for the most similar `Embedding`s
- `EmbeddingStore.remove(String id)` removes a single `Embedding` from the store by ID
- `EmbeddingStore.removeAll(Collection<String> ids)` removes all `Embedding`s from the store whose IDs are present in the given collection.
- `EmbeddingStore.removeAll(Filter)` removes all `Embedding`s that match the specified `Filter` from the store
- `EmbeddingStore.removeAll()` removes all `Embedding`s from the store
</details>


####  EmbeddingSearchRequest
The `EmbeddingSearchRequest` represents a request to search in an `EmbeddingStore`.
It has the following attributes:
- `Embedding queryEmbedding`: The embedding used as a reference.
- `int maxResults`: The maximum number of results to return. This is an optional parameter. Default: 3.
- `double minScore`: The minimum score, ranging from 0 to 1 (inclusive). Only embeddings with a score >= `minScore` will be returned. This is an optional parameter. Default: 0.
- `Filter filter`: The filter to be applied to the `Metadata` during search. Only `TextSegment`s whose `Metadata` matches the `Filter` will be returned.

#### Filter
The `Filter` allows filtering by `Metadata` entries when performing a vector search.

Currently, the following `Filter` types/operations are supported:
-  `IsEqualTo`
-  `IsNotEqualTo`
-  `IsGreaterThan`
-  `IsGreaterThanOrEqualTo`
-  `IsLessThan`
-  `IsLessThanOrEqualTo`
-  `IsIn`
-  `IsNotIn`
-  `ContainsString`
-  `And`
-  `Not`
-  `Or`

:::note
Not all embedding stores support filtering by `Metadata`,
please see the "Filtering by Metadata" column [here](https://docs.langchain4j.dev/integrations/embedding-stores/).

Some stores that support filtering by `Metadata` do not support all possible `Filter` types/operations.
For example, `ContainsString` is currently supported only by Milvus, PgVector and Qdrant.
:::

More details about `Filter` can be found [here](https://github.com/langchain4j/langchain4j/pull/610).


#### EmbeddingSearchResult
The `EmbeddingSearchResult` represents a result of a search in an `EmbeddingStore`.
It contains the list of `EmbeddingMatch`es.


#### Embedding Match
The `EmbeddingMatch` represents a matched `Embedding` along with its relevance score, ID, and original embedded data (usually `TextSegment`).


### Embedding Store Ingestor
The `EmbeddingStoreIngestor` represents an ingestion pipeline and is responsible for 
ingesting `Document`s into an `EmbeddingStore`.

In the simplest configuration, `EmbeddingStoreIngestor` embeds provided `Document`s
using a specified `EmbeddingModel` and stores them, along with their `Embedding`s in a specified `EmbeddingStore`:

```java
EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
        .embeddingModel(embeddingModel)
        .embeddingStore(embeddingStore)
        .build();

ingestor.ingest(document1);
ingestor.ingest(document2, document3);
IngestionResult ingestionResult = ingestor.ingest(List.of(document4, document5, document6));
```

All `ingest()` methods in `EmbeddingStoreIngestor` return an `IngestionResult`.
The `IngestionResult` contains useful information, including `TokenUsage`,
which shows how many tokens were used for embedding.

Optionally, the `EmbeddingStoreIngestor` can transform `Document`s using a specified `DocumentTransformer`.
This can be useful if you want to clean, enrich, or format `Document`s before embedding them.

Optionally, the `EmbeddingStoreIngestor` can split `Document`s into `TextSegment`s using a specified `DocumentSplitter`.
This can be useful if `Document`s are big, and you want to split them into smaller `TextSegment`s to improve the quality
of similarity searches and reduce the size and cost of a prompt sent to the LLM.

Optionally, the `EmbeddingStoreIngestor` can transform `TextSegment`s using a specified `TextSegmentTransformer`.
This can be useful if you want to clean, enrich, or format `TextSegment`s before embedding them.

An example:
```java
EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()

    // adding userId metadata entry to each Document to be able to filter by it later
    .documentTransformer(document -> {
        document.metadata().put("userId", "12345");
        return document;
    })

    // splitting each Document into TextSegments of 1000 tokens each, with a 200-token overlap

> （以上为原文节选，完整内容见 source 链接。）
