package com.tvbox.legacy.model;

/** A search result returned by a site rule. */
public final class VideoSource {
    public final String title;
    public final String detailUrl;
    public final String coverUrl;

    public VideoSource(String title, String detailUrl, String coverUrl) {
        this.title = title == null ? "未命名" : title;
        this.detailUrl = detailUrl == null ? "" : detailUrl;
        this.coverUrl = coverUrl == null ? "" : coverUrl;
    }

    @Override
    public String toString() {
        return title;
    }
}
