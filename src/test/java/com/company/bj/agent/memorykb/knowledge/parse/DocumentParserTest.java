package com.company.bj.agent.memorykb.knowledge.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.bj.agent.memorykb.knowledge.model.ContentBlock;
import com.company.bj.agent.memorykb.knowledge.model.ParsedDocument;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/**
 * 校验 {@link TxtDocumentParser} 与 {@link ExcelDocumentParser} 的解析行为，
 * 以及 {@link DocumentParserRegistry} 的解析器选择逻辑。
 */
class DocumentParserTest {

    /** 纯文本块的类型标识。 */
    private static final String KIND_TEXT = "text";

    /** 表格行块的类型标识。 */
    private static final String KIND_TABLE_ROW = "table-row";

    @Test
    void txt解析得到全文与逐行内容块() throws IOException {
        String text = "第一行\n第二行\n第三行";
        TxtDocumentParser parser = new TxtDocumentParser();

        ParsedDocument document = parser.parse("d1", "a.txt", text.getBytes(StandardCharsets.UTF_8));

        assertThat(document.documentId()).isEqualTo("d1");
        assertThat(document.fileName()).isEqualTo("a.txt");
        assertThat(document.sourceType()).isEqualTo("txt");
        assertThat(document.content()).isEqualTo(text);
        assertThat(document.blocks()).hasSize(3);
        assertThat(document.blocks()).allSatisfy(block -> assertThat(block.kind()).isEqualTo(KIND_TEXT));
        assertThat(document.blocks().get(1).text()).isEqualTo("第二行");
    }

    @Test
    void txt解析的偏移量与全文一致() throws IOException {
        String text = "第一行\n第二行\n第三行";
        TxtDocumentParser parser = new TxtDocumentParser();

        ParsedDocument document = parser.parse("d1", "a.txt", text.getBytes(StandardCharsets.UTF_8));

        List<ContentBlock> blocks = document.blocks();
        assertThat(blocks.get(0).startOffset()).isZero();
        assertThat(blocks.get(0).endOffset()).isEqualTo("第一行".length());
        assertThat(text.substring(blocks.get(1).startOffset(), blocks.get(1).endOffset())).isEqualTo("第二行");
        assertThat(text.substring(blocks.get(2).startOffset(), blocks.get(2).endOffset())).isEqualTo("第三行");
    }

    @Test
    void txt解析保留空行不丢段落结构() throws IOException {
        String text = "第一段\n\n第二段";
        TxtDocumentParser parser = new TxtDocumentParser();

        ParsedDocument document = parser.parse("d1", "a.txt", text.getBytes(StandardCharsets.UTF_8));

        assertThat(document.content()).isEqualTo(text);
        assertThat(document.blocks()).hasSize(3);
        assertThat(document.blocks().get(1).text()).isEmpty();
    }

    @Test
    void txt解析的末尾换行不产生多余空行块() throws IOException {
        String text = "第一行\n第二行\n";
        TxtDocumentParser parser = new TxtDocumentParser();

        ParsedDocument document = parser.parse("d1", "a.txt", text.getBytes(StandardCharsets.UTF_8));

        assertThat(document.blocks()).hasSize(2);
        assertThat(document.blocks().get(1).text()).isEqualTo("第二行");
    }

    @Test
    void txt解析的Windows换行不计入行文本() throws IOException {
        String text = "第一行\r\n第二行";
        TxtDocumentParser parser = new TxtDocumentParser();

        ParsedDocument document = parser.parse("d1", "a.txt", text.getBytes(StandardCharsets.UTF_8));

        assertThat(document.blocks()).hasSize(2);
        assertThat(document.blocks().get(0).text()).isEqualTo("第一行");
        assertThat(text.substring(document.blocks().get(1).startOffset(),
            document.blocks().get(1).endOffset())).isEqualTo("第二行");
    }

    @Test
    void txt支持中文且不乱码() throws IOException {
        String text = "边检知识库第一段。\n第二段包含中文与数字 2026。";
        TxtDocumentParser parser = new TxtDocumentParser();

        ParsedDocument document = parser.parse("d1", "中文.txt", text.getBytes(StandardCharsets.UTF_8));

        assertThat(document.content()).isEqualTo(text);
    }

    @Test
    void excel解析提取多个工作表内容且不乱码() throws IOException {
        byte[] bytes = workbookWithTwoSheets();
        ExcelDocumentParser parser = new ExcelDocumentParser();

        ParsedDocument document = parser.parse("d1", "a.xlsx", bytes);

        assertThat(document.sourceType()).isEqualTo("excel");
        assertThat(document.content()).contains("姓名").contains("张三").contains("边检").contains("李四");        assertThat(document.blocks()).isNotEmpty();
        assertThat(document.blocks()).allSatisfy(block -> assertThat(block.kind()).isEqualTo(KIND_TABLE_ROW));
        assertThat(document.blocks()).extracting(ContentBlock::tableName)
            .contains("Sheet1", "Sheet2");
    }

    @Test
    void excel解析的行号与工作表名正确() throws IOException {
        byte[] bytes = workbookWithTwoSheets();
        ExcelDocumentParser parser = new ExcelDocumentParser();

        ParsedDocument document = parser.parse("d1", "a.xlsx", bytes);

        ContentBlock first = document.blocks().get(0);
        assertThat(first.tableName()).isEqualTo("Sheet1");
        assertThat(first.rowNumber()).isZero();
        assertThat(first.text()).isEqualTo("姓名 | 年龄");
    }

    @Test
    void excel解析数值单元格不乱码() throws IOException {
        byte[] bytes = workbookWithTwoSheets();
        ExcelDocumentParser parser = new ExcelDocumentParser();

        ParsedDocument document = parser.parse("d1", "a.xlsx", bytes);

        // 数字单元格必须用 DataFormatter 渲染，否则会出现科学计数或乱码。
        assertThat(document.content()).contains("28").doesNotContain("2.8E1");
    }

    @Test
    void excel解析的偏移量与拼接全文一致() throws IOException {
        byte[] bytes = workbookWithTwoSheets();
        ExcelDocumentParser parser = new ExcelDocumentParser();

        ParsedDocument document = parser.parse("d1", "a.xlsx", bytes);

        assertThat(document.blocks()).allSatisfy(block -> assertThat(
            document.content().substring(block.startOffset(), block.endOffset())).isEqualTo(block.text()));
    }

    @Test
    void supports只认txt与excel() {
        ExcelDocumentParser excelParser = new ExcelDocumentParser();
        assertThat(excelParser.supports("a.xlsx")).isTrue();
        assertThat(excelParser.supports("a.xls")).isTrue();
        assertThat(excelParser.supports("a.pdf")).isFalse();
        assertThat(excelParser.supports("a.txt")).isFalse();

        TxtDocumentParser txtParser = new TxtDocumentParser();
        assertThat(txtParser.supports("a.txt")).isTrue();
        assertThat(txtParser.supports("a.pdf")).isFalse();
    }

    @Test
    void 注册表按扩展名选择解析器() {
        DocumentParserRegistry registry =
            new DocumentParserRegistry(List.of(new TxtDocumentParser(), new ExcelDocumentParser()));

        assertThat(registry.parserFor("a.txt")).isInstanceOf(TxtDocumentParser.class);
        assertThat(registry.parserFor("a.xlsx")).isInstanceOf(ExcelDocumentParser.class);
    }

    @Test
    void 注册表遇到不支持的类型抛异常() {
        DocumentParserRegistry registry =
            new DocumentParserRegistry(List.of(new TxtDocumentParser(), new ExcelDocumentParser()));

        assertThatThrownBy(() -> registry.parserFor("a.pdf"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("a.pdf");
    }

    /**
     * 在内存中构造一个含两个工作表的中文 xlsx。
     *
     * @return xlsx 文件字节
     * @throws IOException 写入失败
     */
    private byte[] workbookWithTwoSheets() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet1 = workbook.createSheet("Sheet1");
            sheet1.createRow(0).createCell(0).setCellValue("姓名");
            sheet1.getRow(0).createCell(1).setCellValue("年龄");
            sheet1.createRow(1).createCell(0).setCellValue("张三");
            sheet1.getRow(1).createCell(1).setCellValue(28);

            var sheet2 = workbook.createSheet("Sheet2");
            sheet2.createRow(0).createCell(0).setCellValue("业务线");
            sheet2.createRow(1).createCell(0).setCellValue("边检");
            sheet2.createRow(2).createCell(0).setCellValue("李四");

            workbook.write(out);
            return out.toByteArray();
        }
    }
}
