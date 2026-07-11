package com.ourgiant.saml;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Builds a one-time AWS Management Console sign-in URL from temporary STS credentials
 * via the AWS federation endpoint, so a profile's console can be opened without manually
 * pasting keys into the console login form.
 *
 * See: https://docs.aws.amazon.com/IAM/latest/UserGuide/id_roles_providers_enable-console-custom-url.html
 */
public class AwsConsoleLauncher {
    private static final String FEDERATION_ENDPOINT = "https://signin.aws.amazon.com/federation";
    private static final String DEFAULT_DESTINATION = "https://console.aws.amazon.com/";

    /**
     * Calls the AWS federation endpoint and returns a one-time console sign-in URL.
     * Note: SessionDuration must NOT be passed here — the federation endpoint rejects it
     * for credentials obtained via AssumeRole* (which is how this app gets its credentials).
     */
    public static String buildLoginUrl(CredentialManager.AwsCredentials credentials) throws Exception {
        String sessionJson = "{\"sessionId\":\"" + jsonEscape(credentials.getAccessKeyId())
            + "\",\"sessionKey\":\"" + jsonEscape(credentials.getSecretAccessKey())
            + "\",\"sessionToken\":\"" + jsonEscape(credentials.getSessionToken()) + "\"}";

        String signinTokenUrl = FEDERATION_ENDPOINT + "?Action=getSigninToken&Session="
            + URLEncoder.encode(sessionJson, StandardCharsets.UTF_8);

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(signinTokenUrl))
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IllegalStateException("AWS federation endpoint returned HTTP " + response.statusCode());
        }

        String signinToken = SwingMain.extractJsonString(response.body(), "SigninToken");
        if (signinToken == null) {
            throw new IllegalStateException("AWS federation endpoint did not return a sign-in token");
        }

        return FEDERATION_ENDPOINT + "?Action=login"
            + "&Destination=" + URLEncoder.encode(DEFAULT_DESTINATION, StandardCharsets.UTF_8)
            + "&SigninToken=" + URLEncoder.encode(signinToken, StandardCharsets.UTF_8);
    }

    private static String jsonEscape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
