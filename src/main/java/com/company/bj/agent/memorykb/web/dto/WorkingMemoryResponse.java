package com.company.bj.agent.memorykb.web.dto;

import java.util.List;

/**
 * 工作记忆视图。
 *
 * @param tasks 当前作用域下的任务项
 */
public record WorkingMemoryResponse(List<TaskItemResponse> tasks) {

    /**
     * 规范化构造，保证任务项列表永不为 {@code null}。
     *
     * @param tasks 当前作用域下的任务项
     */
    public WorkingMemoryResponse {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }
}
