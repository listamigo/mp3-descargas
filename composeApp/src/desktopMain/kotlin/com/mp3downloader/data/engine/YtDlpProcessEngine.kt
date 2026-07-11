package com.mp3downloader.data.engine

import com.mp3downloader.data.dto.YtDlpSearchJson
import com.mp3downloader.domain.model.DownloadStatus
import com.mp3downloader.domain.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json

class YtDlpProcessEngine : DownloadEngine {

    private val json = Json { ignoreUnknownKeys = true }
    private val activeProcesses = mutableMapOf<String, Process>()
    private val cancellations = Channel<String>(Channel.UNLIMITED)

    override suspend fun search(query: String, offset: Int): Result<List<Song>> = withContext(Dispatchers.IO) {
        runCatching {
            val total = 20 + maxOf(offset, 0)
            val process = ProcessBuilder(
                "yt-dlp", "--js-runtimes", "deno",
                "--flat-playlist",
                "--dump-json",
                "--no-warnings",
                "ytsearch${total}:$query"
            )
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            if (process.exitValue() != 0) {
                throw RuntimeException("yt-dlp search failed: ${output.take(200)}")
            }

            val all = output.lines().filter { it.isNotBlank() }.map { line ->
                val entry = json.decodeFromString<YtDlpSearchJson>(line)
                Song(
                    id = entry.id,
                    title = entry.title,
                    artist = extractArtist(entry.title),
                    duration = (entry.duration ?: 0.0).toLong(),
                    thumbnailUrl = entry.thumbnail ?: "",
                    audioUrl = null
                )
            }
            all.drop(offset).take(20)
        }
    }

    override suspend fun getAudioStreamUrl(song: Song): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            withTimeout(15_000L) {
                val url = "https://youtube.com/watch?v=${song.id}"
                val process = ProcessBuilder(
                    "yt-dlp", "--js-runtimes", "deno",
                    "--no-warnings",
                    "-f", "bestaudio[ext=m4a]/bestaudio",
                    "--get-url",
                    url
                )
                    .redirectErrorStream(true)
                    .redirectInput(ProcessBuilder.Redirect.PIPE)
                    .start()

                process.outputStream.close()

                val output = process.inputStream.bufferedReader().readText().trim()
                process.waitFor()

                if (process.exitValue() != 0 || output.isBlank()) {
                    throw RuntimeException("Failed to get audio URL: ${output.take(200)}")
                }
                output.lines().first().trim()
            }
        }
    }

    override fun download(
        song: Song,
        outputDir: String
    ): Flow<DownloadResult> = flow {
        emit(DownloadResult(song.id, DownloadStatus.DOWNLOADING, 0f))

        val safeTitle = song.title.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        val basePath = "$outputDir/$safeTitle"
        val outputPath = "$basePath.mp3"
        val thumbPath = "$basePath.jpg"

        val process = runCatching {
            ProcessBuilder(
                "yt-dlp", "--js-runtimes", "deno",
                "--no-warnings",
                "-f", "bestaudio",
                "--extract-audio",
                "--audio-format", "mp3",
                "--audio-quality", "0",
                "--embed-thumbnail",
                "--add-metadata",
                "--write-thumbnail",
                "--convert-thumbnails", "jpg",
                "--parse-metadata", "title:%(title)s",
                "--parse-metadata", "artist:%(channel)s",
                "--parse-metadata", "album:%(playlist_title|)s",
                "--parse-metadata", "genre:YouTube Audio",
                "-o", "$basePath.%(ext)s",
                "https://youtube.com/watch?v=${song.id}"
            )
                .redirectErrorStream(true)
                .start()
        }.getOrElse { e ->
            emit(DownloadResult(song.id, DownloadStatus.FAILED, error = e.message))
            return@flow
        }

        activeProcesses[song.id] = process

        val reader = process.inputStream.bufferedReader()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            if (cancellations.tryReceive().getOrNull() == song.id) {
                process.destroyForcibly()
                emit(DownloadResult(song.id, DownloadStatus.FAILED, error = "Cancelled"))
                return@flow
            }
        }

        val exitCode = process.waitFor()
        activeProcesses.remove(song.id)

        if (exitCode != 0) {
            emit(DownloadResult(song.id, DownloadStatus.FAILED, error = "yt-dlp exited with code $exitCode"))
            return@flow
        }

        emit(DownloadResult(song.id, DownloadStatus.CONVERTING, 0.7f))

        val thumbFile = java.io.File(thumbPath)
        if (thumbFile.exists()) {
            runCatching {
                val metadataProcess = ProcessBuilder(
                    "ffmpeg", "-y",
                    "-i", outputPath,
                    "-i", thumbPath,
                    "-map", "0:0",
                    "-map", "1:0",
                    "-c", "copy",
                    "-id3v2_version", "3",
                    "-metadata:s:v", "title=Album cover",
                    "-metadata:s:v", "comment=Cover (front)",
                    "-disposition:v", "attached_pic",
                    "${outputPath}.tmp.mp3"
                )
                    .redirectErrorStream(true)
                    .start()

                metadataProcess.waitFor()
                thumbFile.delete()

                if (metadataProcess.exitValue() == 0) {
                    java.io.File("${outputPath}.tmp.mp3")
                        .renameTo(java.io.File(outputPath))
                }
            }
        }

        emit(DownloadResult(
            songId = song.id,
            status = DownloadStatus.COMPLETED,
            progress = 1f,
            outputPath = outputPath
        ))
    }.flowOn(Dispatchers.IO)

    override suspend fun cancel(songId: String) {
        cancellations.send(songId)
        activeProcesses.remove(songId)?.destroyForcibly()
    }

    private fun extractArtist(title: String): String {
        val match = Regex("^(.+?)\\s*[-–]").find(title)
        return match?.groupValues?.getOrNull(1)?.trim() ?: "Unknown Artist"
    }
}
