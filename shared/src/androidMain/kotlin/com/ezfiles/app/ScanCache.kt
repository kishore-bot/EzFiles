package com.ezfiles.app

import android.content.Context
import com.ezfiles.reader.data.SCAN_CACHE_FOLDER
import java.io.File
import kotlin.concurrent.thread

private const val TRASH_PREFIX = "$SCAN_CACHE_FOLDER-trash-"

/**
 * Deletes what an earlier run left of a scan. Call on a fresh launch only: a
 * process restored from the background still needs its session. The rename is
 * immediate, so a new scan can't collide with the slower deletion behind it.
 */
fun clearScanCacheOnLaunch(context: Context) {
    val cache = context.cacheDir
    val current = File(cache, SCAN_CACHE_FOLDER)
    if (current.exists()) current.renameTo(File(cache, TRASH_PREFIX + System.nanoTime()))
    val trash = cache.listFiles { file -> file.name.startsWith(TRASH_PREFIX) }.orEmpty()
    if (trash.isEmpty()) return
    thread(name = "scan-cache-cleanup", isDaemon = true) { trash.forEach(File::deleteRecursively) }
}
