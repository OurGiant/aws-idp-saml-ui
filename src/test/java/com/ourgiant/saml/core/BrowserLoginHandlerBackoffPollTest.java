package com.ourgiant.saml.core;

import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;

import java.time.Duration;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BrowserLoginHandlerBackoffPollTest {

    private static final Duration TINY_DELAY = Duration.ofMillis(5);

    private BrowserLoginHandler newHandler(WebDriver driver, java.util.function.BooleanSupplier cancelled) {
        return new BrowserLoginHandler(driver, false, null, false, "111111111111", "SomeRole",
            message -> { }, cancelled);
    }

    @Test
    void findsNeedleOnFirstAttemptWithoutSleeping() {
        WebDriver driver = mock(WebDriver.class);
        when(driver.getPageSource()).thenReturn("<html>has-the-marker</html>");
        BrowserLoginHandler handler = newHandler(driver, () -> false);

        boolean found = handler.pollPageSourceWithBackoff("has-the-marker", Duration.ofSeconds(5),
            TINY_DELAY, TINY_DELAY, 2.0);

        assertTrue(found);
    }

    @Test
    void findsNeedleAfterInitialMisses() {
        WebDriver driver = mock(WebDriver.class);
        when(driver.getPageSource()).thenReturn(
            "<html>nope</html>",
            "<html>still-nope</html>",
            "<html>has-the-marker</html>");
        BrowserLoginHandler handler = newHandler(driver, () -> false);

        boolean found = handler.pollPageSourceWithBackoff("has-the-marker", Duration.ofSeconds(5),
            TINY_DELAY, TINY_DELAY, 2.0);

        assertTrue(found);
    }

    @Test
    void givesUpAfterDeadlineIfNeedleNeverAppears() {
        WebDriver driver = mock(WebDriver.class);
        when(driver.getPageSource()).thenReturn("<html>nope</html>");
        BrowserLoginHandler handler = newHandler(driver, () -> false);

        boolean found = handler.pollPageSourceWithBackoff("has-the-marker", Duration.ofMillis(30),
            TINY_DELAY, TINY_DELAY, 2.0);

        assertFalse(found);
    }

    @Test
    void detectsForcedPasswordResetScreenByHeadingText() {
        WebDriver driver = mock(WebDriver.class);
        when(driver.getPageSource()).thenReturn(
            "<html><body><h1>Reset your Okta password</h1>"
            + "<input name=\"newPassword\"/><input name=\"confirmPassword\"/>"
            + "<button>Reset Password</button></body></html>");
        BrowserLoginHandler handler = newHandler(driver, () -> false);

        boolean found = handler.pollPageSourceWithBackoff("Reset your Okta password", Duration.ofSeconds(5),
            TINY_DELAY, TINY_DELAY, 2.0);

        assertTrue(found);
    }

    @Test
    void doesNotMistakeMfaSelectionScreenForPasswordReset() {
        WebDriver driver = mock(WebDriver.class);
        when(driver.getPageSource()).thenReturn(
            "<html><body><a class=\"button select-factor link-button\">Select Okta Verify.</a></body></html>");
        BrowserLoginHandler handler = newHandler(driver, () -> false);

        boolean found = handler.pollPageSourceWithBackoff("Reset your Okta password", Duration.ofMillis(30),
            TINY_DELAY, TINY_DELAY, 2.0);

        assertFalse(found);
    }

    @Test
    void throwsCancellationExceptionWhenCancelledBeforeAMatch() {
        WebDriver driver = mock(WebDriver.class);
        when(driver.getPageSource()).thenReturn("<html>nope</html>");
        BrowserLoginHandler handler = newHandler(driver, () -> true);

        assertThrows(CancellationException.class, () ->
            handler.pollPageSourceWithBackoff("has-the-marker", Duration.ofSeconds(5),
                TINY_DELAY, TINY_DELAY, 2.0));
    }
}
