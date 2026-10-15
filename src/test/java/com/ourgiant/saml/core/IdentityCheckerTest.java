package com.ourgiant.saml.core;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;
import software.amazon.awssdk.services.sts.model.StsException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IdentityCheckerTest {

    private static StsClient answering(Object result) {
        StsClient client = mock(StsClient.class);
        if (result instanceof RuntimeException e) {
            when(client.getCallerIdentity(any(GetCallerIdentityRequest.class))).thenThrow(e);
        } else {
            when(client.getCallerIdentity(any(GetCallerIdentityRequest.class))).thenReturn((GetCallerIdentityResponse) result);
        }
        return client;
    }

    private static StsException stsError(String code) {
        return (StsException) StsException.builder()
            .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).errorMessage("detail from AWS").build())
            .statusCode(403).message("detail from AWS").build();
    }

    @Test
    void returnsIdentityAndRoleNameForAssumedRole() throws Exception {
        IdentityChecker.Identity identity = IdentityChecker.check(answering(GetCallerIdentityResponse.builder()
            .arn("arn:aws:sts::123456789012:assumed-role/Developer/alice@example.com")
            .account("123456789012").userId("AROAEXAMPLE:alice@example.com").build()));

        assertEquals("123456789012", identity.account());
        assertEquals("Developer", identity.roleName());
        assertEquals("AROAEXAMPLE:alice@example.com", identity.userId());
    }

    @Test
    void roleNameIsNullForNonRoleIdentities() {
        assertNull(new IdentityChecker.Identity("arn:aws:iam::123456789012:user/bob", "123456789012", "AIDA").roleName());
        assertEquals("R", new IdentityChecker.Identity("arn:aws:sts::1:assumed-role/R", "1", "x").roleName());
    }

    @Test
    void mapsStsErrorsToProblems() {
        assertEquals(IdentityChecker.Problem.EXPIRED, problemFor(stsError("ExpiredToken")));
        assertEquals(IdentityChecker.Problem.NOT_RECOGNIZED, problemFor(stsError("InvalidClientTokenId")));
        assertEquals(IdentityChecker.Problem.NOT_RECOGNIZED, problemFor(stsError("SignatureDoesNotMatch")));
        assertEquals(IdentityChecker.Problem.OTHER, problemFor(stsError("Throttling")));
        assertEquals(IdentityChecker.Problem.NETWORK, problemFor(SdkClientException.create("Unable to execute HTTP request")));
    }

    @Test
    void onlyExpiredAndUnrecognizedAreFixedByNewCredentials() {
        assertTrue(IdentityChecker.Problem.EXPIRED.fixedByNewCredentials());
        assertTrue(IdentityChecker.Problem.NOT_RECOGNIZED.fixedByNewCredentials());
        assertFalse(IdentityChecker.Problem.NETWORK.fixedByNewCredentials());
        assertFalse(IdentityChecker.Problem.OTHER.fixedByNewCredentials());
    }

    @Test
    void failureMessageIncludesAwsDetail() {
        IdentityChecker.CheckFailedException e = assertThrows(IdentityChecker.CheckFailedException.class,
            () -> IdentityChecker.check(answering(stsError("ExpiredToken"))));
        assertTrue(e.getMessage().startsWith("These credentials have expired."), e.getMessage());
        assertTrue(e.getMessage().contains("detail from AWS"), e.getMessage());
    }

    private static IdentityChecker.Problem problemFor(RuntimeException error) {
        return assertThrows(IdentityChecker.CheckFailedException.class, () -> IdentityChecker.check(answering(error))).problem();
    }
}
