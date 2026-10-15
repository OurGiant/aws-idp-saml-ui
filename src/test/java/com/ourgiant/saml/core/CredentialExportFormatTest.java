package com.ourgiant.saml.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CredentialExportFormatTest {

    private static final String NL = System.lineSeparator();
    private static final String KEY = "ASIAEXAMPLE";
    private static final String SECRET = "abc/def+ghi=";
    private static final String TOKEN = "IQoJb3JpZ2luX2Vj/token+with=chars";

    @Test
    void bash_exportsSingleQuotedValues() {
        assertEquals("export AWS_ACCESS_KEY_ID='ASIAEXAMPLE'" + NL
                + "export AWS_SECRET_ACCESS_KEY='abc/def+ghi='" + NL
                + "export AWS_SESSION_TOKEN='IQoJb3JpZ2luX2Vj/token+with=chars'" + NL,
            CredentialExportFormat.BASH.format(KEY, SECRET, TOKEN));
    }

    @Test
    void powershell_setsEnvWithSingleQuotes() {
        assertEquals("$env:AWS_ACCESS_KEY_ID = 'ASIAEXAMPLE'" + NL
                + "$env:AWS_SECRET_ACCESS_KEY = 'abc/def+ghi='" + NL
                + "$env:AWS_SESSION_TOKEN = 'IQoJb3JpZ2luX2Vj/token+with=chars'" + NL,
            CredentialExportFormat.POWERSHELL.format(KEY, SECRET, TOKEN));
    }

    @Test
    void cmd_quotesWholeAssignment() {
        assertEquals("set \"AWS_ACCESS_KEY_ID=ASIAEXAMPLE\"" + NL
                + "set \"AWS_SECRET_ACCESS_KEY=abc/def+ghi=\"" + NL
                + "set \"AWS_SESSION_TOKEN=IQoJb3JpZ2luX2Vj/token+with=chars\"" + NL,
            CredentialExportFormat.CMD.format(KEY, SECRET, TOKEN));
    }

    @Test
    void dotenv_matchesPreviousPlainOutput() {
        assertEquals("AWS_ACCESS_KEY_ID=ASIAEXAMPLE" + NL
                + "AWS_SECRET_ACCESS_KEY=abc/def+ghi=" + NL
                + "AWS_SESSION_TOKEN=IQoJb3JpZ2luX2Vj/token+with=chars" + NL,
            CredentialExportFormat.DOTENV.format(KEY, SECRET, TOKEN));
    }

    @Test
    void embeddedSingleQuotesAreEscapedPerShell() {
        assertEquals("export X='it'\\''s'", CredentialExportFormat.BASH.line("X", "it's"));
        assertEquals("$env:X = 'it''s'", CredentialExportFormat.POWERSHELL.line("X", "it's"));
    }

    @Test
    void defaultFor_picksNativeShell() {
        assertEquals(CredentialExportFormat.POWERSHELL, CredentialExportFormat.defaultFor("Windows 11"));
        assertEquals(CredentialExportFormat.BASH, CredentialExportFormat.defaultFor("Mac OS X"));
        assertEquals(CredentialExportFormat.BASH, CredentialExportFormat.defaultFor("Linux"));
        assertEquals(CredentialExportFormat.BASH, CredentialExportFormat.defaultFor(null));
    }

    @Test
    void fromName_fallsBackToPlatformDefaultForUnknownValues() {
        assertEquals(CredentialExportFormat.CMD, CredentialExportFormat.fromName("CMD", "Linux"));
        assertEquals(CredentialExportFormat.BASH, CredentialExportFormat.fromName(null, "Linux"));
        assertEquals(CredentialExportFormat.POWERSHELL, CredentialExportFormat.fromName("FISH", "Windows 10"));
    }
}
