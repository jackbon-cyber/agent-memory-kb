package com.company.bj.agent.memorykb.web.dto;

import com.company.bj.agent.memorykb.memory.MemoryRecord;
import com.company.bj.agent.memorykb.memory.MemoryScope;
import java.util.List;

/**
 * 长期记忆条目的对外形状。
 *
 * @param id        记录标识
 * @param content   记录正文
 * @param weight    权重，影响检索排序
 * @param createdAt 创建时间戳（epoch 毫秒）
 * @param scope     归属作用域
 * @param archived  是否已归档（软删除）
 */
public record MemoryRecordResponse(
    String id,
    String content,
    double weight,
    long createdAt,
    MemoryScope scope,
    boolean archived) {

    /**
     * 由领域记录转换为对外形状。
     *
     * @param record 领域记录
     * @return 对外形状
     */
    public static MemoryRecordResponse from(MemoryRecord record) {
        return new MemoryRecordResponse(record.id(), record.content(), record.weight(),
            record.createdAt(), record.scope(), record.archived());
    }

    /**
     * 批量转换。
     *
     * @param records 领域记录列表
     * @return 对外形状列表
     */
    public static List<MemoryRecordResponse> from(List<MemoryRecord> records) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        List<MemoryRecordResponse> responses = new java.util.ArrayList<>(records.size());
        for (MemoryRecord record : records) {
            responses.add(from(record));
        }
        return List.copyOf(responses);
    }
}
