package com.tvbox.legacy.model;

/** An episode link exposed by a site rule. */
public final class Episode {
    public final String title;
    public final String pageUrl;

    public Episode(String title, String pageUrl) {
        this.title = title == null ? "播放" : title;
        this.pageUrl = pageUrl == null ? "" : pageUrl;
    }

    @Override
    public String toString() {
        return title;
    }
}
