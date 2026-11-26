package com.company.bj.agent.memorykb.knowledge.embed;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 校验 {@link EmbeddingNormalizer} 的单位归一化行为。
 *
 * <p>归一化是本工程距离尺度成立的前提：
 * Chroma 返回平方 L2 距离，不归一化则距离随模长膨胀、得分失去区分度。
 */
class EmbeddingNormalizerTest {

    /** 浮点容差，用于比较向量分量。 */
    private static final double DELTA = 1e-9;

    @Test
    void 非单位向量被归一化到单位长度() {
        double[] vector = {3.0, 4.0};

        double[] normalized = EmbeddingNormalizer.normalize(vector);

        assertThat(EmbeddingNormalizer.norm(normalized)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(DELTA));
        assertThat(normalized[0]).isCloseTo(0.6, org.assertj.core.data.Offset.offset(DELTA));
        assertThat(normalized[1]).isCloseTo(0.8, org.assertj.core.data.Offset.offset(DELTA));
    }

    @Test
    void 归一化保持向量方向不变() {
        double[] vector = {1.0, 2.0, 3.0, 4.0, 5.0};

        double[] normalized = EmbeddingNormalizer.normalize(vector);

        // 方向不变 ⟺ 归一化前后各分量之比相同。
        for (int i = 1; i < vector.length; i++) {
            assertThat(normalized[i] / normalized[0])
                .isCloseTo(vector[i] / vector[0], org.assertj.core.data.Offset.offset(DELTA));
        }
    }

    @Test
    void 已是单位长度的向量归一化后保持不变() {
        double[] vector = {0.0, 1.0};

        double[] normalized = EmbeddingNormalizer.normalize(vector);

        assertThat(normalized[0]).isCloseTo(0.0, org.assertj.core.data.Offset.offset(DELTA));
        assertThat(normalized[1]).isCloseTo(1.0, org.assertj.core.data.Offset.offset(DELTA));
    }

    @Test
    void 零向量原样返回且不产生NaN() {
        double[] vector = {0.0, 0.0, 0.0};

        double[] normalized = EmbeddingNormalizer.normalize(vector);

        assertThat(normalized).containsExactly(0.0, 0.0, 0.0);
        for (double component : normalized) {
            assertThat(Double.isNaN(component)).isFalse();
            assertThat(Double.isInfinite(component)).isFalse();
        }
    }

    @Test
    void 归一化不修改入参数组() {
        double[] vector = {3.0, 4.0};

        EmbeddingNormalizer.normalize(vector);

        assertThat(vector).containsExactly(3.0, 4.0);
    }

    @Test
    void isUnitLength识别单位长度向量() {
        assertThat(EmbeddingNormalizer.isUnitLength(new double[] {0.0, 1.0})).isTrue();
        assertThat(EmbeddingNormalizer.isUnitLength(new double[] {3.0, 4.0})).isFalse();
        assertThat(EmbeddingNormalizer.isUnitLength(new double[] {0.0, 0.0})).isFalse();
    }
}
