package com.company.bj.agent.memorykb.web.dto;

import com.company.bj.agent.memorykb.memory.TaskItem;
import java.util.List;

/**
 * 工作记忆任务项的对外形状。
 *
 * @param id          任务标识
 * @param description 任务描述
 * @param status      当前状态，取值为 {@code TODO} / {@code IN_PROGRESS} / {@code DONE}
 * @param createdAt   创建时间戳（epoch 毫秒）
 */
public record TaskItemResponse(String id, String description, String status, long createdAt) {

    /**
     * 由领域任务项转换为对外形状。
     *
     * @param item 领域任务项
     * @return 对外形状
     */
    public static TaskItemResponse from(TaskItem item) {
        return new TaskItemResponse(item.id(), item.description(),
            item.status().name(), item.createdAt());
    }

    /**
     * 批量转换。
     *
     * @param items 领域任务项列表
     * @return 对外形状列表
     */
    public static List<TaskItemResponse> from(List<TaskItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<TaskItemResponse> responses = new java.util.ArrayList<>(items.size());
        for (TaskItem item : items) {
            responses.add(from(item));
        }
        return List.copyOf(responses);
    }
}
