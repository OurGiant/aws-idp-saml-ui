package com.ourgiant.saml.core;

import java.util.List;

/**
 * Starting points for common identity providers: a placeholder login URL and the page title that
 * shows the login page has loaded. Shared by first-run setup and the Identity Providers screen (#249).
 */
public final class IdpPresets {

    /**
     * @param label             shown in the provider type list
     * @param suggestedName     placeholder AWS provider name; the real one must match IAM
     * @param loginPage         placeholder SAML login URL
     * @param loginTitle        typical login page title
     */
    public record Preset(String label, String suggestedName, String loginPage, String loginTitle) {
        @Override
        public String toString() {
            return label;
        }
    }

    public static final List<Preset> ALL = List.of(
        new Preset("Okta", "Okta", "https://your-org.okta.com/app/amazon_aws/your-app-id/sso/saml", "Okta"),
        new Preset("Azure AD", "AzureAD", "https://login.microsoftonline.com/your-tenant-id/saml2", "Sign in to your account"),
        new Preset("ADFS", "ADFS", "https://your-adfs-server/adfs/ls/IdpInitiatedSignOn.aspx", "Sign In"),
        new Preset("Ping Identity", "Ping", "https://your-ping-server/idp/startSSO.ping", "Sign On"),
        new Preset("OneLogin", "OneLogin", "https://your-subdomain.onelogin.com/trust/saml2/http-post/sso/your-app-id", "OneLogin"),
        new Preset("Other SAML 2.0", "", "https://", "")
    );

    private IdpPresets() {
    }
}
