package org.xross

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import org.xross.structures.XrossType

object XrossTypeSerializer : JsonContentPolymorphicSerializer<XrossType>(XrossType::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<XrossType> {
        return when (element) {
            // 文字列の場合 ("I32", "Void" など)
            is JsonPrimitive -> XrossPrimitiveSerializer
            // オブジェクトの場合 ({"Struct": {...}} や {"Slice": {...}})
            is JsonObject -> {
                when {
                    element.containsKey("Struct") -> object : KSerializer<XrossType.Struct> {
                        override val descriptor = XrossType.Struct.serializer().descriptor
                        override fun deserialize(decoder: Decoder): XrossType.Struct {
                            // Structの中身（JsonObject）を取り出してデコード
                            val input = decoder as JsonDecoder
                            val structData = input.decodeJsonElement().jsonObject["Struct"]!!
                            return input.json.decodeFromJsonElement(XrossType.Struct.serializer(), structData)
                        }
                        override fun serialize(encoder: Encoder, value: XrossType.Struct) {
                            /* 必要なら実装 */
                        }
                    }
                    element.containsKey("Slice") -> XrossType.Slice.serializer()
                    else -> throw IllegalArgumentException("Unknown type: $element")
                }
            }

            else -> throw IllegalArgumentException("Invalid JSON element for XrossType: $element")
        }
    }
}

/**
 * プリミティブな型（引数なしの object）を処理するシリアライザ
 */
private object XrossPrimitiveSerializer : KSerializer<XrossType> {
    override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor(
        "XrossPrimitive",
        kotlinx.serialization.descriptors.PrimitiveKind.STRING
    )
    private val nameToType = mapOf(
        "Void" to XrossType.Void,
        "Bool" to XrossType.Bool,
        "I8" to XrossType.I8,
        "I16" to XrossType.I16,
        "I32" to XrossType.I32,
        "I64" to XrossType.I64,
        "U16" to XrossType.U16,
        "F32" to XrossType.F32,
        "F64" to XrossType.F64,
        "Pointer" to XrossType.Pointer,
        "String" to XrossType.RustString
    )

    override fun deserialize(decoder: Decoder): XrossType {
        val name = decoder.decodeString()
        return nameToType[name] ?: throw IllegalArgumentException("Unknown XrossType: $name")
    }

    override fun serialize(encoder: Encoder, value: XrossType) {
        val name = nameToType.entries.find { it.value == value }?.key
            ?: throw IllegalStateException("Cannot serialize non-primitive XrossType as string: $value")
        encoder.encodeString(name)
    }
}
