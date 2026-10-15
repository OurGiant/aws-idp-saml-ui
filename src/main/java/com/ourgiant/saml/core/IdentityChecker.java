package com.ourgiant.saml.core;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;
import software.amazon.awssdk.services.sts.model.StsException;

import java.time.Duration;

/**
 * "Who am I?" for a profile (#223): calls STS GetCallerIdentity with the profile's saved
 * credentials, which both proves they still work and names the exact role/account they act as.
 * GetCallerIdentity needs no IAM permissions, so any valid credentials succeed.
 */
public final class IdentityChecker {

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(15);

    private IdentityChecker() {
    }

    public record Identity(String arn, String account, String userId) {

        /** The role name for an assumed-role ARN ({@code arn:aws:sts::123:assumed-role/Role/session}), else null. */
        public String roleName() {
            int start = arn == null ? -1 : arn.indexOf(":assumed-role/");
            if (start < 0) {
                return null;
            }
            String rest = arn.substring(start + ":assumed-role/".length());
            int slash = rest.indexOf('/');
            return slash < 0 ? rest : rest.substring(0, slash);
        }
    }

    public enum Problem {
        EXPIRED("These credentials have expired. Request new credentials for this profile."),
        NOT_RECOGNIZED("AWS doesn't recognize these credentials. They may have been revoked, or the credentials "
            + "file was edited. Request new credentials for this profile."),
        NETWORK("Couldn't reach AWS STS. Check your network connection or proxy and try again."),
        OTHER("AWS STS couldn't confirm these credentials.");

        private final String message;

        Problem(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }

        /** Whether requesting new credentials is the fix. */
        public boolean fixedByNewCredentials() {
            return this == EXPIRED || this == NOT_RECOGNIZED;
        }
    }

    public static class CheckFailedException extends Exception {
        private final Problem problem;

        CheckFailedException(Problem problem, Throwable cause) {
            super(problem.message() + (cause != null && cause.getMessage() != null ? " (" + cause.getMessage() + ")" : ""), cause);
            this.problem = problem;
        }

        public Problem problem() {
            return problem;
        }
    }

    public static Identity check(CredentialManager.AwsCredentials credentials, String region) throws CheckFailedException {
        AwsCredentials sdkCredentials = credentials.getSessionToken() != null && !credentials.getSessionToken().isBlank()
            ? AwsSessionCredentials.create(credentials.getAccessKeyId(), credentials.getSecretAccessKey(), credentials.getSessionToken())
            : AwsBasicCredentials.create(credentials.getAccessKeyId(), credentials.getSecretAccessKey());
        try (StsClient client = StsClient.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(sdkCredentials))
                .overrideConfiguration(c -> c.apiCallTimeout(CALL_TIMEOUT))
                .build()) {
            return check(client);
        }
    }

    static Identity check(StsClient client) throws CheckFailedException {
        try {
            GetCallerIdentityResponse response = client.getCallerIdentity(GetCallerIdentityRequest.builder().build());
            return new Identity(response.arn(), response.account(), response.userId());
        } catch (StsException e) {
            String code = e.awsErrorDetails() != null ? e.awsErrorDetails().errorCode() : null;
            Problem problem = switch (code == null ? "" : code) {
                case "ExpiredToken", "ExpiredTokenException", "RequestExpired" -> Problem.EXPIRED;
                case "InvalidClientTokenId", "SignatureDoesNotMatch", "UnrecognizedClientException", "AuthFailure" -> Problem.NOT_RECOGNIZED;
                default -> Problem.OTHER;
            };
            throw new CheckFailedException(problem, e);
        } catch (SdkClientException e) {
            throw new CheckFailedException(Problem.NETWORK, e);
        }
    }
}
