package com.notokens.monolog.backup

/** AuthorizationRequest.setAccount pins the account. Drive-only grants can omit profile fields. */
internal fun matchesAuthorizedAccount(
    expectedId: String, expectedEmail: String, returnedId: String?, returnedEmail: String?
): Boolean {
    val id = returnedId?.trim()?.takeIf { it.isNotEmpty() }
    if (id != null) return id == expectedId
    val email = returnedEmail?.trim()?.takeIf { it.isNotEmpty() }
    return email == null || email.equals(expectedEmail.trim(), ignoreCase = true)
}
