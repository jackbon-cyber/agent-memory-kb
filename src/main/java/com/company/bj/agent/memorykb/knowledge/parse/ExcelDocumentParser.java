package com.company.bj.agent.memorykb.knowledge.parse;

import com.company.bj.agent.memorykb.knowledge.model.ContentBlock;
import com.company.bj.agent.memorykb.knowledge.model.ParsedDocument;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/**
 * Excel 文档（{@code .xlsx} / {@code .xls}）解析器。
 *
 * <p>逐工作表、逐行提取，每个非空行产生一个 {@code kind = "table-row"} 的内容块，
 * 携带工作表名与行号，从而保证同一表格行不会被拆散到不同切片。
 *
 * <p><b>关键点</b>：单元格文本必须用 {@link DataFormatter} 取，不能用
 * {@code getStringCellValue()} —— 后者对数值与日期单元格会抛异常或返回乱码，
 * {@code DataFormatter} 会按单元格类型与显示格式渲染出人类可读文本。
 */
public class ExcelDocumentParser implements DocumentParser {

    /** 表格行块的类型标识。 */
    public static final String KIND_TABLE_ROW = "table-row";

    /** 受支持的新版 Excel 扩展名。 */
    private static final String EXTENSION_XLSX = ".xlsx";

    /** 受支持的旧版 Excel 扩展名。 */
    private static final String EXTENSION_XLS = ".xls";

    /** 同一行内单元格之间的连接符。 */
    public static final String CELL_SEPARATOR = " | ";

    @Override
    public boolean supports(String fileName) {
        if (fileName == null) {
            return false;
        }
        String normalized = fileName.toLowerCase(Locale.ROOT);
        return normalized.endsWith(EXTENSION_XLSX) || normalized.endsWith(EXTENSION_XLS);
    }

    @Override
    public ParsedDocument parse(String documentId, String fileName, byte[] bytes) throws IOException {
        List<ContentBlock> blocks = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        // 全文由各行以换行符连接而成，游标记录下一行在全文中的起始偏移，
        // 且必须跨工作表连续累计，否则第二个工作表的偏移会与全文对不上。
        int cursor = 0;
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            DataFormatter formatter = new DataFormatter();
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                cursor = collectSheet(workbook.getSheetAt(sheetIndex), formatter, blocks, lines, cursor);
            }
        }
        return new ParsedDocument(documentId, fileName, "excel", String.join("\n", lines), blocks);
    }

    /**
     * 提取单个工作表的非空行。
     *
     * @param sheet     工作表
     * @param formatter 单元格文本渲染器
     * @param blocks    内容块收集容器
     * @param lines     全文行收集容器
     * @param cursor    本工作表首行在全文中的起始偏移
     * @return 本工作表末行之后的全文偏移
     */
    private int collectSheet(Sheet sheet, DataFormatter formatter, List<ContentBlock> blocks, List<String> lines,
        int cursor) {
        String tableName = sheet.getSheetName();
        for (Row row : sheet) {
            String text = joinCells(row, formatter);
            if (text.isBlank()) {
                continue;
            }
            blocks.add(new ContentBlock(KIND_TABLE_ROW, text, cursor, cursor + text.length(),
                null, tableName, row.getRowNum()));
            lines.add(text);
            // 换行符占一位，使下一行的起始偏移与全文一致。
            cursor += text.length() + 1;
        }
        return cursor;
    }

    /**
     * 把一行的所有单元格渲染成 {@code "col1 | col2 | col3"} 形式。
     *
     * @param row       行
     * @param formatter 单元格文本渲染器
     * @return 行文本
     */
    private String joinCells(Row row, DataFormatter formatter) {
        StringBuilder builder = new StringBuilder();
        for (Cell cell : row) {
            if (builder.length() > 0) {
                builder.append(CELL_SEPARATOR);
            }
            builder.append(formatter.formatCellValue(cell));
        }
        return builder.toString();
    }
}
