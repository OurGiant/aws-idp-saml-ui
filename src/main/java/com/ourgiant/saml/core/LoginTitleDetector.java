package com.ourgiant.saml.core;

import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Finds an identity provider's login page title (#260), so it doesn't have to be guessed: opens
 * the login URL in a browser and reads the page title once it stops changing. Identity providers
 * usually redirect once or twice before the sign-in page settles, so the first title seen isn't
 * necessarily the one sign-in will wait for.
 */
public final class LoginTitleDetector {
    private static final Logger logger = LoggerFactory.getLogger(LoginTitleDetector.class);

    /** How long to wait for a title that stops changing. */
    static final Duration TIMEOUT = Duration.ofSeconds(30);
    /** How long the title must stay the same to count as settled. */
    static final Duration SETTLE = Duration.ofMillis(2000);
    private static final long POLL_MILLIS = 250;
    static final String UNREACHABLE = "The browser couldn't open the login page. Check the URL, and that you're on "
        + "the network or VPN your identity provider needs.";

    /** Sleeps for the given milliseconds; replaceable in tests. */
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private LoginTitleDetector() {
    }

    /**
     * Opens {@code loginUrl} in {@code browserType} and returns its settled page title.
     *
     * @throws IllegalArgumentException if the URL isn't a secure https URL
     * @throws CancellationException    if {@code cancelled} turns true
     * @throws IllegalStateException    if the page doesn't settle in time or has no title
     */
    public static String detect(String browserType, String loginUrl, boolean showBrowser, BooleanSupplier cancelled)
            throws InterruptedException {
        SamlAuthenticator.validateSecureLoginUrl(loginUrl);
        logger.info("Detecting the login page title of {}", loginUrl);
        WebDriver driver = WebDriverFactory.createWebDriver(browserType, showBrowser, loginUrl);
        try {
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("Detection cancelled");
            }
            try {
                driver.get(loginUrl);
            } catch (org.openqa.selenium.WebDriverException e) {
                // Chrome reports an unreachable host as "net::ERR_...", Firefox as "Reached error page".
                if (isNetworkError(e.getMessage())) {
                    throw new IllegalStateException(UNREACHABLE, e);
                }
                throw e;
            }
            String title = waitForSettledTitle(driver::getTitle, System::nanoTime, Thread::sleep, cancelled, TIMEOUT, SETTLE);
            // A browser's "can't reach this page" screen has a title too (usually the host name).
            if (isBrowserErrorPage(driver.getCurrentUrl())) {
                throw new IllegalStateException(UNREACHABLE);
            }
            logger.info("Detected login page title: {}", title);
            return title;
        } finally {
            driver.quit();
        }
    }

    /** True for a browser's message about a page it couldn't load (DNS, connection, certificate). */
    static boolean isNetworkError(String message) {
        return message != null && (message.contains("net::ERR_") || message.contains("Reached error page"));
    }

    /** True for a browser's own error page (Chrome/Edge {@code chrome-error://}, Firefox {@code about:neterror}). */
    static boolean isBrowserErrorPage(String currentUrl) {
        if (currentUrl == null) {
            return false;
        }
        String url = currentUrl.toLowerCase(java.util.Locale.ROOT);
        return url.contains("-error://") || url.startsWith("about:neterror") || url.startsWith("about:certerror");
    }

    /**
     * Polls {@code title} until it's non-blank and unchanged for {@code settle}.
     *
     * @return the settled title, trimmed
     */
    static String waitForSettledTitle(Supplier<String> title, LongSupplier nanoTime, Sleeper sleeper,
                                      BooleanSupplier cancelled, Duration timeout, Duration settle)
            throws InterruptedException {
        long start = nanoTime.getAsLong();
        String last = null;
        long lastChange = start;
        while (true) {
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("Detection cancelled");
            }
            long now = nanoTime.getAsLong();
            String current = title.get();
            current = current == null ? "" : current.trim();
            if (!current.equals(last)) {
                last = current;
                lastChange = now;
            } else if (!current.isEmpty() && now - lastChange >= settle.toNanos()) {
                return current;
            }
            if (now - start >= timeout.toNanos()) {
                throw new IllegalStateException(current.isEmpty()
                    ? "The login page didn't show a title within " + timeout.toSeconds() + " seconds. "
                        + "Check the login page URL, or enter the title by hand."
                    : "The login page title kept changing for " + timeout.toSeconds() + " seconds (last: \""
                        + current + "\"). Enter the title by hand.");
            }
            sleeper.sleep(POLL_MILLIS);
        }
    }
}
