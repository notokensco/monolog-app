package com.notokens.monolog.setup

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.notokens.monolog.data.SettingsStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Opens Android's folder picker and returns directly to the app that opened setup. */
class ScreenshotFolderActivity : ComponentActivity() {
    private val settings by lazy { SettingsStore(applicationContext) }
    private var pickerLaunched = false
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val folder = result.data?.data?.takeIf { result.resultCode == RESULT_OK && DocumentsContract.isTreeUri(it) }
        if (folder == null) finish() else lifecycleScope.launch { linkFolder(folder) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pickerLaunched = savedInstanceState?.getBoolean("pickerLaunched") == true
        if (pickerLaunched) return
        lifecycleScope.launch {
            val saved = settings.screenshotFolder.first()
            val initial = saved?.let { folder ->
                runCatching {
                    val parent = Uri.parse(folder)
                    DocumentsContract.buildDocumentUriUsingTree(parent, DocumentsContract.getTreeDocumentId(parent))
                }.getOrNull()
            } ?: withContext(Dispatchers.IO) { initialScreenshotFolder() }
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                .putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
            try {
                picker.launch(intent)
                pickerLaunched = true
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this@ScreenshotFolderActivity, "Android’s folder picker is unavailable.", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pickerLaunched", pickerLaunched)
        super.onSaveInstanceState(outState)
    }

    private suspend fun linkFolder(folder: Uri) {
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val previous = settings.screenshotFolder.first()?.let(Uri::parse)
        try {
            contentResolver.takePersistableUriPermission(folder, read)
            var linked = false
            try {
                settings.setScreenshotFolder(folder.toString())
                linked = true
            } finally {
                val release = if (linked) previous?.takeIf { it != folder } else folder.takeIf { it != previous }
                release?.let { runCatching { contentResolver.releasePersistableUriPermission(it, read) } }
            }
            Toast.makeText(this, "Screenshot paste enabled", Toast.LENGTH_SHORT).show()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            Toast.makeText(this, "Couldn’t link that folder. Try choosing Screenshots again.", Toast.LENGTH_LONG).show()
        } finally { finish() }
    }

    @Suppress("DEPRECATION")
    private fun initialScreenshotFolder(): Uri {
        val pictures = "${Environment.DIRECTORY_PICTURES}/Screenshots"
        val camera = "${Environment.DIRECTORY_DCIM}/Screenshots"
        val options = if (Build.MANUFACTURER.equals("Samsung", ignoreCase = true)) listOf(camera, pictures)
            else listOf(pictures, camera)
        val storage = Environment.getExternalStorageDirectory()
        val likely = options.firstOrNull { runCatching { File(storage, it).isDirectory }.getOrDefault(false) }
            ?: options.first()
        return DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$likely")
    }
}
