---
title: LangChain4j 文档：RAG 是什么 —— 索引与检索两个阶段、Easy RAG、核心 API
source: https://docs.langchain4j.dev/tutorials/rag
fetched: 2026-09-14
lang: en
---
## What is RAG?
Simply put, RAG is the way to find and inject relevant pieces of information from your data
into the prompt before sending it to the LLM.
This way LLM will get (hopefully) relevant information and will be able to reply using this information,
which should reduce the probability of hallucinations.

Relevant pieces of information can be found using various
[information retrieval](https://en.wikipedia.org/wiki/Information_retrieval) methods.
The most popular are:
- Full-text (keyword) search. This method uses techniques like TF-IDF and BM25
to search documents by matching the keywords in a query (e.g., what the user is asking)
against a database of documents.
It ranks results based on the frequency and relevance of these keywords in each document.
- Vector search, also known as "semantic search".
Text documents are converted into vectors of numbers using embedding models.
It then finds and ranks documents based on the cosine similarity
or other similarity/distance measures between the query vector and document vectors,
thus capturing deeper semantic meanings.
- Hybrid. Combining multiple search methods (e.g., full-text + vector) usually improves the effectiveness of the search.

Currently, this page focuses mostly on vector search.
Full-text and hybrid search are currently supported only by Azure AI Search integration and Elasticsearch,
see `AzureAiSearchContentRetriever` and `ElasticsearchContentRetriever` for more details.
We plan to expand the RAG toolbox to include full-text and hybrid search in the near future.


## RAG Stages
The RAG process is divided into 2 distinct stages: indexing and retrieval.
LangChain4j provides tooling for both stages.

### Indexing

During the indexing stage, documents are pre-processed in a way that enables efficient search during the retrieval stage.

This process can vary depending on the information retrieval method used.
For vector search, this typically involves cleaning the documents, enriching them with additional data and metadata,
splitting them into smaller segments (aka chunking), embedding these segments, and finally storing them in an embedding store (aka vector database).

The indexing stage usually occurs offline, meaning it does not require end users to wait for its completion.
This can be achieved through, for example, a cron job that re-indexes internal company documentation once a week during the weekend.
The code responsible for indexing can also be a separate application that only handles indexing tasks.

However, in some scenarios, end users may want to upload their custom documents to make them accessible to the LLM.
In this case, indexing should be performed online and be a part of the main application.

Here is a simplified diagram of the indexing stage:
![](/img/rag-ingestion.png)


### Retrieval

The retrieval stage usually occurs online, when a user submits a question that should be answered using the indexed documents.

This process can vary depending on the information retrieval method used.
For vector search, this typically involves embedding the user's query (question)
and performing a similarity search in the embedding store.
Relevant segments (pieces of the original documents) are then injected into the prompt and sent to the LLM.

Here is a simplified diagram of the retrieval stage:
![](/img/rag-retrieval.png)


## RAG Flavours in LangChain4j

LangChain4j offers three flavors of RAG:
- [Easy RAG](/tutorials/rag/#easy-rag): the easiest way to start with RAG
- [Naive RAG](/tutorials/rag/#naive-rag): a basic implementation of RAG using vector search
- [Advanced RAG](/tutorials/rag/#advanced-rag): a modular RAG framework that allows for additional steps such as
query transformation, retrieval from multiple sources, and re-ranking


## Easy RAG
LangChain4j has an "Easy RAG" feature that makes it as easy as possible to get started with RAG.
You don't have to learn about embeddings, choose a vector store, find the right embedding model,
figure out how to parse and split documents, etc.
Just point to your document(s), and LangChain4j will do its magic.

If you need a customizable RAG, skip to the [next section](/tutorials/rag#core-rag-apis).

If you are using Quarkus, there is an even easier way to do Easy RAG.
Please read [Quarkus documentation](https://docs.quarkiverse.io/quarkus-langchain4j/dev/rag-easy-rag.html).

:::note
The quality of such "Easy RAG" will, of course, be lower than that of a tailored RAG setup.
However, this is the easiest way to start learning about RAG and/or make a proof of concept.
Later, you will be able to transition smoothly from Easy RAG to more advanced RAG,
adjusting and customizing more and more aspects.
:::

1. Import the `langchain4j-easy-rag` dependency:
```xml
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-easy-rag</artifactId>
    <version>1.20.0-beta30</version>
</dependency>
```

2. Let's load your documents:
```java
List<Document> documents = FileSystemDocumentLoader.loadDocuments("/home/langchain4j/documentation");
```
This will load all files from the specified directory.

<details>
<summary>What is happening under the hood?</summary>

The Apache Tika library, which supports a wide variety of document types,
is used to detect document types and parse them.
Since we did not explicitly specify which `DocumentParser` to use,
the `FileSystemDocumentLoader` will load an `ApacheTikaDocumentParser`,
provided by `langchain4j-easy-rag` dependency through SPI.
</details>

<details>

> （以上为原文节选，完整内容见 source 链接。）
