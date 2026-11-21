package com.notokens.monolog.backup

import org.junit.Assert.*
import org.junit.Test

class AuthorizedAccountTest {
    @Test fun driveOnlyGrantCanOmitIdentityFieldsForThePinnedAccount() {
        assertTrue(matchesAuthorizedAccount("google-sub", "selected@example.invalid", null, null))
    }
    @Test fun stableAccountIdTakesPrecedenceOverProfileEmail() {
        assertTrue(matchesAuthorizedAccount("google-sub", "selected@example.invalid", "google-sub", "alias@example.invalid"))
        assertFalse(matchesAuthorizedAccount("google-sub", "selected@example.invalid", "different-sub", "selected@example.invalid"))
    }
    @Test fun emailComparisonHandlesCaseAndWhitespaceWhenNoIdIsReturned() {
        assertTrue(matchesAuthorizedAccount("google-sub", "selected@example.invalid", null, " SELECTED@example.invalid "))
        assertFalse(matchesAuthorizedAccount("google-sub", "selected@example.invalid", null, "other@example.invalid"))
    }
}
