package com.notokens.monolog.connect

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class ConnectProtocolTest {
    private val browser = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val nonce = ByteArray(16) { (it * 2 + 1).toByte() }
    private val session = base64Url(ByteArray(16) { (it + 9).toByte() })
    private val url = "${ConnectRequest.ORIGIN}/pair?v=1&mode=local&session=$session" +
        "&key=${base64Url(browser.public.encoded)}&nonce=${base64Url(nonce)}"

    @Test fun browserAndPhoneDeriveTheSameKey() {
        val request = ConnectRequest.parse(url, ConnectMode.LOCAL)
        val phone = ConnectProtocol.pair(request, now = 1000L)
        val phonePublic = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(decodeUrl(phone.channel.phoneKey)))
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(browser.private)
            doPhase(phonePublic, true)
            generateSecret()
        }
        val salt = session.toByteArray() + nonce
        val extract = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt, "HmacSHA256")) }.doFinal(shared)
        val info = "Monolog Connect v1|local|${phone.pin}".toByteArray() + byteArrayOf(1)
        val browserKey = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(extract, "HmacSHA256")) }.doFinal(info)
        assertArrayEquals(phone.channel.key, browserKey)
        assertTrue(phone.pin.matches(Regex("[0-9]{6}")))
        assertEquals(1000L + 2 * 60 * 60_000L, phone.expiresAt)
    }

    @Test fun refusesWrongOriginWrongModeAndAmbiguousQueries() {
        assertTrue(runCatching { ConnectRequest.parse(url.replace("web.monolog.notokens.co", "example.com"), ConnectMode.LOCAL) }.isFailure)
        assertTrue(runCatching { ConnectRequest.parse(url, ConnectMode.WEB) }.isFailure)
        assertTrue(runCatching { ConnectRequest.parse("$url&v=1", ConnectMode.LOCAL) }.isFailure)
        assertTrue(runCatching { ConnectRequest.parse(url.substringBefore("&nonce="), ConnectMode.LOCAL) }.isFailure)
    }

    @Test fun requiresTheCorrectPurposeSessionAndUntamperedEnvelope() {
        val channel = ConnectProtocol.pair(ConnectRequest.parse(url, ConnectMode.LOCAL)).channel
        val clear = """{"op":"sync"}""".toByteArray()
        val envelope = ConnectProtocol.seal(channel, "request", clear)
        assertArrayEquals(clear, ConnectProtocol.open(channel, "request", envelope))
        assertTrue(runCatching { ConnectProtocol.open(channel, "response", envelope) }.isFailure)
        assertTrue(runCatching { ConnectProtocol.open(channel.copy(session = "different"), "request", envelope) }.isFailure)
        envelope[envelope.lastIndex] = (envelope.last().toInt() xor 1).toByte()
        assertTrue(runCatching { ConnectProtocol.open(channel, "request", envelope) }.isFailure)
    }
}
