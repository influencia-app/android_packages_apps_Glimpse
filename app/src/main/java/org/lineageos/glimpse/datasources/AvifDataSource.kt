/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.datasources

import android.graphics.BitmapFactory
import android.net.Uri
import com.radzivon.bartoshyk.avif.coder.HeifCoder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.lineageos.glimpse.avif.AvifScanner
import org.lineageos.glimpse.models.Album
import org.lineageos.glimpse.models.Media
import org.lineageos.glimpse.models.MediaType
import org.lineageos.glimpse.models.RequestStatus
import org.lineageos.glimpse.models.Thumbnail
import java.io.File
import java.util.Date

class AvifDataSource(
    private val scanner: AvifScanner,
) : MediaDataSource {

    private val heifCoder = HeifCoder()

    override fun isMediaItemCompatible(mediaItemUri: Uri): Boolean {
        val uriStr = mediaItemUri.toString()
        return uriStr.endsWith(".avif", ignoreCase = true) ||
                uriStr.startsWith("avif://", ignoreCase = true)
    }

    override suspend fun mediaTypeOf(mediaItemUri: Uri): MediaRequestStatus<MediaType> {
        return RequestStatus.Success(MediaType.IMAGE)
    }

    override fun reels(
        mediaType: MediaType?,
        mimeType: String?,
    ): Flow<MediaRequestStatus<List<Media>>> {
        val paths = scanner.getCachedPaths()
        if (paths.isEmpty()) return flowOf(RequestStatus.Success(emptyList()))

        val medias = paths.mapNotNull { pathToMedia(it) }
            .sortedByDescending { it.dateModified }

        return flowOf(RequestStatus.Success(medias))
    }

    override fun favorites(): Flow<MediaRequestStatus<List<Media>>> {
        return flowOf(RequestStatus.Success(emptyList()))
    }

    override fun trash(): Flow<MediaRequestStatus<List<Media>>> {
        return flowOf(RequestStatus.Success(emptyList()))
    }

    override fun albums(
        mediaType: MediaType?,
        mimeType: String?,
    ): Flow<MediaRequestStatus<List<Album>>> {
        val paths = scanner.getCachedPaths()
        if (paths.isEmpty()) return flowOf(RequestStatus.Success(emptyList()))

        val grouped = paths.groupBy { path ->
            File(path).parentFile?.absolutePath ?: ""
        }

        val albumList = grouped.mapNotNull { (dirPath, filePaths) ->
            val dir = File(dirPath)
            val sorted = filePaths.sortedByDescending { File(it).lastModified() }
            val firstFile = sorted.firstOrNull() ?: return@mapNotNull null

            Album(
                uri = Uri.parse("avif://$dirPath"),
                name = dir.name,
                thumbnail = Thumbnail(uri = Uri.fromFile(File(firstFile))),
                mediaCount = sorted.size,
            )
        }.sortedByDescending { it.name }

        return flowOf(RequestStatus.Success(albumList))
    }

    override fun album(albumUri: Uri): Flow<MediaRequestStatus<Pair<Album, List<Media>>>> {
        val dirPath = albumUri.toString().removePrefix("avif://")
        val dir = File(dirPath)
        if (!dir.isDirectory) {
            return flowOf(RequestStatus.Error(MediaError.NOT_FOUND))
        }

        val paths = scanner.getCachedPaths().filter { path ->
            path.startsWith(dirPath)
        }.sortedByDescending { File(it).lastModified() }

        if (paths.isEmpty()) {
            return flowOf(RequestStatus.Error(MediaError.NOT_FOUND))
        }

        val medias = paths.mapNotNull { pathToMedia(it) }
        val firstFile = File(paths.first())
        val album = Album(
            uri = albumUri,
            name = dir.name,
            thumbnail = Thumbnail(uri = Uri.fromFile(firstFile)),
            mediaCount = medias.size,
        )

        return flowOf(RequestStatus.Success(album to medias))
    }

    override fun media(mediaUri: Uri): Flow<MediaRequestStatus<Media>> {
        val path = mediaUri.toString().removePrefix("file://")
        val file = File(path)
        if (!file.exists() || !file.name.endsWith(".avif", ignoreCase = true)) {
            return flowOf(RequestStatus.Error(MediaError.NOT_FOUND))
        }

        val media = pathToMedia(file.absolutePath)
            ?: return flowOf(RequestStatus.Error(MediaError.NOT_FOUND))

        return flowOf(RequestStatus.Success(media))
    }

    override fun medias(mediaUris: List<Uri>): Flow<MediaRequestStatus<List<Media>>> {
        val medias = mediaUris.mapNotNull { uri ->
            val path = uri.toString().removePrefix("file://")
            pathToMedia(path)
        }
        return flowOf(RequestStatus.Success(medias))
    }

    private fun pathToMedia(path: String): Media? {
        val file = File(path)
        if (!file.exists()) return null

        val parentFile = file.parentFile
        val (width, height) = getDimensions(file)

        return Media(
            uri = Uri.fromFile(file),
            mediaType = MediaType.IMAGE,
            mimeType = "image/avif",
            albumUri = Uri.parse("avif://${parentFile?.absolutePath ?: ""}"),
            albumName = parentFile?.name ?: "AVIF",
            displayName = file.name,
            isFavorite = false,
            isTrashed = false,
            dateAdded = Date(file.lastModified()),
            dateModified = Date(file.lastModified()),
            width = width,
            height = height,
            orientation = 0,
            sizeBytes = file.length(),
        )
    }

    private fun getDimensions(file: File): Pair<Int, Int> {
        return try {
            val bytes = file.inputStream().use { it.readBytes().take(8192).toByteArray() }
            val size = heifCoder.getSize(bytes)
            if (size != null) size.width to size.height
            else fallbackDimensions(file)
        } catch (_: Exception) {
            fallbackDimensions(file)
        }
    }

    private fun fallbackDimensions(file: File): Pair<Int, Int> {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            (options.outWidth.takeIf { it > 0 } ?: 0) to
                    (options.outHeight.takeIf { it > 0 } ?: 0)
        } catch (_: Exception) {
            0 to 0
        }
    }
}
