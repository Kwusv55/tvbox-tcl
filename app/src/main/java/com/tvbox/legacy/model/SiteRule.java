package com.tvbox.legacy.model;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A deliberately small, data-only site rule.
 *
 * The rule never executes JavaScript or bypasses access controls. It describes
 * public HTML/API responses supplied by the user, which keeps the parser
 * predictable on the old Android WebView/HTTP stack.
 */
public final class SiteRule {
    public final String id;
    public final String name;
    public final String baseUrl;
    public final String searchUrl;
    public final String searchItemPattern;
    public final int searchTitleGroup;
    public final int searchUrlGroup;
    public final String searchFilter;
    public final String episodeItemPattern;
    public final int episodeTitleGroup;
    public final int episodeUrlGroup;
    public final String episodeFilter;
    public final String videoUrlPattern;
    public final int videoUrlGroup;
    public final String charset;
    public final String userAgent;
    public final Map<String, String> headers;

    private SiteRule(String id,
                     String name,
                     String baseUrl,
                     String searchUrl,
                     String searchItemPattern,
                     int searchTitleGroup,
                     int searchUrlGroup,
                     String searchFilter,
                     String episodeItemPattern,
                     int episodeTitleGroup,
                     int episodeUrlGroup,
                     String episodeFilter,
                     String videoUrlPattern,
                     int videoUrlGroup,
                     String charset,
                     String userAgent,
                     Map<String, String> headers) {
        this.id = id;
        this.name = name;
        this.baseUrl = baseUrl;
        this.searchUrl = searchUrl;
        this.searchItemPattern = searchItemPattern;
        this.searchTitleGroup = searchTitleGroup;
        this.searchUrlGroup = searchUrlGroup;
        this.searchFilter = searchFilter;
        this.episodeItemPattern = episodeItemPattern;
        this.episodeTitleGroup = episodeTitleGroup;
        this.episodeUrlGroup = episodeUrlGroup;
        this.episodeFilter = episodeFilter;
        this.videoUrlPattern = videoUrlPattern;
        this.videoUrlGroup = videoUrlGroup;
        this.charset = charset;
        this.userAgent = userAgent;
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<String, String>(headers));
    }

    public static SiteRule fromJson(String json) throws JSONException {
        return fromJson(new JSONObject(json));
    }

    public static SiteRule fromJson(JSONObject root) throws JSONException {
        JSONObject search = root.optJSONObject("search");
        JSONObject episodes = root.optJSONObject("episodes");
        JSONObject video = root.optJSONObject("video");
        Map<String, String> headers = new LinkedHashMap<String, String>();
        JSONObject headerObject = root.optJSONObject("headers");
        if (headerObject != null) {
            java.util.Iterator<String> keys = headerObject.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                headers.put(key, headerObject.optString(key, ""));
            }
        }

        String baseUrl = root.optString("baseUrl", "");
        String id = root.optString("id", "rule-" + Math.abs(baseUrl.hashCode()));
        String name = root.optString("name", id);
        String searchUrl = opt(search, root, "url", "searchUrl", "");
        String searchPattern = opt(search, root, "itemPattern", "searchItemPattern", "");
        int searchTitleGroup = optInt(search, root, "titleGroup", "searchTitleGroup", 2);
        int searchUrlGroup = optInt(search, root, "urlGroup", "searchUrlGroup", 1);
        String searchFilter = opt(search, root, "filter", "searchFilter", "");

        String episodePattern = opt(episodes, root, "itemPattern", "episodeItemPattern", "");
        int episodeTitleGroup = optInt(episodes, root, "titleGroup", "episodeTitleGroup", 2);
        int episodeUrlGroup = optInt(episodes, root, "urlGroup", "episodeUrlGroup", 1);
        String episodeFilter = opt(episodes, root, "filter", "episodeFilter", "");

        String videoPattern = opt(video, root, "urlPattern", "videoUrlPattern", "");
        int videoGroup = optInt(video, root, "urlGroup", "videoUrlGroup", 1);
        String charset = root.optString("charset", "UTF-8");
        String userAgent = root.optString("userAgent",
                "TVBox-TCL/1.0 (Android 4.2; public-media-client)");

        if (baseUrl.length() == 0 && searchUrl.length() == 0) {
            throw new JSONException("rule needs baseUrl or search.url");
        }
        return new SiteRule(id, name, baseUrl, searchUrl, searchPattern,
                searchTitleGroup, searchUrlGroup, searchFilter,
                episodePattern, episodeTitleGroup, episodeUrlGroup,
                episodeFilter, videoPattern, videoGroup, charset,
                userAgent, headers);
    }

    private static String opt(JSONObject nested, JSONObject root, String nestedKey,
                              String rootKey, String fallback) {
        if (nested != null && nested.has(nestedKey)) {
            return nested.optString(nestedKey, fallback);
        }
        return root.optString(rootKey, fallback);
    }

    private static int optInt(JSONObject nested, JSONObject root, String nestedKey,
                              String rootKey, int fallback) {
        if (nested != null && nested.has(nestedKey)) {
            return nested.optInt(nestedKey, fallback);
        }
        return root.optInt(rootKey, fallback);
    }

    public String displayName() {
        return name + " (" + id + ")";
    }
}
