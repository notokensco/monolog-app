package com.notokens.monolog.ai

internal object SmartTones {
    private val work = setOf(
        "com.microsoft.teams", "com.slack", "com.microsoft.office.outlook",
        "com.google.android.gm", "com.google.android.apps.meetings"
    )
    private val chat = setOf(
        "com.whatsapp", "org.telegram.messenger", "org.thoughtcrime.securesms",
        "com.facebook.orca", "com.discord", "com.google.android.apps.messaging",
        "com.instagram.android"
    )

    fun style(packageName: String) = when (packageName) {
        in work -> RewriteStyle.PROFESSIONAL
        in chat -> RewriteStyle.FRIENDLY
        else -> null
    }
}
