package org.xross

import kotlinx.serialization.Serializable

@Serializable
data class XrossField(
    val name: String,
    val ty: XrossType,
    val offset: Long,      // フィールドの開始オフセット
    val size: Long,        // フィールド自体のサイズ
    val align: Long,       // フィールドのアライメント
    val docs: List<String> = emptyList()
)