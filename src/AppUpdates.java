package io.github.tommilin.bookconverter;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

final class AppUpdates {
    static final String PROJECT = "TommiLin/Offline-Book-Converter-Android";
    static final String REPO = "https://github.com/" + PROJECT;
    static final String RELEASES = REPO + "/releases";

    static String latest(String installed) throws Exception {
        URL endpoint = new URL("https://api.github.com/repos/" + PROJECT + "/releases?per_page=30");
        HttpURLConnection connection = (HttpURLConnection)endpoint.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(6000);
        connection.setReadTimeout(6000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "OfflineBookConverter-Android");
        try {
            if (connection.getResponseCode() != 200) throw new IOException("GitHub releases are unavailable.");
            try (InputStream in = connection.getInputStream()) {
                return select(new JSONArray(new String(Books.read(in, 2 * 1024 * 1024), StandardCharsets.UTF_8)), installed);
            }
        } finally {
            connection.disconnect();
        }
    }

    static String select(JSONArray releases, String installed) throws JSONException {
        String best = installed;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.getJSONObject(i);
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue;
            String tag = release.optString("tag_name");
            if (!tag.matches("v[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}")) continue;
            String version = tag.substring(1);
            if (compare(version, best) <= 0) continue;
            JSONArray assets = release.optJSONArray("assets");
            if (assets == null) continue;
            for (int a = 0; a < assets.length(); a++) {
                JSONObject asset = assets.optJSONObject(a);
                if (asset != null && ("OfflineBookConverter-Android-" + version + ".apk").equals(asset.optString("name"))
                        && "uploaded".equals(asset.optString("state"))) {
                    best = version;
                    break;
                }
            }
        }
        return best.equals(installed) ? null : best;
    }

    private static int compare(String a, String b) {
        String[] left = a.split("\\."), right = b.split("\\.");
        for (int i = 0; i < 3; i++) {
            int difference = Integer.compare(Integer.parseInt(left[i]), Integer.parseInt(right[i]));
            if (difference != 0) return difference;
        }
        return 0;
    }
}
