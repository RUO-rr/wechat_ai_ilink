---
title: LangChain4j 文档：Naive RAG 与 Advanced RAG（RetrievalAugmentor、Query、QueryTransformer）
source: https://docs.langchain4j.dev/tutorials/rag
fetched: 2026-09-14
lang: en
---
## Naive RAG

Once our documents are ingested (see previous sections), we can create
an `EmbeddingStoreContentRetriever` to enable naive RAG functionality.

When using [AI Services](/tutorials/ai-services), naive RAG can be configured as follows:
```java
ContentRetriever contentRetriever = EmbeddingStoreContentRetriever.builder()
    .embeddingStore(embeddingStore)
    .embeddingModel(embeddingModel)
    .maxResults(5)
    .minScore(0.75)
    .build();

Assistant assistant = AiServices.builder(Assistant.class)
    .chatModel(model)
    .contentRetriever(contentRetriever)
    .build();
```

[Naive RAG Example](https://github.com/langchain4j/langchain4j-examples/blob/main/rag-examples/src/main/java/_2_naive/Naive_RAG_Example.java)


## Advanced RAG

Advanced RAG can be implemented with LangChain4j with the following core components:
- `QueryTransformer`
- `QueryRouter`
- `ContentRetriever`
- `ContentAggregator`
- `ContentInjector`

The following diagram shows how these components work together:
![](/img/advanced-rag.png)

The process is as follows:
1. The user produces a `UserMessage`, which is converted into a `Query`
2. The `QueryTransformer` transforms the `Query` into one or multiple `Query`s
3. Each `Query` is routed by the `QueryRouter` to one or more `ContentRetriever`s
4. Each `ContentRetriever` retrieves relevant `Content`s for each `Query`
5. The `ContentAggregator` combines all retrieved `Content`s into a single final ranked list
6. This list of `Content`s is injected into the original `UserMessage`
7. Finally, the `UserMessage`, containing the original query along with the injected relevant content, is sent to the LLM

Please refer to the Javadoc of each component for more details.

### Retrieval Augmentor

`RetrievalAugmentor` is an entry point into the RAG pipeline.
It is responsible for augmenting a `ChatMessage` with relevant `Content`s
retrieved from various sources.

An instance of a `RetrievalAugmentor` can be specified during the creation of an [AI Service](/tutorials/ai-services):
```java
Assistant assistant = AiServices.builder(Assistant.class)
    ...
    .retrievalAugmentor(retrievalAugmentor)
    .build();
```
Every time an AI Service is invoked, the specified `RetrievalAugmentor`
will be called to augment the current `UserMessage`.

You can use the default implementation of a `RetrievalAugmentor`
(described below) or implement a custom one.

### Default Retrieval Augmentor

LangChain4j provides an out-of-the-box implementation of the `RetrievalAugmentor` interface:
`DefaultRetrievalAugmentor`, which should be suitable for the majority of RAG use cases.
It was inspired by [this article](https://blog.langchain.dev/deconstructing-rag)
and [this paper](https://arxiv.org/abs/2312.10997).
It is recommended to review these resources for a better understanding of the concept.

### Query
`Query` represents a user query in the RAG pipeline.
It contains the text of the query and query metadata.

#### Query Metadata
The `Metadata` inside the `Query` contains information that might be useful in various components
of the RAG pipeline, for example:
- `Metadata.userMessage()` - the original `UserMessage` that should be augmented
- `Metadata.chatMemoryId()` - the value of a `@MemoryId`-annotated method parameter. More details [here](/tutorials/ai-services/#chat-memory). This can be used to identify the user and apply access restrictions or filters during the retrieval.
- `Metadata.chatMemory()` - all previous `ChatMessage`s. This can help to understand the context in which the `Query` was asked.
- `Metadata.invocationParameters()` - contains `InvocationParameters` that can be specified when invoking AI Service:

```java
interface Assistant {
    String chat(@UserMessage String userMessage, InvocationParameters parameters);
}

InvocationParameters parameters = InvocationParameters.from(Map.of("userId", "12345"));
String response = assistant.chat("Hello", parameters);
```

`InvocationParameters` can also be accessed within other AI Service components, such as:
- [`@Tool`-annotated method](/tutorials/tools#invocationparameters)
- [`ToolProvider`](/tutorials/tools#specifying-tools-dynamically): inside the `ToolProviderRequest`
- [`ToolArgumentsErrorHandler`](/tutorials/tools#handling-tool-arguments-errors)
  and [`ToolExecutionErrorHandler`](https://docs.langchain4j.dev/tutorials/tools#handling-tool-execution-errors):
  inside the `ToolErrorContext`

Parameters are stored in a mutable, thread safe `Map`.

Data can be passed between AI Service components inside the `InvocationParameters`
(for example, from one RAG component to another or from a RAG component to a tool)
during a single invocation of the AI Service.

### Query Transformer
`QueryTransformer` transforms the given `Query` into one or multiple `Query`s.
The goal is to enhance retrieval quality by modifying or expanding the original `Query`.

Some known approaches to improve retrieval include:
- Query compression
- Query expansion
- Query re-writing
- Step-back prompting
- Hypothetical document embeddings (HyDE)

More details can be found [here](https://blog.langchain.dev/query-transformations/).

LangChain4j also has an optional community [Prompt Repetition](/integrations/prompt-repetition/) module that provides `RepeatingQueryTransformer`. It repeats the retrieval query before content retrieval and should be used to transform the query itself, not the final augmented prompt sent to the model.

#### Default Query Transformer
`DefaultQueryTransformer` is the default implementation used in `DefaultRetrievalAugmentor`.
It does not make any modifications to the `Query`, it just passes it through.

#### Compressing Query Transformer
`CompressingQueryTransformer` uses an LLM to compress the given `Query`
and previous conversation into a standalone `Query`.
This is useful when the user might ask follow-up questions that refer to information

> （以上为原文节选，完整内容见 source 链接。）
