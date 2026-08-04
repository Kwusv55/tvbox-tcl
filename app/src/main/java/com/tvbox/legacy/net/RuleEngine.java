package com.tvbox.legacy.net;

import android.text.Html;
import android.text.TextUtils;

import com.tvbox.legacy.model.Episode;
import com.tvbox.legacy.model.SiteRule;
import com.tvbox.legacy.model.VideoSource;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
            results.add(new VideoSource(cleanText(title), resolveUrl(requestUrl, href), ""));
        }
        return results;
    }

    public static List<Episode> episodes(SiteRule rule, String detailUrl) throws IOException {
        List<Episode> episodes = new ArrayList<Episode>();
        if (TextUtils.isEmpty(detailUrl)) {
            return episodes;
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

    private static String applyKeyword(String template, String keyword) throws IOException {
        if (TextUtils.isEmpty(template)) {
            return "";
        }
        String encoded = URLEncoder.encode(keyword == null ? "" : keyword, "UTF-8");
        return template.replace("{keyword}", encoded).replace("{q}", encoded);
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
}
