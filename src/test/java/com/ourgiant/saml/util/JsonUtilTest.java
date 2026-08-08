package com.ourgiant.saml.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JsonUtilTest {

    @Test
    void extractJsonString_findsValueForKey() {
        String json = "{\"tag_name\":\"v1.0.10\",\"html_url\":\"https://example.com/releases/v1.0.10\"}";
        assertEquals("v1.0.10", JsonUtil.extractJsonString(json, "tag_name"));
        assertEquals("https://example.com/releases/v1.0.10", JsonUtil.extractJsonString(json, "html_url"));
    }

    @Test
    void extractJsonString_returnsNullWhenKeyMissing() {
        assertNull(JsonUtil.extractJsonString("{\"other_key\":\"value\"}", "tag_name"));
    }
}
