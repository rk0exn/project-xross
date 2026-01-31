package org.xross

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.lang.foreign.ValueLayout

@Serializable
sealed class XrossType {
    @Serializable @SerialName("void") object Void : XrossType()
    @Serializable @SerialName("bool") object Bool : XrossType()
    @Serializable @SerialName("i8") object I8 : XrossType()
    @Serializable @SerialName("i16") object I16 : XrossType()
    @Serializable @SerialName("i32") object I32 : XrossType()
    @Serializable @SerialName("i64") object I64 : XrossType()
    @Serializable @SerialName("u16") object U16 : XrossType() // Java Char
    @Serializable @SerialName("f32") object F32 : XrossType()
    @Serializable @SerialName("f64") object F64 : XrossType()
    @Serializable @SerialName("pointer") object Pointer : XrossType()
    @Serializable @SerialName("string") object StringType : XrossType()

    @Serializable @SerialName("slice")
    data class Slice(val elementType: XrossType) : XrossType()

    /**
     * Java FFM API の ValueLayout への変換
     */
    val layout: java.lang.foreign.MemoryLayout? get() = when (this) {
        Void -> null
        Bool -> ValueLayout.JAVA_BOOLEAN
        I8 -> ValueLayout.JAVA_BYTE
        I16 -> ValueLayout.JAVA_SHORT
        I32 -> ValueLayout.JAVA_INT
        I64 -> ValueLayout.JAVA_LONG
        U16 -> ValueLayout.JAVA_CHAR
        F32 -> ValueLayout.JAVA_FLOAT
        F64 -> ValueLayout.JAVA_DOUBLE
        Pointer, StringType, is Slice -> ValueLayout.ADDRESS
    }
}
