package com.kemzy.liveavatar.source

import android.content.Context
import android.net.Uri
import java.util.UUID

class SourceRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("sources", Context.MODE_PRIVATE)

    fun importUri(uri: Uri, kind: SourceKind = SourceKind.LOCAL): SourceAsset {
        context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val asset = SourceAsset(UUID.randomUUID().toString(), uri, kind)
        save(asset)
        return asset
    }

    fun updateFaceCrop(source: SourceAsset, crop: FaceCrop): SourceAsset = source.copy(crop = crop).also(::save)

    fun setFavorite(source: SourceAsset, favorite: Boolean): SourceAsset = source.copy(favorite = favorite).also(::save)

    fun recent(): List<SourceAsset> = prefs.getStringSet("recent", emptySet()).orEmpty().mapNotNull(::decode).sortedByDescending { it.createdAt }

    private fun save(asset: SourceAsset) {
        val values = prefs.getStringSet("recent", emptySet()).orEmpty().toMutableSet()
        values.removeAll { it.startsWith(asset.id + "|") }
        values.add(encode(asset))
        prefs.edit().putStringSet("recent", values.takeLast(50).toSet()).apply()
    }

    private fun encode(a: SourceAsset) = listOf(a.id, a.uri.toString(), a.kind.name, a.crop.left, a.crop.top, a.crop.right, a.crop.bottom, a.favorite, a.createdAt).joinToString("|")

    private fun decode(value: String): SourceAsset? = runCatching {
        val p = value.split('|')
        SourceAsset(p[0], Uri.parse(p[1]), SourceKind.valueOf(p[2]), FaceCrop(p[3].toFloat(), p[4].toFloat(), p[5].toFloat(), p[6].toFloat()), p[7].toBoolean(), p[8].toLong())
    }.getOrNull()
}
