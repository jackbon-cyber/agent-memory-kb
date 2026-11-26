package com.company.bj.agent.memorykb.web;

import com.company.bj.agent.memorykb.chroma.ChromaException;
import com.company.bj.agent.memorykb.knowledge.embed.EmbeddingException;
import com.company.bj.agent.memorykb.memory.MemoryPersistenceException;
import com.company.bj.agent.memorykb.web.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理。
 *
 * <p>把领域异常翻译成<b>语义正确</b>的 HTTP 状态码与统一的错误响应体。
 *
 * <p><b>为什么状态码必须区分</b>：状态码是调用方（控制台、编排系统、
 * 上游服务）唯一能稳定依赖的机器可读信号。
 * <ul>
 *   <li>{@code 503} —— Chroma 不可用：调用方应当<b>重试</b>，这是暂时性问题；</li>
 *   <li>{@code 502} —— 嵌入服务不可用：同样是外部依赖故障，但语义是「上游网关返回了错误」；</li>
 *   <li>{@code 400} —— 请求本身不合法：重试多少次都一样，必须由人改请求；</li>
 *   <li>{@code 409} —— 状态冲突：请求格式没错，但当前状态不允许执行；</li>
 *   <li>{@code 500} —— 其它未预期异常：属于服务端缺陷，需要排查。</li>
 * </ul>
 * 若把上面四类都归成 500，调用方就<b>无法区分「该重试」与「该报错」</b>，
 * 只能一律重试 —— 而对 400 而言重试永远无效，
 * 最终表现为调用方反复重试直至超时，真正的原因被淹没在重试日志里。
 *
 * <p><b>异常信息不外泄细节</b>：对外只给出可读的说明，
 * 堆栈与内部路径写进服务端日志。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 错误码：Chroma 向量库不可用。 */
    public static final String ERROR_CHROMA_UNAVAILABLE = "chroma_unavailable";

    /** 错误码：嵌入服务不可用。 */
    public static final String ERROR_EMBEDDING_UNAVAILABLE = "embedding_unavailable";

    /** 错误码：请求参数不合法。 */
    public static final String ERROR_INVALID_REQUEST = "invalid_request";

    /** 错误码：状态冲突。 */
    public static final String ERROR_STATE_CONFLICT = "state_conflict";

    /** 错误码：记忆持久化失败。 */
    public static final String ERROR_MEMORY_PERSISTENCE = "memory_persistence_failed";

    /** 错误码：服务端内部错误。 */
    public static final String ERROR_INTERNAL = "internal_error";

    /**
     * 处理 Chroma 不可用。
     *
     * @param exception 异常
     * @param request   出错的请求
     * @return 503 响应
     */
    @ExceptionHandler(ChromaException.class)
    public ResponseEntity<ErrorResponse> handleChroma(ChromaException exception,
        HttpServletRequest request) {
        log.warn("Chroma 调用失败，路径 {}：{}", request.getRequestURI(), exception.getMessage());
        return build(HttpStatus.SERVICE_UNAVAILABLE, ERROR_CHROMA_UNAVAILABLE,
            "向量库暂时不可用，请稍后重试", request);
    }

    /**
     * 处理嵌入服务不可用。
     *
     * @param exception 异常
     * @param request   出错的请求
     * @return 502 响应
     */
    @ExceptionHandler(EmbeddingException.class)
    public ResponseEntity<ErrorResponse> handleEmbedding(EmbeddingException exception,
        HttpServletRequest request) {
        log.warn("嵌入服务调用失败，路径 {}：{}", request.getRequestURI(), exception.getMessage());
        return build(HttpStatus.BAD_GATEWAY, ERROR_EMBEDDING_UNAVAILABLE,
            "向量模型服务暂时不可用，请稍后重试", request);
    }

    /**
     * 处理记忆持久化失败。
     *
     * @param exception 异常
     * @param request   出错的请求
     * @return 500 响应
     */
    @ExceptionHandler(MemoryPersistenceException.class)
    public ResponseEntity<ErrorResponse> handleMemoryPersistence(MemoryPersistenceException exception,
        HttpServletRequest request) {
        log.error("记忆持久化失败，路径 {}", request.getRequestURI(), exception);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ERROR_MEMORY_PERSISTENCE,
            "记忆持久化失败，操作未能生效", request);
    }

    /**
     * 处理请求参数不合法。
     *
     * <p><b>覆盖面刻意包含 {@code NumberFormatException}</b>：
     * 它是 {@code IllegalArgumentException} 的子类，
     * 范围过滤的取值解析失败正是以这个形态抛出。
     *
     * @param exception 异常
     * @param request   出错的请求
     * @return 400 响应
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException exception,
        HttpServletRequest request) {
        log.warn("请求参数不合法，路径 {}：{}", request.getRequestURI(), exception.getMessage());
        return build(HttpStatus.BAD_REQUEST, ERROR_INVALID_REQUEST,
            exception.getMessage(), request);
    }

    /**
     * 处理状态冲突。
     *
     * @param exception 异常
     * @param request   出错的请求
     * @return 409 响应
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException exception,
        HttpServletRequest request) {
        log.warn("请求状态冲突，路径 {}：{}", request.getRequestURI(), exception.getMessage());
        return build(HttpStatus.CONFLICT, ERROR_STATE_CONFLICT,
            exception.getMessage(), request);
    }

    /**
     * 处理其它未预期异常。
     *
     * @param exception 异常
     * @param request   出错的请求
     * @return 500 响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception exception,
        HttpServletRequest request) {
        log.error("未预期异常，路径 {}", request.getRequestURI(), exception);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ERROR_INTERNAL,
            "服务内部错误，请联系管理员", request);
    }

    /**
     * 构造错误响应。
     *
     * @param status  HTTP 状态
     * @param error   错误码
     * @param message 错误说明
     * @param request 出错的请求
     * @return 错误响应
     */
    private ResponseEntity<ErrorResponse> build(HttpStatus status, String error, String message,
        HttpServletRequest request) {
        ErrorResponse body = new ErrorResponse(error, message, request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
