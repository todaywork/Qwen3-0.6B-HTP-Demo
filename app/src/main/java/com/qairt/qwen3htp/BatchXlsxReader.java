package com.qairt.qwen3htp;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

final class BatchXlsxReader {
    private BatchXlsxReader() {}

    static List<MainActivity.BatchInput> readInputs(File file) throws Exception {
        List<MainActivity.BatchInput> inputs = new ArrayList<>();
        try (ZipFile zip = new ZipFile(file)) {
            List<String> sharedStrings = readSharedStrings(zip);
            Document sheet = readXml(zip, "xl/worksheets/sheet1.xml");
            NodeList rows = sheet.getElementsByTagNameNS("*", "row");
            for (int i = 0; i < rows.getLength(); i++) {
                Element row = (Element) rows.item(i);
                if (parseInt(row.getAttribute("r"), i + 1) <= 1) continue;
                Map<String, String> values = new HashMap<>();
                NodeList cells = row.getElementsByTagNameNS("*", "c");
                for (int j = 0; j < cells.getLength(); j++) {
                    Element cell = (Element) cells.item(j);
                    String col = columnName(cell.getAttribute("r"));
                    if ("A".equals(col) || "B".equals(col)) {
                        values.put(col, cellText(cell, sharedStrings));
                    }
                }
                String prompt = safe(values.get("A")).trim();
                String expected = safe(values.get("B")).trim();
                if (!prompt.isEmpty()) inputs.add(new MainActivity.BatchInput(prompt, expected));
            }
        }
        return inputs;
    }

    private static List<String> readSharedStrings(ZipFile zip) throws Exception {
        List<String> values = new ArrayList<>();
        if (zip.getEntry("xl/sharedStrings.xml") == null) return values;
        NodeList items = readXml(zip, "xl/sharedStrings.xml").getElementsByTagNameNS("*", "si");
        for (int i = 0; i < items.getLength(); i++) {
            values.add(textFromDescendantT((Element) items.item(i)));
        }
        return values;
    }

    private static Document readXml(ZipFile zip, String name) throws Exception {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) throw new IllegalArgumentException("Missing xlsx entry: " + name);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        try (InputStream in = zip.getInputStream(entry)) {
            return builder.parse(new InputSource(in));
        }
    }

    private static String cellText(Element cell, List<String> sharedStrings) {
        String type = cell.getAttribute("t");
        if ("inlineStr".equals(type)) return textFromDescendantT(cell);
        String raw = firstChildText(cell, "v");
        if ("s".equals(type)) {
            int index = parseInt(raw, -1);
            return index >= 0 && index < sharedStrings.size() ? sharedStrings.get(index) : "";
        }
        return raw != null ? raw : "";
    }

    private static String textFromDescendantT(Element element) {
        StringBuilder out = new StringBuilder();
        NodeList texts = element.getElementsByTagNameNS("*", "t");
        for (int i = 0; i < texts.getLength(); i++) out.append(texts.item(i).getTextContent());
        return out.toString();
    }

    private static String firstChildText(Element element, String name) {
        NodeList nodes = element.getElementsByTagNameNS("*", name);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent();
    }

    private static String columnName(String ref) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < ref.length(); i++) {
            char c = Character.toUpperCase(ref.charAt(i));
            if (c < 'A' || c > 'Z') break;
            out.append(c);
        }
        return out.toString();
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (Exception e) { return fallback; }
    }

    private static String safe(String value) { return value != null ? value : ""; }
}
