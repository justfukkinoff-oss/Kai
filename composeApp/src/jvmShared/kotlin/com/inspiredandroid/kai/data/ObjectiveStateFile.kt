package com.inspiredandroid.kai.data

import com.inspiredandroid.kai.getAppFilesDirectory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

internal actual fun writeObjectiveStateFile(conversationId: String, markdown: String?) {
    if (!Regex("[a-zA-Z0-9_-]{1,128}").matches(conversationId)) return
    try {
        val directory = Paths.get(getAppFilesDirectory(), "objectives", conversationId)
        val path = directory.resolve("OBJECTIVE_STATE.md")
        if (markdown == null) {
            Files.deleteIfExists(path)
            return
        }
        Files.createDirectories(directory)
        val temp = Files.createTempFile(directory, ".objective-", ".tmp")
        try {
            Files.write(temp, markdown.encodeToByteArray())
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    } catch (e: IOException) {
        println("Objective checkpoint file write failed: ${e::class.simpleName}")
    } catch (e: SecurityException) {
        println("Objective checkpoint file access denied")
    }
}
