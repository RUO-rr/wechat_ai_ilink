---
title: LangChain4j 文档：TextSegment、DocumentSplitter 与 TextSegmentTransformer
source: https://docs.langchain4j.dev/tutorials/rag
fetched: 2026-09-14
lang: en
---
### Text Segment
Once your `Document`s are loaded, it is time to split (chunk) them into smaller segments (pieces).
LangChain4j's domain model includes a `TextSegment` class that represents a segment of a `Document`.
As the name suggests, `TextSegment` can represent only textual information.

<details>
<summary>To split or not to split?</summary>

There are several reasons why you might want to include only a few relevant segments
instead of the entire knowledge base in the prompt:
- LLMs have a limited context window, so the entire knowledge base might not fit
- The more information you provide in the prompt, the longer it takes for the LLM to process it and respond
- The more information you provide in the prompt, the more you pay
- Irrelevant information in the prompt might distract the LLM and increase the chance of hallucinations
- The more information you provide in the prompt, the harder it is to explain based on which information the LLM responded

We can address these concerns by splitting a knowledge base into smaller, more digestible segments.
How big should those segments be? That is a good question. As always, it depends.

There are currently 2 widely used approaches:
1. Each document (e.g., a PDF file, a web page, etc.) is atomic and indivisible.
During retrieval in the RAG pipeline, the N most relevant documents are retrieved and injected into the prompt.
You will most probably need to use a long-context LLM in this case since documents can be quite long.
This approach is suitable if retrieving complete documents is important,
such as when you can't afford to miss some details.
- Pros: No context is lost.
- Cons:
  - More tokens are consumed.
  - Sometimes, documents can contain multiple sections/topics, and not all of them are relevant to the query.
  - Vector search quality suffers because complete documents of various sizes are compressed into a single, fixed-length vector.

2. Documents are split into smaller segments, such as chapters, paragraphs, or sometimes even sentences.
During retrieval in the RAG pipeline, the N most relevant segments are retrieved and injected into the prompt.
The challenge lies in ensuring each segment provides sufficient context/information for the LLM to understand it.
Missing context can lead to the LLM misinterpreting the given segment and hallucinating.
A common strategy is to split documents into segments with overlap, but this doesn't completely solve the problem.
Several advanced techniques can help here, for example, "sentence window retrieval", "auto-merging retrieval",
and "parent document retrieval".
We won't go into details here, but essentially, these methods help to fetch more context around the retrieved segments,
providing the LLM with additional information before and after the retrieved segment.
- Pros:
  - Better quality of vector search.
  - Reduced token consumption.
- Cons: Some context may still be lost.

</details>

<details>
<summary>Useful methods</summary>

- `TextSegment.text()` returns the text of the `TextSegment`
- `TextSegment.metadata()` returns the `Metadata` of the `TextSegment`
- `TextSegment.from(String, Metadata)` creates a `TextSegment` from text and `Metadata`
- `TextSegment.from(String)` creates a `TextSegment` from text with empty `Metadata`
</details>

### Document Splitter
LangChain4j has a `DocumentSplitter` interface with several out-of-the-box implementations:
- `DocumentByParagraphSplitter`
- `DocumentByLineSplitter`
- `DocumentBySentenceSplitter`
- `DocumentByWordSplitter`
- `DocumentByCharacterSplitter`
- `DocumentByRegexSplitter`
- Recursive: `DocumentSplitters.recursive(...)`

They all work as follows:
1. You instantiate a `DocumentSplitter`, specifying the desired size of `TextSegment`s and,
optionally, an overlap in characters or tokens.
2. You call the `split(Document)` or `splitAll(List<Document>)` methods of the `DocumentSplitter`.
3. The `DocumentSplitter` splits the given `Document`s into smaller units,
the nature of which varies with the splitter. For instance, `DocumentByParagraphSplitter` divides
a document into paragraphs (defined by two or more consecutive newline characters),
while `DocumentBySentenceSplitter` uses the OpenNLP library's sentence detector to split
a document into sentences, and so on.
4. The `DocumentSplitter` then combines these smaller units (paragraphs, sentences, words, etc.) into `TextSegment`s,
attempting to include as many units as possible in a single `TextSegment` without exceeding the limit set in step 1.
If some of the units are still too large to fit into a `TextSegment`, it calls a sub-splitter.
This is another `DocumentSplitter` capable of splitting units that do not fit into more granular units.
All `Metadata` entries are copied from the `Document` to each `TextSegment`.
A unique metadata entry "index" is added to each text segment.
The first `TextSegment` will contain `index=0`, the second `index=1`, and so on.


### Text Segment Transformer
`TextSegmentTransformer` is similar to `DocumentTransformer` (described above), but it transforms `TextSegment`s.

As with the `DocumentTransformer`, there is no one-size-fits-all solution,
so we recommend implementing your own `TextSegmentTransformer`, tailored to your unique data.

One technique that works quite well for improving retrieval is to include the `Document` title or a short summary
in each `TextSegment`.


### Embedding
The `Embedding` class encapsulates a numerical vector that represents the "semantic meaning"
of the content that has been embedded (usually text, such as a `TextSegment`).

Read more about vector embeddings here:
- https://www.elastic.co/what-is/vector-embedding
- https://www.pinecone.io/learn/vector-embeddings/
- https://cloud.google.com/blog/topics/developers-practitioners/meet-ais-multitool-vector-embeddings

<details>

> （以上为原文节选，完整内容见 source 链接。）
