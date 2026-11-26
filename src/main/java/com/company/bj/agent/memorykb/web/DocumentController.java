package com.company.bj.agent.memorykb.web;

import com.company.bj.agent.memorykb.knowledge.IngestionService;
import com.company.bj.agent.memorykb.knowledge.KnowledgeCatalogService;
import com.company.bj.agent.memorykb.knowledge.model.IngestionResult;
import com.company.bj.agent.memorykb.web.dto.DeleteDocumentResponse;
import com.company.bj.agent.memorykb.web.dto.DocumentSummary;
import com.company.bj.agent.memorykb.web.dto.IngestionResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库文档管理接口。
 *
 * <p>负责上传文档、列出已入库文档与删除文档三件事。
 *
 * <p><b>文档标识就是原始文件名</b>（含扩展名，中文原样保留）：
 * 切片标识由 {@code documentId + "#" + sequence} 确定性派生，
 * 而 Task 6 的评测集按「文件名#序号」预测切片 id。
 * 若在此处对文件名做任何规范化（转小写、去空格、转义路径分隔符），
 * 评测集的 id 就会全部失配 —— 且失配表现为「召回率莫名偏低」，
 * 极难回溯到是标识规范化造成的。因此此处<b>原样使用</b>客户端提供的文件名，
 * 只做「取路径最后一段」这一项必要的安全处理。
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    /** 支持的扩展名：纯文本。 */
    private static final String EXTENSION_TXT = ".txt";

    /** 支持的扩展名：Excel。 */
    private static final String EXTENSION_XLSX = ".xlsx";

    /** 支持的扩展名：Excel 旧版格式。 */
    private static final String EXTENSION_XLS = ".xls";

    /** 附加元数据键：知识库标识。 */
    private static final String METADATA_KB = "kb";

    /** 路径分隔符，取文件名时按此截断。 */
    private static final char PATH_SEPARATOR = '/';

    /** 路径分隔符（Windows）。 */
    private static final char WINDOWS_PATH_SEPARATOR = '\\';

    private final IngestionService ingestionService;
    private final KnowledgeCatalogService catalogService;

    /**
     * 构造文档管理接口。
     *
     * @param ingestionService 摄取服务，负责解析、切片与入库
     * @param catalogService   文档清单服务，负责聚合与删除
     */
    public DocumentController(IngestionService ingestionService, KnowledgeCatalogService catalogService) {
        this.ingestionService = ingestionService;
        this.catalogService = catalogService;
    }

    /**
     * 上传并入库一份文档。
     *
     * <p><b>为什么在读取字节前先校验扩展名</b>：把不支持的文件类型
     * 在入口就挡掉，可以避免为一份必然失败的请求去调用嵌入模型 ——
     * 那会白白消耗配额并在失败信息里混入模型服务的噪音，
     * 让真正的原因（格式不支持）被淹没。
     *
     * @param file 上传的文件
     * @param kb   可选的知识库标识，作为附加元数据写入每个切片
     * @return 入库结果
     * @throws IllegalArgumentException 文件为空、扩展名不支持或文件内容为空时抛出
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public IngestionResponse upload(@RequestParam("file") MultipartFile file,
        @RequestParam(value = "kb", required = false) String kb) {
        String fileName = resolveFileName(file);
        requireSupportedExtension(fileName);

        byte[] bytes = readBytes(file);
        if (bytes.length == 0) {
            throw new IllegalArgumentException("uploaded file '" + fileName + "' is empty");
        }

        IngestionResult result = ingestionService.ingest(fileName, fileName, bytes, extraMetadata(kb));
        return new IngestionResponse(result.documentId(), result.chunkCount(), result.ingestedCount());
    }

    /**
     * 列出知识库中已入库的文档。
     *
     * @return 文档摘要列表
     */
    @GetMapping
    public List<DocumentSummary> list() {
        return catalogService.listDocuments();
    }

    /**
     * 删除一个文档及其全部切片。
     *
     * @param documentId 文档标识
     * @return 删除结果
     * @throws IllegalArgumentException 文档标识为空时抛出
     */
    @DeleteMapping("/{documentId}")
    public DeleteDocumentResponse delete(@PathVariable("documentId") String documentId) {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId must not be blank");
        }
        int chunkCount = catalogService.deleteDocument(documentId);
        return new DeleteDocumentResponse(documentId, chunkCount > 0, chunkCount);
    }

    /**
     * 解析上传文件的原始文件名。
     *
     * <p>只取路径最后一段：客户端可能传的是 {@code C:\dir\a.txt} 或
     * {@code /var/tmp/a.txt}，其中目录部分既无业务含义又会污染文档标识。
     * 但<b>不做其它规范化</b> —— 大小写、空格、中文一律原样保留，
     * 因为评测集的切片 id 前缀依赖原始文件名。
     *
     * @param file 上传的文件
     * @return 文件名
     * @throws IllegalArgumentException 文件为空或没有原始文件名时抛出
     */
    private String resolveFileName(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("uploaded file must not be empty");
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            throw new IllegalArgumentException("uploaded file must carry an original filename");
        }
        int separatorIndex = Math.max(originalName.lastIndexOf(PATH_SEPARATOR),
            originalName.lastIndexOf(WINDOWS_PATH_SEPARATOR));
        String fileName = separatorIndex >= 0 ? originalName.substring(separatorIndex + 1) : originalName;
        if (fileName.isBlank()) {
            throw new IllegalArgumentException("uploaded file must carry an original filename");
        }
        return fileName;
    }

    /**
     * 校验文件扩展名是否受支持。
     *
     * @param fileName 文件名
     * @throws IllegalArgumentException 扩展名不受支持时抛出
     */
    private void requireSupportedExtension(String fileName) {
        String lowerCase = fileName.toLowerCase(java.util.Locale.ROOT);
        if (!lowerCase.endsWith(EXTENSION_TXT)
            && !lowerCase.endsWith(EXTENSION_XLSX)
            && !lowerCase.endsWith(EXTENSION_XLS)) {
            throw new IllegalArgumentException(
                "unsupported file type: '" + fileName + "', expected one of .txt/.xlsx/.xls");
        }
    }

    /**
     * 读取上传文件的字节内容。
     *
     * @param file 上传的文件
     * @return 文件字节
     * @throws IllegalArgumentException 读取失败时抛出
     */
    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("failed to read uploaded file: " + e.getMessage(), e);
        }
    }

    /**
     * 组装附加元数据。
     *
     * <p>缺省不写入 {@code kb} 字段：写入空字符串会让 Chroma 的元数据里
     * 多一个无意义的列，也会让「按 {@code kb} 过滤」的行为变得难以解释
     * （空值与未设置是两回事）。
     *
     * @param kb 知识库标识，可为 {@code null}
     * @return 附加元数据，缺省时为空 Map
     */
    private Map<String, Object> extraMetadata(String kb) {
        Map<String, Object> metadata = new LinkedHashMap<>(1);
        if (kb != null && !kb.isBlank()) {
            metadata.put(METADATA_KB, kb.trim());
        }
        return metadata;
    }
}
