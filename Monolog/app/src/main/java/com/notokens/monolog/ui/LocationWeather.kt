package com.notokens.monolog.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.notokens.monolog.data.SettingsStore
import com.notokens.monolog.ui.theme.PaperKeySubText
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
import org.json.JSONArray
import org.json.JSONObject

private object LocalForecast {
    data class Forecast(val temperature: Int, val sky: WeatherSky, val place: String,
        val forecastTime: Instant, val updatedAt: Long)
    private data class CachedForecast(val forecast: Forecast, val response: WeatherResponse)
    private data class WeatherResponse(val series: JSONArray,
        val expiresAt: Long, val lastModified: String?)
    private val cache = LruCache<String, CachedForecast>(8)
    private var latest: Forecast? = null
    private val mutex = Mutex()

    suspend fun fetch(context: Context, force: Boolean): Forecast? = mutex.withLock {
        val recent = latest
        if (!force && recent != null && SystemClock.elapsedRealtime() - recent.updatedAt < 30 * 60_000) return@withLock recent
        val location = location(context) ?: return@withLock null
        val lat = "%.2f".format(Locale.US, location.latitude)
        val lon = "%.2f".format(Locale.US, location.longitude)
        val point = "$lat,$lon"
        val saved = cache.get(point)
        coroutineScope {
            val place = if (saved == null) async {
                withTimeoutOrNull(2500) { placeName(context, lat.toDouble(), lon.toDouble()) }
                    ?: "$lat°, $lon°"
            } else null
            val cachedResponse = saved?.response
            val response = if (cachedResponse != null && SystemClock.elapsedRealtime() < cachedResponse.expiresAt) {
                cachedResponse
            } else withContext(Dispatchers.IO) { fetchForecast(lat, lon, cachedResponse) }
            if (response == null) {
                place?.cancel()
                return@coroutineScope null
            }
            val forecast = currentForecast(response.series, saved?.forecast?.place ?: checkNotNull(place).await())
                ?: return@coroutineScope null
            cache.put(point, CachedForecast(forecast, response))
            latest = forecast
            forecast
        }
    }

    private fun fetchForecast(lat: String, lon: String, saved: WeatherResponse?): WeatherResponse? {
        return try {
            val connection = URL("https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=$lat&lon=$lon")
                .openConnection() as HttpsURLConnection
            try {
                connection.connectTimeout = 7000
                connection.readTimeout = 8000
                connection.setRequestProperty("User-Agent", "Monolog Android monolog@notokens.co")
                saved?.lastModified?.let { connection.setRequestProperty("If-Modified-Since", it) }
                val status = connection.responseCode
                val expiresIn = (connection.expiration - System.currentTimeMillis()).coerceAtLeast(30 * 60_000L)
                val expiry = SystemClock.elapsedRealtime() + expiresIn
                val lastModified = connection.getHeaderField("Last-Modified")
                if (status == 304 && saved != null) {
                    return saved.copy(expiresAt = expiry, lastModified = lastModified ?: saved.lastModified)
                }
                check(status == 200)
                val root = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
                WeatherResponse(root.getJSONObject("properties").getJSONArray("timeseries"), expiry, lastModified)
            } finally { connection.disconnect() }
        } catch (_: Exception) { null }
    }

    private fun currentForecast(series: JSONArray, place: String): Forecast? = runCatching {
        val now = Instant.now().epochSecond
        val (time, row) = (0 until series.length()).asSequence().map { series.getJSONObject(it) }
            .map { Instant.parse(it.getString("time")) to it }
            .minByOrNull { (time, _) -> abs(time.epochSecond - now) } ?: return null
        if (abs(time.epochSecond - now) > 3 * 3600) return null
        val data = row.getJSONObject("data")
        val temperature = data.getJSONObject("instant").getJSONObject("details")
            .getDouble("air_temperature").roundToInt()
        val symbol = listOf("next_1_hours", "next_6_hours", "next_12_hours").firstNotNullOfOrNull { key ->
            data.optJSONObject(key)?.optJSONObject("summary")?.optString("symbol_code")?.takeIf(String::isNotBlank)
        }.orEmpty()
        Forecast(temperature, WeatherSky.fromSymbol(symbol), place, time, SystemClock.elapsedRealtime())
    }.getOrNull()

    private suspend fun placeName(context: Context, latitude: Double, longitude: Double): String? =
        suspendCancellableCoroutine { request ->
            if (!Geocoder.isPresent()) {
                request.resume(null)
                return@suspendCancellableCoroutine
            }
            try {
                Geocoder(context, Locale.getDefault()).getFromLocation(latitude, longitude, 1,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<android.location.Address>) {
                            val address = addresses.firstOrNull()
                            val place = address?.locality ?: address?.subAdminArea ?: address?.subLocality
                            val name = listOfNotNull(place, address?.adminArea, address?.countryName)
                                .distinctBy(String::lowercase).take(2).joinToString(", ").takeIf(String::isNotBlank)
                            if (request.isActive) request.resume(name)
                        }

                        override fun onError(errorMessage: String?) {
                            if (request.isActive) request.resume(null)
                        }
                    })
            } catch (_: Exception) {
                if (request.isActive) request.resume(null)
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
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val browser = LocalUriHandler.current
    var result by remember { mutableStateOf<LocalForecast.Forecast?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var showAttribution by remember { mutableStateOf(false) }
    suspend fun refresh(force: Boolean) {
        if (loading || settings.incognito.first()) return
        loading = true
        try {
            result = LocalForecast.fetch(context.applicationContext, force)
            failed = result == null
        } finally { loading = false }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.values.any { it }) scope.launch { settings.setLocationWeather(true); refresh(true) }
        else failed = true
    }
    LaunchedEffect(enabled, private, lifecycle) {
        if (enabled && !private) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    refresh(false)
                    delay(30 * 60_000L)
                }
            }
        } else result = null
    }
    if (private) return
    Row(Modifier.fillMaxWidth().glassSurface(24).clickable {
        if (granted(context, Manifest.permission.ACCESS_COARSE_LOCATION) || granted(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
            scope.launch { settings.setLocationWeather(true); refresh(true) }
        } else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }.padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        val forecast = result
        if (forecast == null) {
            WeatherArtwork(WeatherSky.UNKNOWN, false)
            Text(when {
                loading -> "Finding local weather\u2026"
                failed -> "Weather unavailable \u00b7 Retry"
                else -> "Use location for weather"
            }, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        } else {
            WeatherArtwork(forecast.sky, true)
            Column(Modifier.weight(1f)) {
                Text("${forecast.temperature}\u00b0C \u00b7 ${forecast.sky.title}", style = MaterialTheme.typography.labelLarge)
                Text("${forecast.place} \u00b7 ${localForecastTime(forecast.forecastTime)}",
                    color = PaperKeySubText, style = MaterialTheme.typography.labelSmall, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }
            Box {
                IconControl(Glyph.INFO, "Weather source", { showAttribution = true })
                DropdownMenu(showAttribution, { showAttribution = false }) {
                    DropdownMenuItem(text = { Text("Weather data by MET Norway") }, onClick = {
                        showAttribution = false
                        browser.openUri("https://www.met.no/en")
                    })
                    DropdownMenuItem(text = { Text("CC BY 4.0 · Forecast rounded and summarized") }, onClick = {
                        showAttribution = false
                        browser.openUri("https://creativecommons.org/licenses/by/4.0/")
                    })
                }
            }
        }
    }
}

private fun localForecastTime(time: Instant): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(time)

private enum class WeatherSky(val title: String) {
    SUN("Sunny"), MOON("Clear night"), MIXED("Partly cloudy"), CLOUD("Cloudy"), RAIN("Rain"),
    STORM("Thunderstorms"), SLEET("Sleet"), SNOW("Snow"), FOG("Fog"), UNKNOWN("Local forecast");

    companion object {
        fun fromSymbol(symbol: String): WeatherSky {
            val code = symbol.substringBefore('_')
            return when {
                "thunder" in code -> STORM
                "sleet" in code -> SLEET
                "snow" in code -> SNOW
                "rain" in code -> RAIN
                code == "fog" -> FOG
                code == "cloudy" -> CLOUD
                code == "partlycloudy" -> MIXED
                code == "clearsky" || code == "fair" -> if (symbol.endsWith("_night")) MOON else SUN
                else -> UNKNOWN
            }
        }
    }
}

@Composable
private fun WeatherArtwork(sky: WeatherSky, hasForecast: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    var appeared by remember(sky, hasForecast) { mutableStateOf(false) }
    LaunchedEffect(sky, hasForecast) {
        if (hasForecast) withFrameNanos { }
        appeared = true
    }
    val alpha by animateFloatAsState(if (appeared) 1f else .26f, tween(480), label = "Weather artwork")
    Canvas(Modifier.size(43.dp).graphicsLayer { this.alpha = alpha }) {
        val unit = size.minDimension / 40
        fun p(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun line(color: Color, x1: Float, y1: Float, x2: Float, y2: Float, width: Float = 1.5f) =
            drawLine(color, p(x1,y1), p(x2,y2), width * unit, StrokeCap.Round)
        val gold = Color(0xffd1842b)
        val rain = Color(0xff4e8da9)
        val cloud = Color(0xffa7b5d8)
        val center = if (sky == WeatherSky.MIXED) p(13f, 13f) else p(20f, 20f)
        drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = .19f), Color.Transparent),
            p(20f, 20f), 20 * unit), 20 * unit, p(20f, 20f))
        if (sky == WeatherSky.SUN || sky == WeatherSky.MIXED) {
            drawCircle(gold, 6.4f * unit, center)
            repeat(8) { ray ->
                val angle = ray * Math.PI / 4
                val delta = Offset(cos(angle).toFloat(), sin(angle).toFloat()) * unit
                drawLine(gold, center + delta * 8.7f, center + delta * 11.8f, 1.45f * unit, StrokeCap.Round)
            }
        }
        if (sky == WeatherSky.MOON) {
            val crescent = Path().apply {
                moveTo(23 * unit, 8 * unit)
                cubicTo(10 * unit, 6 * unit, 5 * unit, 24 * unit, 15 * unit, 30 * unit)
                cubicTo(24 * unit, 36 * unit, 33 * unit, 28 * unit, 32 * unit, 21 * unit)
                cubicTo(23 * unit, 25 * unit, 16 * unit, 17 * unit, 23 * unit, 8 * unit)
                close()
            }
            drawPath(crescent, cloud)
            drawCircle(gold, 1.6f * unit, p(30f, 12f))
        }
        if (sky in listOf(WeatherSky.MIXED, WeatherSky.CLOUD, WeatherSky.RAIN, WeatherSky.STORM, WeatherSky.SLEET, WeatherSky.SNOW)) {
            drawCircle(cloud, 7 * unit, p(16f, 20f))
            drawCircle(cloud, 8 * unit, p(24f, 17f))
            drawOval(cloud, p(7f, 17f), Size(29 * unit, 11 * unit))
        }
        when (sky) {
            WeatherSky.RAIN, WeatherSky.SLEET -> {
                line(rain, 15f, 30f, 12f, 34f)
                line(rain, 22f, 30f, 19f, 36f)
                if (sky == WeatherSky.SLEET) drawCircle(accent, 1.5f * unit, p(28f, 33f))
                else line(rain, 28f, 30f, 25f, 34f)
            }
            WeatherSky.SNOW -> for (x in listOf(13f, 21f, 29f)) {
                line(accent, x, 30f, x, 36f, 1f)
                line(accent, x - 2.5f, 31.5f, x + 2.5f, 34.5f, 1f)
                line(accent, x - 2.5f, 34.5f, x + 2.5f, 31.5f, 1f)
            }
            WeatherSky.STORM -> {
                line(gold, 23f, 25f, 18f, 31f, 2.5f)
                line(gold, 18f, 31f, 23f, 31f, 2.5f)
                line(gold, 23f, 31f, 19f, 37f, 2.5f)
            }
            WeatherSky.FOG -> {
                line(accent, 8f, 14f, 29f, 14f)
                line(cloud, 11f, 20f, 34f, 20f, 2f)
                line(accent, 7f, 26f, 27f, 26f)
            }
            WeatherSky.UNKNOWN -> {
                drawCircle(accent.copy(alpha = .6f), 3 * unit, p(20f, 20f))
                drawCircle(accent.copy(alpha = .35f), 8 * unit, p(20f, 20f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.2f * unit))
            }
            else -> Unit
        }
    }
}
