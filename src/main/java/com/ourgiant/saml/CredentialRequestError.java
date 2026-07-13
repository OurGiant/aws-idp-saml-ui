package com.ourgiant.saml;

import org.openqa.selenium.NoSuchWindowException;
import org.openqa.selenium.TimeoutException;
import software.amazon.awssdk.services.sts.model.ExpiredTokenException;
import software.amazon.awssdk.services.sts.model.IdpCommunicationErrorException;
import software.amazon.awssdk.services.sts.model.StsException;

import javax.swing.*;
import java.util.concurrent.ExecutionException;

/**
 * Categorizes a credential-request failure so the error dialog can show failure-specific
 * guidance and only offer Retry when retrying is actually likely to help.
 */
public class CredentialRequestError {

    public enum Category {
        TRANSIENT,
        CONFIGURATION,
        USER_CANCELLED,
        UNKNOWN
    }

    private final Category category;
    private final String title;
    private final String guidance;
    private final String detail;
    private final boolean retryable;
    private final int iconMessageType;

    private CredentialRequestError(Category category, String title, String guidance, String detail,
                                    boolean retryable, int iconMessageType) {
        this.category = category;
        this.title = title;
        this.guidance = guidance;
        this.detail = detail;
        this.retryable = retryable;
        this.iconMessageType = iconMessageType;
    }

    public static CredentialRequestError classify(Throwable error) {
        Throwable root = unwrapExecutionException(error);
        String message = root.getMessage() != null ? root.getMessage() : root.toString();

        if (findCause(root, NoSuchWindowException.class) != null
                || message.contains("Password entry cancelled by user")) {
            return new CredentialRequestError(Category.USER_CANCELLED, "Login Cancelled",
                "The login was cancelled before it could finish.",
                message, true, JOptionPane.INFORMATION_MESSAGE);
        }

        StsException stsError = findCause(root, StsException.class);
        if (stsError != null) {
            if (stsError instanceof IdpCommunicationErrorException || stsError instanceof ExpiredTokenException) {
                return new CredentialRequestError(Category.TRANSIENT, "Temporary AWS Error",
                    "AWS STS had a temporary problem communicating with the identity provider. This is usually transient — you can safely retry.",
                    message, true, JOptionPane.WARNING_MESSAGE);
            }
            return new CredentialRequestError(Category.CONFIGURATION, "AWS Configuration Error",
                "AWS STS rejected the SAML assertion or role request. Check the IAM role's trust policy and this profile's account/role configuration.",
                message, false, JOptionPane.ERROR_MESSAGE);
        }

        if (root instanceof IllegalArgumentException) {
            return new CredentialRequestError(Category.CONFIGURATION, "Configuration Error",
                "This profile or SAML provider is missing required configuration.",
                message, false, JOptionPane.ERROR_MESSAGE);
        }

        if (containsAny(message, "Matching role not found", "Could not find role element",
                "SAML Assertion not found", "SAML response not found")) {
            return new CredentialRequestError(Category.CONFIGURATION, "SAML Configuration Error",
                "The identity provider didn't return a role matching this profile's account/role configuration. Check the profile and the IdP's role mapping.",
                message, false, JOptionPane.ERROR_MESSAGE);
        }

        if (containsAny(message, "MFA push selection", "FastPass selection")) {
            return new CredentialRequestError(Category.TRANSIENT, "Login Timed Out",
                "The MFA/FastPass prompt didn't appear in time. This can happen with a slow connection, an unapproved push, "
                    + "or an incorrect password on the previous screen. You can safely retry.",
                message, true, JOptionPane.WARNING_MESSAGE);
        }

        if (findCause(root, TimeoutException.class) != null) {
            return new CredentialRequestError(Category.TRANSIENT, "Login Timed Out",
                "A step in the login flow took too long, most likely due to a slow connection or IdP page load. You can safely retry.",
                message, true, JOptionPane.WARNING_MESSAGE);
        }

        return new CredentialRequestError(Category.UNKNOWN, "Authentication Error",
            "An unexpected error occurred while requesting credentials.",
            message, true, JOptionPane.ERROR_MESSAGE);
    }

    private static Throwable unwrapExecutionException(Throwable t) {
        Throwable current = t;
        while (current instanceof ExecutionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T findCause(Throwable t, Class<T> type) {
        Throwable current = t;
        while (current != null) {
            if (type.isInstance(current)) {
                return (T) current;
            }
            current = current.getCause();
        }
        return null;
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    public Category category() {
        return category;
    }

    public boolean retryable() {
        return retryable;
    }

    public String title() {
        return title;
    }

    public int iconMessageType() {
        return iconMessageType;
    }

    public String htmlMessage() {
        return "<html><body style='width: 320px'>"
            + "<p>" + escapeHtml(guidance) + "</p>"
            + "<p style='color: gray; font-size: 90%;'>" + escapeHtml(detail) + "</p>"
            + "</body></html>";
    }

    public String statusMessage() {
        return title + ": " + detail;
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
