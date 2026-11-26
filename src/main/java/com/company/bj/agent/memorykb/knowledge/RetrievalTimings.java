package com.company.bj.agent.memorykb.knowledge;

/**
 * 一次检索的<b>分相耗时</b>。
 *
 * <p>把检索耗时拆成「查询向量化」与「向量库查询」两段，
 * 目的是让延迟数据能被<b>归因</b>：
 * <ul>
 *   <li>{@code embeddingMs} —— 把查询文本发给嵌入模型并拿回向量的耗时。
 *       这一段发生在<b>外部模型服务</b>上，本工程无法通过代码优化它；
 *       唯一可控的手段是缓存（见 {@code CachedEmbeddingService}）；</li>
 *   <li>{@code searchMs} —— 向量库完成相似度检索的耗时。
 *       这一段发生在<b>本工程部署的 Chroma</b> 上，是优化杠杆真正所在
 *       （索引、集合规模、过滤条件复杂度）。</li>
 * </ul>
 *
 * <p><b>为什么必须分开</b>：把两段合成一个数字时，
 * 「P95 是 172 ms」既不能说明检索慢，也不能说明检索快 ——
 * 它主要反映的是远程模型往返。
 * 只有分开，才能得出「向量库检索 9.5 ms达标，模型往返 190 ms 是瓶颈」这样的
 * <b>可行动结论</b>，而不是一句「优化一下延迟」。
 *
 * @param embeddingMs 查询向量化的耗时（毫秒）
 * @param searchMs    向量库查询的耗时（毫秒）
 */
public record RetrievalTimings(double embeddingMs, double searchMs) {

    /**
     * 两段之和，即端到端耗时。
     *
     * @return 端到端耗时（毫秒）
     */
    public double totalMs() {
        return embeddingMs + searchMs;
    }
}
