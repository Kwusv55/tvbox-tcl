package com.tvbox.legacy.net;

import android.text.Html;
import android.text.TextUtils;

import com.tvbox.legacy.model.Episode;
import com.tvbox.legacy.model.SiteRule;
import com.tvbox.legacy.model.VideoSource;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Executes the small, data-only rule format on API 17 devices. */
public final class RuleEngine {
    private static final int MAX_RESULTS = 80;
    private static final Pattern DEFAULT_ITEM = Pattern.compile(
            "<a[^>]+href\\s*=\\s*[\\\"']([^\\\"']+)[\\\"'][^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern MEDIA_URL = Pattern.compile(
            "https?://[^\\\"'<>\\s]+\\.(?:m3u8|mp4|m4v|flv)(?:\\?[^\\\"'<>\\s]*)?",
            Pattern.CASE_INSENSITIVE);

    private RuleEngine() {
    }

    public static List<VideoSource> search(SiteRule rule, String keyword) throws IOException {
        if (rule == null) {
            throw new IOException("no active rule");
        }
        if (rule.isCms()) {
            return searchCms(rule, keyword);
        }
        String requestUrl = applyKeyword(rule.searchUrl, keyword);
        if (TextUtils.isEmpty(requestUrl)) {
            requestUrl = applyKeyword(rule.baseUrl, keyword);
        }
        String html = requestText(rule, requestUrl);
        Pattern itemPattern = compileOrDefault(rule.searchItemPattern, DEFAULT_ITEM);
        List<VideoSource> results = new ArrayList<VideoSource>();
        Matcher matcher = itemPattern.matcher(html);
        while (matcher.find() && results.size() < MAX_RESULTS) {
            String title = group(matcher, rule.searchTitleGroup);
            String href = group(matcher, rule.searchUrlGroup);
            if (TextUtils.isEmpty(title) || TextUtils.isEmpty(href)) {
                continue;
            }
            if (!passesFilter(title, href, rule.searchFilter)) {
                continue;
            }
            results.add(new VideoSource(cleanText(title), resolveUrl(requestUrl, href), "", rule.id));
        }
        return results;
    }

    /** Search every configured source concurrently, matching Kazumi's plugin index behavior. */
    public static SearchResult searchAll(final List<SiteRule> rules, final String keyword) {
        if (rules == null || rules.isEmpty()) {
            return new SearchResult(Collections.<VideoSource>emptyList(), 0);
        }
        int workers = Math.max(1, Math.min(6, rules.size()));
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        List<Future<List<VideoSource>>> futures = new ArrayList<Future<List<VideoSource>>>();
        for (final SiteRule rule : rules) {
            futures.add(executor.submit(new Callable<List<VideoSource>>() {
                @Override
                public List<VideoSource> call() throws Exception {
                    return search(rule, keyword);
                }
            }));
        }
        List<VideoSource> merged = new ArrayList<VideoSource>();
        Set<String> seen = new HashSet<String>();
        int failed = 0;
        try {
            for (Future<List<VideoSource>> future : futures) {
                try {
                    List<VideoSource> values = future.get();
                    for (VideoSource value : values) {
                        if (merged.size() >= 120) {
                            break;
                        }
                        String key = value.title.toLowerCase(Locale.US).trim() + "\n" + value.detailUrl;
                        if (seen.add(key)) {
                            merged.add(value);
                        }
                    }
                } catch (Exception error) {
                    failed++;
                }
            }
        } finally {
            executor.shutdownNow();
        }
        Collections.sort(merged, new Comparator<VideoSource>() {
            @Override
            public int compare(VideoSource left, VideoSource right) {
                return left.title.compareToIgnoreCase(right.title);
            }
        });
        return new SearchResult(merged, failed);
    }

    public static List<Episode> episodes(SiteRule rule, String detailUrl) throws IOException {
        List<Episode> episodes = new ArrayList<Episode>();
        if (TextUtils.isEmpty(detailUrl)) {
            return episodes;
        }
        if (rule.isCms()) {
            return cmsEpisodes(rule, detailUrl);
        }
        if (TextUtils.isEmpty(rule.episodeItemPattern)) {
            episodes.add(new Episode("播放", detailUrl));
            return episodes;
        }
        String html = requestText(rule, detailUrl);
        Pattern itemPattern = compileOrDefault(rule.episodeItemPattern, DEFAULT_ITEM);
        Matcher matcher = itemPattern.matcher(html);
        while (matcher.find() && episodes.size() < MAX_RESULTS) {
            String title = group(matcher, rule.episodeTitleGroup);
            String href = group(matcher, rule.episodeUrlGroup);
            if (TextUtils.isEmpty(title) || TextUtils.isEmpty(href)) {
                continue;
            }
            if (!passesFilter(title, href, rule.episodeFilter)) {
                continue;
            }
            episodes.add(new Episode(cleanText(title), resolveUrl(detailUrl, href)));
        }
        if (episodes.isEmpty()) {
            episodes.add(new Episode("播放", detailUrl));
        }
        return episodes;
    }

    public static String resolveVideoUrl(SiteRule rule, String pageUrl) throws IOException {
        if (TextUtils.isEmpty(pageUrl)) {
            throw new IOException("empty video URL");
        }
        if (looksLikeMedia(pageUrl)) {
            return pageUrl;
        }
        String html = requestText(rule, pageUrl);
        if (!TextUtils.isEmpty(rule.videoUrlPattern)) {
            Matcher matcher = compile(rule.videoUrlPattern).matcher(html);
            if (matcher.find()) {
                String value = group(matcher, rule.videoUrlGroup);
                if (!TextUtils.isEmpty(value)) {
                    return resolveUrl(pageUrl, value);
                }
            }
        }
        Matcher mediaMatcher = MEDIA_URL.matcher(html);
        if (mediaMatcher.find()) {
            return mediaMatcher.group();
        }
        throw new IOException("no playable URL found");
    }

    private static String requestText(SiteRule rule, String url) throws IOException {
        if (TextUtils.isEmpty(url)) {
            throw new IOException("empty request URL");
        }
        Map<String, String> headers = new HashMap<String, String>(rule.headers);
        if (!TextUtils.isEmpty(rule.userAgent)) {
            headers.put("User-Agent", rule.userAgent);
        }
        return HttpClient.get(url, headers).text(rule.charset);
    }

    private static List<VideoSource> searchCms(SiteRule rule, String keyword) throws IOException {
        String endpoint = rule.searchUrl;
        if (TextUtils.isEmpty(endpoint)) {
            endpoint = TextUtils.isEmpty(rule.apiUrl) ? rule.baseUrl : rule.apiUrl;
        }
        endpoint = applyKeyword(endpoint, keyword);
        if (endpoint.indexOf("{keyword}") < 0 && endpoint.indexOf("{q}") < 0
                && endpoint.indexOf("wd=") < 0) {
            endpoint += endpoint.indexOf('?') >= 0 ? "&" : "?";
            endpoint += "ac=detail&wd=" + URLEncoder.encode(keyword == null ? "" : keyword, "UTF-8");
        }
        try {
            JSONObject root = new JSONObject(requestText(rule, endpoint));
            JSONArray list = firstArray(root, "list", "data");
            List<VideoSource> results = new ArrayList<VideoSource>();
            if (list == null) {
                return results;
            }
            for (int index = 0; index < list.length() && results.size() < MAX_RESULTS; index++) {
                JSONObject item = list.optJSONObject(index);
                if (item == null) {
                    continue;
                }
                String typeName = item.optString("type_name", "");
                if (!TextUtils.isEmpty(typeName)
                        && !matchesCategory(typeName, rule.category)) {
                    continue;
                }
                String title = item.optString("vod_name", item.optString("name", ""));
                String id = item.optString("vod_id", item.optString("id", ""));
                if (TextUtils.isEmpty(title) || TextUtils.isEmpty(id)) {
                    continue;
                }
                results.add(new VideoSource(title, cmsDetailUrl(rule, id),
                        item.optString("vod_pic", item.optString("pic", "")), rule.id));
            }
            return results;
        } catch (Exception error) {
            throw new IOException("invalid CMS response", error);
        }
    }

    private static boolean matchesCategory(String actual, String requested) {
        if (TextUtils.isEmpty(requested)) {
            return true;
        }
        String actualLower = actual.toLowerCase(Locale.US);
        String requestedLower = requested.toLowerCase(Locale.US);
        if (actualLower.indexOf(requestedLower) >= 0) {
            return true;
        }
        // CMS providers use both "动漫" and "动画片" for the same catalog.
        boolean animeRequest = requestedLower.indexOf("动漫") >= 0
                || requestedLower.indexOf("动画") >= 0
                || requestedLower.indexOf("anime") >= 0;
        return animeRequest && (actualLower.indexOf("动漫") >= 0
                || actualLower.indexOf("动画") >= 0
                || actualLower.indexOf("anime") >= 0
                || actualLower.indexOf("cartoon") >= 0);
    }

    private static List<Episode> cmsEpisodes(SiteRule rule, String detailUrl) throws IOException {
        List<Episode> episodes = new ArrayList<Episode>();
        String json = requestText(rule, detailUrl);
        try {
            JSONObject root = new JSONObject(json);
            JSONArray list = firstArray(root, "list", "data");
            if (list == null || list.length() == 0) {
                episodes.add(new Episode("播放", detailUrl));
                return episodes;
            }
            JSONObject item = list.optJSONObject(0);
            String playUrl = item == null ? "" : item.optString("vod_play_url", "");
            if (TextUtils.isEmpty(playUrl)) {
                episodes.add(new Episode("播放", detailUrl));
                return episodes;
            }
            String firstSource = playUrl.split("\\$\\$\\$", 2)[0];
            String[] items = firstSource.split("#");
            for (int index = 0; index < items.length && episodes.size() < MAX_RESULTS; index++) {
                String entry = items[index].trim();
                if (entry.length() == 0) {
                    continue;
                }
                int separator = entry.indexOf('$');
                String title = separator > 0 ? entry.substring(0, separator) : "第" + (index + 1) + "集";
                String url = separator > 0 ? entry.substring(separator + 1) : entry;
                if (url.length() > 0) {
                    episodes.add(new Episode(title.trim(), url.trim()));
                }
            }
            if (episodes.isEmpty()) {
                episodes.add(new Episode("播放", detailUrl));
            }
            return episodes;
        } catch (Exception error) {
            throw new IOException("invalid CMS detail response", error);
        }
    }

    private static JSONArray firstArray(JSONObject root, String primary, String secondary) {
        JSONArray array = root.optJSONArray(primary);
        if (array != null) {
            return array;
        }
        JSONObject dataObject = root.optJSONObject(secondary);
        if (dataObject != null) {
            array = dataObject.optJSONArray("list");
        }
        if (array == null) {
            array = root.optJSONArray(secondary);
        }
        return array;
    }

    private static String cmsDetailUrl(SiteRule rule, String id) throws IOException {
        String endpoint = TextUtils.isEmpty(rule.apiUrl) ? rule.baseUrl : rule.apiUrl;
        String separator = endpoint.indexOf('?') >= 0 ? "&" : "?";
        return endpoint + separator + "ac=detail&ids=" + URLEncoder.encode(id, "UTF-8");
    }

    private static String applyKeyword(String template, String keyword) throws IOException {
        if (TextUtils.isEmpty(template)) {
            return "";
        }
        String encoded = URLEncoder.encode(keyword == null ? "" : keyword, "UTF-8");
        return template.replace("{keyword}", encoded)
                .replace("{q}", encoded)
                .replace("{wd}", encoded);
    }

    public static String resolveUrl(String base, String value) {
        if (TextUtils.isEmpty(value)) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("//")) {
            try {
                return new URL(base).getProtocol() + ":" + trimmed;
            } catch (MalformedURLException ignored) {
                return "http:" + trimmed;
            }
        }
        try {
            return new URL(new URL(base), trimmed).toString();
        } catch (MalformedURLException ignored) {
            return trimmed;
        }
    }

    private static boolean looksLikeMedia(String url) {
        String lower = url.toLowerCase(Locale.US);
        return lower.contains(".m3u8") || lower.contains(".mp4")
                || lower.contains(".m4v") || lower.contains(".flv");
    }

    private static boolean passesFilter(String title, String url, String filter) {
        return TextUtils.isEmpty(filter)
                || (title + " " + url).toLowerCase(Locale.US)
                .contains(filter.toLowerCase(Locale.US));
    }

    private static Pattern compileOrDefault(String expression, Pattern fallback) {
        if (TextUtils.isEmpty(expression)) {
            return fallback;
        }
        try {
            return compile(expression);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static Pattern compile(String expression) {
        return Pattern.compile(expression, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    }

    private static String group(Matcher matcher, int group) {
        try {
            return matcher.group(group);
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String cleanText(String value) {
        String text = value == null ? "" : value;
        text = Html.fromHtml(text).toString();
        return text.replaceAll("\\s+", " ").trim();
    }

    public static final class SearchResult {
        public final List<VideoSource> items;
        public final int failedSources;

        SearchResult(List<VideoSource> items, int failedSources) {
            this.items = items;
            this.failedSources = failedSources;
        }

        public int size() {
            return items.size();
        }
    }
}
