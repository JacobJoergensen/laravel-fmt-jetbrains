package io.github.jacobjoergensen.laravelfmt

import com.intellij.formatting.service.DocumentMerger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.Key

/** Applies laravel-fmt's own edits instead of replacing the whole text, which drops folds and markers on unchanged lines. */
class LaravelFmtDocumentMerger : DocumentMerger {

    override fun updateDocument(document: Document, newText: String): Boolean {
        val pending = document.getUserData(PENDING) ?: return false

        document.putUserData(PENDING, null)

        // The offsets only hold for the text laravel-fmt formatted, and only produce the text it returned.
        if (pending.stamp != document.modificationStamp || pending.text != newText) return false

        pending.replacements.forEach { document.replaceString(it.start, it.end, it.text) }

        return true
    }

    data class Pending(val stamp: Long, val text: String, val replacements: List<LaravelFmtFormattingService.Replacement>)

    companion object {
        val PENDING = Key.create<Pending>("laravel-fmt.pending")
    }
}
