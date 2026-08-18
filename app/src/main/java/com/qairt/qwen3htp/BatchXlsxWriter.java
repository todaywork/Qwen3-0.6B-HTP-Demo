package com.qairt.qwen3htp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class BatchXlsxWriter {
    private static final String[] HEADERS = {
            "输入提示词", "预期结果", "input_tokneid", "app推理结果", "比对结果",
            "Prefill(s)", "Decode(s)", "Total(s)",
            "Prefill(tok/s)", "Decode(tok/s)", "PromptTokens", "DecodeTokens"
    };

    private BatchXlsxWriter() {}

    static void write(File outputFile, List<BatchResult> results) throws IOException {
        File parent = outputFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create output directory: " + parent.getAbsolutePath());
        }
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(outputFile))) {
            entry(zip, "[Content_Types].xml", contentTypes());
            entry(zip, "_rels/.rels", rootRels());
            entry(zip, "xl/workbook.xml", workbook());
            entry(zip, "xl/_rels/workbook.xml.rels", workbookRels());
            entry(zip, "xl/styles.xml", styles());
            entry(zip, "xl/worksheets/sheet1.xml", sheet(results));
        }
    }

    private static String sheet(List<BatchResult> results) {
        StringBuilder xml = new StringBuilder();
        int lastRow = Math.max(1, results.size() + 1);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" ")
                .append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
                .append("<dimension ref=\"A1:L").append(lastRow).append("\"/>")
                .append("<sheetViews><sheetView workbookViewId=\"0\">")
                .append("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>")
                .append("</sheetView></sheetViews><sheetFormatPr defaultRowHeight=\"15\"/><cols>")
                .append(col(1, 1, 30)).append(col(2, 2, 25)).append(col(3, 3, 80))
                .append(col(4, 4, 40)).append(col(5, 8, 12))
                .append(col(9, 10, 15)).append(col(11, 12, 14))
                .append("</cols><sheetData><row r=\"1\">");
        for (int i = 0; i < HEADERS.length; i++) xml.append(textCell(ref(i + 1, 1), HEADERS[i], 1));
        xml.append("</row>");

        for (int i = 0; i < results.size(); i++) {
            BatchResult result = results.get(i);
            int row = i + 2;
            xml.append("<row r=\"").append(row).append("\">")
                    .append(textCell(ref(1, row), result.prompt, 2))
                    .append(textCell(ref(2, row), result.expected, 2))
                    .append(textCell(ref(3, row), result.inputTokenIds, 2))
                    .append(textCell(ref(4, row), result.error == null ? result.output : result.error, 2))
                    .append(textCell(ref(5, row), result.compareResult, 2));
            RunMetrics metrics = result.metrics;
            ProfileMetrics profile = metrics != null ? metrics.profile : null;
            double prefill = MetricsText.prefillSeconds(metrics);
            double decode = MetricsText.decodeSeconds(metrics);
            xml.append(numberCell(ref(6, row), prefill, 3))
                    .append(numberCell(ref(7, row), decode, 3))
                    .append(numberCell(ref(8, row), prefill + decode, 3))
                    .append(numberCell(ref(9, row), profile != null ? profile.promptTokensPerSecond : 0.0, 3))
                    .append(numberCell(ref(10, row), profile != null ? profile.generatedTokensPerSecond : 0.0, 3))
                    .append(integerCell(ref(11, row), profile != null ? profile.promptTokens : -1, 4))
                    .append(integerCell(ref(12, row), profile != null ? profile.generatedTokens : -1, 4))
                    .append("</row>");
        }
        return xml.append("</sheetData><autoFilter ref=\"A1:L").append(lastRow)
                .append("\"/><pageMargins left=\"0.7\" right=\"0.7\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/></worksheet>")
                .toString();
    }

    private static String col(int min, int max, int width) {
        return "<col min=\"" + min + "\" max=\"" + max + "\" width=\"" + width + "\" customWidth=\"1\"/>";
    }

    private static String textCell(String ref, String value, int style) {
        return "<c r=\"" + ref + "\" s=\"" + style + "\" t=\"inlineStr\"><is><t>"
                + escape(value != null ? value : "") + "</t></is></c>";
    }

    private static String numberCell(String ref, double value, int style) {
        return "<c r=\"" + ref + "\" s=\"" + style + "\"><v>"
                + String.format(Locale.US, "%.6f", value) + "</v></c>";
    }

    private static String integerCell(String ref, long value, int style) {
        return "<c r=\"" + ref + "\" s=\"" + style + "\"><v>" + value + "</v></c>";
    }

    private static String ref(int col, int row) {
        StringBuilder name = new StringBuilder();
        for (int value = col; value > 0; value = (value - 1) / 26) {
            name.insert(0, (char) ('A' + (value - 1) % 26));
        }
        return name + String.valueOf(row);
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String contentTypes() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
                + "</Types>";
    }

    private static String rootRels() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "</Relationships>";
    }

    private static String workbook() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<sheets><sheet name=\"批量推理结果\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>";
    }

    private static String workbookRels() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                + "</Relationships>";
    }

    private static String styles() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"0.00000\"/></numFmts>"
                + "<fonts count=\"2\"><font><name val=\"Calibri\"/><family val=\"2\"/><color theme=\"1\"/><sz val=\"11\"/></font><font><b val=\"1\"/><color rgb=\"00FFFFFF\"/><sz val=\"11\"/></font></fonts>"
                + "<fills count=\"3\"><fill><patternFill/></fill><fill><patternFill patternType=\"gray125\"/></fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"005B36B7\"/><bgColor rgb=\"005B36B7\"/></patternFill></fill></fills>"
                + "<borders count=\"2\"><border><left/><right/><top/><bottom/><diagonal/></border><border><left style=\"thin\"/><right style=\"thin\"/><top style=\"thin\"/><bottom style=\"thin\"/></border></borders>"
                + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                + "<cellXfs count=\"5\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/><xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"1\" applyAlignment=\"1\" xfId=\"0\"><alignment horizontal=\"center\" vertical=\"center\" wrapText=\"1\"/></xf><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"1\" applyAlignment=\"1\" xfId=\"0\"><alignment vertical=\"center\" wrapText=\"1\"/></xf><xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"1\" applyAlignment=\"1\" xfId=\"0\"><alignment vertical=\"center\" wrapText=\"1\"/></xf><xf numFmtId=\"1\" fontId=\"0\" fillId=\"0\" borderId=\"1\" applyAlignment=\"1\" xfId=\"0\"><alignment vertical=\"center\" wrapText=\"1\"/></xf></cellXfs>"
                + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles><dxfs count=\"0\"/><tableStyles count=\"0\" defaultTableStyle=\"TableStyleMedium9\" defaultPivotStyle=\"PivotStyleLight16\"/></styleSheet>";
    }
}
