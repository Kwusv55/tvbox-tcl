package com.tvbox.legacy.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Parses one lightweight rule from object, array, or {rules:[...]} JSON. */
public final class RuleStore {
    private RuleStore() {
    }

    public static SiteRule parse(String json) throws JSONException {
        if (json == null || json.trim().length() == 0) {
            throw new JSONException("empty rule");
        }
        String value = json.trim();
        if (value.startsWith("[")) {
            JSONArray array = new JSONArray(value);
            if (array.length() == 0) {
                throw new JSONException("rule array is empty");
            }
            return SiteRule.fromJson(array.getJSONObject(0));
        }
        JSONObject root = new JSONObject(value);
        JSONArray rules = root.optJSONArray("rules");
        if (rules != null) {
            if (rules.length() == 0) {
                throw new JSONException("rules array is empty");
            }
            return SiteRule.fromJson(rules.getJSONObject(0));
        }
        if (root.optJSONArray("sites") != null) {
            throw new JSONException("qist/tvbox config needs its native player; import a lightweight rule");
        }
        JSONObject nested = root.optJSONObject("rule");
        return SiteRule.fromJson(nested == null ? root : nested);
    }
}
