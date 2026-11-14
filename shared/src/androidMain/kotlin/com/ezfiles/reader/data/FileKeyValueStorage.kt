package com.ezfiles.reader.data

import java.io.File
import java.io.IOException

/**
 * Larger values, one file per key, for data too big for SharedPreferences
 * (such as the library's last scan). Writes go to a temporary file first, so
 * a crash mid-write leaves the previous value, never half a file. Anything
 * stored here can be rebuilt, so read and write failures just mean "no value".
 */
internal class FileKeyValueStorage(private val dir: File) : KeyValueStorage {
    override fun get(key: String): String? = try {
        fileFor(key).takeIf { it.isFile }?.readText()
    } catch (e: IOException) {
        null
    }

    override fun put(key: String, value: String?) {
        val file = fileFor(key)
        try {
            if (value == null) {
                file.delete()
                return
            }
            dir.mkdirs()
            val part = File(dir, "${file.name}.part")
            part.writeText(value)
            if (!part.renameTo(file)) {
                file.delete()
                part.renameTo(file)
            }
        } catch (e: IOException) {
            // A cache: the next scan writes it again.
        }
    }

    private fun fileFor(key: String) = File(dir, key.filter { it.isLetterOrDigit() || it == '_' } + ".json")
}
