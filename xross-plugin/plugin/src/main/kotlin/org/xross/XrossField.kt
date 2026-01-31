package org.xross

import kotlinx.serialization.Serializable

@Serializable
data class XrossField(
    val name: String,
    val ty: XrossType,
    val docs: List<String> = emptyList()
)