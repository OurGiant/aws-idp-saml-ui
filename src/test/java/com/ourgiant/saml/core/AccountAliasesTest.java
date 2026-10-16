package com.ourgiant.saml.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountAliasesTest {

    /** Shaped like AWS's signin.aws.amazon.com/saml role-selection page. */
    private static final String ROLE_SELECTION_PAGE = """
        <form id="saml_form" name="saml_form" action="/saml" method="post">
          <input type="hidden" name="SAMLResponse" value="PHNhbWxwOlJlc3BvbnNlPg==" />
          <fieldset>
            <div class="saml-account"> <div onClick="expandCollapse(0);">
              <img id="image0" src="/static/image/down.png" valign="middle"></img>
              <div class="saml-account-name">Account: payments-prod (111111111111)</div>
              </div>
              <hr style="border: 1px solid #ddd;">
              <div class="saml-account" id="0">
                <div class="saml-role" onClick="checkRadio(this);">
                  <input type="radio" name="roleIndex" value="arn:aws:iam::111111111111:role/Admin" class="saml-radio" id="arn:aws:iam::111111111111:role/Admin" />
                  <label for="arn:aws:iam::111111111111:role/Admin" class="saml-role-description">Admin</label>
                </div>
              </div>
            </div>
            <div class="saml-account"> <div onClick="expandCollapse(1);">
              <div class="saml-account-name">Account: 222222222222</div>
              </div>
            </div>
            <div class="saml-account"> <div onClick="expandCollapse(2);">
              <div class="saml-account-name">Account:&nbsp;data-lake-dev&nbsp;(333333333333)</div>
              </div>
            </div>
          </fieldset>
        </form>
        """;

    @Test
    void readsAliasesForAccountsThatHaveThem() {
        assertEquals(Map.of("111111111111", "payments-prod", "333333333333", "data-lake-dev"),
            AccountAliases.parse(ROLE_SELECTION_PAGE));
    }

    @Test
    void nothingToReadGivesAnEmptyMap() {
        assertTrue(AccountAliases.parse(null).isEmpty());
        assertTrue(AccountAliases.parse("").isEmpty());
        assertTrue(AccountAliases.parse("<html>AWS console home, no role selection here</html>").isEmpty());
        assertTrue(AccountAliases.parse("Account: Not_An-Alias! (111111111111)").isEmpty(), "aliases are lowercase");
    }
}
