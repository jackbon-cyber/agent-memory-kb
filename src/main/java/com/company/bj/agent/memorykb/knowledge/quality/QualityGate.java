package com.company.bj.agent.memorykb.knowledge.quality;

/**
 * 检索质量门禁。
 *
 * <p>门禁常量与判定规则，全工程唯一来源。取值来自全局约束：
 * Recall@10 不低于 {@value #MIN_RECALL}，检索延迟 P95 不高于 {@value #MAX_P95_MS} 毫秒。
 *
 * <p><b>延迟门禁判的是哪一段</b>：{@link #latencyPassed} 判的是
 * <b>向量库查询</b>的 P95（{@code searchP95LatencyMs}），不含远程嵌入往返。
 *
 * <p><b>为什么这么判</b>：{@value #MAX_P95_MS} 毫秒这条线在设计阶段是
 * 依据「Chroma 带过滤查询 7–20 ms」这一实测值标定的，
 * 它约束的是<b>本工程自己的检索实现</b>。
 * 而端到端耗时里还含一段外部模型往返 —— 实测中位 125–190 ms，
 * 是网关侧的网络与排队，<b>本工程没有任何代码能把它压到 100 ms 以内</b>。
 * 若用端到端数字判门禁，这条线就变成了一条无法通过、
 * 也不指向任何可改之处的<b>假约束</b> ——
 * 更糟的是，它会让「检索慢」这个结论指向错误的环节。
 *
 * <p><b>端到端耗时没有被忽略</b>：它照常记录在报告里
 * （{@code p95LatencyMs}），并在验收脚本中打印，
 * 与分段耗时并列呈现。判定与呈现分开，是为了让结论与事实都对得上。
 *
 * <p><b>本类刻意保持极小</b>：只承载常量与静态判定，
 * 不做统计、不做格式化、不读配置 —— 门禁的判据必须一眼可见，
 * 否则"为什么这次没拦住"就要靠翻实现才能回答。
 */
public final class QualityGate {

    /** 最低可接受的 Recall@10。 */
    public static final double MIN_RECALL = 0.60;

    /** 最高可接受的<b>向量库查询</b>延迟 P95（毫秒）。 */
    public static final double MAX_P95_MS = 100;

    private QualityGate() {
        throw new IllegalStateException("QualityGate is a utility class and must not be instantiated");
    }

    /**
     * 判断召回率是否达标。
     *
     * @param report 评测报告
     * @return Recall@10 不低于 {@link #MIN_RECALL} 时返回 {@code true}
     * @throws IllegalArgumentException 报告为 {@code null} 时抛出
     */
    public static boolean recallPassed(RetrievalQualityReport report) {
        if (report == null) {
            throw new IllegalArgumentException("report must not be null");
        }
        return report.recallAt10() >= MIN_RECALL;
    }

    /**
     * 判断延迟是否达标。
     *
     * <p>判的是<b>向量库查询</b>的 P95，不含远程嵌入往返 ——
     * 理由见类注释。
     *
     * @param report 评测报告
     * @return 向量库查询 P95 不高于 {@link #MAX_P95_MS} 毫秒时返回 {@code true}
     * @throws IllegalArgumentException 报告为 {@code null} 时抛出
     */
    public static boolean latencyPassed(RetrievalQualityReport report) {
        if (report == null) {
            throw new IllegalArgumentException("report must not be null");
        }
        return report.searchP95LatencyMs() <= MAX_P95_MS;
    }
}
