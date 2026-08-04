package com.tvbox.legacy.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Parses one lightweight rule from object, array, or {rules:[...]} JSON. */
public final class RuleStore {
    private RuleStore() {
    }

    public static SiteRule parse(String json) throws JSONException {
        List<SiteRule> rules = parseMany(json);
        if (rules.isEmpty()) {
            throw new JSONException("rule list is empty");
        }
        return rules.get(0);
    }

    public static List<SiteRule> parseMany(String json) throws JSONException {
        if (json == null || json.trim().length() == 0) {
            throw new JSONException("empty rule");
        }
        String value = json.trim();
        List<SiteRule> result = new ArrayList<SiteRule>();
        if (value.startsWith("[")) {
            addArray(result, new JSONArray(value));
            return result;
        }
        JSONObject root = new JSONObject(value);
        if (root.optJSONArray("sites") != null) {
            throw new JSONException("qist/tvbox config needs its native player; import a lightweight rule");
        }
        JSONArray rules = root.optJSONArray("rules");
        if (rules != null) {
            addArray(result, rules);
            return result;
        }
        JSONObject nested = root.optJSONObject("rule");
        result.add(SiteRule.fromJson(nested == null ? root : nested));
        return result;
    }

    private static void addArray(List<SiteRule> result, JSONArray array) throws JSONException {
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = array.optJSONObject(index);
            if (item != null) {
                result.add(SiteRule.fromJson(item));
            }
        }
    }
}
