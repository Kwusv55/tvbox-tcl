package com.tvbox.legacy.model;

/** A search result returned by a site rule. */
public final class VideoSource {
    public final String title;
    public final String detailUrl;
    public final String coverUrl;
    public final String sourceId;
    public final String matchText;

    public VideoSource(String title, String detailUrl, String coverUrl) {
        this(title, detailUrl, coverUrl, "");
    }

    public VideoSource(String title, String detailUrl, String coverUrl, String sourceId) {
        this(title, detailUrl, coverUrl, sourceId, title);
    }

    public VideoSource(String title, String detailUrl, String coverUrl, String sourceId,
                       String matchText) {
        this.title = title == null ? "未命名" : title;
        this.detailUrl = detailUrl == null ? "" : detailUrl;
        this.coverUrl = coverUrl == null ? "" : coverUrl;
        this.sourceId = sourceId == null ? "" : sourceId;
        this.matchText = matchText == null ? this.title : matchText;
    }

    @Override
    public String toString() {
        return title;
    }
}
