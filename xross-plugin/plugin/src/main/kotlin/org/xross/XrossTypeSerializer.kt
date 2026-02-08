package org.xross

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import org.xross.structures.XrossType

object XrossTypeSerializer : KSerializer<XrossType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("XrossType", PrimitiveKind.STRING)

    private val nameToPrimitive = mapOf(
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
        val jsonInput = decoder as? JsonDecoder ?: throw IllegalStateException("Only JSON is supported")
        return when (val element = jsonInput.decodeJsonElement()) {
            is JsonPrimitive -> {
                val name = element.content
                nameToPrimitive[name] ?: throw IllegalArgumentException("Unknown primitive type: $name")
            }

            is JsonObject -> {
                val typeKey = element.keys.firstOrNull()
                val body = element[typeKey]?.jsonObject ?: throw IllegalArgumentException("Invalid body")

                val signature = body["signature"]?.jsonPrimitive?.content ?: ""
                val ownershipStr = body["ownership"]?.jsonPrimitive?.content ?: "Owned"
                val ownership = try {
                    XrossType.Ownership.valueOf(ownershipStr)
                } catch (e: IllegalArgumentException) {
                    XrossType.Ownership.Owned
                }

                when (typeKey) {
                    "Object" -> XrossType.Object(signature, ownership)
                    else -> throw IllegalArgumentException("Unknown type: $typeKey")
                }
            }

            else -> throw IllegalArgumentException("Invalid JSON")
        }
    }

    override fun serialize(encoder: Encoder, value: XrossType) {
        val jsonOutput = encoder as? JsonEncoder ?: throw IllegalStateException("Only JSON is supported")
        val element = when (value) {
            is XrossType.Object -> buildJsonObject {
                putJsonObject("Object") {
                    put("signature", value.signature)
                    put("ownership", value.ownership.name)
                }
            }

            else -> {
                val name = nameToPrimitive.entries.find { it.value == value }?.key
                    ?: throw IllegalArgumentException("Unknown type instance: $value")
                JsonPrimitive(name)
            }
        }
        jsonOutput.encodeJsonElement(element)
    }
}
