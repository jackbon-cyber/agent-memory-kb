package com.company.bj.agent.memorykb.knowledge.parse;

import com.company.bj.agent.memorykb.knowledge.model.ParsedDocument;
import java.io.IOException;

/**
 * 文档解析器扩展点。
 *
 * <p>每种文件类型一个实现类，由 {@link DocumentParserRegistry} 依据文件名扩展名路由，
 * 新增格式只需新增实现类，不改动调用方。
 */
public interface DocumentParser {

    /**
     * 判断本解析器是否支持给定文件名。
     *
     * @param fileName 文件名（可含路径）
     * @return 支持时返回 {@code true}
     */
    boolean supports(String fileName);

    /**
     * 把原始字节解析为结构化文档。
     *
     * @param documentId 文档标识，约定为原始文件名（含扩展名）
     * @param fileName   原始文件名
     * @param bytes      文件原始字节
     * @return 解析后的文档
     * @throws IOException 文件损坏、编码无法识别或工作簿无法打开时抛出
     */
    ParsedDocument parse(String documentId, String fileName, byte[] bytes) throws IOException;
}
