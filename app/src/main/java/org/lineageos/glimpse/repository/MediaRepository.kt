/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.repository

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import org.lineageos.glimpse.avif.AvifScanner
import org.lineageos.glimpse.datasources.AvifDataSource
import org.lineageos.glimpse.datasources.LocalDataSource
import org.lineageos.glimpse.datasources.MediaDataSource
import org.lineageos.glimpse.datasources.MediaRequestStatus
import org.lineageos.glimpse.models.Album
import org.lineageos.glimpse.models.Media
import org.lineageos.glimpse.models.MediaType
import org.lineageos.glimpse.models.RequestStatus

/**
 * Media repository. This class coordinates all the providers and their data source.
 * All methods that involves a URI as a parameter will be redirected to the
 * proper data source that can handle the media item.
 */
class MediaRepository(
    private val context: Context,
) {
    /**
     * Content resolver.
     */
    private val contentResolver = context.contentResolver

    /**
     * Local data source singleton.
     */
    private val localDataSource = LocalDataSource(
        contentResolver,
        MediaStore.VOLUME_EXTERNAL,
    ) as MediaDataSource

    /**
     * AVIF data source singleton.
     */
    private val avifScanner by lazy { AvifScanner(context) }
    private val avifDataSource by lazy { AvifDataSource(contentResolver, avifScanner) }

    /**
     * @see MediaDataSource.isMediaItemCompatible
     */
    fun isMediaItemCompatible(mediaItemUri: Uri) =
        localDataSource.isMediaItemCompatible(mediaItemUri)

    /**
     * @see MediaDataSource.mediaTypeOf
     */
    suspend fun mediaTypeOf(mediaItemUri: Uri) = localDataSource.mediaTypeOf(mediaItemUri)

    /**
     * @see MediaDataSource.reels
     */
    fun reels(
        mediaType: MediaType? = null,
        mimeType: String? = null,
    ) = localDataSource.reels(mediaType, mimeType)

    /**
     * @see MediaDataSource.favorites
     */
    fun favorites() = localDataSource.favorites()

    /**
     * @see MediaDataSource.trash
     */
    fun trash() = localDataSource.trash()

    /**
     * @see MediaDataSource.albums
     * Merges local albums with AVIF albums.
     */
    fun albums(
        mediaType: MediaType? = null,
        mimeType: String? = null,
    ): Flow<MediaRequestStatus<List<Album>>> = combine(
        localDataSource.albums(mediaType, mimeType),
        avifDataSource.albums(mediaType, mimeType),
    ) { local, avif ->
        when {
            local is RequestStatus.Success && avif is RequestStatus.Success ->
                RequestStatus.Success(local.data + avif.data)
            local is RequestStatus.Success -> local
            avif is RequestStatus.Success -> avif
            else -> local
        }
    }

    /**
     * @see MediaDataSource.album
     * Routes AVIF URIs to avifDataSource.
     */
    fun album(albumUri: Uri) = when {
        albumUri.toString().startsWith("avif://") -> avifDataSource.album(albumUri)
        else -> localDataSource.album(albumUri)
    }

    /**
     * @see MediaDataSource.media
     * Routes AVIF file URIs to avifDataSource.
     */
    fun media(mediaUri: Uri) = when {
        mediaUri.toString().endsWith(".avif", ignoreCase = true) ->
            avifDataSource.media(mediaUri)
        else -> localDataSource.media(mediaUri)
    }

    /**
     * @see MediaDataSource.medias
     */
    fun medias(mediaUris: List<Uri>) = localDataSource.medias(mediaUris)

    /**
     * AVIF-only reels.
     */
    fun avifReels() = avifDataSource.reels()

    /**
     * AVIF-only albums.
     */
    fun avifAlbums() = avifDataSource.albums()

    /**
     * AVIF album from a bucket URI.
     */
    fun avifAlbum(albumUri: Uri) = avifDataSource.album(albumUri)

    /**
     * AVIF scanner instance.
     */
    fun getAvifScanner() = avifScanner
}
