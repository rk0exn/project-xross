package org.xross

import kotlinx.serialization.Serializable

/**
 * Rust側の所有権モデルに対応するメソッドの実行タイプ
 */

@Serializable
data class XrossMethod(
    val name: String,
    val symbol: String,
    val methodType: XrossMethodType, // 追加
    val isConstructor: Boolean,
    /** * List<XrossType> から List<XrossField> に変更
     * これにより引数名 (name) を保持できるようになります
     */
    val args: List<XrossField>,
    val ret: XrossType,
    val docs: List<String>
)