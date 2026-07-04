package com.ourgiant.saml;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordManagerTest {

    @Mock
    private DatabaseManager databaseManager;

    private final Map<String, String> config = new HashMap<>();

    @BeforeEach
    void setUp() {
        config.clear();
        when(databaseManager.getConfig(anyString())).thenAnswer(inv -> config.get(inv.getArgument(0, String.class)));
        doAnswer(inv -> config.put(inv.getArgument(0, String.class), inv.getArgument(1, String.class)))
                .when(databaseManager).setConfig(anyString(), anyString());
        lenient().when(databaseManager.getPasswordExpirationMinutes()).thenReturn(1440);
    }

    @Test
    void storeAndRetrievePassword_roundTripsCorrectly() {
        PasswordManager passwordManager = new PasswordManager(databaseManager);

        passwordManager.storePassword("hunter2");

        assertEquals("hunter2", passwordManager.retrievePassword());
    }

    @Test
    void storedPassword_isNotKeptAsPlaintextInConfig() {
        PasswordManager passwordManager = new PasswordManager(databaseManager);

        passwordManager.storePassword("hunter2");

        assertEquals(false, config.get("okta_password").contains("hunter2"));
    }

    @Test
    void retrievePassword_returnsNullWhenNothingStored() {
        PasswordManager passwordManager = new PasswordManager(databaseManager);

        assertNull(passwordManager.retrievePassword());
    }

    @Test
    void retrievePassword_returnsNullAndClearsWhenExpired() {
        when(databaseManager.getPasswordExpirationMinutes()).thenReturn(0);
        PasswordManager passwordManager = new PasswordManager(databaseManager);
        passwordManager.storePassword("hunter2");

        assertNull(passwordManager.retrievePassword());
        assertEquals("", config.get("okta_password"));
    }

    @Test
    void clearPassword_removesStoredPassword() {
        PasswordManager passwordManager = new PasswordManager(databaseManager);
        passwordManager.storePassword("hunter2");

        passwordManager.clearPassword();

        assertNull(passwordManager.retrievePassword());
    }

    @Test
    void isPasswordStorageEnabled_defaultsToFalse() {
        PasswordManager passwordManager = new PasswordManager(databaseManager);

        assertEquals(false, passwordManager.isPasswordStorageEnabled());
    }

    @Test
    void setPasswordStorageEnabled_falseClearsStoredPassword() {
        PasswordManager passwordManager = new PasswordManager(databaseManager);
        passwordManager.storePassword("hunter2");

        passwordManager.setPasswordStorageEnabled(false);

        assertNull(passwordManager.retrievePassword());
    }
}
