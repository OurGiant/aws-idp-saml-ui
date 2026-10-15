package com.ourgiant.saml.core;

import org.apache.commons.codec.binary.Base64;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class BrowserLoginHandlerRoleCheckTest {

    private static BrowserLoginHandler handlerFor(String account, String role) {
        return new BrowserLoginHandler(mock(WebDriver.class), false, null, true, account, role,
            message -> { }, () -> false);
    }

    private static String assertionWithRoles(String... roleArns) {
        StringBuilder values = new StringBuilder();
        for (String arn : roleArns) {
            String account = arn.split(":")[4];
            values.append("<saml:AttributeValue>").append(arn)
                .append(",arn:aws:iam::").append(account).append(":saml-provider/Okta</saml:AttributeValue>\n");
        }
        String xml = """
                <samlp:Response xmlns:samlp="urn:oasis:names:tc:SAML:2.0:protocol"
                                 xmlns:saml="urn:oasis:names:tc:SAML:2.0:assertion">
                  <saml:Assertion>
                    <saml:AttributeStatement>
                      <saml:Attribute Name="https://aws.amazon.com/SAML/Attributes/Role">
                        %s
                      </saml:Attribute>
                    </saml:AttributeStatement>
                  </saml:Assertion>
                </samlp:Response>
                """.formatted(values);
        return Base64.encodeBase64String(xml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void requireRoleInAssertion_passesWhenRoleIsPresent() {
        String saml = assertionWithRoles("arn:aws:iam::111111111111:role/Admin");

        assertDoesNotThrow(() -> handlerFor("111111111111", "Admin").requireRoleInAssertion(saml));
    }

    @Test
    void requireRoleInAssertion_failsFastWithAvailableRolesWhenMissing() {
        String saml = assertionWithRoles("arn:aws:iam::111111111111:role/ReadOnly", "arn:aws:iam::222222222222:role/Admin");

        RoleNotFoundException ex = assertThrows(RoleNotFoundException.class,
            () -> handlerFor("111111111111", "Admin").requireRoleInAssertion(saml));

        assertEquals(2, ex.availableRoles().size());
        assertEquals("ReadOnly", ex.nearMatches().get(0).roleName());
    }

    @Test
    void requireRoleInAssertion_defersToPageSelectionWhenAssertionIsUnparseable() {
        assertDoesNotThrow(() -> handlerFor("111111111111", "Admin").requireRoleInAssertion("not-base64-xml"));
    }
}
