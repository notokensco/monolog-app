package com.notokens.monolog.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.notokens.monolog.data.SettingsStore
import com.notokens.monolog.ui.theme.PaperKeySubText
import java.net.URL
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import org.json.JSONObject

private object LocalForecast {
    data class Forecast(val label: String, val updatedAt: Long)
    private var cached: Forecast? = null
    private val mutex = Mutex()

    suspend fun fetch(context: Context, force: Boolean): Forecast? = mutex.withLock {
        val saved = cached
        if (!force && saved != null && SystemClock.elapsedRealtime() - saved.updatedAt < 30 * 60_000) return@withLock saved
        val location = location(context) ?: return@withLock null
        withContext(Dispatchers.IO) {
            try {
                val lat = "%.2f".format(Locale.US, location.latitude)
                val lon = "%.2f".format(Locale.US, location.longitude)
                val connection = URL("https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=$lat&lon=$lon")
                    .openConnection() as HttpsURLConnection
                try {
                    connection.connectTimeout = 7000
                    connection.readTimeout = 8000
                    connection.setRequestProperty("User-Agent", "Monolog Android (https://github.com/notokensco/monolog-app)")
                    check(connection.responseCode == 200)
                    val root = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
                    val data = root.getJSONObject("properties").getJSONArray("timeseries").getJSONObject(0).getJSONObject("data")
                    val temperature = data.getJSONObject("instant").getJSONObject("details").getDouble("air_temperature").roundToInt()
                    val code = listOf("next_1_hours", "next_6_hours").firstNotNullOfOrNull { key ->
                        data.optJSONObject(key)?.optJSONObject("summary")?.optString("symbol_code")
                    }.orEmpty().substringBefore('_')
                    val condition = when {
                        "thunder" in code -> "Thunderstorms"
                        "snow" in code || "sleet" in code -> "Snow or sleet"
                        "rain" in code -> "Rain"
                        code == "partlycloudy" -> "Partly cloudy"
                        code == "cloudy" -> "Cloudy"
                        code == "fog" -> "Fog"
                        code == "clearsky" || code == "fair" -> "Clear"
                        else -> null
                    }
                    Forecast(listOfNotNull("$temperature°C", condition).joinToString(" · "),
                        SystemClock.elapsedRealtime()).also { cached = it }
                } finally { connection.disconnect() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun location(context: Context): Location? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val precise = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        if (!precise && !granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)) return null
        val providers = listOfNotNull(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER.takeIf { precise }).filter { manager.isProviderEnabled(it) }
        val recent = providers.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.elapsedRealtimeNanos }
        if (recent != null && SystemClock.elapsedRealtimeNanos() - recent.elapsedRealtimeNanos < 600_000_000_000L) return recent
        for (provider in providers) {
            val fix = withTimeoutOrNull(if (provider == LocationManager.GPS_PROVIDER) 14_000 else 4500) {
                suspendCancellableCoroutine { request ->
                    val cancel = CancellationSignal()
                    request.invokeOnCancellation { cancel.cancel() }
                    try {
                        manager.getCurrentLocation(provider, cancel, context.mainExecutor) { point ->
                            if (request.isActive) request.resume(point)
                        }
                    } catch (_: Exception) {
                        if (request.isActive) request.resume(null)
                    }
                }
            }
            if (fix != null) return fix
        }
        return null
    }
}

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

@Composable
internal fun WeatherChip() {
    val context = LocalContext.current
    val settings = remember(context) { SettingsStore(context.applicationContext) }
    val enabled by settings.locationWeather.collectAsState(false)
    val private by settings.incognito.collectAsState(false)
    val scope = rememberCoroutineScope()
    val browser = LocalUriHandler.current
    var result by remember { mutableStateOf<LocalForecast.Forecast?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    suspend fun refresh(force: Boolean) {
        if (loading || settings.incognito.first()) return
        loading = true
        try {
            result = LocalForecast.fetch(context.applicationContext, force)
            failed = result == null
        } finally { loading = false }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.values.any { it }) scope.launch { settings.setLocationWeather(true) }
        else failed = true
    }
    LaunchedEffect(enabled, private) {
        if (enabled && !private) refresh(false)
        else result = null
    }
    if (private) return
    Row(Modifier.fillMaxWidth().glassSurface(24).clickable {
        if (granted(context, Manifest.permission.ACCESS_COARSE_LOCATION) || granted(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
            scope.launch { settings.setLocationWeather(true); refresh(true) }
        } else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }.padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        MonologIcon(Glyph.GLOBE, Modifier.size(16.dp))
        Text(when {
            loading -> "Finding local weather…"
            result != null -> result?.label.orEmpty()
            failed -> "Weather unavailable · Retry"
            else -> "Use location for weather"
        }, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        if (result != null) Text("MET Norway", Modifier.clickable { browser.openUri("https://www.met.no/en") },
            style = MaterialTheme.typography.labelSmall, color = PaperKeySubText)
    }
}
