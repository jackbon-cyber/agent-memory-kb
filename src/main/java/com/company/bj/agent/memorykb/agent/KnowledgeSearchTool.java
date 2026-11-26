package com.company.bj.agent.memorykb.agent;

import com.company.bj.agent.memorykb.knowledge.FilterCondition;
import com.company.bj.agent.memorykb.knowledge.FilterLogic;
import com.company.bj.agent.memorykb.knowledge.KnowledgeRetriever;
import com.company.bj.agent.memorykb.knowledge.model.RetrievedChunk;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 知识库检索工具。
 *
 * <p>把平台的 {@link KnowledgeRetriever} 端口暴露给智能体：
 * 模型给出查询文本（可选给出元数据过滤条件），工具返回可供阅读的命中片段，
 * 并把命中的切片记录进 {@link CitationCollector} 作为回答的溯源凭据。
 *
 * <p><b>不使用 AgentScope 自带的 {@code KnowledgeRetrievalTools}</b>，原因有三：
 * <ul>
 *   <li>它的检索最终落到 {@code VDBStoreBase.search}，而该 SPI 的
 *       {@code SearchDocumentDto} <b>没有 where 过滤字段</b>，
 *       无法承载「按元数据过滤」这一核心需求；</li>
 *   <li>它需要 {@code SimpleKnowledge} + {@code EmbeddingModel} 才能工作，
 *       而本工程的嵌入走自建的 {@code OpenAiCompatibleEmbeddingService}
 *       （带向量归一化与 TTL 缓存），绕一圈反而多一份向量口径；</li>
 *   <li>它<b>不返回结构化命中</b>，引用无法从模型文本反推 ——
 *       本工具在命中时直接把切片交给收集器。</li>
 * </ul>
 *
 * <p><b>作用域说明</b>：知识库集合是<b>平台共享</b>的，不按作用域隔离 ——
 * 隔离只作用于四层记忆。因此本工具无需作用域。
 * 需要按租户/主体隔离的语义检索走长期记忆工具（{@link LongTermMemoryTool}）。
 */
public class KnowledgeSearchTool {

    /** 默认返回条数。 */
    public static final int DEFAULT_LIMIT = 5;

    /** 返回条数上限，防止模型一次拉取过多片段挤占上下文。 */
    public static final int MAX_LIMIT = 20;

    /** 命中为空时给模型的提示文本。 */
    private static final String NO_HIT_MESSAGE = "知识库中未找到相关内容。";

    /** 元数据过滤字段：来源文件名。 */
    private static final String METADATA_FILE_NAME = "file_name";

    /** 元数据过滤字段：文档标识。 */
    private static final String METADATA_DOCUMENT_ID = "document_id";

    private final KnowledgeRetriever retriever;

    /**
     * 构造知识库检索工具。
     *
     * @param retriever 平台检索端口
     */
    public KnowledgeSearchTool(KnowledgeRetriever retriever) {
        this.retriever = retriever;
    }

    /**
     * 在知识库中做语义检索。
     *
     * <p><b>工具执行线程与提问线程不同</b>：AgentScope 把工具调用调度到
     * {@code boundedElastic} 线程上，因此本方法<b>不依赖 ThreadLocal</b>。
     * 引用收集器通过 {@code RuntimeContext.extras} 送达 ——
     * 该通道已实测确认可跨线程读取。
     *
     * @param query      查询文本
     * @param limit      返回条数上限，缺省为 {@value #DEFAULT_LIMIT}
     * @param fileName   可选的来源文件名过滤，缺省不过滤
     * @param documentId 可选的文档标识过滤，缺省不过滤
     * @param context    运行时上下文，由 AgentScope 自动注入
     * @return 供模型阅读的命中片段文本
     */
    @Tool(name = "search_knowledge",
        description = "检索企业知识库并返回相关片段。回答用户问题前应先调用本工具，"
            + "检索结果才是事实依据；可选用 file_name / document_id 按元数据过滤。")
    public String searchKnowledge(
        @ToolParam(name = "query", description = "检索用的查询文本") String query,
        @ToolParam(name = "limit", description = "返回片段数量上限，默认 5",
            required = false) Integer limit,
        @ToolParam(name = "file_name", description = "可选的来源文件名过滤条件",
            required = false) String fileName,
        @ToolParam(name = "document_id", description = "可选的文档标识过滤条件",
            required = false) String documentId,
        RuntimeContext context) {
        if (query == null || query.isBlank()) {
            return NO_HIT_MESSAGE;
        }

        List<RetrievedChunk> hits = retriever.retrieve(
            query,
            buildFilters(fileName, documentId),
            FilterLogic.AND,
            resolveLimit(limit),
            null);

        collectCitations(context, hits);
        return formatHits(hits);
    }

    /**
     * 构造元数据过滤条件。
     *
     * <p>缺省的过滤字段不参与查询 —— 传入空值条件会让 Chroma 的 where 表达式
     * 语义不明确，因此直接不构造该条件。
     *
     * @param fileName   来源文件名过滤值，可为 {@code null}
     * @param documentId 文档标识过滤值，可为 {@code null}
     * @return 过滤条件列表，无有效条件时为空列表
     */
    private List<FilterCondition> buildFilters(String fileName, String documentId) {
        List<FilterCondition> filters = new ArrayList<>(2);
        if (fileName != null && !fileName.isBlank()) {
            filters.add(new FilterCondition(METADATA_FILE_NAME, FilterCondition.OPERATOR_EQ, fileName));
        }
        if (documentId != null && !documentId.isBlank()) {
            filters.add(new FilterCondition(METADATA_DOCUMENT_ID, FilterCondition.OPERATOR_EQ, documentId));
        }
        return filters;
    }

    /**
     * 解析返回条数上限。
     *
     * @param limit 模型给出的条数，可为 {@code null}
     * @return 落在 {@code [1, MAX_LIMIT]} 区间内的条数
     */
    private int resolveLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /**
     * 把命中切片记录进引用收集器。
     *
     * @param context 运行时上下文，可为 {@code null}
     * @param hits    命中的切片列表
     */
    private void collectCitations(RuntimeContext context, List<RetrievedChunk> hits) {
        if (context == null || hits == null || hits.isEmpty()) {
            return;
        }
        Object collector = context.getExtra().get(AgentContextKeys.CITATION_COLLECTOR);
        if (collector instanceof CitationCollector citationCollector) {
            citationCollector.addAll(hits);
        }
    }

    /**
     * 把命中切片格式化为模型可读的文本。
     *
     * <p>每条片段带上来源文件名与字符区间，使模型能在回答里给出可追溯的引用。
     *
     * @param hits 命中的切片列表
     * @return 格式化文本，无命中时返回空结果提示
     */
    private String formatHits(List<RetrievedChunk> hits) {
        if (hits == null || hits.isEmpty()) {
            return NO_HIT_MESSAGE;
        }
        StringBuilder builder = new StringBuilder(256);
        builder.append("检索到 ").append(hits.size()).append(" 条相关片段：\n\n");
        for (int i = 0; i < hits.size(); i++) {
            RetrievedChunk hit = hits.get(i);
            builder.append('[').append(i + 1).append("] 来源=")
                .append(fileNameOf(hit))
                .append(" 相似度=")
                .append(String.format(Locale.ROOT, "%.4f", hit.score()))
                .append('\n');
            builder.append(hit.content()).append('\n');
        }
        return builder.toString();
    }

    /**
     * 取出命中切片的来源文件名。
     *
     * @param hit 命中切片
     * @return 来源文件名，缺失时返回占位文本
     */
    private String fileNameOf(RetrievedChunk hit) {
        Object fileName = hit.metadata().get(METADATA_FILE_NAME);
        return fileName == null ? "未知来源" : fileName.toString();
    }
}
