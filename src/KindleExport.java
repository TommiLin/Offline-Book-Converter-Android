package io.github.tommilin.bookconverter;

import android.content.Context;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import java.io.*;

final class KindleExport {
    static void write(Context context, File epub, File output, boolean legacy) throws IOException {
        String[] common = {"--chapter", "/", "--chapter-mark", "none", "--no-chapters-in-toc",
            "--output-profile", "kindle", "--no-inline-toc"};
        Object[] args = new Object[common.length + (legacy ? 4 : 2)];
        args[0] = epub.getAbsolutePath();
        args[1] = output.getAbsolutePath();
        System.arraycopy(common, 0, args, 2, common.length);
        if (legacy) {
            args[args.length - 2] = "--mobi-file-type";
            args[args.length - 1] = "old";
        }
        convert(context, args);
        verify(output, legacy ? 6 : 8);
    }

    static void toEpub(Context context, File input, File output) throws IOException {
        convert(context, new Object[]{input.getAbsolutePath(), output.getAbsolutePath(),
            "--chapter", "/", "--chapter-mark", "none", "--no-chapters-in-toc"});
        try (java.util.zip.ZipFile epub = new java.util.zip.ZipFile(output)) {
            if (epub.getEntry("META-INF/container.xml") == null) throw new IOException("Invalid EPUB output.");
        }
    }

    static void toFb2(Context context, File input, File output) throws Exception {
        convert(context, new Object[]{input.getAbsolutePath(), output.getAbsolutePath(),
            "--chapter", "/", "--chapter-mark", "none", "--no-chapters-in-toc"});
        try (InputStream stream = new FileInputStream(output)) {
            if (!"FictionBook".equals(Books.parse(Books.read(stream, Books.LIMIT)).getDocumentElement().getLocalName()))
                throw new IOException("Invalid FB2 output.");
        }
    }

    private static void convert(Context context, Object[] args) throws IOException {
        if (!Python.isStarted()) Python.start(new AndroidPlatform(context.getApplicationContext()));
        Python python = Python.getInstance();
        python.getModule("tempfile").put("tempdir", context.getCacheDir().getAbsolutePath());
        PyObject module = python.getModule("ebook_converter_lib");
        try (PyObject result = module.callAttr("convert", args)) {
            if (!result.callAttr("__getitem__", "success").toBoolean())
                throw new IOException(result.callAttr("__getitem__", "message").toString());
        }
    }

    static void verify(File output, int version) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(output, "r")) {
            file.seek(60);
            if (file.readLong() != 0x424f4f4b4d4f4249L) throw new IOException("Invalid Kindle book header.");
            file.seek(78);
            long record = Integer.toUnsignedLong(file.readInt());
            file.seek(record + 16);
            if (file.readInt() != 0x4d4f4249) throw new IOException("Missing MOBI header.");
            file.seek(record + 36);
            if (file.readInt() != version) throw new IOException("Unexpected Kindle book version.");
        }
    }
}
