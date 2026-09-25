package com.mp3downloader.data.engine

import java.io.File

/**
 * Builds a non-colliding output path: "song.m4a", "song (1).m4a", ...
 * Shared by the engines so a failed or duplicated download never overwrites
 * an existing file.
 */
internal fun uniqueOutputFile(outputDir: String, baseName: String, extension: String): File {
    val dir = File(outputDir)
    val base = File(dir, "$baseName.$extension")
    if (!base.exists()) return base
    var n = 1
    while (true) {
        val candidate = File(dir, "$baseName ($n).$extension")
        if (!candidate.exists()) return candidate
        n++
    }
}
