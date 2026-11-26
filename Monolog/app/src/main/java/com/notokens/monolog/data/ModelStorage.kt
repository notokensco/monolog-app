package com.notokens.monolog.data

import android.content.Context
import java.io.File

internal fun removeRetiredJournalModels(context: Context) {
    val root = context.noBackupFilesDir
    listOf("models/journal-summarizer-200m-v1.litertlm", "models/journal-summarizer-200m-v1.litertlm.partial",
        "sentence-encoder/model.onnx", "sentence-encoder/model.onnx.part",
        "sentence-encoder/tokenizer.json", "sentence-encoder/tokenizer.json.part").forEach { File(root, it).delete() }
    File(root, "sentence-encoder").delete()
}

internal fun storageBreakdown(context: Context): List<Pair<String, Long>> {
    fun bytes(file: File) = file.walkTopDown().filter(File::isFile).sumOf(File::length)
    val root = context.noBackupFilesDir
    val models = File(root, "models")
    val voice = root.listFiles().orEmpty().filter { it.name.startsWith("moonshine-") || it.name.startsWith("vox-") }
    return listOf(
        "Installed app and native code" to (File(context.applicationInfo.sourceDir).length() +
            context.applicationInfo.splitSourceDirs.orEmpty().sumOf { File(it).length() } +
            (context.applicationInfo.nativeLibraryDir?.let { bytes(File(it)) } ?: 0)),
        "Text models" to bytes(models),
        "Voice packs" to voice.sumOf { bytes(it) },
        "Journal database and attachments" to bytes(File(context.dataDir, "databases")),
        "Cache" to bytes(context.cacheDir),
        "Other app files" to (bytes(context.filesDir) + root.listFiles().orEmpty().filter { it != models && it !in voice }.sumOf { bytes(it) })
    )
}
