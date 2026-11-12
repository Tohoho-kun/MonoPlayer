package com.example.monoplayer.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object LibraryScanner {

    private val AUDIO_EXTENSIONS = setOf("mp3", "wav", "flac", "m4a", "aac", "ogg", "opus")

    suspend fun scanAllSources(
        context: Context,
        safFolderUris: List<String>,
        customAliasMap: Map<String, String>
    ): List<TrackItem> = withContext(Dispatchers.IO) {
        val rawList = mutableListOf<TrackItem>()

        // 1. Scan Android MediaStore Audio if permission is granted
        try {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.TRACK,
                MediaStore.Audio.Media.DATA
            )
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                "${MediaStore.Audio.Media.TITLE} ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    val title = cursor.getString(titleCol) ?: "Unknown Title"
                    val artist = cursor.getString(artistCol) ?: "Unknown Artist"
                    val album = cursor.getString(albumCol) ?: "Unknown Album"
                    val duration = cursor.getLong(durCol)
                    val trackNum = cursor.getInt(trackCol) % 1000
                    val path = if (dataCol >= 0) cursor.getString(dataCol) ?: "" else ""
                    val folder = if (path.isNotEmpty()) File(path).parent ?: "MediaStore" else "MediaStore"

                    if (duration > 1000L) {
                        rawList.add(
                            TrackItem(
                                id = "ms_$id",
                                uriString = contentUri.toString(),
                                title = title,
                                rawArtist = artist,
                                normalizedArtistKey = "",
                                canonicalArtistName = "",
                                album = album,
                                durationMs = duration,
                                trackNumber = trackNum,
                                folderPath = folder
                            )
                        )
                    }
                }
            }
        } catch (_: SecurityException) {
            // Permission not granted yet; MediaStore skipped gracefully
        } catch (_: Exception) {
        }

        // 2. Recursively scan user-selected SAF folders (using cached metadata to avoid slow MMR I/O)
        val cachedTracksByUri = AppPreferencesRepository(context)
            .loadCachedLibraryTracks()
            .associateBy { it.uriString }

        for (treeUriStr in safFolderUris) {
            try {
                val treeUri = Uri.parse(treeUriStr)
                val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                if (rootDoc != null && rootDoc.exists()) {
                    scanDocumentTreeRecursive(
                        context = context,
                        dir = rootDoc,
                        currentPath = rootDoc.name ?: "Folder",
                        outList = rawList,
                        cachedByUri = cachedTracksByUri
                    )
                }
            } catch (_: Exception) {
            }
        }

        // 3. Append built-in stereo test tracks at the end of the library so real user songs come first
        val demoDir = DemoAudioGenerator.ensureDemoFilesGenerated(context)
        for (spec in DemoAudioGenerator.BUILTIN_SPECS) {
            val file = File(demoDir, spec.fileName)
            if (file.exists()) {
                rawList.add(
                    TrackItem(
                        id = spec.id,
                        uriString = Uri.fromFile(file).toString(),
                        title = spec.title,
                        rawArtist = spec.rawArtist,
                        normalizedArtistKey = "",
                        canonicalArtistName = "",
                        album = spec.album,
                        durationMs = 8000L,
                        trackNumber = spec.trackNumber,
                        sampleRate = 44100,
                        bitrateKbps = 1411,
                        channels = 2,
                        folderPath = demoDir.absolutePath,
                        genre = spec.genre,
                        isDemoTrack = true
                    )
                )
            }
        }

        // 4. Normalize all artists across the entire library to absorb naming variations
        val normalized = applyArtistNormalization(rawList, customAliasMap)
        AppPreferencesRepository(context).saveCachedLibraryTracks(normalized)
        normalized
    }

    private fun scanDocumentTreeRecursive(
        context: Context,
        dir: DocumentFile,
        currentPath: String,
        outList: MutableList<TrackItem>,
        cachedByUri: Map<String, TrackItem>,
        depth: Int = 0
    ) {
        if (depth > 8) return
        val children = dir.listFiles()
        for (file in children) {
            if (file.isDirectory) {
                val subName = file.name ?: "Sub"
                scanDocumentTreeRecursive(context, file, "$currentPath/$subName", outList, cachedByUri, depth + 1)
            } else if (file.isFile) {
                val name = file.name ?: continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext in AUDIO_EXTENSIONS) {
                    val uri = file.uri
                    val uriStr = uri.toString()
                    val existing = outList.any { it.uriString == uriStr }
                    if (!existing) {
                        val cached = cachedByUri[uriStr]
                        if (cached != null) {
                            outList.add(cached)
                        } else {
                            extractTrackFromUri(context, uri, name, currentPath)?.let { outList.add(it) }
                        }
                    }
                }
            }
        }
    }

    private fun extractTrackFromUri(
        context: Context,
        uri: Uri,
        fallbackName: String,
        folderPath: String
    ): TrackItem? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            val title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }
                ?: fallbackName.substringBeforeLast('.')
            val artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() }
                ?: mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                ?.takeIf { it.isNotBlank() }
                ?: "Unknown Artist"
            val album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.takeIf { it.isNotBlank() }
                ?: folderPath.substringAfterLast('/')
            val duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val bitrate = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toIntOrNull() ?: 320000) / 1000
            val trackStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
            val trackNum = trackStr?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0
            val genre = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE) ?: ""

            TrackItem(
                id = "saf_${uri.toString().hashCode().toUInt()}",
                uriString = uri.toString(),
                title = title,
                rawArtist = artist,
                normalizedArtistKey = "",
                canonicalArtistName = "",
                album = album,
                durationMs = duration.coerceAtLeast(1000L),
                trackNumber = trackNum,
                bitrateKbps = bitrate,
                folderPath = folderPath,
                genre = genre
            )
        } catch (_: Exception) {
            TrackItem(
                id = "saf_${uri.toString().hashCode().toUInt()}",
                uriString = uri.toString(),
                title = fallbackName.substringBeforeLast('.'),
                rawArtist = "Unknown Artist",
                normalizedArtistKey = "",
                canonicalArtistName = "",
                album = folderPath.substringAfterLast('/'),
                durationMs = 180_000L,
                folderPath = folderPath
            )
        } finally {
            try {
                mmr.release()
            } catch (_: Exception) {
            }
        }
    }

    fun applyArtistNormalization(
        tracks: List<TrackItem>,
        customAliasMap: Map<String, String>
    ): List<TrackItem> {
        val groupedByKey = tracks.groupBy { track ->
            ArtistNormalizer.computeCanonicalKey(track.rawArtist, customAliasMap)
        }

        val canonicalNamesByKey = groupedByKey.mapValues { (_, groupTracks) ->
            val rawSet = groupTracks.map { it.rawArtist }.toSet()
            ArtistNormalizer.chooseCanonicalDisplayName(rawSet, customAliasMap)
        }

        return tracks.map { track ->
            val key = ArtistNormalizer.computeCanonicalKey(track.rawArtist, customAliasMap)
            val canonicalName = canonicalNamesByKey[key] ?: track.rawArtist
            track.copy(
                normalizedArtistKey = key,
                canonicalArtistName = canonicalName
            )
        }
    }
}
