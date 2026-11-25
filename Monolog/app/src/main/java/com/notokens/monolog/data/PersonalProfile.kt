package com.notokens.monolog.data

import java.util.regex.Pattern

internal data class PersonalProfile(
    val name: String = "",
    val nameUpdatedAt: Long = 0,
    val localSignIn: Long = 0,
    val driveSignIn: Long = 0
)

private val nameCluster = Pattern.compile("\\X")

internal fun limitProfileName(input: String): String {
    val clean = input.filterNot { it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' }
    val chars = nameCluster.matcher(clean)
    repeat(15) { if (!chars.find()) return clean }
    return clean.substring(0, chars.end())
}
