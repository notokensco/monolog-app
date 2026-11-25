package com.notokens.monolog.connect

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal enum class ConnectMode(val wire: String) { LOCAL("local"), WEB("web") }

internal data class ConnectRequest(
    val mode: ConnectMode, val session: String, val browserKey: ECPublicKey, val nonce: ByteArray, val stay: Boolean,
    val handoff: Boolean = false
) {
    companion object {
        const val ORIGIN = "https://web.monolog.notokens.co"
        const val TEST_ORIGIN = "http://127.0.0.1:5173"
        val ALLOWED_ORIGINS = setOf(ORIGIN, TEST_ORIGIN)
        private val token = Regex("[A-Za-z0-9_-]{22}")

        fun parse(text: String, selected: ConnectMode): ConnectRequest {
            require(text.length in 1..768) { "Scan the Web Connect QR from your browser." }
            val uri = URI(text.trim())
            val requestOrigin = if (uri.scheme != null && uri.rawAuthority != null) {
                "${uri.scheme}://${uri.rawAuthority}"
            } else {
                null
            }
            require(requestOrigin != null && requestOrigin in ALLOWED_ORIGINS &&
                uri.rawPath == "/pair" && uri.rawFragment == null) {
                "Scan the pairing QR displayed in Monolog on your computer."
            }
            val pairs = checkNotNull(uri.rawQuery).split('&').map { entry ->
                entry.substringBefore('=') to URLDecoder.decode(entry.substringAfter('=', ""), StandardCharsets.UTF_8)
            }
            val parameters = pairs.toMap()
            require(parameters.size == pairs.size && parameters.keys.all { it in setOf("v", "mode", "session", "key", "nonce", "stay", "handoff") })
            require(parameters["v"] == "1" && parameters["mode"] == selected.wire) { "Choose the same connection mode on both devices." }
            val session = parameters["session"].orEmpty()
            require(token.matches(session) && base64Url(decodeUrl(session)) == session)
            val nonce = parameters["nonce"].orEmpty()
            require(token.matches(nonce) && base64Url(decodeUrl(nonce)) == nonce)
            val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(decodeUrl(parameters.getValue("key")))) as ECPublicKey
            require(publicKey.params.curve.field.fieldSize == 256)
            val stay = parameters["stay"]
            require(stay == null || stay == "0" || (stay == "1" && selected == ConnectMode.WEB))
            val handoff = parameters["handoff"]
            require(handoff == null || (handoff == "1" && selected == ConnectMode.LOCAL))
            return ConnectRequest(selected, session, publicKey, decodeUrl(nonce), stay == "1", handoff == "1")
        }
    }
}

internal data class ConnectChannel(val session: String, val phoneKey: String, val key: ByteArray)
internal data class ConnectPairing(val channel: ConnectChannel, val pin: String, val expiresAt: Long)

internal object ConnectProtocol {
    const val MAX_COMMAND_BYTES = 7_000_000
    private const val LABEL = "Monolog Connect v1"
    private val random = SecureRandom()

    fun pair(request: ConnectRequest, now: Long = System.currentTimeMillis()): ConnectPairing {
        val phone = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val pin = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000))
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(phone.private)
            doPhase(request.browserKey, true)
            generateSecret()
        }
        val key = try {
            val prk = hmac(request.session.toByteArray() + request.nonce, shared)
            try { hmac(prk, "$LABEL|${request.mode.wire}|$pin".toByteArray() + byteArrayOf(1)) }
            finally { prk.fill(0) }
        } finally { shared.fill(0) }
        val duration = when {
            request.mode == ConnectMode.LOCAL -> 2 * 60 * 60_000L
            else -> 24 * 60 * 60_000L
        }
        return ConnectPairing(ConnectChannel(request.session, base64Url(phone.public.encoded), key), pin, now + duration)
    }

    fun seal(channel: ConnectChannel, purpose: String, clear: ByteArray): ByteArray {
        val iv = ByteArray(12).also(random::nextBytes)
        return iv + cipher(channel, purpose, iv, Cipher.ENCRYPT_MODE).doFinal(clear)
    }

    fun open(channel: ConnectChannel, purpose: String, envelope: ByteArray): ByteArray {
        require(envelope.size >= 28)
        return cipher(channel, purpose, envelope.copyOfRange(0, 12), Cipher.DECRYPT_MODE)
            .doFinal(envelope, 12, envelope.size - 12)
    }

    private fun cipher(channel: ConnectChannel, purpose: String, iv: ByteArray, mode: Int) =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(channel.key, "AES"), GCMParameterSpec(128, iv))
            updateAAD("$LABEL|${channel.session}|$purpose".toByteArray())
        }

    private fun hmac(key: ByteArray, input: ByteArray) = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(input)
    }
}

internal fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
internal fun decodeUrl(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
