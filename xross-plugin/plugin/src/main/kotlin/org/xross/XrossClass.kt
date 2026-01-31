package org.xross

import kotlinx.serialization.Serializable


@Serializable
data class XrossClass(
    val packageName: String, // JSONのキー名に合わせる
    val structName: String,
    val methods: List<XrossMethod>
)
