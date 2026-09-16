package com.kemzy.liveavatar.models

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class ModelImporter(private val context: Context) {
    private val plan = ModelImportPlan.required()
    private val root = File(context.filesDir, "models")

    suspend fun importFromTree(treeUri: Uri): ModelImportResult = withContext(Dispatchers.IO) {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: return@withContext ModelImportResult(false, listOf("Unable to open selected model folder"))
        if (!tree.isDirectory) return@withContext ModelImportResult(false, listOf("Selected item is not a folder"))

        root.mkdirs()
        val errors = mutableListOf<String>()
        val copied = mutableMapOf<String, Boolean>()

        for (entry in plan.entries) {
            val source = findRelative(tree, entry.relativePath)
            if (source == null || !source.isFile) {
                copied[entry.key] = false
                errors += "Missing ${entry.relativePath}"
                continue
            }
            val destination = File(root, entry.relativePath)
            destination.parentFile?.mkdirs()
            val temp = File(destination.parentFile, ".${destination.name}.part")
            try {
                context.contentResolver.openInputStream(source.uri).use { input ->
                    requireNotNull(input) { "Cannot open ${entry.relativePath}" }
                    FileOutputStream(temp).use { output ->
                        input.copyTo(output, DEFAULT_BUFFER_SIZE)
                        output.fd.sync()
                    }
                }
                require(temp.length() > 0L) { "Empty model ${entry.relativePath}" }
                if (destination.exists() && destination.length() == temp.length()) {
                    temp.delete()
                } else {
                    if (destination.exists()) destination.delete()
                    check(temp.renameTo(destination)) { "Unable to finalize ${entry.relativePath}" }
                }
                copied[entry.key] = true
            } catch (t: Throwable) {
                temp.delete()
                copied[entry.key] = false
                errors += "Failed ${entry.relativePath}: ${t.message ?: "unknown error"}"
            }
        }

        ModelImportResult(plan.isComplete(copied), errors)
    }

    fun privateRoot(): File = root

    private fun findRelative(root: DocumentFile, relativePath: String): DocumentFile? {
        var current: DocumentFile = root
        for (segment in relativePath.split('/')) {
            current = current.findFile(segment) ?: return null
        }
        return current
    }

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 256 * 1024
    }
}

data class ModelImportResult(val complete: Boolean, val errors: List<String>)
