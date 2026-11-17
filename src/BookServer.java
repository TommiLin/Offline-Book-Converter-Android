package io.github.tommilin.bookconverter;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class BookServer implements AutoCloseable {
    private final ServerSocket listener;
    private final File library;
    private static final String DOWNLOAD_PATH = "/b/";
    private static final int PORT = 8080;
    private Socket client;

    BookServer(InetAddress address, File library) throws IOException {
        this.library = library;
        listener = new ServerSocket(PORT, 4, address);
        Thread thread = new Thread(this::serve, "book-sharing");
        thread.setDaemon(true);
        thread.start();
    }

    String url() {
        return "http://" + listener.getInetAddress().getHostAddress() + ":" + listener.getLocalPort();
    }

    static File[] books(File library) {
        File[] files = library.listFiles(file -> file.isFile() && (file.getName().endsWith(".epub") || file.getName().endsWith(".mobi") || file.getName().endsWith(".azw3") || file.getName().endsWith(".fb2")));
        if (files == null) return new File[0];
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        return files;
    }

    private void serve() {
        while (!listener.isClosed()) {
            try (Socket socket = listener.accept()) {
                synchronized (this) {
                    if (listener.isClosed()) return;
                    client = socket;
                }
                socket.setSoTimeout(2500);
                handle(socket);
            } catch (IOException ignored) {
                // A browser can cancel a request. Continue serving the next one.
            } finally {
                synchronized (this) { client = null; }
            }
        }
    }

    private static String requestLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (line.size() < 4096) {
            int c = input.read();
            if (c == -1 || c == '\n') return line.toString("US-ASCII").trim();
            line.write(c);
        }
        return "";
    }

    private void handle(Socket socket) throws IOException {
        InputStream input = new BufferedInputStream(socket.getInputStream());
        String[] request = requestLine(input).split(" ");
        OutputStream output = new BufferedOutputStream(socket.getOutputStream());
        if (request.length != 3 || !("GET".equals(request[0]) || "HEAD".equals(request[0]))) {
            page(output, "400 Bad Request", "Invalid request.", false);
            return;
        }
        // Drain headers with a fixed budget before sending a response.
        for (int i = 0; i < 32; i++) {
            if (requestLine(input).isEmpty()) break;
            if (i == 31) return;
        }
        boolean head = "HEAD".equals(request[0]);
        String target = request[1];
        if (target.equals("/")) {
            StringBuilder html = new StringBuilder("<h1>Converted books</h1><p>Offline Book Converter</p>");
            File[] books = books(library);
            if (books.length == 0) html.append("<p>Convert a book on the phone to add it here.</p>");
            else {
                html.append("<p>").append(books.length).append(" books</p><ul>");
                for (File file : books) {
                    html.append("<li><a href=\"").append(DOWNLOAD_PATH).append(encode(file.getName())).append("\">")
                        .append(Books.esc(file.getName())).append("</a></li>");
                }
                html.append("</ul><p>Kindle 4 requires a Kindle-compatible book such as MOBI for reading.</p>");
            }
            page(output, "200 OK", html.toString(), head);
        } else if (target.startsWith(DOWNLOAD_PATH)) {
            String name = target.substring(DOWNLOAD_PATH.length());
            for (File file : books(library)) {
                if (!encode(file.getName()).equals(name)) continue;
                try (InputStream book = new FileInputStream(file)) {
                    headers(output, "200 OK", mime(file.getName()), file.length(),
                        "Content-Disposition: attachment; filename=\"" + file.getName().replaceAll("[^A-Za-z0-9._-]", "_")
                        + "\"; filename*=UTF-8''" + encode(file.getName()) + "\r\n");
                    if (!head) {
                        byte[] buffer = new byte[8192];
                        int size;
                        while ((size = book.read(buffer)) != -1) output.write(buffer, 0, size);
                    }
                    output.flush();
                }
                return;
            }
            page(output, "404 Not Found", "Book not found.", head);
        } else {
            page(output, "404 Not Found", "Open the link shown on the phone.", head);
        }
    }

    static String mime(String name) {
        if (name.endsWith(".mobi")) return "application/x-mobipocket-ebook";
        if (name.endsWith(".azw3")) return "application/vnd.amazon.ebook";
        if (name.endsWith(".fb2")) return "application/x-fictionbook+xml";
        return "application/epub+zip";
    }

    private static String encode(String name) throws UnsupportedEncodingException {
        return URLEncoder.encode(name, "UTF-8").replace("+", "%20").replace("*", "%2A");
    }

    private static void page(OutputStream output, String status, String contents, boolean head) throws IOException {
        byte[] bytes = ("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>Converted books</title>"
            + "<style>body{font:17px Georgia,serif;color:#111;background:#fff;margin:1.2em}"
            + "h1{font-size:1.5em}ul{list-style:none;padding:0}li{border-top:1px solid #777;padding:.8em 0}"
            + "a{color:#000;text-decoration:underline;word-wrap:break-word}</style></head><body>"
            + contents + "</body></html>").getBytes(StandardCharsets.UTF_8);
        headers(output, status, "text/html; charset=utf-8", bytes.length, "");
        if (!head) output.write(bytes);
        output.flush();
    }

    private static void headers(OutputStream output, String status, String mime, long size, String extra) throws IOException {
        output.write(("HTTP/1.0 " + status + "\r\nContent-Type: " + mime + "\r\nContent-Length: " + size
            + "\r\nCache-Control: no-store\r\nConnection: close\r\n" + extra + "\r\n")
            .getBytes(StandardCharsets.US_ASCII));
    }

    @Override public synchronized void close() {
        try { listener.close(); } catch (IOException ignored) { }
        if (client != null) try { client.close(); } catch (IOException ignored) { }
    }
}
