package com.ourgiant.saml.gui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IdentityProvidersDialogTest {

    @Test
    void host_showsJustTheLoginHost() {
        assertEquals("acme.okta.com", IdentityProvidersDialog.host("https://acme.okta.com/app/aws/sso/saml"));
        assertEquals("not a url", IdentityProvidersDialog.host("not a url"));
        assertEquals("", IdentityProvidersDialog.host(""));
    }

    @Test
    void summarize_capsLongLists() {
        assertEquals("a, b", IdentityProvidersDialog.summarize(List.of("a", "b")));
        assertEquals("a, b, c, d, e and 2 more", IdentityProvidersDialog.summarize(List.of("a", "b", "c", "d", "e", "f", "g")));
    }
}
