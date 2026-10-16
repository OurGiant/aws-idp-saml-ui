package com.ourgiant.saml.core;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Where Open Console lands for a profile (#226): its own console page if it sets one, else its
 * region's console home, else the plain console home.
 *
 * <p>The destination is handed to the AWS federation sign-in, so anything other than an AWS
 * console page is refused — otherwise a samlsts entry could turn sign-in into a redirect to an
 * arbitrary site. Only the commercial partition's console hosts are allowed, because sign-in
 * goes through {@code signin.aws.amazon.com}, which can't log in to GovCloud or China consoles.
 */
public final class ConsoleDestination {

    public static final String DEFAULT = "https://console.aws.amazon.com/";
    private static final Pattern REGION = Pattern.compile("[a-z]{2}(-[a-z]+)+-\\d+");

    private ConsoleDestination() {
    }

    /**
     * @param consolePage   the profile's {@code consoledestination}, or null/blank
     * @param explicitRegion the profile's (or global) {@code awsregion} only if actually set — not
     *                       a fallback default, which would override the console's remembered region
     * @throws IllegalArgumentException if {@code consolePage} is set but not an allowed AWS console URL
     */
    public static String resolve(String consolePage, String explicitRegion) {
        if (consolePage != null && !consolePage.isBlank()) {
            String problem = problemWith(consolePage);
            if (problem != null) {
                throw new IllegalArgumentException(problem);
            }
            return consolePage.trim();
        }
        String region = explicitRegion == null ? null : explicitRegion.trim();
        // GovCloud/China regions have no console under console.aws.amazon.com (see the class comment).
        if (isValidRegion(region) && !region.startsWith("us-gov-") && !region.startsWith("cn-")) {
            return "https://" + region + ".console.aws.amazon.com/console/home?region=" + region;
        }
        return DEFAULT;
    }

    public static boolean isValidRegion(String region) {
        return region != null && REGION.matcher(region).matches();
    }

    /** Null if {@code url} is an allowed console destination, else a message explaining why not. */
    public static String problemWith(String url) {
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            return "\"" + url.trim() + "\" isn't a valid URL.";
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return "The console page must start with https://.";
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (uri.getRawUserInfo() != null || uri.getPort() != -1) {
            return "The console page can't include a user name, password or port.";
        }
        if (host.equals("console.amazonaws-us-gov.com") || host.endsWith(".console.amazonaws-us-gov.com")
                || host.equals("console.amazonaws.cn") || host.endsWith(".console.amazonaws.cn")) {
            return "GovCloud and China console pages aren't supported: Open Console signs in through the "
                + "standard AWS sign-in, which can't log in to those partitions.";
        }
        if (!host.equals("console.aws.amazon.com") && !host.endsWith(".console.aws.amazon.com")) {
            return "The console page must be an AWS console address (…console.aws.amazon.com), not \"" + host + "\".";
        }
        return null;
    }
}
