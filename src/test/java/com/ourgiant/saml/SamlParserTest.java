package com.ourgiant.saml;

import org.apache.commons.codec.binary.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SamlParserTest {

    private final SamlParser parser = new SamlParser();

    @Test
    void parseRolesFromSaml_extractsRolesFromAttributeValues() throws Exception {
        String saml = samlWithRoleAttributeValues(
                "arn:aws:iam::123456789012:role/AdminRole,arn:aws:iam::123456789012:saml-provider/Okta",
                "arn:aws:iam::987654321098:role/ReadOnlyRole,arn:aws:iam::987654321098:saml-provider/Okta"
        );

        List<SamlRole> roles = parser.parseRolesFromSaml(encode(saml));

        assertEquals(2, roles.size());
        assertEquals("123456789012", roles.get(0).getAccountNumber());
        assertEquals("AdminRole", roles.get(0).getRoleName());
        assertEquals("987654321098", roles.get(1).getAccountNumber());
        assertEquals("ReadOnlyRole", roles.get(1).getRoleName());
    }

    @Test
    void parseRolesFromSaml_ignoresNonRoleAttributes() throws Exception {
        String saml = """
                <samlp:Response xmlns:samlp="urn:oasis:names:tc:SAML:2.0:protocol"
                                 xmlns:saml="urn:oasis:names:tc:SAML:2.0:assertion">
                  <saml:Assertion>
                    <saml:AttributeStatement>
                      <saml:Attribute Name="https://aws.amazon.com/SAML/Attributes/SessionDuration">
                        <saml:AttributeValue>3600</saml:AttributeValue>
                      </saml:Attribute>
                    </saml:AttributeStatement>
                  </saml:Assertion>
                </samlp:Response>
                """;

        List<SamlRole> roles = parser.parseRolesFromSaml(encode(saml));

        assertTrue(roles.isEmpty());
    }

    @Test
    void parseRolesFromSaml_throwsWhenAssertionMissing() {
        String saml = """
                <samlp:Response xmlns:samlp="urn:oasis:names:tc:SAML:2.0:protocol">
                </samlp:Response>
                """;

        assertThrows(Exception.class, () -> parser.parseRolesFromSaml(encode(saml)));
    }

    @Test
    void parseRolesFromSaml_rejectsDoctypeDeclarations(@TempDir Path tempDir) throws Exception {
        // Regression test for the XXE fix: a malicious/compromised IdP could otherwise smuggle a
        // DOCTYPE with an external entity to read local files (or trigger SSRF via an external DTD).
        // Legitimate SAML responses never contain a DOCTYPE, so parsing must reject it outright
        // rather than silently resolving the entity.
        Path secretFile = tempDir.resolve("secret.txt");
        Files.writeString(secretFile, "top-secret-contents");

        String saml = """
                <?xml version="1.0"?>
                <!DOCTYPE foo [<!ENTITY xxe SYSTEM "%s">]>
                <samlp:Response xmlns:samlp="urn:oasis:names:tc:SAML:2.0:protocol"
                                 xmlns:saml="urn:oasis:names:tc:SAML:2.0:assertion">
                  <saml:Assertion>
                    <saml:AttributeStatement>
                      <saml:Attribute Name="https://aws.amazon.com/SAML/Attributes/Role">
                        <saml:AttributeValue>&xxe;</saml:AttributeValue>
                      </saml:Attribute>
                    </saml:AttributeStatement>
                  </saml:Assertion>
                </samlp:Response>
                """.formatted(secretFile.toUri());

        assertThrows(Exception.class, () -> parser.parseRolesFromSaml(encode(saml)));
    }

    private static String samlWithRoleAttributeValues(String... roleValues) {
        StringBuilder valuesXml = new StringBuilder();
        for (String value : roleValues) {
            valuesXml.append("<saml:AttributeValue>").append(value).append("</saml:AttributeValue>\n");
        }

        return """
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
                """.formatted(valuesXml);
    }

    private static String encode(String xml) {
        return Base64.encodeBase64String(xml.getBytes(StandardCharsets.UTF_8));
    }
}
