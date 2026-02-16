package org.xross.structures

import kotlinx.serialization.Serializable

/**
 * Metadata for a method to be bridged from Rust to Kotlin.
 */
@Serializable
data class XrossMethod(
    val name: String,
    val symbol: String,
    val methodType: XrossMethodType = XrossMethodType.Static,
    val handleMode: HandleMode = HandleMode.Normal,
    val isConstructor: Boolean,
    val isDefault: Boolean = false,
    val isAsync: Boolean = false,
    val args: List<XrossField>,
    val ret: XrossType,
    val safety: XrossThreadSafety,
    val docs: List<String> = emptyList(),
)
