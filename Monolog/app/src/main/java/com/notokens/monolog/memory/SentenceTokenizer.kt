package com.notokens.monolog.memory

import android.util.Base64
import android.util.JsonReader
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Unigram segmentation and the model's published SentencePiece normalization map. */
internal class SentenceTokenizer(file: File) {
    private data class Piece(val id: Int, val score: Float)
    private val vocabulary = HashMap<String, Piece>(260_000)
    private var charsMap = ByteArray(0)
    private var trie = IntArray(0)
    private var maximum = 1
    private var unknownScore = -100f

    init {
        JsonReader(file.reader()).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) when (reader.nextName()) {
                "normalizer" -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        if (reader.nextName() == "precompiled_charsmap") {
                            val bytes = Base64.decode(reader.nextString(), Base64.DEFAULT)
                            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                            val size = buffer.int
                            require(size > 0 && size % 4 == 0 && size + 4 < bytes.size)
                            trie = IntArray(size / 4) { buffer.int }
                            charsMap = bytes.copyOfRange(size + 4, bytes.size)
                        } else reader.skipValue()
                    }
                    reader.endObject()
                }
                "model" -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        if (reader.nextName() == "vocab") {
                            reader.beginArray()
                            var id = 0
                            var minimum = 0f
                            while (reader.hasNext()) {
                                reader.beginArray()
                                val text = reader.nextString()
                                val score = reader.nextDouble().toFloat()
                                reader.endArray()
                                if (id >= 4) {
                                    vocabulary[text] = Piece(id, score)
                                    maximum = maxOf(maximum, text.length)
                                    minimum = minOf(minimum, score)
                                }
                                id++
                            }
                            unknownScore = minimum - 10
                            reader.endArray()
                        } else reader.skipValue()
                    }
                    reader.endObject()
                }
                else -> reader.skipValue()
            }
            reader.endObject()
        }
        require(vocabulary.size > 200_000 && trie.isNotEmpty())
    }

    fun tokens(text: String): LongArray {
        val result = mutableListOf(0L)
        for (word in normalize(text.take(4000)).split(Regex("\\s+")).filter(String::isNotBlank)) {
            result.addAll(segment("▁$word").map(Int::toLong))
            if (result.size >= 127) break
        }
        return (result.take(127) + 2L).toLongArray()
    }

    private fun segment(text: String): List<Int> {
        val scores = FloatArray(text.length + 1) { Float.NEGATIVE_INFINITY }
        val previous = IntArray(text.length + 1)
        val ids = IntArray(text.length + 1) { 3 }
        scores[0] = 0f
        var start = 0
        while (start < text.length) {
            val charEnd = start + Character.charCount(text.codePointAt(start))
            var hasCharacter = false
            for (end in charEnd..minOf(text.length, start + maximum)) {
                val piece = vocabulary[text.substring(start, end)] ?: continue
                if (end == charEnd) hasCharacter = true
                val score = scores[start] + piece.score
                if (score > scores[end]) {
                    scores[end] = score
                    previous[end] = start
                    ids[end] = piece.id
                }
            }
            if (!hasCharacter && scores[start] + unknownScore > scores[charEnd]) {
                scores[charEnd] = scores[start] + unknownScore
                previous[charEnd] = start
                ids[charEnd] = 3
            }
            start = charEnd
        }
        val reversed = mutableListOf<Int>()
        var position = text.length
        while (position > 0) {
            val id = ids[position]
            if (id != 3 || reversed.lastOrNull() != 3) reversed.add(id)
            position = previous[position]
        }
        return reversed.asReversed()
    }

    private fun normalize(text: String): String {
        val input = text.toByteArray(Charsets.UTF_8)
        val output = java.io.ByteArrayOutputStream()
        var start = 0
        while (start < input.size) {
            var node = offset(trie[0])
            var matched = 0
            var value = -1
            for (i in start until input.size) {
                node = node xor (input[i].toInt() and 255)
                if (node !in trie.indices) break
                val unit = trie[node]
                if ((unit and 0x800000FF.toInt()) != (input[i].toInt() and 255)) break
                node = node xor offset(unit)
                if (unit and 256 != 0 && node in trie.indices) {
                    matched = i - start + 1
                    value = trie[node] and Int.MAX_VALUE
                }
            }
            if (matched > 0 && value in charsMap.indices) {
                var end = value
                while (end < charsMap.size && charsMap[end] != 0.toByte()) end++
                output.write(charsMap, value, end - value)
                start += matched
            } else {
                output.write(input[start].toInt())
                start++
            }
        }
        return output.toByteArray().toString(Charsets.UTF_8)
    }
    private fun offset(unit: Int) = (unit ushr 10) shl ((unit and 512) ushr 6)
}
