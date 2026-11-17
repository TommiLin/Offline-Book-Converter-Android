package io.github.tommilin.bookconverter;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.*;

public final class Books {
    static final String XHTML = "http://www.w3.org/1999/xhtml";
    static final String EPUB = "http://www.idpf.org/2007/ops";
    static final String OPF = "http://www.idpf.org/2007/opf";
    static final int LIMIT = 48 * 1024 * 1024;

    static byte[] read(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int size;
        while ((size = input.read(buffer)) != -1) {
            if (size > limit - output.size()) throw new IOException("This book exceeds the size limit.");
            output.write(buffer, 0, size);
        }
        return output.toByteArray();
    }

    static Document parse(byte[] bytes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        disableFeatureIfSupported(factory, "http://xml.org/sax/features/external-general-entities");
        disableFeatureIfSupported(factory, "http://xml.org/sax/features/external-parameter-entities");
        factory.setExpandEntityReferences(false);
        javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> {
            throw new org.xml.sax.SAXException("External XML references are unsupported.");
        });
        return builder.parse(new ByteArrayInputStream(bytes));
    }

    private static void disableFeatureIfSupported(DocumentBuilderFactory factory, String feature) {
        try {
            factory.setFeature(feature, false);
        } catch (javax.xml.parsers.ParserConfigurationException unsupported) {
            // Android's DOM factory does not expose these SAX switches.
            // The resolver still blocks external references.
        }
    }

    static List<Element> elements(Node root, String name) {
        NodeList nodes = root instanceof Document
            ? ((Document)root).getElementsByTagNameNS("*", name)
            : ((Element)root).getElementsByTagNameNS("*", name);
        List<Element> result = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) result.add((Element)nodes.item(i));
        return result;
    }

    static Element first(Node node, String name) throws IOException {
        List<Element> found = elements(node, name);
        if (found.isEmpty()) throw new IOException("Missing book metadata: " + name);
        return found.get(0);
    }

    static byte[] serialize(Document doc) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        TransformerFactory.newInstance().newTransformer().transform(new DOMSource(doc), new StreamResult(bytes));
        return bytes.toByteArray();
    }

    static String esc(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static byte[] utf(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    static byte[] require(Map<String,byte[]> files, String path) throws IOException {
        byte[] bytes = files.get(path);
        if (bytes == null) throw new IOException("Missing EPUB file: " + path);
        return bytes;
    }

    static URI link(String base, String href) throws Exception {
        URI target = new URI(null, null, "/" + base, null).resolve(new URI(href)).normalize();
        if (target.isAbsolute() || target.getAuthority() != null || !target.getPath().startsWith("/")
                || target.getPath().startsWith("/../")) throw new IOException("Unsupported contents link: " + href);
        return target;
    }

    static final class Entry {
        final String label;
        final String href;
        final boolean group;
        Entry(String label, String href, boolean group) {
            this.label = label; this.href = href; this.group = group;
        }
    }

    private static final java.util.regex.Pattern CHAPTER =
        java.util.regex.Pattern.compile("^(глава|chapter)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);

    static String entryLabel(Entry entry, boolean dash) {
        return (dash && !entry.group && CHAPTER.matcher(entry.label).find() ? "— " : "") + entry.label;
    }

    static String contents(List<Entry> entries, boolean dash) {
        StringBuilder out = new StringBuilder();
        for (Entry entry : entries) {
            out.append("<p style=\"margin-left:0;margin-right:0;text-indent:0;margin-top:")
                .append(entry.group ? "1.2" : "0.3").append("em;margin-bottom:0.35em\">");
            if (entry.group) out.append("<strong>");
            out.append("<a href=\"").append(esc(entry.href)).append("\">")
                .append(esc(entryLabel(entry, dash))).append("</a>");
            if (entry.group) out.append("</strong>");
            out.append("</p>");
        }
        return out.toString();
    }

    static String page(String title, String body) {
        return "<html xmlns=\"" + XHTML + "\"><head><title>" + esc(title)
            + "</title></head><body>" + body + "</body></html>";
    }

    private static String checkedHref(Map<String,byte[]> files, Map<String,Set<String>> ids,
                                      String source, String href, String output) throws Exception {
        URI target = link(source, href);
        String path = target.getPath().substring(1);
        byte[] bytes = require(files, path);
        String id = target.getFragment();
        if (id != null && !id.isEmpty()) {
            if (!ids.containsKey(path)) {
                Set<String> values = new HashSet<>();
                NodeList all = parse(bytes).getElementsByTagName("*");
                for (int i = 0; i < all.getLength(); i++) {
                    Element node = (Element)all.item(i);
                    values.add(node.getAttribute("id"));
                    values.add(node.getAttribute("name"));
                    values.add(node.getAttributeNS("http://www.w3.org/XML/1998/namespace", "id"));
                }
                ids.put(path, values);
            }
            if (!ids.get(path).contains(id)) throw new IOException("Missing chapter link: " + href);
        }
        String[] from = output.split("/");
        String[] to = path.split("/");
        int shared = 0;
        while (shared < from.length - 1 && shared < to.length - 1 && from[shared].equals(to[shared])) shared++;
        StringBuilder relative = new StringBuilder();
        for (int i = shared; i < from.length - 1; i++) relative.append("../");
        for (int i = shared; i < to.length; i++) {
            if (i > shared) relative.append('/');
            relative.append(to[i]);
        }
        return new URI(null, null, relative.toString(), null, id).toASCIIString();
    }

    static int repair(InputStream input, Map<String,byte[]> files, boolean dash,
                      boolean detect, boolean fix) throws Exception {
        int remaining = LIMIT;
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry item;
            while ((item = zip.getNextEntry()) != null) {
                if (item.isDirectory()) continue;
                if (files.size() >= 8192) throw new IOException("This EPUB contains too many files.");
                byte[] bytes = read(zip, remaining);
                remaining -= bytes.length;
                if (files.put(item.getName(), bytes) != null) throw new IOException("Duplicate EPUB file.");
            }
        }
        if (files.containsKey("META-INF/encryption.xml")) {
            for (Element method : elements(parse(files.get("META-INF/encryption.xml")), "EncryptionMethod")) {
                String algorithm = method.getAttribute("Algorithm");
                if (!algorithm.equals("http://www.idpf.org/2008/embedding")
                        && !algorithm.equals("http://ns.adobe.com/pdf/enc#RC"))
                    throw new IOException("Encrypted ebooks are unsupported.");
            }
        }
        String opfPath = first(parse(require(files, "META-INF/container.xml")), "rootfile").getAttribute("full-path");
        Document packageDoc = parse(require(files, opfPath));
        Element manifest = first(packageDoc, "manifest");
        Element spine = first(packageDoc, "spine");
        String navPath = null, ncxPath = null;
        Set<String> itemIds = new HashSet<>();
        for (Element item : elements(manifest, "item")) {
            itemIds.add(item.getAttribute("id"));
            if (Arrays.asList(item.getAttribute("properties").split("\\s+")).contains("nav"))
                navPath = link(opfPath, item.getAttribute("href")).getPath().substring(1);
            if (item.getAttribute("id").equals(spine.getAttribute("toc"))
                    || (ncxPath == null && item.getAttribute("media-type").equals("application/x-dtbncx+xml")))
                ncxPath = link(opfPath, item.getAttribute("href")).getPath().substring(1);
        }
        String folder = opfPath.substring(0, opfPath.lastIndexOf('/') + 1);
        String id = "obc-contents";
        while (files.containsKey(folder + id + ".xhtml") || itemIds.contains(id)) id += "-page";
        String path = folder + id + ".xhtml";
        List<Entry> entries = new ArrayList<>();
        Map<String,Set<String>> ids = new HashMap<>();
        if (navPath != null) {
            Document nav = parse(require(files, navPath));
            for (Element section : elements(nav, "nav")) {
                if (!Arrays.asList(section.getAttributeNS(EPUB, "type").split("\\s+")).contains("toc")) continue;
                for (Element anchor : elements(section, "a")) {
                    Node parent = anchor.getParentNode();
                    while (parent instanceof Element && parent != section
                            && !"li".equals(parent.getLocalName())) parent = parent.getParentNode();
                    boolean group = parent instanceof Element && parent != section
                        && !elements(parent, "ol").isEmpty();
                    entries.add(new Entry(anchor.getTextContent().trim(), checkedHref(files, ids, navPath,
                        anchor.getAttribute("href"), path), group));
                }
                break;
            }
        }
        if (entries.isEmpty() && ncxPath != null) {
            Document ncx = parse(require(files, ncxPath));
            for (Element point : elements(ncx, "navPoint")) {
                Element content = first(point, "content");
                entries.add(new Entry(first(first(point, "navLabel"), "text").getTextContent().trim(),
                    checkedHref(files, ids, ncxPath, content.getAttribute("src"), path),
                    !elements(point, "navPoint").isEmpty()));
            }
        }
        boolean detected = entries.isEmpty() && detect;
        if (detected) {
            Map<String,String> manifestPaths = new HashMap<>();
            for (Element item : elements(manifest, "item")) {
                if ("application/xhtml+xml".equals(item.getAttribute("media-type")))
                    manifestPaths.put(item.getAttribute("id"), link(opfPath, item.getAttribute("href")).getPath().substring(1));
            }
            for (Element item : elements(spine, "itemref")) {
                String chapter = manifestPaths.get(item.getAttribute("idref"));
                if (chapter == null || chapter.equals(navPath)) continue;
                Document content = parse(require(files, chapter));
                List<Element> headings = elements(content, "h1");
                if (headings.isEmpty()) headings = elements(content, "h2");
                for (Element heading : headings) {
                    String label = heading.getTextContent().trim();
                    if (label.isEmpty()) continue;
                    String anchor = heading.getAttribute("id");
                    if (anchor.isEmpty()) {
                        anchor = "obc-" + UUID.randomUUID();
                        heading.setAttribute("id", anchor);
                    }
                    files.put(chapter, serialize(content));
                    ids.remove(chapter);
                    URI target = new URI(null, null, "/" + chapter, null, anchor);
                    entries.add(new Entry(label, checkedHref(files, ids, path, target.toASCIIString(), path), false));
                }
            }
        }
        if (entries.isEmpty() || (!fix && !detected)) return entries.size();
        if (detected && navPath == null && ncxPath == null) {
            ncxPath = folder + id + ".ncx";
            Element item = packageDoc.createElementNS(OPF, "item");
            item.setAttribute("id", id + "-nav");
            item.setAttribute("href", id + ".ncx");
            item.setAttribute("media-type", "application/x-dtbncx+xml");
            manifest.appendChild(item);
            spine.setAttribute("toc", item.getAttribute("id"));
            String uid = field(first(packageDoc, "metadata"), "identifier");
            files.put(ncxPath, utf("<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">"
                + "<head><meta name=\"dtb:uid\" content=\"" + esc(uid) + "\"/>"
                + "<meta name=\"dtb:depth\" content=\"1\"/>"
                + "<meta name=\"dtb:totalPageCount\" content=\"0\"/>"
                + "<meta name=\"dtb:maxPageNumber\" content=\"0\"/></head>"
                + "<docTitle><text>" + esc(field(first(packageDoc, "metadata"), "title"))
                + "</text></docTitle><navMap/></ncx>"));
        }
        String markup = contents(entries, dash);
        if (navPath != null) {
            Document nav = parse(require(files, navPath));
            for (Element section : elements(nav, "nav")) {
                if (!Arrays.asList(section.getAttributeNS(EPUB, "type").split("\\s+")).contains("toc")) continue;
                StringBuilder flat = new StringBuilder("<ol xmlns=\"" + XHTML + "\">");
                for (Entry e : entries) {
                    String original = checkedHref(files, ids, path, e.href, navPath);
                    flat.append("<li><a href=\"").append(esc(original)).append("\">")
                        .append(e.group ? "<strong>" : "").append(esc(entryLabel(e, dash)))
                        .append(e.group ? "</strong>" : "").append("</a></li>");
                }
                flat.append("</ol>");
                Node replacement = nav.importNode(parse(utf(flat.toString())).getDocumentElement(), true);
                Element oldList = null;
                for (Element list : elements(section, "ol")) {
                    if (list.getParentNode() == section) {
                        oldList = list;
                        break;
                    }
                }
                if (oldList == null) section.appendChild(replacement);
                else {
                    section.insertBefore(replacement, oldList);
                    section.removeChild(oldList);
                }
                files.put(navPath, serialize(nav));
                break;
            }
        }
        if (ncxPath != null) {
            Document ncx = parse(require(files, ncxPath));
            List<Element> points = elements(first(ncx, "navMap"), "navPoint");
            Element map = first(ncx, "navMap");
            int order = 1;
            if (detected && points.isEmpty()) {
                String ns = map.getNamespaceURI();
                for (Entry entry : entries) {
                    Element point = ncx.createElementNS(ns, "navPoint");
                    point.setAttribute("id", "detected-" + order);
                    point.setAttribute("playOrder", Integer.toString(order++));
                    Element label = ncx.createElementNS(ns, "navLabel");
                    Element text = ncx.createElementNS(ns, "text");
                    text.setTextContent(entry.label);
                    label.appendChild(text);
                    point.appendChild(label);
                    Element target = ncx.createElementNS(ns, "content");
                    target.setAttribute("src", checkedHref(files, ids, path, entry.href, ncxPath));
                    point.appendChild(target);
                    map.appendChild(point);
                }
            }
            for (Element point : points) {
                // Detach first: Android DOM checks the insertion index after removing a moved node.
                point.getParentNode().removeChild(point);
                map.appendChild(point);
                point.setAttribute("playOrder", Integer.toString(order++));
            }
            for (Element meta : elements(ncx, "meta"))
                if ("dtb:depth".equals(meta.getAttribute("name")) && !points.isEmpty())
                    meta.setAttribute("content", "1");
            // Keep EPUB 2 menu targets and labels, while removing nesting.
            files.put(ncxPath, serialize(ncx));
        }
        if (!fix) {
            files.put(opfPath, serialize(packageDoc));
            return entries.size();
        }
        files.put(path, utf(page("Contents", "<h1>Contents</h1>" + markup)));
        List<Element> guides = elements(packageDoc, "guide");
        Element guide;
        if (guides.isEmpty()) {
            guide = packageDoc.createElementNS(OPF, "guide");
            packageDoc.getDocumentElement().appendChild(guide);
        } else {
            guide = guides.get(0);
        }
        Element tocReference = null;
        for (Element reference : elements(guide, "reference")) {
            if ("toc".equals(reference.getAttribute("type"))) {
                tocReference = reference;
                break;
            }
        }
        if (tocReference == null) {
            tocReference = packageDoc.createElementNS(OPF, "reference");
            tocReference.setAttribute("type", "toc");
            guide.appendChild(tocReference);
        }
        tocReference.setAttribute("href", id + ".xhtml");
        tocReference.setAttribute("title", "Contents");
        Element item = packageDoc.createElementNS(OPF, "item");
        item.setAttribute("id", id);
        item.setAttribute("href", id + ".xhtml");
        item.setAttribute("media-type", "application/xhtml+xml");
        manifest.appendChild(item);
        Element ref = packageDoc.createElementNS(OPF, "itemref");
        ref.setAttribute("idref", id);
        spine.appendChild(ref);
        files.put(opfPath, serialize(packageDoc));
        return entries.size();
    }

    static void fb2Section(Element section, StringBuilder body, List<Entry> nav) {
        String id = null;
        for (Node node = section.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element)) continue;
            Element child = (Element)node;
            if ("title".equals(child.getLocalName())) {
                id = "section-" + (nav.size() + 1);
                String title = child.getTextContent().trim().replaceAll("\\s+", " ");
                nav.add(new Entry(title, "book.xhtml#" + id, !elements(section, "section").isEmpty()));
                body.append("<h2 id=\"").append(id).append("\">").append(esc(title)).append("</h2>");
            } else if ("section".equals(child.getLocalName())) {
                fb2Section(child, body, nav);
            } else if ("empty-line".equals(child.getLocalName())) {
                body.append("<br/>");
            } else {
                List<Element> paragraphs = elements(child, "p");
                if (paragraphs.isEmpty()) body.append("<p>").append(esc(child.getTextContent())).append("</p>");
                else for (Element paragraph : paragraphs)
                    body.append("<p>").append(esc(paragraph.getTextContent())).append("</p>");
            }
        }
    }

    static int create(InputStream input, String filename, Map<String,byte[]> files, boolean dash, boolean fix) throws Exception {
        byte[] bytes = read(input, LIMIT);
        String title = filename.replaceFirst("\\.[^.]+$", "");
        StringBuilder body = new StringBuilder();
        List<Entry> entries = new ArrayList<>();
        if (filename.toLowerCase(Locale.ROOT).endsWith(".fb2")) {
            Document fb2 = parse(bytes);
            if (!"FictionBook".equals(fb2.getDocumentElement().getLocalName())) throw new IOException("Invalid FB2 file.");
            List<Element> titles = elements(fb2, "book-title");
            if (!titles.isEmpty() && !titles.get(0).getTextContent().trim().isEmpty()) title = titles.get(0).getTextContent().trim();
            for (Element part : elements(fb2, "body")) fb2Section(part, body, entries);
        } else {
            String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            for (String line : text.split("\\R", -1))
                body.append(line.trim().isEmpty() ? "<br/>" : "<p>" + esc(line) + "</p>");
        }
        if (parse(utf(page(title, body.toString()))).getDocumentElement().getTextContent().trim().equals(title.trim()))
            throw new IOException("This book contains no readable text.");
        String uid = "urn:uuid:" + UUID.randomUUID();
        files.put("META-INF/container.xml", utf("<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles><rootfile full-path=\"book.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>"));
        boolean chapters = !entries.isEmpty();
        boolean hasContentsPage = chapters && fix;
        files.put("book.opf", utf("<package xmlns=\"" + OPF + "\" version=\"3.0\" unique-identifier=\"book-id\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:identifier id=\"book-id\">" + uid + "</dc:identifier><dc:title>" + esc(title) + "</dc:title><dc:language>und</dc:language><meta property=\"dcterms:modified\">" + new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT) {{ setTimeZone(TimeZone.getTimeZone("UTC")); }}.format(new Date()) + "</meta></metadata><manifest><item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/><item id=\"book\" href=\"book.xhtml\" media-type=\"application/xhtml+xml\"/>"
            + (hasContentsPage ? "<item id=\"contents\" href=\"contents.xhtml\" media-type=\"application/xhtml+xml\"/>" : "")
            + "</manifest><spine>" + "<itemref idref=\"book\"/>" + (hasContentsPage ? "<itemref idref=\"contents\"/>" : "") + "</spine>"
            + (hasContentsPage ? "<guide><reference type=\"toc\" title=\"Contents\" href=\"contents.xhtml\"/></guide>" : "")
            + "</package>"));
        files.put("book.xhtml", utf(page(title, body.toString())));
        if (hasContentsPage) files.put("contents.xhtml", utf(page("Contents", "<h1>Contents</h1>" + contents(entries, dash))));
        StringBuilder nav = new StringBuilder("<nav xmlns:epub=\"" + EPUB + "\" epub:type=\"toc\"><h1>Contents</h1><ol>");
        for (Entry entry : entries) nav.append("<li><a href=\"").append(esc(entry.href)).append("\">").append(esc(entry.label)).append("</a></li>");
        if (!chapters) nav.append("<li><a href=\"book.xhtml\">").append(esc(title)).append("</a></li>");
        files.put("nav.xhtml", utf(page("Contents", nav.append("</ol></nav>").toString())));
        return entries.size();
    }

    static final class Info {
        String title = "", author = "", language = "";
        int chapters, pages;
    }

    static String packagePath(Map<String,byte[]> files) throws Exception {
        return first(parse(require(files, "META-INF/container.xml")), "rootfile").getAttribute("full-path");
    }

    static String field(Element metadata, String name) {
        List<Element> values = elements(metadata, name);
        return values.isEmpty() ? "" : values.get(0).getTextContent().trim();
    }

    static void updateMetadata(Document doc, Element metadata, String name, String value) {
        if (value == null) return;
        List<Element> found = elements(metadata, name);
        if (!found.isEmpty()) {
            found.get(0).setTextContent(value);
        } else if (!value.isEmpty()) {
            Element item = doc.createElementNS("http://purl.org/dc/elements/1.1/", "dc:" + name);
            item.setTextContent(value);
            metadata.appendChild(item);
        }
    }

    static Info inspect(InputStream input, String name) throws Exception {
        Map<String,byte[]> files = new LinkedHashMap<>();
        Info info = new Info();
        boolean epub = name.toLowerCase(Locale.ROOT).endsWith(".epub");
        if (!epub && name.toLowerCase(Locale.ROOT).endsWith(".fb2")) {
            byte[] bytes = read(input, LIMIT);
            Document fb2 = parse(bytes);
            List<Element> metadata = elements(fb2, "title-info");
            if (!metadata.isEmpty()) {
                Element source = metadata.get(0);
                info.title = field(source, "book-title");
                info.language = field(source, "lang");
                List<Element> authors = elements(source, "author");
                if (!authors.isEmpty()) {
                    Element author = authors.get(0);
                    info.author = (field(author, "first-name") + " " + field(author, "last-name")).trim();
                }
            }
            info.chapters = create(new ByteArrayInputStream(bytes), name, files, false, false);
            return info;
        }
        info.chapters = epub ? repair(input, files, false, false, false)
            : create(input, name, files, false, false);
        Element metadata = first(parse(require(files, packagePath(files))), "metadata");
        info.title = field(metadata, "title");
        info.author = field(metadata, "creator");
        info.language = field(metadata, "language");
        for (Map.Entry<String,byte[]> file : files.entrySet()) {
            if (!file.getKey().toLowerCase(Locale.ROOT).endsWith(".ncx")) continue;
            for (Element pages : elements(parse(file.getValue()), "pageList"))
                info.pages += elements(pages, "pageTarget").size();
            break;
        }
        if (info.pages == 0 && epub) {
            Document pkg = parse(require(files, packagePath(files)));
            for (Element item : elements(first(pkg, "manifest"), "item")) {
                if (!Arrays.asList(item.getAttribute("properties").split("\\s+")).contains("nav")) continue;
                String nav = link(packagePath(files), item.getAttribute("href")).getPath().substring(1);
                for (Element node : elements(parse(require(files, nav)), "nav"))
                    if (Arrays.asList(node.getAttributeNS(EPUB, "type").split("\\s+")).contains("page-list"))
                        info.pages += elements(node, "a").size();
            }
        }
        return info;
    }

    public static int convert(InputStream input, OutputStream output, String name, boolean dash) throws Exception {
        return convert(input, output, name, dash, false, true, null, null);
    }

    public static int convert(InputStream input, OutputStream output, String name, boolean dash,
                              boolean detect, boolean fix, String title, String author) throws Exception {
        Map<String,byte[]> files = new LinkedHashMap<>();
        String filename = name.toLowerCase(Locale.ROOT);
        int count;
        if (filename.endsWith(".epub")) count = repair(input, files, dash, detect, fix);
        else if (filename.endsWith(".fb2") || filename.endsWith(".txt"))
            count = create(input, name, files, dash, fix);
        else throw new IOException("Choose an EPUB, FB2 or UTF-8 TXT file.");
        if (title != null || author != null) {
            String path = packagePath(files);
            Document doc = parse(require(files, path));
            Element metadata = first(doc, "metadata");
            updateMetadata(doc, metadata, "title", title);
            updateMetadata(doc, metadata, "creator", author);
            files.put(path, serialize(doc));
        }
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            byte[] type = utf("application/epub+zip");
            ZipEntry mimetype = new ZipEntry("mimetype");
            mimetype.setMethod(ZipEntry.STORED);
            mimetype.setSize(type.length);
            CRC32 crc = new CRC32();
            crc.update(type);
            mimetype.setCrc(crc.getValue());
            zip.putNextEntry(mimetype);
            zip.write(type);
            zip.closeEntry();
            for (Map.Entry<String,byte[]> item : files.entrySet()) {
                if (item.getKey().equals("mimetype")) continue;
                zip.putNextEntry(new ZipEntry(item.getKey()));
                zip.write(item.getValue());
                zip.closeEntry();
            }
        }
        return count;
    }
}
