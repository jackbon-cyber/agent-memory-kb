package com.company.bj.agent.memorykb.web.dto;

/**
 * 统一错误响应。
 *
 * <p>错误码是<b>机器可判定</b>的稳定标识（前端据此分支处理），
 * 错误信息是<b>人类可读</b>的说明（直接展示给用户）。
 *
 * @param error   错误码，例如 {@code chroma_unavailable}
 * @param message 错误说明
 * @param path    出错的请求路径
 */
public record ErrorResponse(String error, String message, String path) {
}
