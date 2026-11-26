package com.company.bj.agent.memorykb.chroma;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 校验 {@link ChromaQueryResultReader} 的结果解析与得分换算。
 *
 * <p>本类是 {@code ChromaStore} 与 {@code ChromaKnowledgeRetriever} 共用的解析器，
 * 因此这里的断言同时约束了两个消费者的行为 ——
 * 得分公式只有一份实现，不会出现两个入口给出不同得分的情况。
 */
class ChromaQueryResultReaderTest {

    /** 浮点比较容差。 */
    private static final org.assertj.core.data.Offset<Double> OFFSET =
        org.assertj.core.data.Offset.offset(1e-9);

    /**
     * 构造一份命中结果。
     *
     * @param ids       命中记录 id
     * @param documents 命中文本
     * @param metadatas 命中元数据
     * @param distances 命中距离
     * @return 检索结果
     */
    private ChromaQueryResult result(List<String> ids, List<String> documents,
        List<Map<String, Object>> metadatas, List<Double> distances) {
        return new ChromaQueryResult(List.of(ids), List.of(documents), List.of(metadatas), List.of(distances));
    }

    @Test
    void 命中条数取自第一行() {
        ChromaQueryResult result = result(List.of("r1", "r2"), List.of("a", "b"),
            List.of(Map.of(), Map.of()), List.of(0.1, 0.2));

        assertThat(ChromaQueryResultReader.hitCount(result)).isEqualTo(2);
    }

    @Test
    void 响应缺项时命中条数为零() {
        ChromaQueryResult empty = new ChromaQueryResult(null, null, null, null);
        ChromaQueryResult noRow = new ChromaQueryResult(List.of(), List.of(), List.of(), List.of());

        assertThat(ChromaQueryResultReader.hitCount(empty)).isZero();
        assertThat(ChromaQueryResultReader.hitCount(noRow)).isZero();
    }

    @Test
    void 读取命中的各字段() {
        ChromaQueryResult result = result(List.of("r1"), List.of("命中内容"),
            List.of(Map.of("file_name", "手册.txt")), List.of(0.25));

        assertThat(ChromaQueryResultReader.idAt(result, 0)).isEqualTo("r1");
        assertThat(ChromaQueryResultReader.documentAt(result, 0)).isEqualTo("命中内容");
        assertThat(ChromaQueryResultReader.metadataAt(result, 0)).containsEntry("file_name", "手册.txt");
        assertThat(ChromaQueryResultReader.distanceAt(result, 0)).isEqualTo(0.25);
    }

    @Test
    void 字段缺失时返回安全的空值() {
        ChromaQueryResult result = result(List.of("r1"), List.of(), List.of(), List.of());

        assertThat(ChromaQueryResultReader.documentAt(result, 0)).isEmpty();
        assertThat(ChromaQueryResultReader.metadataAt(result, 0)).isEmpty();
        assertThat(ChromaQueryResultReader.distanceAt(result, 0)).isNull();
    }

    /**
     * 得分按 {@code 1/(1+distance)} 换算，越大越相关。
     *
     * <p>Chroma 返回的是<b>平方</b> L2 距离；单位向量之间该距离落在 {@code [0, 4]}，
     * 因此得分落在 {@code [0.2, 1.0]}。
     */
    @Test
    void 得分按距离倒数关系换算() {
        assertThat(ChromaQueryResultReader.toScore(0.0)).isCloseTo(1.0, OFFSET);
        assertThat(ChromaQueryResultReader.toScore(1.0)).isCloseTo(0.5, OFFSET);
        assertThat(ChromaQueryResultReader.toScore(3.0)).isCloseTo(0.25, OFFSET);
        // 单位向量之间的平方 L2 上界是 4（完全反向），对应得分下界 0.2。
        assertThat(ChromaQueryResultReader.toScore(4.0)).isCloseTo(0.2, OFFSET);
    }

    @Test
    void 距离缺失时得分为零() {
        assertThat(ChromaQueryResultReader.toScore(null)).isZero();
    }

    /**
     * 得分随距离单调递减 —— 这是"越大越相关"的语义基础。
     */
    @Test
    void 得分随距离单调递减() {
        double previous = ChromaQueryResultReader.toScore(0.0);

        for (double distance = 0.5; distance <= 4.0; distance += 0.5) {
            double current = ChromaQueryResultReader.toScore(distance);
            assertThat(current).isLessThan(previous);
            previous = current;
        }
    }

    /**
     * 单位向量下，得分与余弦相似度单调对应。
     *
     * <p>单位向量之间 {@code squared_L2 = 2 - 2·cos}，
     * 因此 {@code 1/(1+squared_L2)} 是余弦相似度的单调递增变换。
     * 这条断言把"得分可当作相似度理解"这一前提钉死。
     */
    @Test
    void 单位向量下得分与余弦相似度单调对应() {
        // 余弦从低到高排列，得分应当随之单调递增。
        double[] cosines = {-1.0, -0.5, 0.0, 0.5, 0.8, 1.0};
        double previousScore = Double.NEGATIVE_INFINITY;

        for (double cosine : cosines) {
            double squaredL2 = 2.0 - 2.0 * cosine;
            double score = ChromaQueryResultReader.toScore(squaredL2);
            assertThat(score).isGreaterThan(previousScore);
            previousScore = score;
        }
        // 余弦为 1（完全相同）时得分为 1，余弦为 -1（完全相反）时得分为 0.2。
        assertThat(ChromaQueryResultReader.toScore(0.0)).isCloseTo(1.0, OFFSET);
        assertThat(ChromaQueryResultReader.toScore(4.0)).isCloseTo(0.2, OFFSET);
    }
}
