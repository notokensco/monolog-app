package com.notokens.monolog.backup

import android.accounts.Account
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.CustomCredential
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

internal data class BackupAccount(
    val id: String = "", val email: String = "", val name: String = "", val session: String = "",
    val connected: Boolean = false, val reconnect: Boolean = false,
    val journal: Boolean = true, val context: Boolean = true, val attachments: Boolean = true,
    val frequency: String = "Manual", val lastBackup: Long = 0, val loadedYears: Set<Int> = emptySet()
)

internal class BackupAccountStore private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("drive_backup_account", Context.MODE_PRIVATE)
    private fun read() = BackupAccount(
        prefs.getString("id", "").orEmpty(), prefs.getString("email", "").orEmpty(),
        prefs.getString("name", "").orEmpty(), prefs.getString("session", "").orEmpty(),
        prefs.getBoolean("connected", false), prefs.getBoolean("reconnect", false),
        prefs.getBoolean("journal", true), prefs.getBoolean("context", true), prefs.getBoolean("attachments", true),
        prefs.getString("frequency", "Manual").orEmpty(), prefs.getLong("lastBackup", 0),
        prefs.getStringSet("loadedYears", emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.toSet()
    )
    private val mutableState = MutableStateFlow(read())
    val state = mutableState.asStateFlow()
    private val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        mutableState.value = read()
    }
    init { prefs.registerOnSharedPreferenceChangeListener(listener) }

    private fun android.content.SharedPreferences.Editor.persist() {
        check(commit()) { "Could not save account settings." }
        mutableState.value = read()
    }

    fun select(id: String, email: String, name: String) {
        BackupWork.cancel(context)
        prefs.edit().clear().putString("id", id).putString("email", email).putString("name", name)
            .putString("session", UUID.randomUUID().toString()).persist()
    }
    fun signOut() {
        BackupWork.cancel(context)
        prefs.edit().clear().persist()
    }
    fun disconnect() {
        BackupWork.cancel(context)
        prefs.edit().putBoolean("connected", false).putBoolean("reconnect", false)
            .putString("frequency", "Manual").putString("session", UUID.randomUUID().toString()).persist()
    }
    fun connected(session: String) {
        requireSession(session)
        prefs.edit().putBoolean("connected", true).putBoolean("reconnect", false).persist()
        BackupWork.schedule(context, state.value)
    }
    fun needsConsent(session: String) {
        if (state.value.session != session) return
        BackupWork.cancel(context)
        prefs.edit().putBoolean("reconnect", true).persist()
    }
    fun selection(journal: Boolean, context: Boolean, attachments: Boolean) {
        BackupWork.cancel(this.context)
        prefs.edit().putBoolean("journal", journal).putBoolean("context", context)
            .putBoolean("attachments", attachments).persist()
        BackupWork.schedule(this.context, state.value)
    }
    fun frequency(value: String) {
        require(value in listOf("Manual", "Daily", "Weekly"))
        prefs.edit().putString("frequency", value).persist()
        BackupWork.schedule(context, state.value)
    }
    fun success(session: String) {
        requireSession(session)
        prefs.edit().putLong("lastBackup", System.currentTimeMillis()).persist()
    }
    fun loaded(session: String, years: Set<Int>) {
        requireSession(session)
        prefs.edit().putStringSet("loadedYears", (state.value.loadedYears + years).map(Int::toString).toSet()).persist()
    }
    fun requireSession(session: String) {
        check(session.isNotBlank() && state.value.session == session && state.value.id.isNotBlank()) {
            "The selected Google account changed. Try again."
        }
    }
    companion object {
        @Volatile private var instance: BackupAccountStore? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: BackupAccountStore(context.applicationContext).also { instance = it }
        }
    }
}

internal object GoogleBackupAuth {
    const val WEB_CLIENT_ID = "1027000826226-phe55n81vkpn5fqu4dbhfoihn6setd3r.apps.googleusercontent.com"
    const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    suspend fun signIn(context: Context): Triple<String, String, String> {
        val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(BackupCrypto.randomBytes(32))
        val option = GetSignInWithGoogleOption.Builder(WEB_CLIENT_ID).setNonce(nonce).build()
        val response = CredentialManager.create(context).getCredential(context,
            GetCredentialRequest.Builder().addCredentialOption(option).build())
        val credential = response.credential
        require(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
        val google = GoogleIdTokenCredential.createFrom(credential.data)
        val claims = JSONObject(Base64.getUrlDecoder().decode(google.idToken.split('.')[1]).toString(Charsets.UTF_8))
        require(claims.getString("aud") == WEB_CLIENT_ID && claims.getString("nonce") == nonce &&
            claims.getString("iss") in listOf("https://accounts.google.com", "accounts.google.com") &&
            claims.getLong("exp") > System.currentTimeMillis() / 1000)
        return Triple(claims.getString("sub"), google.id, google.displayName.orEmpty())
    }

    fun request(account: BackupAccount): AuthorizationRequest = AuthorizationRequest.builder()
        .setAccount(Account(account.email, "com.google"))
        .setRequestedScopes(listOf(Scope(DRIVE_SCOPE))).build()

    @Suppress("DEPRECATION")
    fun token(result: AuthorizationResult, account: BackupAccount): String {
        if (result.hasResolution() || DRIVE_SCOPE !in result.grantedScopes) throw DriveConsentRequired()
        val google = result.toGoogleSignInAccount()
        check(matchesAuthorizedAccount(account.id, account.email, google?.id, google?.email)) {
            "Drive authorized a different account. Reconnect Drive."
        }
        return result.accessToken?.takeIf { it.isNotBlank() } ?: throw DriveConsentRequired()
    }

    suspend fun accessToken(context: Context, account: BackupAccount): String =
        token(Identity.getAuthorizationClient(context).authorize(request(account)).await(), account)

    suspend fun signOut(context: Context) {
        CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
    }
    suspend fun revoke(context: Context, account: BackupAccount) {
        Identity.getAuthorizationClient(context).revokeAccess(RevokeAccessRequest.builder()
            .setAccount(Account(account.email, "com.google")).setScopes(listOf(Scope(DRIVE_SCOPE))).build()).await()
    }
}
