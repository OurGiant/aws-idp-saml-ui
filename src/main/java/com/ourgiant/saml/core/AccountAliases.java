package com.ourgiant.saml.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Account aliases from AWS's SAML role-selection page (#244). The SAML assertion itself carries
 * only account numbers; AWS adds aliases when it renders {@code signin.aws.amazon.com/saml}, where
 * each account group is headed "Account: my-alias (123456789012)" (or "Account: 123456789012"
 * when the account has no alias). Best-effort by design: if AWS changes that markup, or skips the
 * page (a user with a single role), the result is simply empty and imports fall back to numbers.
 */
public final class AccountAliases {

    // An IAM account alias is 3-63 lowercase letters, digits and hyphens.
    private static final Pattern ACCOUNT_HEADING =
        Pattern.compile("Account:\\s*([a-z0-9][a-z0-9-]{1,61}[a-z0-9])\\s*\\((\\d{12})\\)");

    private AccountAliases() {
    }

    /** Account number to alias, for every account on the page that has one. */
    public static Map<String, String> parse(String pageSource) {
        Map<String, String> aliases = new LinkedHashMap<>();
        if (pageSource == null || pageSource.isEmpty()) {
            return aliases;
        }
        String text = pageSource.replace("&nbsp;", " ").replace("&#160;", " ");
        Matcher matcher = ACCOUNT_HEADING.matcher(text);
        while (matcher.find()) {
            aliases.putIfAbsent(matcher.group(2), matcher.group(1));
        }
        return aliases;
    }
}
