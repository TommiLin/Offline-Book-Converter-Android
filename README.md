# Offline Book Converter for Android

Tap **+** to open EPUB, FB2, UTF-8 TXT, MOBI, AZW, AZW3, DOCX, ODT, RTF, HTML, or PDF. Kindle inputs must be DRM-free. PDF import works best with a text layer and can use existing bookmarks. Scans need OCR before import. The book card shows
the available metadata, file size, input format, and contents entry count. The card stays visible before a book is selected. **Change** picks a different source; **Delete** clears the selection.


Open **Edit** or **Convert book** to change the title, author, output format, or contents
options. Chapter detection looks for headings in EPUBs without a
contents list. For unstructured DOCX anthologies, it can recognize repeated
story headings and bookmarked sections. If the title is missing, the
app uses the input filename without its extension. **Fix contents layout** controls the flattened in-book
contents page. Choose **Convert now** or **Save for later**.

The dark interface has **Convert** and **History** tabs. History shows
queued jobs in **Saved for later** and completed books under **Today**
and **Earlier**. Use **Select** to choose individual books or tap **Select all** directly above the list for batch conversion or deletion. Conversion shows elapsed time and, for batches, the number of processed books. Queued jobs keep a private copy of the source and
settings. Convert or delete them from the queue. The Wi-Fi catalog
shows completed books and imported MOBI files.

The app exports EPUB, MOBI 6 (Kindle 4), AZW3, and FB2 offline on 64-bit ARM
and x86 Android 7.0 or later. FB2 import supports text and section
titles. Illustrated FB2 content and DRM-protected books are unsupported.

## Wi-Fi sharing

Connect the phone and reader to the same Wi-Fi network. Tap **Start Wi-Fi
sharing** and enter the short address shown on the phone, such as
http://192.168.31.31:8080. Keep the app open during the download and tap
**Stop Wi-Fi sharing** when finished. While enabled, the catalog is available
to devices on the same network. Choose MOBI 6 for Kindle 4.

## Build

Use JDK 17 or later, Gradle 9.3.1, Android SDK 36 with Build Tools 36.0.0, and Python 3.11. Set
ANDROID_HOME to the Android SDK path and CHAQUOPY_BUILD_PYTHON to the Python
3.11 executable. Run build.ps1 on Windows to build and sign with an Android debug keystore. Its path can be supplied with -Keystore. Gradle assembleDebug produces an unsigned APK. Set BOOK_WHEELS to a local wheelhouse if using predownloaded dependencies.
Gradle downloads the Android-compatible Python runtime and conversion engine
at build time. Use the same debug or release signing key when updating an
existing installation.

## Source and licenses

The original Java code is MIT-licensed. The combined Android build incorporates
GPL-3.0 components from ebook-converter-lib 1.6.1, derived from calibre.
Source and license: https://github.com/bilec/ebook-converter-lib
Matching package source: https://pypi.org/project/ebook-converter-lib/1.6.1/#files
Android Python integration: https://github.com/chaquo/chaquopy
