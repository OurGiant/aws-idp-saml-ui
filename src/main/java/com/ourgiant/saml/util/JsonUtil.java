package com.ourgiant.saml.util;

/** Minimal JSON string-value extraction shared by callers that don't need a full JSON parser. */
public final class JsonUtil {

    private JsonUtil() {
    }

    public static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start == -1) return null;
        start += search.length();
        int end = json.indexOf("\"", start);
        if (end == -1) return null;
        return json.substring(start, end);
    }
}
