package io.github.tommilin.bookconverter;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.view.MotionEvent;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.util.Locale;
import java.net.*;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.LinkProperties;
import android.net.LinkAddress;
import android.view.WindowManager;

public final class MainActivity extends Activity {
    private static final int PICK = 1, SAVE = 2, IMPORT_MOBI = 3;
    private Uri source;
    private String filename;
    private File pending;
    private File saveSelection;
    private FrameLayout screens;
    private ScrollView converterScreen, historyScreen;
    private Button convertTab, historyTab, change, clearSelection;
    private float touchX, touchY;
    private static final String[] FORMAT_LABELS = {"EPUB", "MOBI 6 (Kindle 4)", "AZW3", "FB2"};
    private Button choose, convert, save;
    private ImageButton add;
    private TextView bookInfo;
    private File queue;
    private static final class Draft {
        Uri uri;
        File folder;
        String name, title, author, extension = ".epub";
        boolean detect = true, repair = true, dash = true;
        Books.Info info;
        long size = -1;
        long added = System.currentTimeMillis();

        org.json.JSONObject json() throws org.json.JSONException {
            return new org.json.JSONObject().put("name", name).put("title", title)
                .put("author", author).put("extension", extension).put("detect", detect)
                .put("repair", repair).put("dash", dash).put("added", added);
        }

        static Draft read(File folder) throws Exception {
            Draft draft = new Draft();
            draft.folder = folder;
            org.json.JSONObject data;
            try (InputStream in = new android.util.AtomicFile(new File(folder, "job.json")).openRead()) {
                data = new org.json.JSONObject(new String(Books.read(in, 32768), java.nio.charset.StandardCharsets.UTF_8));
            }
            draft.name = data.getString("name");
            draft.added = data.optLong("added", folder.lastModified());
            draft.size = new File(folder, "book").length();
            draft.title = data.optString("title");
            draft.author = data.optString("author");
            draft.extension = data.optString("extension", ".epub");
            if (!java.util.Arrays.asList(FORMATS).contains(draft.extension)) throw new IOException("Unknown output format.");
            draft.detect = data.optBoolean("detect", true);
            draft.repair = data.optBoolean("repair", true);
            draft.dash = data.optBoolean("dash", true);
            return draft;
        }

        InputStream open(android.content.ContentResolver resolver) throws IOException {
            InputStream input = folder == null ? resolver.openInputStream(uri) : new FileInputStream(new File(folder, "book"));
            if (input == null) throw new IOException("Could not open the book.");
            return input;
        }
    }
    private static final java.util.Set<String> INPUTS = new java.util.HashSet<>(java.util.Arrays.asList(
        ".epub", ".fb2", ".txt", ".mobi", ".azw", ".azw3", ".docx", ".odt", ".rtf", ".html", ".htm", ".pdf"));
    private static final String[] FORMATS = {".epub", ".mobi", ".azw3", ".fb2"};
    private TextView file, status;
    private ProgressBar progress;
    private TextView conversionResult;
    private boolean selecting;
    private final java.util.Set<File> selected = new java.util.LinkedHashSet<>();
    private final android.os.Handler clock = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateProgress();
            if (session.busy && session.conversion) clock.postDelayed(this, 250);
        }
    };
    private Button share, importMobi;
    private static final int SURFACE = 0xff171b21;
    private static final int CARD = 0xff222831;
    private static final int INK = 0xffedf1f7;
    private static final int NAVY = 0xffa9c7e9;
    private static final int SECONDARY = 0xffaab7c7;
    private static final int OUTLINE = 0xff3a4553;
    private TextView shareStatus;
    private File library;
    private static final class Session {
        boolean busy;
        boolean conversion;
        long started;
        int done, total;
        String resultBanner;
        boolean resultSuccess;
        File result;
        Draft draft;
        String message;
        MainActivity screen;
        BookServer server;
        boolean checkingUpdate;
        String updateVersion;
        void stopSharing() {
            if (server != null) server.close();
            server = null;
        }
    }
    private Session session;

    @Override public Object onRetainNonConfigurationInstance() { return session; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Object previous = getLastNonConfigurationInstance();
        session = previous instanceof Session ? (Session)previous : new Session();
        session.screen = this;
        library = new File(getFilesDir(), "converted-books");
        queue = new File(getFilesDir(), "queued-books");
        if (state != null) {
            source = state.getParcelable("source");
            filename = state.getString("filename");
            if (session.draft == null && source != null && filename != null) {
                session.draft = new Draft();
                session.draft.uri = source;
                session.draft.name = filename;
                session.draft.title = state.getString("draftTitle", "");
                session.draft.author = state.getString("draftAuthor", "");
                session.draft.extension = state.getString("draftExtension", ".epub");
                session.draft.detect = state.getBoolean("draftDetect", true);
                session.draft.repair = state.getBoolean("draftRepair", true);
                session.draft.dash = state.getBoolean("draftDash", true);
                session.draft.size = state.getLong("draftSize", -1);
                session.draft.added = state.getLong("draftAdded", System.currentTimeMillis());
            }
            String requested = state.getString("saveSelection");
            if (requested != null) saveSelection = new File(requested);
            String path = state.getString("pending");
            if (path != null && new File(path).isFile()) session.result = new File(path);
        }
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(18), dp(12), dp(18), 0);
        shell.setFitsSystemWindows(true);
        shell.setBackgroundColor(SURFACE);
        setContentView(shell);
        applyBars(getWindow());

        LinearLayout header = horizontal();
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(label("Book Converter", 24, INK, true));
        titles.addView(label("Convert books, detect chapters, and fix their layout.", 13, SECONDARY, false));
        TextView supported = label("Input: EPUB, FB2, TXT, MOBI, AZW, AZW3, DOCX, ODT, RTF, HTML, PDF", 12, SECONDARY, false);
        supported.setPadding(0, dp(5), 0, dp(8));
        titles.addView(supported);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        add = iconButton(R.drawable.ic_add, "Add book", this::pickBook);
        android.graphics.drawable.GradientDrawable circle = shape(NAVY, 24, 0);
        add.setBackground(new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x33263344), circle, null));
        add.setColorFilter(SURFACE);
        header.addView(iconButton(R.drawable.ic_info, "About and help", this::showAbout), actionSize());
        header.addView(add, actionSize());
        shell.addView(header);
        shell.addView(screenTabs());
        screens = new FrameLayout(this);
        LinearLayout.LayoutParams content = new LinearLayout.LayoutParams(-1, 0, 1);
        content.topMargin = dp(16);
        shell.addView(screens, content);
        converterScreen = new ScrollView(this);
        converterScreen.setFillViewport(true);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(0, 0, 0, dp(20));
        converterScreen.addView(layout);
        screens.addView(converterScreen, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout sourceCard = horizontal();
        sourceCard.setGravity(android.view.Gravity.TOP);
        sourceCard.setPadding(dp(12), dp(10), dp(4), dp(10));
        sourceCard.setBackground(shape(CARD, 14, OUTLINE));
        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        file = label("", 16, INK, true);
        file.setMaxLines(2);
        file.setEllipsize(android.text.TextUtils.TruncateAt.END);
        details.addView(file);
        bookInfo = label("", 14, SECONDARY, false);
        bookInfo.setPadding(0, dp(8), 0, 0);
        bookInfo.setLineSpacing(dp(5), 1);
        details.addView(bookInfo);
        sourceCard.addView(details, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        choose = button("Edit", false);
        change = button("Change", false);
        clearSelection = button("Delete", false);
        for (Button action : new Button[]{choose, change, clearSelection}) {
            LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(-1, dp(48));
            if (actions.getChildCount() > 0) spacing.topMargin = dp(6);
            actions.addView(action, spacing);
        }
        sourceCard.addView(actions, new LinearLayout.LayoutParams(dp(88), -2));
        layout.addView(sourceCard);

        convert = button("Convert book", true);
        LinearLayout.LayoutParams convertSpace = new LinearLayout.LayoutParams(-1, dp(50));
        convertSpace.topMargin = dp(18);
        layout.addView(convert, convertSpace);
        save = button("Save book", false);
        LinearLayout.LayoutParams saveSpace = new LinearLayout.LayoutParams(-1, -2);
        saveSpace.topMargin = dp(10);
        layout.addView(save, saveSpace);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(NAVY));
        layout.addView(progress);
        status = label("", 14, SECONDARY, false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        layout.addView(status);
        conversionResult = label("", 16, INK, true);
        conversionResult.setPadding(dp(12), dp(10), dp(12), dp(10));
        conversionResult.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams resultSpace = new LinearLayout.LayoutParams(-1, -2);
        resultSpace.topMargin = dp(8);
        layout.addView(conversionResult, resultSpace);

        LinearLayout wifi = horizontal();
        TextView wifiLabel = label("Wi-Fi sharing", 16, INK, true);
        wifi.addView(wifiLabel, new LinearLayout.LayoutParams(0, -2, 1));
        share = button("Start", false);
        wifi.addView(share);
        layout.addView(wifi);
        shareStatus = label("", 14, SECONDARY, false);
        shareStatus.setTextIsSelectable(true);
        layout.addView(shareStatus);
        importMobi = button("Add a ready MOBI for Kindle", false);
        layout.addView(importMobi);
        share.setOnClickListener(v -> toggleServer());
        importMobi.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, IMPORT_MOBI);
        });
        choose.setOnClickListener(v -> editDraft(session.draft));
        change.setOnClickListener(v -> pickBook());
        clearSelection.setContentDescription("Remove selected book");
        clearSelection.setOnClickListener(v -> {
            session.draft = null;
            source = null;
            filename = null;
            session.message = null;
            refresh();
        });
        convert.setOnClickListener(v -> convertBook());
        save.setOnClickListener(v -> pickSaveDestination(pending));
        refresh();
        android.content.SharedPreferences preferences = getPreferences(MODE_PRIVATE);
        if (!preferences.getBoolean("first_update_check_started", false)) {
            preferences.edit().putBoolean("first_update_check_started", true).apply();
            checkUpdates(false);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        showAvailableUpdate();
    }

    private String installedVersion() {
        return BuildConfig.VERSION_NAME;
    }

    private void showAbout() {
        new AlertDialog.Builder(this).setTitle("About Book Converter")
            .setMessage("Version " + installedVersion()
                + "\nAuthor: TommiLin\n\nConvert books offline, find chapters, and fix contents layout."
                + "\n\nTap + to choose a book, select a format in Edit, and tap Convert now."
                + "\n\nUse Wi-Fi sharing to download finished books from another device on the same network.")
            .setNeutralButton("Links", (dialog, button) -> showHelpLinks())
            .setNegativeButton("Close", null)
            .setPositiveButton("Check for updates", (dialog, button) -> checkUpdates(true))
            .show();
    }

    private void showHelpLinks() {
        String[] links = {"Full README", "GitHub repository", "Author profile", "Release history"};
        new AlertDialog.Builder(this).setTitle("Help and links").setItems(links, (dialog, index) -> {
            if (index == 0) showReadme();
            else if (index == 1) openLink(AppUpdates.REPO);
            else if (index == 2) openLink("https://github.com/TommiLin");
            else openLink(AppUpdates.RELEASES);
        }).setNegativeButton("Back", (dialog, button) -> showAbout()).show();
    }

    private void showReadme() {
        try (InputStream in = getAssets().open("README.md")) {
            String readme = new String(Books.read(in, 128 * 1024), java.nio.charset.StandardCharsets.UTF_8);
            TextView text = label(readme, 14, INK, false);
            text.setPadding(dp(20), dp(8), dp(20), dp(12));
            text.setTextIsSelectable(true);
            ScrollView scroll = new ScrollView(this);
            scroll.addView(text);
            new AlertDialog.Builder(this).setTitle("Full README").setView(scroll)
                .setNeutralButton("GitHub README", (dialog, button) -> openLink(AppUpdates.REPO + "#readme"))
                .setPositiveButton("Close", null).show();
        } catch (IOException error) {
            openLink(AppUpdates.REPO + "#readme");
        }
    }

    private void openLink(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (android.content.ActivityNotFoundException error) {
            new AlertDialog.Builder(this).setTitle("Open in a browser").setMessage(url)
                .setPositiveButton("Close", null).show();
        }
    }

    private void checkUpdates(boolean manual) {
        if (session.checkingUpdate) {
            if (manual) new AlertDialog.Builder(this).setMessage("Checking for updates…")
                .setPositiveButton("Close", null).show();
            return;
        }
        session.checkingUpdate = true;
        String installed = installedVersion();
        new Thread(() -> {
            String version = null;
            boolean available = true;
            try {
                version = AppUpdates.latest(installed);
            } catch (Exception error) {
                available = false;
            }
            String update = version;
            boolean reachable = available;
            runOnUiThread(() -> {
                session.checkingUpdate = false;
                MainActivity current = session.screen;
                if (current == null || current.isFinishing()) return;
                if (update != null) {
                    session.updateVersion = update;
                    current.showAvailableUpdate();
                } else if (manual && current.hasWindowFocus()) {
                    new AlertDialog.Builder(current).setTitle("Updates")
                        .setMessage(reachable ? "No newer Android release is available."
                            : "Could not check GitHub. Try again when you are online.")
                        .setPositiveButton("Close", null).show();
                }
            });
        }, "check-updates").start();
    }

    private void showAvailableUpdate() {
        if (session == null || session.updateVersion == null || isFinishing() || !hasWindowFocus()) return;
        String version = session.updateVersion;
        session.updateVersion = null;
        new AlertDialog.Builder(this).setTitle("Update available")
            .setMessage("Version " + version + " is available on GitHub. Installed: " + installedVersion() + ".")
            .setNegativeButton("Later", null)
            .setPositiveButton("Open GitHub", (dialog, button) -> openLink(AppUpdates.RELEASES + "/tag/v" + version))
            .show();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) showAvailableUpdate();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putParcelable("source", session.draft == null ? null : source);
        if (saveSelection != null) state.putString("saveSelection", saveSelection.getAbsolutePath());
        state.putString("filename", filename);
        if (session.result != null) state.putString("pending", session.result.getAbsolutePath());
        Draft draft = session.draft;
        if (draft != null) {
            state.putString("draftTitle", draft.title);
            state.putString("draftAuthor", draft.author);
            state.putString("draftExtension", draft.extension);
            state.putBoolean("draftDetect", draft.detect);
            state.putBoolean("draftRepair", draft.repair);
            state.putBoolean("draftDash", draft.dash);
            state.putLong("draftSize", draft.size);
            state.putLong("draftAdded", draft.added);
        }
    }

    @Override protected void onDestroy() {
        clock.removeCallbacks(tick);
        closeHistory();
        if (session.screen == this) session.screen = null;
        if (isFinishing()) session.stopSharing();
        super.onDestroy();
    }

    private void refresh() {
        pending = session.result;
        Draft draft = session.draft;
        file.setText(draft == null ? "No book selected"
            : draft.title == null || draft.title.isEmpty() ? displayTitle(draft.name) : draft.title);
        file.setContentDescription(draft == null ? null : draft.name);
        bookInfo.setText(summary(draft));
        status.setText(session.message == null ? "" : session.message);
        choose.setEnabled(!session.busy && draft != null);
        change.setText(draft == null ? "Choose book" : "Change");
        change.setEnabled(!session.busy);
        clearSelection.setEnabled(!session.busy && draft != null);
        add.setEnabled(!session.busy);
        convert.setEnabled(!session.busy && draft != null);
        save.setEnabled(!session.busy && pending != null);
        conversionResult.setVisibility(session.resultBanner == null ? View.GONE : View.VISIBLE);
        conversionResult.setText(session.resultBanner);
        conversionResult.setBackground(shape(session.resultSuccess ? 0xff214036 : 0xff49362e, 12, OUTLINE));
        updateProgress();
        clock.removeCallbacks(tick);
        if (session.busy && session.conversion) clock.postDelayed(tick, 250);
        importMobi.setEnabled(!session.busy);
        boolean serving = session.server != null;
        share.setText(serving ? "Stop" : "Start");
        share.setContentDescription(serving ? "Stop Wi-Fi sharing" : "Start Wi-Fi sharing");
        shareStatus.setText(serving ? "Open on the Kindle browser while both devices are on the same Wi-Fi:\n"
            + session.server.url()
            : "Share books with devices on the same Wi-Fi.");
        if (serving) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        File requestedSave = request == SAVE ? saveSelection : null;
        if (request == SAVE) saveSelection = null;
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == IMPORT_MOBI) {
            String name = displayName(uri);
            run("Adding MOBI…", () -> importMobi(uri, name));
        } else if (request == PICK) {
            String name = displayName(uri);
            if (name == null || !INPUTS.contains(extensionOf(name))) {
                session.message = "Choose one of the supported input formats.";
                refresh();
                return;
            }
            session.result = null;
            source = uri;
            filename = name;
            Draft draft = new Draft();
            draft.uri = uri;
            draft.name = name;
            session.draft = draft;
            run("Reading book details…", () -> {
                draft.size = fileSize(uri);
                File normalized = normalizeDraft(draft);
                try (InputStream in = normalized == null ? draft.open(getContentResolver()) : new FileInputStream(normalized)) {
                    draft.info = Books.inspect(in, normalized == null ? draft.name : "book.epub");
                    draft.title = fallbackTitle(draft, draft.info.title);
                    draft.author = draft.info.author;
                    if (extensionOf(draft.name).equals(".docx") &&
                            ("Unknown".equalsIgnoreCase(draft.author) || "AVS Office Studio".equalsIgnoreCase(draft.author)))
                        draft.author = "";
                } finally {
                    if (normalized != null) normalized.delete();
                }
                return "Ready to convert.";
            });
        } else if (request == SAVE && requestedSave != null) {
            File resultFile = requestedSave;
            run("Saving book…", () -> {
                try (InputStream in = new FileInputStream(resultFile);
                     OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) throw new IOException("Could not open the selected destination.");
                    byte[] bytes = new byte[8192];
                    int count;
                    while ((count = in.read(bytes)) != -1) out.write(bytes, 0, count);
                }
                return "Book saved.";
            });
        }
    }

    private void convertBook() {
        if (session.draft != null) editDraft(session.draft);
    }

    private void convertDraft(Draft draft) {
        runConversion(1, () -> exportDraft(draft));
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static String fallbackTitle(Draft draft, String title) {
        String value = title == null ? "" : title.trim();
        if (value.isEmpty() || value.equalsIgnoreCase("Unknown") || value.matches("(?i)(?:source|prepared)-[0-9]+(?:\\.[a-z0-9]+)?"))
            return draft.name.substring(0, draft.name.length() - extensionOf(draft.name).length());
        return value;
    }

    private File normalizeDraft(Draft draft) throws Exception {
        String extension = extensionOf(draft.name);
        if (extension.equals(".epub") || extension.equals(".fb2") || extension.equals(".txt")) return null;
        File input = File.createTempFile("source-", extension, getCacheDir());
        File epub = null;
        File prepared = null;
        boolean success = false;
        try {
            try (InputStream in = draft.open(getContentResolver());
                 OutputStream out = new FileOutputStream(input)) {
                byte[] buffer = new byte[8192];
                int size;
                long bytes = 0;
                while ((size = in.read(buffer)) != -1) {
                    bytes += size;
                    if (bytes > Books.LIMIT) throw new IOException("This book exceeds the size limit.");
                    out.write(buffer, 0, size);
                }
            }
            if (extension.equals(".docx")) {
                prepared = File.createTempFile("prepared-", ".docx", getCacheDir());
                DocxPreparation.prepare(input, prepared, fallbackTitle(draft, null), draft.detect);
            }
            epub = File.createTempFile("normalized-", ".epub", getCacheDir());
            KindleExport.toEpub(this, prepared == null ? input : prepared, epub);
            success = true;
            return epub;
        } finally {
            input.delete();
            if (prepared != null) prepared.delete();
            if (!success && epub != null) epub.delete();
        }
    }

    private String exportDraft(Draft draft) throws Exception {
        String name = draft.name;
        String extension = draft.extension;
        prepareLibrary();
        File epub = File.createTempFile("repair-", ".epub", getCacheDir());
        File export = null;
        File normalized = null;
        try {
            normalized = normalizeDraft(draft);
            int links;
            try (InputStream in = normalized == null ? draft.open(getContentResolver()) : new FileInputStream(normalized);
                 OutputStream out = new FileOutputStream(epub)) {
                if (in == null) throw new IOException("Could not open the selected book.");
                links = Books.convert(in, out, normalized == null ? name : "book.epub", draft.dash, draft.detect, draft.repair, fallbackTitle(draft, draft.title), draft.author);
            }
            export = File.createTempFile("converted-", extension, getCacheDir());
            if (".epub".equals(extension)) {
                try (InputStream in = new FileInputStream(epub);
                     OutputStream out = new FileOutputStream(export)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                }
            } else {
                exportBook(epub, export, extension);
            }
            String outputName = fallbackTitle(draft, draft.title);
            session.result = store(export, outputName + "_clean", extension);
            if (draft.folder != null && removeDraft(draft.folder)
                    && session.draft != null && draft.folder.equals(session.draft.folder)) {
                session.draft = null;
            }
            String label = extension.substring(1).toUpperCase(Locale.ROOT);
            return links > 0 ? label + " ready. Contents entries: " + links
                : label + " ready. This book has no chapters.";
        } finally {
            epub.delete();
            if (normalized != null) normalized.delete();
            if (export != null) export.delete();
        }
    }

    private void pickBook() {
        if (session.busy) return;
        closeHistory();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, PICK);
    }

    private LinearLayout screenTabs() {
        LinearLayout tabs = horizontal();
        tabs.setBackground(shape(CARD, 12, OUTLINE));
        convertTab = button("Convert", false);
        historyTab = button("History", false);
        convertTab.setOnClickListener(v -> closeHistory());
        historyTab.setOnClickListener(v -> showHistory());
        tabs.addView(convertTab, new LinearLayout.LayoutParams(0, dp(46), 1));
        tabs.addView(historyTab, new LinearLayout.LayoutParams(0, dp(46), 1));
        selectTab(false);
        return tabs;
    }

    private void selectTab(boolean history) {
        convertTab.setSelected(!history);
        historyTab.setSelected(history);
        convertTab.setBackground(shape(history ? CARD : 0xff3a4a60, 12, 0));
        historyTab.setBackground(shape(history ? 0xff3a4a60 : CARD, 12, 0));
    }

    private long fileSize(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0) && cursor.getLong(0) >= 0)
                return cursor.getLong(0);
        } catch (RuntimeException ignored) { }
        try (android.content.res.AssetFileDescriptor info = getContentResolver().openAssetFileDescriptor(uri, "r")) {
            return info == null ? -1 : info.getLength();
        } catch (IOException | RuntimeException ignored) { return -1; }
    }

    private String summary(Draft draft) {
        String missing = "—";
        String input = draft == null ? missing : draft.name.substring(draft.name.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT);
        Books.Info info = draft == null ? null : draft.info;
        String author = draft == null || draft.author == null || draft.author.isEmpty() ? missing : draft.author;
        String size = draft == null || draft.size < 0 ? missing
            : android.text.format.Formatter.formatFileSize(this, draft.size);
        String chapters = info == null ? missing : info.chapters > 0 ? Integer.toString(info.chapters) : "None listed";
        String language = info == null || info.language.isEmpty() || "und".equals(info.language) ? missing : info.language;
        return "Author: " + author + "\nSize: " + size + "\nFormat: " + input
            + "\nContents entries: " + chapters + "\nLanguage: " + language;
    }

    private TextView section(String name) {
        TextView title = label(name, 14, SECONDARY, true);
        title.setPadding(dp(8), dp(14), 0, dp(4));
        return title;
    }

    private void editDraft(Draft draft) {
        Dialog sheet = new Dialog(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(10), dp(18), dp(18));
        content.setBackground(shape(CARD, 22, OUTLINE));
        View handle = new View(this);
        handle.setBackground(shape(SECONDARY, 2, 0));
        LinearLayout.LayoutParams grip = new LinearLayout.LayoutParams(dp(36), dp(4));
        grip.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        content.addView(handle, grip);
        content.addView(section("BOOK OPTIONS"));
        content.addView(label("Edit book", 24, INK, true));
        EditText title = field(content, "Title", draft.title);
        EditText author = field(content, "Author", draft.author);
        content.addView(section("Output format"));
        Spinner output = new Spinner(this);
        ArrayAdapter<String> choices = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, FORMAT_LABELS);
        output.setAdapter(choices);
        output.setSelection(Math.max(0, java.util.Arrays.asList(FORMATS).indexOf(draft.extension)));
        content.addView(output, new LinearLayout.LayoutParams(-1, dp(48)));
        CheckBox detect = option(content, "Detect chapters when no contents are listed", draft.detect);
        CheckBox repair = option(content, "Fix contents layout", draft.repair);
        CheckBox dash = option(content, "Add a dash before chapter names", draft.dash);
        TextView example = label("Example: Chapter 1 → — Chapter 1", 13, SECONDARY, false);
        example.setPadding(dp(12), 0, 0, dp(10));
        content.addView(example);
        Runnable apply = () -> {
            draft.title = title.getText().toString().trim();
            draft.author = author.getText().toString().trim();
            draft.extension = FORMATS[output.getSelectedItemPosition()];
            draft.detect = detect.isChecked();
            draft.repair = repair.isChecked();
            draft.dash = dash.isChecked();
        };
        Button now = button("Convert now", true);
        now.setOnClickListener(v -> {
            apply.run();
            sheet.dismiss();
            closeHistory();
            convertDraft(draft);
        });
        content.addView(now, new LinearLayout.LayoutParams(-1, dp(48)));
        Button later = button("Save for later", false);
        later.setOnClickListener(v -> {
            apply.run();
            sheet.dismiss();
            closeHistory();
            run("Saving for later…", () -> saveDraft(draft));
        });
        content.addView(later);
        Button cancel = button("Cancel", false);
        cancel.setFocusableInTouchMode(true);
        cancel.setOnClickListener(v -> sheet.dismiss());
        content.addView(cancel);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        sheet.setContentView(scroll);
        cancel.requestFocus();
        sheet.show();
        android.view.Window window = sheet.getWindow();
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setGravity(android.view.Gravity.BOTTOM);
        window.setLayout(-1, -2);
    }

    private EditText field(LinearLayout content, String hint, String value) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setText(value);
        input.setTextColor(INK);
        input.setHintTextColor(SECONDARY);
        content.addView(input);
        return input;
    }

    private CheckBox option(LinearLayout content, String text, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(text);
        box.setTextColor(INK);
        box.setChecked(checked);
        content.addView(box);
        return box;
    }

    private String saveDraft(Draft draft) throws Exception {
        boolean fresh = draft.folder == null;
        File folder = fresh ? new File(queue, java.util.UUID.randomUUID().toString()) : draft.folder;
        if (fresh && !folder.mkdirs()) throw new IOException("Could not create a saved job.");
        try {
            if (fresh) {
                try (InputStream in = draft.open(getContentResolver());
                     OutputStream out = new FileOutputStream(new File(folder, "book"))) {
                    byte[] buffer = new byte[8192];
                    long total = 0;
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        total += count;
                        if (total > Books.LIMIT) throw new IOException("This book exceeds the size limit.");
                        out.write(buffer, 0, count);
                    }
                }
            }
            android.util.AtomicFile metadata = new android.util.AtomicFile(new File(folder, "job.json"));
            byte[] job = Books.utf(draft.json().toString());
            FileOutputStream output = metadata.startWrite();
            try {
                output.write(job);
                metadata.finishWrite(output);
            } catch (IOException failure) {
                metadata.failWrite(output);
                throw failure;
            }
            draft.folder = folder;
            return "Saved for later. Open History to continue.";
        } catch (Exception failure) {
            if (fresh) removeDraft(folder);
            throw failure;
        }
    }

    private boolean removeDraft(File folder) {
        File metadata = new File(folder, "job.json");
        if (metadata.exists() && !metadata.delete()) return false;
        new File(folder, "book").delete();
        folder.delete();
        return true;
    }

    private void addQueuedRow(LinearLayout list, File folder) {
        try {
            Draft draft = Draft.read(folder);
            LinearLayout row = horizontal();
            row.setBackground(shape(0xff26394e, 12, OUTLINE));
            row.setPadding(dp(10), dp(3), 0, dp(3));
            String name = draft.title.isEmpty() ? displayTitle(draft.name) : draft.title;
            TextView title = label(name + "\nPending · " + draft.extension.substring(1).toUpperCase(Locale.ROOT), 14, INK, true);
            title.setOnClickListener(v -> editDraft(draft));
            row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            if (selecting) {
                row.addView(selectionBox(folder));
                title.setOnClickListener(v -> toggleSelection(folder));
            } else {
            row.addView(iconButton(R.drawable.ic_repeat, "Convert saved book", () -> {
                closeHistory();
                convertDraft(draft);
            }), actionSize());
            row.addView(iconButton(R.drawable.ic_delete, "Delete saved job", () -> {
                new AlertDialog.Builder(this).setTitle("Delete saved job?")
                    .setMessage(name).setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete", (dialog, button) -> {
                        if (removeDraft(folder)) {
                            if (session.draft != null && folder.equals(session.draft.folder)) session.draft = null;
                        } else session.message = "Could not delete the saved job.";
                        refresh();
                        showHistory();
                    }).show();
            }), actionSize());
            }
            LinearLayout.LayoutParams item = new LinearLayout.LayoutParams(-1, -2);
            item.topMargin = dp(8);
            list.addView(row, item);
        } catch (Exception error) {
            list.addView(label("A saved job could not be opened.", 14, SECONDARY, false));
        }
    }

    private void pickSaveDestination(File book) {
        if (book == null || !book.isFile()) return;
        saveSelection = book;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(BookServer.mime(book.getName()));
        intent.putExtra(Intent.EXTRA_TITLE, book.getName());
        startActivityForResult(intent, SAVE);
    }

    private boolean horizontalSwipe(MotionEvent event, float x, float y, int direction) {
        float dx = event.getX() - x;
        float dy = Math.abs(event.getY() - y);
        return dx * direction > 80 * getResources().getDisplayMetrics().density && Math.abs(dx) > 2 * dy;
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            touchX = event.getX();
            touchY = event.getY();
        } else if (!session.busy && event.getActionMasked() == MotionEvent.ACTION_UP
                && horizontalSwipe(event, touchX, touchY, historyScreen == null ? -1 : 1)) {
            MotionEvent cancel = MotionEvent.obtain(event);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(cancel);
            cancel.recycle();
            if (historyScreen == null) showHistory();
            else closeHistory();
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    private void closeHistory() {
        if (historyScreen == null) return;
        screens.removeView(historyScreen);
        historyScreen = null;
        converterScreen.setVisibility(View.VISIBLE);
        selectTab(false);
    }

    @Override public void onBackPressed() {
        if (historyScreen != null) closeHistory();
        else super.onBackPressed();
    }

    private void showHistory() {
        if (session.busy) return;
        int position = historyScreen == null ? 0 : historyScreen.getScrollY();
        if (historyScreen != null) screens.removeView(historyScreen);
        File[] books = BookServer.books(library);
        java.util.Arrays.sort(books, java.util.Comparator.comparingLong(File::lastModified).reversed());
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        File[] drafts = queue.listFiles(folder -> new File(folder, "job.json").isFile());
        java.util.List<File> available = new java.util.ArrayList<>(java.util.Arrays.asList(books));
        if (drafts != null) available.addAll(java.util.Arrays.asList(drafts));
        selected.retainAll(available);
        if (!available.isEmpty()) addSelectionControls(list, available);
        if (drafts != null && drafts.length > 0) {
            list.addView(section("Saved for later"));
            for (File folder : drafts) addQueuedRow(list, folder);
        }
        if (books.length == 0 && (drafts == null || drafts.length == 0)) {
            TextView empty = label("No books are stored in the app.", 15, SECONDARY, false);
            empty.setPadding(dp(8), dp(16), 0, 0);
            list.addView(empty);
        }
        java.text.DateFormat date = android.text.format.DateFormat.getMediumDateFormat(this);
        String previousGroup = "";
        for (File book : books) {
            java.util.Calendar today = java.util.Calendar.getInstance();
            java.util.Calendar day = java.util.Calendar.getInstance();
            day.setTimeInMillis(book.lastModified());
            String group = today.get(java.util.Calendar.YEAR) == day.get(java.util.Calendar.YEAR)
                && today.get(java.util.Calendar.DAY_OF_YEAR) == day.get(java.util.Calendar.DAY_OF_YEAR)
                ? "Today" : "Earlier";
            if (!group.equals(previousGroup)) {
                list.addView(section(group));
                previousGroup = group;
            }
            LinearLayout row = horizontal();
            row.setPadding(dp(10), dp(6), 0, dp(6));
            row.setBackground(shape(CARD, 12, OUTLINE));
            LinearLayout details = new LinearLayout(this);
            details.setOrientation(LinearLayout.VERTICAL);
            TextView name = label(displayTitle(book.getName()), 16, INK, true);
            name.setMaxLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            details.addView(name);
            String extension = book.getName().substring(book.getName().lastIndexOf('.') + 1).toUpperCase(Locale.ROOT);
            details.addView(label(extension + " · " + date.format(new java.util.Date(book.lastModified())), 13, SECONDARY, false));
            details.setContentDescription("File details: " + book.getName());
            details.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("File details").setMessage(book.getName()).setPositiveButton("Close", null).show());
            row.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
            if (selecting) {
                row.addView(selectionBox(book));
                details.setOnClickListener(v -> toggleSelection(book));
            } else {
                row.addView(iconButton(R.drawable.ic_save, "Save", () -> {
                    closeHistory();
                    pickSaveDestination(book);
                }), actionSize());
                row.addView(iconButton(R.drawable.ic_repeat, "Convert again", () -> chooseHistoryFormat(book)), actionSize());
                row.addView(iconButton(R.drawable.ic_delete, "Delete", () -> confirmRemoval(book)), actionSize());
            }
            LinearLayout.LayoutParams item = new LinearLayout.LayoutParams(-1, -2);
            item.topMargin = dp(8);
            list.addView(row, item);
        }
        scroll.addView(list);
        historyScreen = scroll;
        converterScreen.setVisibility(View.GONE);
        screens.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        selectTab(true);
        if (position > 0) scroll.post(() -> scroll.scrollTo(0, position));
    }

    private void addSelectionControls(LinearLayout list, java.util.List<File> available) {
        LinearLayout row = horizontal();
        if (!selecting) {
            Button select = button("Select", false);
            select.setOnClickListener(v -> { selecting = true; showHistory(); });
            row.addView(select);
            Button selectAll = button("Select all", false);
            selectAll.setOnClickListener(v -> {
                selecting = true;
                selected.clear();
                selected.addAll(available);
                showHistory();
            });
            row.addView(selectAll);
            list.addView(row);
            return;
        }
        boolean all = selected.size() == available.size();
        Button selectAll = button(all ? "Deselect all" : "Select all", false);
        selectAll.setOnClickListener(v -> {
            selected.clear();
            if (!all) selected.addAll(available);
            showHistory();
        });
        row.addView(selectAll);
        TextView count = label(selected.size() + " selected", 14, SECONDARY, false);
        row.addView(count, new LinearLayout.LayoutParams(0, -2, 1));
        Button done = button("Done", false);
        done.setOnClickListener(v -> { selecting = false; selected.clear(); showHistory(); });
        row.addView(done);
        list.addView(row);
        LinearLayout actions = horizontal();
        Button convert = button("Convert selected", true);
        Button delete = button("Delete selected", false);
        convert.setEnabled(!selected.isEmpty());
        delete.setEnabled(!selected.isEmpty());
        convert.setOnClickListener(v -> chooseBatchFormat(new java.util.ArrayList<>(selected)));
        delete.setOnClickListener(v -> confirmBatchDelete(new java.util.ArrayList<>(selected)));
        actions.addView(convert, new LinearLayout.LayoutParams(0, dp(48), 1));
        actions.addView(delete, new LinearLayout.LayoutParams(0, dp(48), 1));
        list.addView(actions);
    }

    private CheckBox selectionBox(File book) {
        CheckBox box = new CheckBox(this);
        box.setMinWidth(dp(48));
        box.setMinHeight(dp(48));
        box.setContentDescription("Select " + book.getName());
        box.setChecked(selected.contains(book));
        box.setOnClickListener(v -> toggleSelection(book));
        return box;
    }

    private void toggleSelection(File book) {
        if (!selected.remove(book)) selected.add(book);
        showHistory();
    }

    private void chooseBatchFormat(java.util.List<File> books) {
        String[] formats = new String[FORMAT_LABELS.length + 1];
        formats[0] = "Use saved formats";
        System.arraycopy(FORMAT_LABELS, 0, formats, 1, FORMAT_LABELS.length);
        new AlertDialog.Builder(this).setTitle("Convert " + books.size() + " selected books")
            .setItems(formats, (dialog, index) -> {
                selecting = false;
                selected.clear();
                closeHistory();
                convertBatch(books, index == 0 ? null : FORMATS[index - 1]);
            })
            .setNegativeButton("Cancel", null).show();
    }

    private void convertBatch(java.util.List<File> books, String output) {
        runConversion(books.size(), () -> {
            int ok = 0;
            String firstError = "";
            for (int i = 0; i < books.size(); i++) {
                File book = books.get(i);
                try {
                    if (book.isDirectory()) {
                        Draft draft = Draft.read(book);
                        if (output != null) draft.extension = output;
                        exportDraft(draft);
                    } else {
                        String extension = output == null ? book.getName().substring(book.getName().lastIndexOf('.')) : output;
                        exportHistoryBook(book, extension);
                    }
                    ok++;
                } catch (Exception | LinkageError error) {
                    if (firstError.isEmpty()) firstError = error.getMessage() == null ? "Check the file and try again." : error.getMessage();
                }
                int processed = i + 1;
                runOnUiThread(() -> {
                    session.done = processed;
                    if (session.screen != null) session.screen.updateProgress();
                });
            }
            if (ok != books.size())
                throw new IOException(ok + " of " + books.size() + " books converted. " + firstError);
            return ok + " books added to History.";
        });
    }

    private void confirmBatchDelete(java.util.List<File> books) {
        new AlertDialog.Builder(this).setTitle("Delete " + books.size() + " selected items?")
            .setMessage("Completed books are removed from Wi-Fi sharing. Separately saved copies are kept.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete", (dialog, button) -> {
                int deleted = 0;
                for (File book : books) {
                    boolean removed = book.isDirectory() ? removeDraft(book) : !book.exists() || book.delete();
                    if (!removed) continue;
                    selected.remove(book);
                    if (book.equals(session.result)) session.result = null;
                    if (session.draft != null && book.equals(session.draft.folder)) session.draft = null;
                    deleted++;
                }
                selecting = !selected.isEmpty();
                new AlertDialog.Builder(this).setMessage(deleted + " of " + books.size() + " items deleted.")
                    .setPositiveButton("Close", null).show();
                refresh();
                showHistory();
            }).show();
    }

    static String displayTitle(String filename) {
        String title = filename.replaceFirst("\\.[^.]+$", "")
            .replaceFirst("(?i)_clean(?: \\(\\d+\\))?$", "")
            .replaceFirst("-\\d{8,}$", "").replace('_', ' ').trim();
        return title.isEmpty() ? filename : title;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void applyBars(android.view.Window window) {
        window.setStatusBarColor(SURFACE);
        window.setNavigationBarColor(SURFACE);
        window.getDecorView().setSystemUiVisibility(0);
    }

    private LinearLayout horizontal() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        return row;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(size);
        label.setTextColor(color);
        if (bold) label.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        return label;
    }

    private android.graphics.drawable.GradientDrawable shape(int fill, int radius, int border) {
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(fill);
        background.setCornerRadius(dp(radius));
        if (border != 0) background.setStroke(dp(1), border);
        return background;
    }

    private Button button(String title, boolean primary) {
        Button button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setMinimumWidth(0);
        button.setPadding(dp(12), 0, dp(12), 0);
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, shape(0xff303741, 12, 0));
        states.addState(new int[]{}, shape(primary ? NAVY : 0x00000000, 12, 0));
        button.setBackground(new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x26384660), states, shape(CARD, 12, 0)));
        button.setTextColor(new android.content.res.ColorStateList(
            new int[][]{{-android.R.attr.state_enabled}, {}},
            new int[]{SECONDARY, primary ? SURFACE : NAVY}));
        return button;
    }

    private LinearLayout.LayoutParams actionSize() {
        return new LinearLayout.LayoutParams(dp(48), dp(48));
    }

    private ImageButton iconButton(int resource, String title, Runnable action) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(resource);
        button.setColorFilter(NAVY);
        button.setContentDescription(title);
        if (android.os.Build.VERSION.SDK_INT >= 26) button.setTooltipText(title);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setBackground(new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x26384660), null, shape(CARD, 12, 0)));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private void chooseHistoryFormat(File book) {
        new AlertDialog.Builder(this)
            .setTitle("Convert again")
            .setItems(FORMAT_LABELS, (dialog, index) -> {
                closeHistory();
                convertFromHistory(book, FORMATS[index]);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void convertFromHistory(File book, String extension) {
        runConversion(1, () -> exportHistoryBook(book, extension));
    }

    private String exportHistoryBook(File book, String extension) throws Exception {
        String name = book.getName();
        File export = File.createTempFile("reconverted-", extension, getCacheDir());
        File normalized = null;
        try {
            if (".epub".equals(extension)) {
                KindleExport.toEpub(this, book, export);
            } else {
                File epub = book;
                if (!name.endsWith(".epub")) {
                    normalized = File.createTempFile("reconvert-input-", ".epub", getCacheDir());
                    KindleExport.toEpub(this, book, normalized);
                    epub = normalized;
                }
                exportBook(epub, export, extension);
            }
            session.result = store(export, name.substring(0, name.lastIndexOf('.')), extension);
            return extension.substring(1).toUpperCase(Locale.ROOT) + " ready. Added to conversion history.";
        } finally {
            export.delete();
            if (normalized != null) normalized.delete();
        }
    }

    private void exportBook(File epub, File result, String extension) throws Exception {
        if (".fb2".equals(extension)) KindleExport.toFb2(this, epub, result);
        else KindleExport.write(this, epub, result, ".mobi".equals(extension));
    }

    private void confirmRemoval(File book) {
        new AlertDialog.Builder(this)
            .setTitle("Delete shared copy?")
            .setMessage(book.getName() + "\n\nThis removes the book from history and Wi-Fi sharing. Copies saved separately are kept.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete", (dialog, button) -> {
                if (book.exists() && !book.delete()) {
                    session.message = "Could not delete the book. Please try again.";
                } else {
                    if (book.equals(session.result)) session.result = null;
                    session.message = "Book removed from Wi-Fi sharing.";
                }
                refresh();
                showHistory();
            })
            .show();
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (RuntimeException ignored) { }
        return uri.getLastPathSegment();
    }

    private void prepareLibrary() throws IOException {
        if (!library.isDirectory() && !library.mkdirs()) throw new IOException("Could not open the book library.");
    }

    private File store(File temp, String name, String extension) throws IOException {
        String safe = name.replace('/', '_').replace('\\', '_').replaceAll("[\\p{Cntrl}]", "_");
        if (safe.length() > 64) safe = safe.substring(0, 64);
        if (safe.isEmpty()) safe = "Book";
        File destination = new File(library, safe + extension);
        for (int i = 2; destination.exists(); i++) destination = new File(library, safe + " (" + i + ")" + extension);
        if (!temp.renameTo(destination)) throw new IOException("Could not save the book in the library.");
        return destination;
    }

    private String importMobi(Uri uri, String name) throws Exception {
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".mobi"))
            throw new IOException("Choose a ready MOBI file.");
        prepareLibrary();
        File temp = File.createTempFile("imported-", ".part", library);
        try {
            try (InputStream source = getContentResolver().openInputStream(uri);
                 OutputStream output = new FileOutputStream(temp)) {
                if (source == null) throw new IOException("Could not open the selected file.");
                byte[] data = Books.read(source, Books.LIMIT);
                if (data.length < 78 || !"BOOKMOBI".equals(new String(data, 60, 8, java.nio.charset.StandardCharsets.US_ASCII)))
                    throw new IOException("This file is not a MOBI book.");
                output.write(data);
            }
            store(temp, name.substring(0, name.length() - 5), ".mobi");
            return "MOBI added to Wi-Fi sharing.";
        } finally {
            temp.delete();
        }
    }

    private void toggleServer() {
        if (session.server != null) {
            session.stopSharing();
        } else {
            try {
                Inet4Address address = (Inet4Address)wifiAddress();
                session.server = new BookServer(address, library);
            } catch (BindException error) {
                session.message = "Wi-Fi sharing could not start. Port 8080 is already in use.";
            } catch (IOException error) {
                session.message = error.getMessage();
            }
        }
        refresh();
    }

    private InetAddress wifiAddress() throws IOException {
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
            LinkProperties properties = manager.getLinkProperties(network);
            if (properties == null) continue;
            for (LinkAddress link : properties.getLinkAddresses())
                if (link.getAddress() instanceof Inet4Address) return link.getAddress();
        }
        throw new IOException("Connect the phone to Wi-Fi, then try again.");
    }

    private interface Task { String execute() throws Exception; }

    private static String duration(long start) {
        long millis = android.os.SystemClock.elapsedRealtime() - start;
        if (millis < 1000) return String.format(Locale.ROOT, "%.1f s", millis / 1000.0);
        long seconds = millis / 1000;
        return seconds < 60 ? seconds + " s" : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private void updateProgress() {
        progress.setVisibility(session.busy ? View.VISIBLE : View.GONE);
        boolean batch = session.conversion && session.total > 1;
        progress.setIndeterminate(!batch);
        if (batch) {
            progress.setMax(session.total);
            progress.setProgress(session.done);
        }
        if (session.busy && session.conversion) {
            String elapsed = duration(session.started);
            status.setText(batch ? "Converting " + session.done + " / " + session.total + "  ·  " + elapsed
                : "Converting book…  ·  " + elapsed);
        }
    }

    private void runConversion(int total, Task task) {
        session.total = total;
        run("Converting book…", task, true);
    }

    private void run(String message, Task task) {
        run(message, task, false);
    }

    private void run(String message, Task task, boolean conversion) {
        session.busy = true;
        session.conversion = conversion;
        session.started = android.os.SystemClock.elapsedRealtime();
        session.done = 0;
        session.resultBanner = null;
        session.message = message;
        refresh();
        new Thread(() -> {
            String result;
            boolean success;
            try {
                result = task.execute();
                success = true;
            } catch (Exception error) {
                result = error.getMessage() == null ? "Check the book and try again." : error.getMessage();
                success = false;
            } catch (LinkageError error) {
                result = "The Kindle conversion engine could not start.";
                success = false;
            } catch (OutOfMemoryError error) {
                result = "This book is too large for the available memory.";
                success = false;
            }
            String finalResult = result;
            boolean completed = success;
            String elapsed = duration(session.started);
            runOnUiThread(() -> {
                session.busy = false;
                session.message = conversion ? null : (completed ? "" : "Could not finish. ") + finalResult;
                if (conversion) {
                    session.resultSuccess = completed;
                    session.resultBanner = (completed ? "Conversion complete" : "Conversion incomplete")
                        + "  ·  " + elapsed + "\n" + finalResult;
                }
                if (session.screen != null) session.screen.refresh();
            });
        }, "book-converter").start();
    }
}
