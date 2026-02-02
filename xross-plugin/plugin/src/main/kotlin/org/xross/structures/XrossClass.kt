package org.xross.structures

import kotlinx.serialization.Serializable

@Serializable
data class XrossClass(
    val packageName: String,
    val structName: String,
    val symbolPrefix: String,
    val docs: List<String> = emptyList(),
    val fields: List<XrossField> = emptyList(),
    val methods: List<XrossMethod>
)