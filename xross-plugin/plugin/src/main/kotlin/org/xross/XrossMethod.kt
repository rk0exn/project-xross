package org.xross

import kotlinx.serialization.Serializable

@Serializable
data class XrossMethod(
    val name: String,
    val symbol: String,
    val isConstructor: Boolean,
    val args: List<XrossType>,
    val ret: XrossType,
    val docs: List<String>
)
