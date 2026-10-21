package com.ourgiant.saml.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Provider errors name the provider the way the UI does, without the Fed- prefix (#259). */
class SamlAuthenticatorProviderMessageTest {

    @Test
    void notFound_namesProviderWithoutPrefix_andSaysWhereToFixIt() {
        String message = SamlAuthenticator.providerNotFound("Fed-CorpOkta");
        assertTrue(message.contains("\"CorpOkta\""), message);
        assertFalse(message.contains("Fed-"), message);
        assertTrue(message.contains("File → Identity Providers"), message);
    }

    @Test
    void notFound_withNoProviderChosen() {
        assertTrue(SamlAuthenticator.providerNotFound(null).startsWith("No identity provider is chosen"));
    }

    @Test
    void incomplete_namesProviderWithoutPrefix() {
        String message = SamlAuthenticator.providerIncomplete("Fed-CorpOkta");
        assertTrue(message.contains("\"CorpOkta\"") && !message.contains("Fed-"), message);
    }
}
