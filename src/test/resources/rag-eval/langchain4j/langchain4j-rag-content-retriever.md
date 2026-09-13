---
title: LangChain4j 文档：ContentRetriever、QueryRouter 与 ContentAggregator
source: https://docs.langchain4j.dev/tutorials/rag
fetched: 2026-09-14
lang: en
---
### Content Retriever
`ContentRetriever` retrieves `Content`s from an underlying data source using a given `Query`.
The underlying data source can be virtually anything:
- Embedding store
- Full-text search engine
- Hybrid of vector and full-text search
- Web Search Engine
- Knowledge graph
- SQL database
- etc.

The list of `Content` returned by `ContentRetriever` is ordered by relevance, from highest to lowest.

#### Embedding Store Content Retriever
`EmbeddingStoreContentRetriever` retrieves relevant `Content` from the `EmbeddingStore` using
the `EmbeddingModel` to embed the `Query`.

Here is an example:
```java
EmbeddingStore embeddingStore = ...
EmbeddingModel embeddingModel = ...

ContentRetriever contentRetriever = EmbeddingStoreContentRetriever.builder()
    .embeddingStore(embeddingStore)
    .embeddingModel(embeddingModel)
    .maxResults(3)
     // maxResults can also be specified dynamically depending on the query
    .dynamicMaxResults(query -> 3)
    .minScore(0.75)
     // minScore can also be specified dynamically depending on the query
    .dynamicMinScore(query -> 0.75)
    .filter(metadataKey("userId").isEqualTo("12345"))
    // filter can also be specified dynamically depending on the query
    .dynamicFilter(query -> {
        String userId = query.metadata().invocationParameters().get("userId");
        return metadataKey("userId").isEqualTo(userId);
    })
    .build();

interface Assistant {
    String chat(@UserMessage String userMessage, InvocationParameters parameters);
}

InvocationParameters parameters = InvocationParameters.from(Map.of("userId", "12345"));
String response = assistant.chat("Hello", parameters);
```

To embed the query with `input_type=query` (pairing with the ingestor's `DOCUMENT`, see
[Query vs Document Embeddings](#query-vs-document-embeddings-opt-in)), set the input type on the retriever:

```java
ContentRetriever contentRetriever = EmbeddingStoreContentRetriever.builder()
    .embeddingStore(embeddingStore)
    .embeddingModel(embeddingModel)
    .embeddingInputType(EmbeddingInputType.QUERY)
    .build();
```

The default is unchanged (no input type is sent). The `EmbeddingModel` must support the `input_type` parameter.

#### Web Search Content Retriever
`WebSearchContentRetriever` retrieves relevant `Content` from the web using a `WebSearchEngine`.

All supported `WebSearchEngine` integrations can be [found here](/category/web-search-engines).

Here is an example:
```java
WebSearchEngine googleSearchEngine = GoogleCustomWebSearchEngine.builder()
        .apiKey(System.getenv("GOOGLE_API_KEY"))
        .csi(System.getenv("GOOGLE_SEARCH_ENGINE_ID"))
        .build();

ContentRetriever contentRetriever = WebSearchContentRetriever.builder()
        .webSearchEngine(googleSearchEngine)
        .maxResults(3)
        .build();
```
Complete example can be found [here](https://github.com/langchain4j/langchain4j-examples/blob/main/rag-examples/src/main/java/_3_advanced/_08_Advanced_RAG_Web_Search_Example.java).

#### SQL Database Content Retriever
`SqlDatabaseContentRetriever` is an experimental implementation of the `ContentRetriever`
that can be found in the `langchain4j-experimental-sql` module.

It uses the `DataSource` and an LLM to generate and execute SQL queries
for given natural language `Query`.

See javadoc of the `SqlDatabaseContentRetriever` for more information.

Here is an [example](https://github.com/langchain4j/langchain4j-examples/blob/main/rag-examples/src/main/java/_3_advanced/_10_Advanced_RAG_SQL_Database_Retreiver_Example.java).

#### Azure AI Search Content Retriever
`AzureAiSearchContentRetriever` is an integration with
[Azure AI Search](https://azure.microsoft.com/en-us/products/ai-services/ai-search).
It supports full-text, vector, and hybrid search, as well as re-ranking. 
It can be found in the `langchain4j-azure-ai-search` module.
Please refer to the `AzureAiSearchContentRetriever` Javadoc for more information.

#### Neo4j Content Retriever
`Neo4jContentRetriever` is an integration with the [Neo4j](https://neo4j.com/) graph database.
It converts natural language queries into Neo4j Cypher queries
and retrieves relevant information by running these queries in Neo4j.
It can be found in the `langchain4j-community-neo4j-retriever` module.

#### Elasticsearch Content Retriever
`ElasticsearchContentRetriever` is an integration with
[Elasticsearch](https://www.elastic.co/elasticsearch).
It supports full-text, vector, and hybrid search.
It can be found in the `langchain4j-elasticsearch` module.
Please refer to the `ElasticsearchContentRetriever` Javadoc for more information.

### Query Router
`QueryRouter` is responsible for routing `Query` to the appropriate `ContentRetriever`(s).

#### Default Query Router
`DefaultQueryRouter` is the default implementation used in `DefaultRetrievalAugmentor`.
It routes each `Query` to all configured `ContentRetriever`s.

#### Language Model Query Router
`LanguageModelQueryRouter` uses the LLM to decide where to route the given `Query`.

### Content Aggregator
The `ContentAggregator` is responsible for aggregating multiple ranked lists of `Content` from:
- multiple `Query`s
- multiple `ContentRetriever`s
- both

#### Default Content Aggregator
The `DefaultContentAggregator` is the default implementation of `ContentAggregator`,
which performs two-stage Reciprocal Rank Fusion (RRF).

> （以上为原文节选，完整内容见 source 链接。）
