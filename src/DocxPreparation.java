package io.github.tommilin.bookconverter;

import java.io.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.*;
import org.w3c.dom.*;

final class DocxPreparation {
    private static final String WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String DC = "http://purl.org/dc/elements/1.1/";
    private static final Pattern STORY = Pattern.compile("[\\p{Lu}][\\p{Lu}\\p{N} ,.!?’'():–—-]+ by .{2,70}");
    private static final Pattern SECTION = Pattern.compile(
        "(?:[0-9]{1,2}|[IVXLCDM]{1,7}|(?:Chapter|Part) [A-Za-z0-9 -]{1,24})", Pattern.CASE_INSENSITIVE);

    static void prepare(File input, File output, String baseName, boolean detect) throws Exception {
        Map<String,byte[]> changed = new HashMap<>();
        try (ZipFile zip = new ZipFile(input)) {
            Document properties = readXml(zip, "docProps/core.xml");
            if (properties != null) {
                Element core = properties.getDocumentElement();
                List<Element> titles = Books.elements(core, "title");
                if (titles.isEmpty()) {
                    Element title = properties.createElementNS(DC, "dc:title");
                    title.setTextContent(baseName);
                    core.appendChild(title);
                } else if (titles.get(0).getTextContent().trim().isEmpty()) {
                    titles.get(0).setTextContent(baseName);
                }
                for (Element creator : Books.elements(core, "creator")) {
                    if (creator.getTextContent().trim().equalsIgnoreCase("AVS Office Studio"))
                        creator.getParentNode().removeChild(creator);
                }
                changed.put("docProps/core.xml", Books.serialize(properties));
            }
            if (detect) detectHeadings(zip, changed);
            try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(output))) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                byte[] block = new byte[8192];
                int remaining = Books.LIMIT;
                int count = 0;
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    if (++count > 8192) throw new IOException("This DOCX contains too many files.");
                    out.putNextEntry(new ZipEntry(entry.getName()));
                    byte[] replacement = changed.get(entry.getName());
                    if (replacement != null) {
                        remaining -= replacement.length;
                        if (remaining < 0) throw new IOException("This DOCX exceeds the size limit.");
                        out.write(replacement);
                    } else {
                        try (InputStream in = zip.getInputStream(entry)) {
                            int size;
                            while ((size = in.read(block)) != -1) {
                                remaining -= size;
                                if (remaining < 0) throw new IOException("This DOCX exceeds the size limit.");
                                out.write(block, 0, size);
                            }
                        }
                    }
                    out.closeEntry();
                }
            }
        }
    }

    private static Document readXml(ZipFile zip, String name) throws Exception {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) return null;
        try (InputStream in = zip.getInputStream(entry)) {
            return Books.parse(Books.read(in, Books.LIMIT));
        }
    }

    private static void detectHeadings(ZipFile zip, Map<String,byte[]> changed) throws Exception {
        Document body = readXml(zip, "word/document.xml");
        Document styles = readXml(zip, "word/styles.xml");
        if (body == null || styles == null) return;
        for (Element field : Books.elements(body, "instrText"))
            if (field.getTextContent().trim().matches("(?is)^TOC(?:\\s.*)?$")) return;
        for (Element field : Books.elements(body, "fldSimple"))
            if (field.getAttributeNS(WORD, "instr").trim().matches("(?is)^TOC(?:\\s.*)?$")) return;
        Set<String> structured = new HashSet<>();
        for (Element style : Books.elements(styles, "style")) {
            String name = attribute(style, "name");
            if (name.matches("(?i)heading\\s+[1-9]") || !Books.elements(style, "outlineLvl").isEmpty())
                structured.add(style.getAttributeNS(WORD, "styleId"));
        }
        if (!Books.elements(body, "outlineLvl").isEmpty()) return;
        for (Element style : Books.elements(body, "pStyle"))
            if (structured.contains(style.getAttributeNS(WORD, "val"))) return;

        Map<Element,Integer> headings = new LinkedHashMap<>();
        int stories = 0;
        for (Element paragraph : Books.elements(body, "p")) {
            StringBuilder text = new StringBuilder();
            for (Element run : Books.elements(paragraph, "t")) text.append(run.getTextContent());
            String label = text.toString().replace((char)0x00a0, ' ').trim();
            if (STORY.matcher(label).matches()) {
                headings.put(paragraph, 1);
                stories++;
            } else if (stories > 0 && SECTION.matcher(label).matches()) {
                for (Element bookmark : Books.elements(paragraph, "bookmarkStart")) {
                    if (bookmark.getAttributeNS(WORD, "name").startsWith("_Toc")) {
                        headings.put(paragraph, 2);
                        break;
                    }
                }
            }
        }
        if (stories < 2) return;
        String top = headingStyle(styles, 1);
        String inner = headingStyle(styles, 2);
        for (Map.Entry<Element,Integer> heading : headings.entrySet()) {
            Element paragraph = heading.getKey();
            Element props = direct(paragraph, "pPr");
            if (props == null) {
                props = body.createElementNS(WORD, "w:pPr");
                paragraph.insertBefore(props, paragraph.getFirstChild());
            }
            Element style = direct(props, "pStyle");
            if (style == null) {
                style = body.createElementNS(WORD, "w:pStyle");
                props.insertBefore(style, props.getFirstChild());
            }
            style.setAttributeNS(WORD, "w:val", heading.getValue() == 1 ? top : inner);
        }
        changed.put("word/document.xml", Books.serialize(body));
        changed.put("word/styles.xml", Books.serialize(styles));
    }

    private static String attribute(Element parent, String key) {
        Element child = direct(parent, key);
        return child == null ? "" : child.getAttributeNS(WORD, "val");
    }

    private static Element direct(Element parent, String key) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element && WORD.equals(node.getNamespaceURI()) && key.equals(node.getLocalName()))
                return (Element)node;
        return null;
    }

    private static String headingStyle(Document styles, int level) {
        String id = "OBCDetectedHeading" + level;
        Set<String> ids = new HashSet<>();
        for (Element existing : Books.elements(styles, "style"))
            ids.add(existing.getAttributeNS(WORD, "styleId"));
        while (ids.contains(id)) id += "X";
        Element style = styles.createElementNS(WORD, "w:style");
        style.setAttributeNS(WORD, "w:type", "paragraph");
        style.setAttributeNS(WORD, "w:styleId", id);
        Element name = styles.createElementNS(WORD, "w:name");
        name.setAttributeNS(WORD, "w:val", "heading " + level);
        style.appendChild(name);
        styles.getDocumentElement().appendChild(style);
        return id;
    }
}
