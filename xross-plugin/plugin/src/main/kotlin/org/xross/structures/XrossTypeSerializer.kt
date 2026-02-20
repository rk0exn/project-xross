package org.xross.structures

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

/**
 * Custom serializer for [XrossType] to handle its polymorphic nature and recursive structure in JSON.
 */
object XrossTypeSerializer : KSerializer<XrossType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("XrossType", PrimitiveKind.STRING)

    private val nameToPrimitive = mapOf(
        "Void" to XrossType.Void,
        "Bool" to XrossType.Bool,
        "I8" to XrossType.I8,
        "U8" to XrossType.U8,
        "I16" to XrossType.I16,
        "U16" to XrossType.U16,
        "I32" to XrossType.I32,
        "U32" to XrossType.U32,
        "I64" to XrossType.I64,
        "U64" to XrossType.U64,
        "ISize" to XrossType.ISize,
        "USize" to XrossType.USize,
        "F32" to XrossType.F32,
        "F64" to XrossType.F64,
        "Pointer" to XrossType.Pointer,
        "String" to XrossType.RustString,
    )

    override fun deserialize(decoder: Decoder): XrossType {
        val jsonInput = decoder as? JsonDecoder ?: throw IllegalStateException("Only JSON is supported")
        return when (val element = jsonInput.decodeJsonElement()) {
            is JsonPrimitive -> {
                val name = element.content
                nameToPrimitive[name] ?: throw IllegalArgumentException("Unknown primitive type: $name")
            }

            is JsonObject -> {
                val typeKey = element.keys.firstOrNull() ?: throw IllegalArgumentException("Empty object")
                val body = element[typeKey] ?: throw IllegalArgumentException("Missing body")

                when (typeKey) {
                    "Object" -> {
                        val obj = body.jsonObject
                        val signature = obj["signature"]?.jsonPrimitive?.content ?: ""
                        val ownershipStr = obj["ownership"]?.jsonPrimitive?.content ?: "Owned"
                        val ownership = XrossType.Ownership.valueOf(ownershipStr)
                        XrossType.Object(signature, ownership)
                    }
                    "Option" -> XrossType.Optional(deserializeRecursive(body))
                    "Slice" -> XrossType.Slice(deserializeRecursive(body))
                    "Vec" -> XrossType.Vec(deserializeRecursive(body))
                    "VecDeque" -> XrossType.VecDeque(deserializeRecursive(body))
                    "LinkedList" -> XrossType.LinkedList(deserializeRecursive(body))
                    "HashSet" -> XrossType.HashSet(deserializeRecursive(body))
                    "BTreeSet" -> XrossType.BTreeSet(deserializeRecursive(body))
                    "BinaryHeap" -> XrossType.BinaryHeap(deserializeRecursive(body))
                    "HashMap" -> {
                        val obj = body.jsonObject
                        XrossType.HashMap(
                            deserializeRecursive(obj["key"]!!),
                            deserializeRecursive(obj["value"]!!),
                        )
                    }
                    "BTreeMap" -> {
                        val obj = body.jsonObject
                        XrossType.BTreeMap(
                            deserializeRecursive(obj["key"]!!),
                            deserializeRecursive(obj["value"]!!),
                        )
                    }
                    "Result" -> {
                        val obj = body.jsonObject
                        XrossType.Result(
                            deserializeRecursive(obj["ok"]!!),
                            deserializeRecursive(obj["err"]!!),
                        )
                    }
                    "Async" -> XrossType.Async(deserializeRecursive(body))
                    else -> throw IllegalArgumentException("Unknown complex type: $typeKey")
                }
            }

            else -> throw IllegalArgumentException("Invalid JSON")
        }
    }

    private fun deserializeRecursive(element: JsonElement): XrossType = Json.decodeFromJsonElement(this, element)

    override fun serialize(encoder: Encoder, value: XrossType) {
        val jsonOutput = encoder as? JsonEncoder ?: throw IllegalStateException("Only JSON is supported")
        val element = when (value) {
            is XrossType.Object -> buildJsonObject {
                putJsonObject("Object") {
                    put("signature", value.signature)
                    put("ownership", value.ownership.name)
                }
            }
            is XrossType.Optional -> buildJsonObject { put("Option", serializeRecursive(value.inner)) }
            is XrossType.Slice -> buildJsonObject { put("Slice", serializeRecursive(value.inner)) }
            is XrossType.Vec -> buildJsonObject { put("Vec", serializeRecursive(value.inner)) }
            is XrossType.VecDeque -> buildJsonObject { put("VecDeque", serializeRecursive(value.inner)) }
            is XrossType.LinkedList -> buildJsonObject { put("LinkedList", serializeRecursive(value.inner)) }
            is XrossType.HashSet -> buildJsonObject { put("HashSet", serializeRecursive(value.inner)) }
            is XrossType.BTreeSet -> buildJsonObject { put("BTreeSet", serializeRecursive(value.inner)) }
            is XrossType.BinaryHeap -> buildJsonObject { put("BinaryHeap", serializeRecursive(value.inner)) }
            is XrossType.HashMap -> buildJsonObject {
                putJsonObject("HashMap") {
                    put("key", serializeRecursive(value.key))
                    put("value", serializeRecursive(value.value))
                }
            }
            is XrossType.BTreeMap -> buildJsonObject {
                putJsonObject("BTreeMap") {
                    put("key", serializeRecursive(value.key))
                    put("value", serializeRecursive(value.value))
                }
            }
            is XrossType.Result -> buildJsonObject {
                putJsonObject("Result") {
                    put("ok", serializeRecursive(value.ok))
                    put("err", serializeRecursive(value.err))
                }
            }
            is XrossType.Async -> buildJsonObject { put("Async", serializeRecursive(value.inner)) }
            else -> {
                val name = nameToPrimitive.entries.find { it.value == value }?.key
                    ?: throw IllegalArgumentException("Unknown type instance: $value")
                JsonPrimitive(name)
            }
        }
        jsonOutput.encodeJsonElement(element)
    }

    private fun serializeRecursive(type: XrossType): JsonElement = Json.encodeToJsonElement(this, type)
}
