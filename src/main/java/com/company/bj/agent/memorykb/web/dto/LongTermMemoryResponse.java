package com.company.bj.agent.memorykb.web.dto;

import java.util.List;

/**
 * 长期记忆视图。
 *
 * @param records 当前作用域下的长期记忆条目
 */
public record LongTermMemoryResponse(List<MemoryRecordResponse> records) {

    /**
     * 规范化构造，保证条目列表永不为 {@code null}。
     *
     * @param records 当前作用域下的长期记忆条目
     */
    public LongTermMemoryResponse {
        records = records == null ? List.of() : List.copyOf(records);
    }
}
