package com.tvbox.legacy.net;

import android.content.Context;
import android.content.SharedPreferences;

import com.tvbox.legacy.model.RuleStore;
import com.tvbox.legacy.model.SiteRule;

import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Local/remote catalog storage for the TV-friendly source picker. */
public final class RuleCatalog {
    public static final String REMOTE_URL =
            "https://raw.githubusercontent.com/Kwusv55/tvbox-tcl/master/rules/anime.json";
    private static final String PREFS = "tcl-tvbox-catalog";
    private static final String JSON = "json";
    private static final String SOURCE = "source";
    private static final String SYNC_TIME = "sync-time";
    private static final long REFRESH_INTERVAL_MS = 6L * 60L * 60L * 1000L;
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    private RuleCatalog() {
    }

    public static String load(Context context) throws IOException {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String saved = preferences.getString(JSON, "");
        if (saved.length() > 0) {
            return saved;
        }
        return readAsset(context, "anime.json");
    }

    public static List<SiteRule> parse(String raw) throws JSONException {
        return RuleStore.parseMany(raw);
    }

    public static void save(Context context, String raw, String source) throws JSONException {
        RuleStore.parseMany(raw);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(JSON, raw)
                .putString(SOURCE, source == null ? "本地" : source)
                .putLong(SYNC_TIME, System.currentTimeMillis())
                .apply();
    }

    public static String source(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(SOURCE, "内置目录");
    }

    public static boolean shouldRefresh(Context context) {
        long last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(SYNC_TIME, 0L);
        return System.currentTimeMillis() - last >= REFRESH_INTERVAL_MS;
    }

    public static String fetchRemote() throws IOException, JSONException {
        String raw = HttpClient.get(REMOTE_URL, null).text("UTF-8");
        RuleStore.parseMany(raw);
        return raw;
    }

    private static String readAsset(Context context, String name) throws IOException {
        InputStream input = context.getAssets().open(name);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_BYTES) {
                    throw new IOException("catalog is too large");
                }
                output.write(buffer, 0, count);
            }
            return output.toString("UTF-8");
        } finally {
            input.close();
        }
    }
}
