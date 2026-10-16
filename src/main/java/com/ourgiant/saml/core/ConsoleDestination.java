package com.ourgiant.saml.core;

import java.util.regex.Pattern;

/**
 * Where Open Console lands for a profile (#226, #239): the profile's chosen {@link ConsoleService}
 * if any, else Console Home, in the profile's region when one is explicitly set.
 *
 * <p>Only the commercial partition is used, because sign-in goes through
 * {@code signin.aws.amazon.com}, which can't log in to GovCloud or China consoles; a GovCloud or
 * China region therefore gets the region-less commercial page instead of a non-existent host.
 */
public final class ConsoleDestination {

    public static final String DEFAULT = "https://console.aws.amazon.com/";
    private static final Pattern REGION = Pattern.compile("[a-z]{2}(-[a-z]+)+-\\d+");

    private ConsoleDestination() {
    }

    /**
     * @param serviceId      the profile's {@code consoleservice}, or null for Console Home
     * @param explicitRegion the profile's (or global) {@code awsregion} only if actually set — not
     *                       a fallback default, which would override the console's remembered region
     */
    public static String resolve(String serviceId, String explicitRegion) {
        ConsoleService service = ConsoleService.find(serviceId);
        String region = explicitRegion == null ? null : explicitRegion.trim();
        boolean useRegion = isValidRegion(region) && !region.startsWith("us-gov-") && !region.startsWith("cn-");
        if (service == null) {
            return useRegion ? "https://" + region + ".console.aws.amazon.com/console/home?region=" + region : DEFAULT;
        }
        return useRegion
            ? "https://" + region + ".console.aws.amazon.com/" + service.path() + "?region=" + region
            : DEFAULT + service.path();
    }

    public static boolean isValidRegion(String region) {
        return region != null && REGION.matcher(region).matches();
    }
}
