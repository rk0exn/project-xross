package org.xross.structures

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed class XrossDefinition {

    abstract val signature: String
    abstract val symbolPrefix: String
    abstract val packageName: String
    abstract val name: String
    abstract val methods: List<XrossMethod>
    abstract val docs: List<String>
    abstract val isCopy: Boolean

    @Serializable
    @SerialName("struct")
    data class Struct(
        override val signature: String,
        override val symbolPrefix: String,
        override val packageName: String,
        override val name: String,
        val fields: List<XrossField> = emptyList(),
        override val methods: List<XrossMethod> = emptyList(),
        override val docs: List<String> = emptyList(),
        override val isCopy: Boolean = false
    ) : XrossDefinition()

    @Serializable
    @SerialName("enum")
    data class Enum(
        override val signature: String,
        override val symbolPrefix: String,
        override val packageName: String,
        override val name: String,
        val variants: List<XrossVariant> = emptyList(),
        override val methods: List<XrossMethod> = emptyList(),
        override val docs: List<String> = emptyList(),
        override val isCopy: Boolean = false
    ) : XrossDefinition()

    @Serializable
    @SerialName("opaque")
    data class Opaque(
        override val signature: String,
        override val symbolPrefix: String,
        override val packageName: String,
        override val name: String,
        // 中身は公開されないため、メソッドやドキュメントは最小限（あるいは無し）
        override val methods: List<XrossMethod> = emptyList(),
        override val docs: List<String> = emptyList(),
        val isClonable: Boolean = true,
        override val isCopy: Boolean = false
    ) : XrossDefinition()
}
