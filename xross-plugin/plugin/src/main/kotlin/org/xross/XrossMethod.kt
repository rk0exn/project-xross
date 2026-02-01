package org.xross

import kotlinx.serialization.Serializable

/**
 * Rust側の所有権モデルに対応するメソッドの実行タイプ
 */

@Serializable
data class XrossMethod(
    val name: String,
    val symbol: String,
    val methodType: XrossMethodType = XrossMethodType.Static,
    val isConstructor: Boolean,
    val args: List<XrossField>,
    val ret: XrossType,
    val docs: List<String> = emptyList()
)