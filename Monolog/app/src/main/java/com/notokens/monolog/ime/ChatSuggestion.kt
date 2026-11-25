package com.notokens.monolog.ime

internal data class ChatSuggestion(val reply: String, val tip: String, val noReply: Boolean)

private val chatField = Regex("(?im)^[ \\t]*(?:[-*][ \\t]+)?\\*{0,2}(?:suggested[ \\t]+)?(reply|tip)\\*{0,2}[ \\t]*:\\*{0,2}[ \\t]*")
private val noDraft = Regex("(?i)^(?:NO_REPLY|none|n/?a|\\(?blank\\)?|[-–—]+)[.!]?$")
private val bareLabel = Regex("(?i)^\\**(?:suggested +)?(?:reply|tip)\\**:?$")
private val draftPreamble = Regex("(?i)^(?:here(?:'s|’s| is) (?:a |the )?(?:draft|suggested reply)|(?:a |the )?draft:)")

internal fun parseChatSuggestion(answer: String, goal: String): ChatSuggestion {
    val text = answer.trim().removeSurrounding("```").trim()
    val fields = chatField.findAll(text).toList()
    fun content(label: String): String? = fields.withIndex().firstNotNullOfOrNull { (index, match) ->
        if (!match.groupValues[1].equals(label, ignoreCase = true)) return@firstNotNullOfOrNull null
        text.substring(match.range.last + 1, fields.getOrNull(index + 1)?.range?.first ?: text.length)
            .trim().takeIf(String::isNotEmpty)
    }
    val candidate = (if (fields.isEmpty()) text else content("reply")).orEmpty().trim('"', '“', '”').trim()
    val noReply = candidate.trimEnd('.', '!').equals("NO_REPLY", true)
    val reply = candidate.takeUnless {
        noDraft.matches(it) || bareLabel.matches(it) || chatField.containsMatchIn(it) || draftPreamble.containsMatchIn(it) ||
            it.equals(goal.trim(), true) || it.contains("```")
    }.orEmpty().take(400)
    val tip = content("tip").orEmpty().take(400)
    return ChatSuggestion(reply, tip, noReply)
}
