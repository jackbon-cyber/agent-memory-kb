package com.company.bj.agent.memorykb.knowledge.quality;

import java.util.List;

/**
 * 检索质量评测报告。
 *
 * <p>一次评测的整体结论与逐查询明细。整体口径：
 * <ul>
 *   <li>{@code recallAt10} —— 每条查询取 top-10 命中，统计「命中数 ÷ 应命中数」，
 *       整体取<b>总命中数 ÷ 总应命中数</b>（加权口径，而非各查询召回率的算术平均 ——
 *       后者会让「期望切片很少」的查询被过度加权）；</li>
 *   <li>{@code medianLatencyMs} / {@code p95LatencyMs} —— 每条查询的检索耗时
 *       （<b>含嵌入</b>的端到端耗时），中位数反映常态体验，P95 反映尾部延迟；</li>
 *   <li>{@code searchP95LatencyMs} —— <b>只含向量库查询</b>的 P95，
 *       不含远程嵌入往返。</li>
 * </ul>
 *
 * <p><b>为什么额外记一份 searchP95</b>：端到端耗时里，
 * 「远程嵌入模型往返」与「本工程的向量库检索」是两段性质完全不同的开销 ——
 * 前者发生在外部服务上，本工程优化不了；后者才是优化杠杆所在。
 * 只报端到端数字时，P95 超标会得到「延迟太高」这种无从落地的结论；
 * 两份数字并列，才能回答「瓶颈究竟在哪一段」。
 *
 * <p>门禁判定由 {@link QualityGate} 承担，本记录只负责承载事实。
 *
 * @param recallAt10         整体 Recall@10，取值落在 [0, 1]
 * @param queryCount         参与评测的查询条数
 * @param hitCount           命中的应命中切片总数
 * @param expectedCount      应命中切片总数
 * @param medianLatencyMs    检索耗时的中位数（毫秒，含嵌入）
 * @param p95LatencyMs       检索耗时的 P95（毫秒，含嵌入）
 * @param searchP95LatencyMs 只含向量库查询的 P95（毫秒）
 * @param details            逐查询明细
 */
public record RetrievalQualityReport(
    double recallAt10,
    int queryCount,
    int hitCount,
    int expectedCount,
    double medianLatencyMs,
    double p95LatencyMs,
    double searchP95LatencyMs,
    List<QueryDetail> details) {

    /**
     * 规范化明细列表，保证下游拿到的明细永不为 {@code null}。
     */
    public RetrievalQualityReport {
        details = details == null ? List.of() : List.copyOf(details);
    }

    /**
     * 单条查询的评测明细。
     *
     * @param query         查询文本
     * @param hitCount      该查询命中的应命中切片数
     * @param expectedCount 该查询的应命中切片总数
     * @param recall        该查询的召回率，取值落在 [0, 1]
     * @param latencyMs     该查询的检索耗时（毫秒，含嵌入）
     * @param searchMs      该查询的向量库查询耗时（毫秒，不含嵌入）
     */
    public record QueryDetail(
        String query,
        int hitCount,
        int expectedCount,
        double recall,
        double latencyMs,
        double searchMs) {
    }
}
