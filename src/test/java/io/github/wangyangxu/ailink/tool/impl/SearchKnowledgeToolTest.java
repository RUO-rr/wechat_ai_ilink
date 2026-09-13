package io.github.wangyangxu.ailink.tool.impl;

import io.github.wangyangxu.ailink.rag.KnowledgeRetriever;
import io.github.wangyangxu.ailink.rag.RetrievalResult;
import io.github.wangyangxu.ailink.rag.RetrievedChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchKnowledgeToolTest {

    @Mock
    private KnowledgeRetriever retriever;

    @InjectMocks
    private SearchKnowledgeTool tool;

    private RetrievalResult hit(String context) {
        return new RetrievalResult("q",
                List.of(new RetrievedChunk(1L, 0, "resume-builder/x.md", "x.md", "章节", "内容", 0.9d, "hybrid")),
                context);
    }

    @Test
    void returnsRetrievedContextAsToolResult() {
        when(retriever.retrieve(eq("学生简历怎么写"), anyInt())).thenReturn(hit("[知识库检索结果] 片段内容"));

        String result = tool.execute("{\"query\":\"学生简历怎么写\"}");

        assertTrue(result.contains("[知识库检索结果]"));
        verify(retriever).retrieve("学生简历怎么写", 4);
    }

    @Test
    void clampsTopKIntoAllowedRange() {
        when(retriever.retrieve(anyString(), anyInt())).thenReturn(hit("c"));

        tool.execute("{\"query\":\"规则\",\"top_k\":99}");

        verify(retriever).retrieve("规则", 10);
    }

    @Test
    void emptyResultTellsModelNotToFabricate() {
        when(retriever.retrieve(anyString(), anyInt())).thenReturn(RetrievalResult.empty("q"));

        String result = tool.execute("{\"query\":\"不存在的资料\"}");

        assertTrue(result.contains("没有检索到"));
        assertTrue(result.contains("不要编造"));
    }

    @Test
    void blankQueryIsRejected() {
        String result = tool.execute("{\"query\":\"   \"}");

        assertEquals("检索失败：query 不能为空。", result);
    }

    @Test
    void malformedArgumentsDoNotThrow() {
        String result = tool.execute("not-a-json");

        assertTrue(result.startsWith("知识库检索失败"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void definitionIsGeneralDomainAndNamedSearchKnowledge() {
        assertEquals("search_knowledge", tool.getName());
        assertEquals("general", tool.domain());
        assertEquals("search_knowledge",
                ((Map<String, Object>) tool.getDefinition().get("function")).get("name"));
    }
}