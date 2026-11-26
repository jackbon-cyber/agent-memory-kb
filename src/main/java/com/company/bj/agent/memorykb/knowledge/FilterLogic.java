package com.company.bj.agent.memorykb.knowledge;

/**
 * 多条件过滤的组合逻辑。
 *
 * <p>决定多个 {@link FilterCondition} 之间是"全部满足"还是"任一满足"，
 * 分别对应 Chroma where 表达式中的 {@code $and} 与 {@code $or}。
 */
public enum FilterLogic {

    /** 全部条件都满足（AND）。 */
    AND("$and"),

    /** 任一条件满足即可（OR）。 */
    OR("$or");

    /** Chroma where 表达式中的组合操作符键名。 */
    private final String whereKey;

    FilterLogic(String whereKey) {
        this.whereKey = whereKey;
    }

    /**
     * 返回 Chroma where 表达式中的组合操作符键名。
     *
     * @return 组合操作符键名，形如 {@code $and}
     */
    public String whereKey() {
        return whereKey;
    }
}
