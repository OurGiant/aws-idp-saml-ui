package com.ourgiant.saml.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebDriverFactoryTest {

    @Test
    void edgePreferencesAllowLocalNetworkAccessForExactHttpsOrigin() {
        Map<String, Object> preferences = WebDriverFactory.edgePreferences(
            "https://login.example.com/app/sso/saml", true);

        assertEquals(Map.of("setting", 1), localNetworkAccessExceptions(preferences)
            .get("https://login.example.com:443,*"));
        assertTrue(preferences.containsKey("protocol_handler"));
    }

    @Test
    void edgePreferencesPreserveCustomPortInLocalNetworkAccessOrigin() {
        Map<String, Object> preferences = WebDriverFactory.edgePreferences(
            "https://login.example.com:8443/app/sso/saml", true);

        assertEquals(Map.of("setting", 1), localNetworkAccessExceptions(preferences)
            .get("https://login.example.com:8443,*"));
    }

    @Test
    void edgePreferencesOmitLocalNetworkExceptionForUnsupportedSchemes() {
        Map<String, Object> preferences = WebDriverFactory.edgePreferences(
            "file://login.example.com/app/sso/saml", true);

        assertFalse(preferences.containsKey("profile.content_settings.exceptions.local_network_access"));
        assertFalse(preferences.containsKey("protocol_handler"));
    }

    @Test
    void edgePreferencesDoNotGrantFastPassPermissionsWhenFastPassIsDisabled() {
        Map<String, Object> preferences = WebDriverFactory.edgePreferences(
            "https://login.example.com/app/sso/saml", false);

        assertTrue(preferences.isEmpty());
    }

    @Test
    void edgePreferencesDoNotGrantFastPassPermissionsToHttpOrigins() {
        Map<String, Object> preferences = WebDriverFactory.edgePreferences(
            "http://login.example.com/app/sso/saml", true);

        assertTrue(preferences.isEmpty());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> localNetworkAccessExceptions(Map<String, Object> preferences) {
        return (Map<String, Object>) preferences.get(
            "profile.content_settings.exceptions.local_network_access");
    }
}