package org.xross

import kotlinx.serialization.Serializable

@Serializable
data class XrossClass(
    val packageName: String,
    val structName: String,
    val size: Long,        // 構造体全体のサイズ (sizeof)
    val align: Long,       // 構造体のアライメント (alignof)
    val docs: List<String> = emptyList(),
    val fields: List<XrossField> = emptyList(),
    val methods: List<XrossMethod>
)
