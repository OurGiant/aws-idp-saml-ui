package com.ourgiant.saml.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginTitleDetectorTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Duration SETTLE = Duration.ofSeconds(2);

    /** A fake clock that advances only when the detector sleeps. */
    private final AtomicLong clock = new AtomicLong();

    /** Titles returned in turn on each poll; the last one repeats. */
    private java.util.function.Supplier<String> titles(String... sequence) {
        Deque<String> queue = new ArrayDeque<>(List.of(sequence));
        return () -> queue.size() > 1 ? queue.poll() : queue.peek();
    }

    private String run(java.util.function.Supplier<String> titles, java.util.function.BooleanSupplier cancelled) throws Exception {
        return LoginTitleDetector.waitForSettledTitle(titles, clock::get,
            millis -> clock.addAndGet(Duration.ofMillis(millis).toNanos()), cancelled, TIMEOUT, SETTLE);
    }

    @Test
    void returnsTitleOnceItStopsChanging_afterRedirects() throws Exception {
        String title = run(titles("", "Redirecting...", "Redirecting...", "  Acme - Sign In  "), () -> false);
        assertEquals("Acme - Sign In", title);
        long seconds = Duration.ofNanos(clock.get()).toSeconds();
        assertTrue(seconds >= 2 && seconds < 5, "waited for the settle time, not the timeout: " + seconds + "s");
    }

    @Test
    void blankTitleTimesOutWithAHelpfulMessage() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> run(titles(""), () -> false));
        assertTrue(e.getMessage().contains("didn't show a title"), e.getMessage());
    }

    @Test
    void ever_changingTitleTimesOut() {
        AtomicLong n = new AtomicLong();
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> run(() -> "Loading " + n.incrementAndGet(), () -> false));
        assertTrue(e.getMessage().contains("kept changing"), e.getMessage());
    }

    @Test
    void cancellationStopsPolling() {
        AtomicLong polls = new AtomicLong();
        assertThrows(CancellationException.class,
            () -> run(() -> { polls.incrementAndGet(); return "x"; }, () -> polls.get() >= 3));
    }

    @Test
    void rejectsInsecureUrlBeforeOpeningABrowser() {
        assertThrows(IllegalArgumentException.class,
            () -> LoginTitleDetector.detect("chrome", "http://insecure.example/saml", false, () -> false));
    }

    @Test
    void recognisesBrowserErrorPages() {
        assertTrue(LoginTitleDetector.isBrowserErrorPage("chrome-error://chromewebdata/"));
        assertTrue(LoginTitleDetector.isBrowserErrorPage("about:neterror?e=dnsNotFound&u=https%3A//x.invalid/"));
        assertTrue(LoginTitleDetector.isBrowserErrorPage("about:certerror?e=nssBadCert"));
        org.junit.jupiter.api.Assertions.assertFalse(LoginTitleDetector.isBrowserErrorPage("https://acme.okta.com/login"));
        org.junit.jupiter.api.Assertions.assertFalse(LoginTitleDetector.isBrowserErrorPage(null));
    }

    @Test
    void recognisesBrowserNetworkErrors() {
        assertTrue(LoginTitleDetector.isNetworkError("unknown error: net::ERR_NAME_NOT_RESOLVED\n  (Session info: chrome=141)"));
        assertTrue(LoginTitleDetector.isNetworkError("Reached error page: about:neterror?e=dnsNotFound"));
        org.junit.jupiter.api.Assertions.assertFalse(LoginTitleDetector.isNetworkError("session not created"));
        org.junit.jupiter.api.Assertions.assertFalse(LoginTitleDetector.isNetworkError(null));
    }
}
