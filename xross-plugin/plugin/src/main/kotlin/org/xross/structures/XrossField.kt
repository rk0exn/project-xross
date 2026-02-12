package org.xross.structures

import kotlinx.serialization.Serializable

@Serializable
data class XrossField(
    val name: String,
    val ty: XrossType,
    val safety: XrossThreadSafety,
    val docs: List<String> = emptyList(),
)
