package org.xross.structures

import kotlinx.serialization.Serializable

@Serializable
data class XrossVariant(
    val name: String,
    val fields: List<XrossField> = emptyList(),
    val docs: List<String> = emptyList(),
)
