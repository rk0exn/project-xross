package org.xross

import com.squareup.kotlinpoet.*
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
    @Serializable @SerialName("u16") object U16 : XrossType()
    @Serializable @SerialName("f32") object F32 : XrossType()
    @Serializable @SerialName("f64") object F64 : XrossType()
    @Serializable @SerialName("pointer") object Pointer : XrossType()
    @Serializable @SerialName("string") object StringType : XrossType()

    @Serializable @SerialName("slice")
    data class Slice(val elementType: XrossType) : XrossType()

    /** KotlinPoet 用の型取得 */
    val kotlinType: TypeName get() = when (this) {
        I32 -> INT
        I64 -> LONG
        F32 -> FLOAT
        F64 -> DOUBLE
        Bool -> BOOLEAN
        I8 -> BYTE
        I16 -> SHORT
        U16 -> CHAR
        Void -> UNIT
        Pointer, StringType, is Slice -> ClassName("java.lang.foreign", "MemorySegment")
    }

    /** FFM API (ValueLayout) へのマッピング */
    val layoutMember: MemberName get() = MemberName("java.lang.foreign.ValueLayout", when (this) {
        I32 -> "JAVA_INT"
        I64 -> "JAVA_LONG"
        F32 -> "JAVA_FLOAT"
        F64 -> "JAVA_DOUBLE"
        Bool -> "JAVA_BOOLEAN"
        I8 -> "JAVA_BYTE"
        I16 -> "JAVA_SHORT"
        U16 -> "JAVA_CHAR"
        Pointer, StringType, is Slice, Void -> "ADDRESS"
    })
}
