package com.notokens.monolog.data

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

data class BookPlacement(val id: Long?)

internal class JournalBooks(private val context: Context) {
    private val db = MonologDatabase.get(context)
    val books = db.books().observe().map { entries -> entries.mapNotNull(::open).sortedBy { it.name.lowercase() } }.flowOn(Dispatchers.IO)

    suspend fun create(input: String): Long = db.withTransaction {
        val name = input.trim().replace(Regex("\\s+"), " ").take(60)
        require(name.isNotBlank())
        db.books().all().firstNotNullOfOrNull { open(it)?.takeIf { book -> book.name.equals(name, true) } }?.id
            ?: db.books().insert(JournalBook(name = JournalCipher.seal(name, "journal-book")))
    }

    suspend fun move(ids: List<Long>, placement: BookPlacement) = db.withTransaction {
        val book = placement.id?.let { db.books().get(it)?.id }
        if (placement.id == null || book != null) ids.forEach { db.notes().move(it, book) }
    }

    suspend fun delete(book: JournalBook) = db.withTransaction {
        MonologData.repository(context).deleteNotes(db.notes().inBook(book.id))
        db.books().delete(book)
    }

    private fun open(book: JournalBook): JournalBook? = runCatching {
        book.copy(name = JournalCipher.open(book.name, "journal-book"))
    }.getOrNull()
}

private val filingInstruction = Regex("(?i)^add this (?:note |context |screenshot )?(?:to|in) (?:the )?(.+?) book[.!]?$")

internal fun bookInstruction(text: String, books: List<JournalBook>): Pair<String, BookPlacement>? {
    val trimmed = text.trimEnd()
    val name = filingInstruction.matchEntire(trimmed.substringAfterLast('\n').trim())?.groupValues?.get(1) ?: return null
    val book = books.firstOrNull { it.name.equals(name.trim(), true) } ?: return null
    return trimmed.substringBeforeLast('\n', "").trimEnd() to BookPlacement(book.id)
}
