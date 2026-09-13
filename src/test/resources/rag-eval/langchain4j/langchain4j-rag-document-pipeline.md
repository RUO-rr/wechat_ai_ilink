---
title: LangChain4j 文档：RAG 文档管线（Document / Parser / Transformer / Splitter / TextSegment）
source: https://docs.langchain4j.dev/tutorials/rag
fetched: 2026-09-14
lang: en
---
### Document
A `Document` class represents an entire document, such as a single PDF file or a web page.
At the moment, the `Document` can only represent textual information,
but future updates will enable it to support images and tables as well.

<details>
<summary>Useful methods</summary>

- `Document.text()` returns the text of the `Document`
- `Document.metadata()` returns the `Metadata` of the `Document` (see "Metadata" section below)
- `Document.toTextSegment()` converts the `Document` into a `TextSegment` (see "TextSegment" section below)
- `Document.from(String, Metadata)` creates a `Document` from text and `Metadata`
- `Document.from(String)` creates a `Document` from text with empty `Metadata`
</details>

### Metadata
Each `Document` contains `Metadata`.
It stores meta information about the `Document`, such as its name, source, last update date, owner,
or any other relevant details.

The `Metadata` is stored as a key-value map, where the key is of the `String` type,
and the value can be one of the following types: `String`, `Integer`, `Long`, `Float`, `Double`, `UUID`.

`Metadata` is useful for several reasons:
- When including the content of the `Document` in a prompt to the LLM,
metadata entries can also be included, providing the LLM with additional information to consider.
For example, providing the `Document` name and source can help improve the LLM's understanding of the content.
- When searching for relevant content to include in the prompt,
one can filter by `Metadata` entries.
For example, you can narrow down a semantic search to only `Document`s
belonging to a specific owner.
- When the source of the `Document` is updated (for example, a specific page of documentation),
one can easily locate the corresponding `Document` by its metadata entry (for example, "id", "source", etc.)
and update it in the `EmbeddingStore` as well to keep it in sync.

<details>
<summary>Useful methods</summary>

- `Metadata.from(Map)` creates `Metadata` from a `Map`
- `Metadata.put(String key, String value)` / `put(String, int)` / etc., adds an entry to the `Metadata`
- `Metadata.putAll(Map)` adds multiple entries to the `Metadata`
- `Metadata.getString(String key)` / `getInteger(String key)` / etc., returns a value of the `Metadata` entry, casting it to the required type
- `Metadata.containsKey(String key)` checks whether `Metadata` contains an entry with the specified key
- `Metadata.remove(String key)` removes an entry from the `Metadata` by key
- `Metadata.copy()` returns a copy of the `Metadata`
- `Metadata.toMap()` converts `Metadata` into a `Map`
- `Metadata.merge(Metadata)` merges the current `Metadata` with another `Metadata`
</details>

### Document Loader
You can create a `Document` from a `String`, but a simpler method is to use one of our document loaders included in the library:
- `FileSystemDocumentLoader` from the `langchain4j` module
- `ClassPathDocumentLoader` from the `langchain4j` module
- `UrlDocumentLoader` from the `langchain4j` module
- `AmazonS3DocumentLoader` from the `langchain4j-document-loader-amazon-s3` module
- `AzureBlobStorageDocumentLoader` from the `langchain4j-document-loader-azure-storage-blob` module
- `GitHubDocumentLoader` from the `langchain4j-document-loader-github` module
- `GoogleCloudStorageDocumentLoader` from the `langchain4j-document-loader-google-cloud-storage` module
- `SeleniumDocumentLoader` from the `langchain4j-document-loader-selenium` module
- `PlaywrightDocumentLoader` from the `langchain4j-document-loader-playwright` module
- `TencentCosDocumentLoader` from the `langchain4j-document-loader-tencent-cos` module


### Document Parser
`Document`s can represent files in various formats, such as PDF, DOC, TXT, etc.
To parse each of these formats, there's a `DocumentParser` interface with several implementations included in the library:
- `TextDocumentParser` from the `langchain4j` module, which can parse files in plain text format (e.g. TXT, HTML, MD, etc.)
- `ApachePdfBoxDocumentParser` from the `langchain4j-document-parser-apache-pdfbox` module, which can parse PDF files
- `ApachePoiDocumentParser` from the `langchain4j-document-parser-apache-poi` module, which can parse MS Office file formats
(e.g. DOC, DOCX, PPT, PPTX, XLS, XLSX, etc.)
- `ApacheTikaDocumentParser` from the `langchain4j-document-parser-apache-tika` module,
which can automatically detect and parse almost all existing file formats
- `DoclingDocumentParser` from the `langchain4j-document-parser-docling` module, 
  which uses [Docling Java](https://docling-project.github.io/docling-java/current/) and [Docling](https://docling.ai) to process documents.
- `MarkdownDocumentParser` from the `langchain4j-document-parser-markdown` module,
  which can parse files in markdown format
- `YamlDocumentParser` from the `langchain4j-document-parser-yaml` module,
  which can parse files in yaml format

Here is an example of how to load one or multiple `Document`s from the file system:
```java
// Load a single document
Document document = FileSystemDocumentLoader.loadDocument("/home/langchain4j/file.txt", new TextDocumentParser());

// Load all documents from a directory
List<Document> documents = FileSystemDocumentLoader.loadDocuments("/home/langchain4j", new TextDocumentParser());

// Load all *.txt documents from a directory
PathMatcher pathMatcher = FileSystems.getDefault().getPathMatcher("glob:*.txt");
List<Document> documents = FileSystemDocumentLoader.loadDocuments("/home/langchain4j", pathMatcher, new TextDocumentParser());

// Load all documents from a directory and its subdirectories
List<Document> documents = FileSystemDocumentLoader.loadDocumentsRecursively("/home/langchain4j", new TextDocumentParser());

> （以上为原文节选，完整内容见 source 链接。）
