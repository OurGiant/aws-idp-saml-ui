package com.ourgiant.saml;

import org.junit.jupiter.api.Test;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BrowserLoginHandlerXPathLiteralTest {

    @Test
    void toXPathLiteral_wrapsPlainValueInSingleQuotes() {
        assertEquals("'my-role'", BrowserLoginHandler.toXPathLiteral("my-role"));
    }

    @Test
    void toXPathLiteral_usesDoubleQuotesWhenValueContainsSingleQuote() {
        assertEquals("\"O'Brien-role\"", BrowserLoginHandler.toXPathLiteral("O'Brien-role"));
    }

    @Test
    void toXPathLiteral_usesConcatWhenValueContainsBothQuoteTypes() throws Exception {
        String malicious = "x') or contains(@id,'\"and\"'";
        String literal = BrowserLoginHandler.toXPathLiteral(malicious);
        assertEquals(malicious, evaluateXPathLiteral(literal));
    }

    @Test
    void toXPathLiteral_preventsInjectionBreakingOutOfContains() throws Exception {
        // An attacker-controlled role name attempting to inject an "or true()" style
        // predicate must be treated as a literal string, not as XPath syntax.
        String injectionAttempt = "') or '1'='1";
        String literal = BrowserLoginHandler.toXPathLiteral(injectionAttempt);
        assertEquals(injectionAttempt, evaluateXPathLiteral(literal));
    }

    /**
     * Evaluates the given XPath string-literal expression against a trivial document
     * and returns the resulting string, proving it round-trips as data rather than
     * being interpreted as XPath syntax.
     */
    private static String evaluateXPathLiteral(String literalExpression) throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        Element root = doc.createElement("root");
        doc.appendChild(root);

        XPath xpath = XPathFactory.newInstance().newXPath();
        return (String) xpath.evaluate("string(" + literalExpression + ")", doc, XPathConstants.STRING);
    }
}
