package com.ourgiant.saml.core;

import java.time.Instant;

/**
 * Token state manager that delegates to DatabaseManager for persistence.
 * This class maintains backward compatibility with existing code.
 */
public class TokenStateManager {
    private final DatabaseManager databaseManager;

    public TokenStateManager() {
        this.databaseManager = new DatabaseManager();
    }

    public void updateExpiration(String profileName, Instant expiration) {
        databaseManager.updateExpiration(profileName, expiration);
    }

    /** Forgets a profile's saved expiry (its credentials were cleared, #224). */
    public void clearExpiration(String profileName) {
        databaseManager.deleteProfileState(profileName);
    }

    public Instant getExpiration(String profileName) {
        return databaseManager.getExpiration(profileName);
    }

    public boolean isTokenValid(String profileName) {
        return databaseManager.isTokenValid(profileName);
    }

    public java.util.Map<String, Instant> getAllExpirations() {
        return databaseManager.getAllExpirations();
    }

    public void close() {
        databaseManager.close();
    }
}
