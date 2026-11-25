package com.notokens.monolog.ime

import android.content.Context
import android.content.Intent
import android.content.ClipDescription
import android.content.ClipboardManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

internal data class DeviceAction(val label: String, val uri: Uri, val intentAction: String, val body: String? = null) {
    fun open(context: Context) {
        val intent = Intent(intentAction, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!body.isNullOrBlank()) intent.putExtra("sms_body", body)
        if (runCatching { context.startActivity(intent) }.isFailure) {
            Toast.makeText(context, "No app is available for this action", Toast.LENGTH_SHORT).show()
        }
    }
}

internal object DeviceActions {
    private val phone = Regex("""(?<![\w+])(?:\+\s*)?\d(?:[ ().-]*\d){6,14}(?![\w]|[ ()-]*\d)""")
    private val mail = Regex("""[A-Za-z0-9.!#$%&'*+/=?^`{|}~-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,63}""")
    private val website = Regex("""https?://[^\s<>]+""", RegexOption.IGNORE_CASE)
    private val street = Regex(
        """\b\d{1,6}[A-Za-z]?\s+(?:[\p{L}][\p{L}'.-]*\s+){1,5}(?:Street|Road|Avenue|Lane|Drive|Boulevard|Way|Marg|Nagar|Gali|St\.?|Rd\.?|Ave\.?)\b""",
        RegexOption.IGNORE_CASE)
    private val request = Regex("""^\s*(?:please\s+)?(call|dial|text|sms|message|email|mail|open|visit|navigate to|directions to)\b\s*(.*)$""",
        RegexOption.IGNORE_CASE)

    fun detect(text: String, allowLinks: Boolean, explicit: Boolean = false): List<DeviceAction> {
        val command = if (explicit) request.matchEntire(text.trim()) ?: return emptyList() else null
        val verb = command?.groupValues?.get(1)?.lowercase()
        val target = command?.groupValues?.get(2) ?: text
        if ((verb == "navigate to" || verb == "directions to") && allowLinks && target.isNotBlank()) {
            return listOf(DeviceAction("Open directions", Uri.parse("geo:0,0?q=" + Uri.encode(target)), Intent.ACTION_VIEW))
        }
        if (verb == null || verb in setOf("email", "mail")) mail.find(target)?.let {
            return listOf(DeviceAction("Email", Uri.fromParts("mailto", it.value, null), Intent.ACTION_SENDTO))
        }
        if (allowLinks && (verb == null || verb in setOf("open", "visit"))) website.find(target)?.let {
            val uri = Uri.parse(it.value.trimEnd('.', ',', ')', ']', '?'))
            if (!uri.host.isNullOrBlank() && uri.userInfo == null) return listOf(DeviceAction("Open link", uri, Intent.ACTION_VIEW))
        }
        if (allowLinks && verb == null) street.find(target)?.let { match ->
            return listOf(DeviceAction("Directions", Uri.parse("geo:0,0?q=" + Uri.encode(match.value)), Intent.ACTION_VIEW))
        }
        val number = phone.find(target)
        if (number != null && (verb == null || verb in setOf("call", "dial", "text", "sms", "message"))) {
            val digits = number.value.filter(Char::isDigit)
            if (digits.length >= 13 && !number.value.startsWith('+')) return emptyList()
            if (!explicit && (digits.length < 10 || number.value.matches(Regex("""\d{4}-\d{2}-\d{2}""")))) return emptyList()
            val address = (if (number.value.startsWith('+')) "+" else "") + digits
            val call = DeviceAction("Call", Uri.fromParts("tel", address, null), Intent.ACTION_DIAL)
            val message = target.substring(number.range.last + 1).trimStart(' ', ':', ',')
                .removePrefix("saying ").removePrefix("that ").takeIf { explicit && it.isNotBlank() }?.take(500)
            val sms = DeviceAction("Text", Uri.fromParts("smsto", address, null), Intent.ACTION_SENDTO, message)
            return when (verb) {
                "call", "dial" -> listOf(call)
                "text", "sms", "message" -> listOf(sms)
                else -> listOf(call, sms)
            }
        }
        return emptyList()
    }
}

@Composable
internal fun rememberClipboardActions(enabled: Boolean, customTriggers: String): List<DeviceAction> {
    val context = LocalContext.current
    val detector = remember(customTriggers) { SensitiveDraft().apply { setWords(customTriggers) } }
    var actions by remember { mutableStateOf(emptyList<DeviceAction>()) }
    var copiedAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(copiedAt) {
        if (copiedAt != 0L) {
            delay((copiedAt + 15_000L - System.currentTimeMillis()).coerceAtLeast(0L))
            actions = emptyList()
        }
    }
    DisposableEffect(context, enabled, detector) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        fun update() {
            actions = runCatching {
                val clip = clipboard?.primaryClip ?: return@runCatching emptyList()
                if (clip.itemCount == 0 || clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true) {
                    return@runCatching emptyList()
                }
                copiedAt = clip.description.timestamp
                if (System.currentTimeMillis() - copiedAt >= 15_000L) return@runCatching emptyList()
                val text = clip.getItemAt(0).text?.toString()?.takeIf { it.length <= 400 && !detector.matches(it) }
                if (text == null) emptyList() else DeviceActions.detect(text, allowLinks = true)
            }.getOrDefault(emptyList())
        }
        val listener = ClipboardManager.OnPrimaryClipChangedListener { update() }
        if (enabled) {
            clipboard?.addPrimaryClipChangedListener(listener)
            update()
        }
        onDispose {
            if (enabled) clipboard?.removePrimaryClipChangedListener(listener)
            actions = emptyList()
        }
    }
    return if (enabled) actions else emptyList()
}
