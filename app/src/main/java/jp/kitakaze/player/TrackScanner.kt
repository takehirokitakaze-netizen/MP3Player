package jp.kitakaze.player

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

data class Track(val uri: Uri, val name: String, val sortKey: String) {
    fun toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(uri.toString())
            .setUri(uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(name).build())
            .build()
}

/** 選んだフォルダ（サブフォルダ3階層まで）から音声ファイルを集めて名前順に並べる */
object TrackScanner {
    private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac")

    fun scan(context: Context, treeUri: Uri): List<Track> {
        val out = mutableListOf<Track>()
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        scanDir(context, treeUri, rootId, "", out, 0)
        return out.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.sortKey })
    }

    private fun scanDir(
        context: Context, treeUri: Uri, docId: String, prefix: String,
        out: MutableList<Track>, depth: Int
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (depth < 3) scanDir(context, treeUri, id, "$prefix$name/", out, depth + 1)
                } else if (isAudio(name, mime)) {
                    out += Track(
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                        name.substringBeforeLast('.'),
                        prefix + name
                    )
                }
            }
        }
    }

    private fun isAudio(name: String, mime: String): Boolean =
        mime.startsWith("audio/") || name.substringAfterLast('.', "").lowercase() in AUDIO_EXT
}
