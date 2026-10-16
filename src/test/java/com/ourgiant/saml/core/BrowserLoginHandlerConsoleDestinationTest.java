package com.ourgiant.saml.core;

import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BrowserLoginHandlerConsoleDestinationTest {

    private static final String PAGE = "https://eu-west-1.console.aws.amazon.com/ec2/home?region=eu-west-1";

    private static BrowserLoginHandler handler(WebDriver driver, AtomicBoolean cancelled) {
        return new BrowserLoginHandler(driver, false, null, true, "111111111111", "Admin", msg -> { }, cancelled::get);
    }

    @Test
    void goesToTheProfilesConsolePageOnceTheConsoleHasLoaded() {
        WebDriver driver = mock(WebDriver.class);
        when(driver.getCurrentUrl()).thenReturn("https://us-east-1.console.aws.amazon.com/console/home?region=us-east-1");
        BrowserLoginHandler handler = handler(driver, new AtomicBoolean());
        handler.setConsoleDestination(PAGE);

        handler.openConsoleDestination(Duration.ofSeconds(2));

        verify(driver).get(PAGE);
    }

    @Test
    void consoleHomeOrNoDestinationLeavesTheBrowserAlone() {
        WebDriver driver = mock(WebDriver.class);
        BrowserLoginHandler handler = handler(driver, new AtomicBoolean());

        handler.openConsoleDestination(Duration.ofSeconds(1));
        handler.setConsoleDestination(ConsoleDestination.DEFAULT);
        handler.openConsoleDestination(Duration.ofSeconds(1));

        verify(driver, never()).get(anyString());
    }

    @Test
    void consoleThatNeverLoadsIsQuietlyLeftAsIs() {
        WebDriver driver = mock(WebDriver.class);
        when(driver.getCurrentUrl()).thenReturn("https://signin.aws.amazon.com/saml");
        BrowserLoginHandler handler = handler(driver, new AtomicBoolean());
        handler.setConsoleDestination(PAGE);

        assertDoesNotThrow(() -> handler.openConsoleDestination(Duration.ofMillis(700)));
        verify(driver, never()).get(anyString());
    }

    @Test
    void signInUrlsThatMerelyMentionTheConsoleDontCountAsSignedIn() {
        WebDriver driver = mock(WebDriver.class);
        when(driver.getCurrentUrl()).thenReturn(
            "https://signin.aws.amazon.com/oauth?redirect_uri=https%3A%2F%2Fconsole.aws.amazon.com%2Fconsole%2Fhome");
        BrowserLoginHandler handler = handler(driver, new AtomicBoolean());
        handler.setConsoleDestination(PAGE);

        handler.openConsoleDestination(Duration.ofMillis(700));

        verify(driver, never()).get(anyString());
        org.junit.jupiter.api.Assertions.assertTrue(BrowserLoginHandler.isConsoleHost("https://console.aws.amazon.com/"));
        org.junit.jupiter.api.Assertions.assertTrue(BrowserLoginHandler.isConsoleHost("https://us-east-1.console.aws.amazon.com/ec2/home"));
        org.junit.jupiter.api.Assertions.assertFalse(BrowserLoginHandler.isConsoleHost("https://console.aws.amazon.com.evil.example/"));
        org.junit.jupiter.api.Assertions.assertFalse(BrowserLoginHandler.isConsoleHost("not a url"));
    }

    @Test
    void cancellingWhileWaitingStillCancels() {
        WebDriver driver = mock(WebDriver.class);
        when(driver.getCurrentUrl()).thenReturn("https://signin.aws.amazon.com/saml");
        AtomicBoolean cancelled = new AtomicBoolean(true);
        BrowserLoginHandler handler = handler(driver, cancelled);
        handler.setConsoleDestination(PAGE);

        assertThrows(CancellationException.class, () -> handler.openConsoleDestination(Duration.ofSeconds(2)));
    }
}
