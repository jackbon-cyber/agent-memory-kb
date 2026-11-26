package com.company.bj.agent.memorykb.web;

import com.company.bj.agent.memorykb.memory.MemoryScope;

/**
 * 记忆作用域请求头。
 *
 * <p>作用域来自 HTTP 请求头而非登录态：本次交付明确<b>不做鉴权</b>
 * （见 design.md 的非目标清单），因此请求头是调用方声明作用域的唯一通道。
 * 缺省请求头是<b>正常</b>的调用形态（单机部署、调试），
 * 缺失时回落到 {@code default}/{@code default} 而不是报错。
 *
 * <p><b>为什么不把作用域做成查询参数</b>：作用域一旦出现在 URL 里，
 * 就容易被中间层日志、浏览器历史与 Referer 头记录下来；
 * 而请求头是控制台与后端约定的标准通道。
 * 更关键的是——即便作用域由调用方传入，
 * 它也只作为「作用域解析」的输入，<b>绝不会</b>直接传给记忆存储：
 * 存储契约不接受作用域入参，越权防线仍由 {@code ScopeResolver} 统一把守。
 */
public final class ScopeHeaders {

    /** 租户标识请求头。 */
    public static final String HEADER_TENANT_ID = "X-Tenant-Id";

    /** 主体标识请求头。 */
    public static final String HEADER_OWNER_KEY = "X-Owner-Key";

    /** 工具类不允许实例化。 */
    private ScopeHeaders() {
        throw new UnsupportedOperationException("utility class");
    }

    /**
     * 由请求头解析出作用域。
     *
     * @param tenantId 租户标识请求头值，可为 {@code null}
     * @param ownerKey 主体标识请求头值，可为 {@code null}
     * @return 作用域，缺失字段回落到缺省值
     */
    public static MemoryScope resolve(String tenantId, String ownerKey) {
        return MemoryScope.of(tenantId, ownerKey);
    }
}
