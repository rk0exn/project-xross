package org.xross.structures

import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.BYTE
import com.squareup.kotlinpoet.CHAR
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.DOUBLE
import com.squareup.kotlinpoet.FLOAT
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.SHORT
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.UNIT
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.xross.XrossTypeSerializer

@Serializable(with = XrossTypeSerializer::class)
sealed class XrossType {
    object Void : XrossType()
    object Bool : XrossType()
    object I8 : XrossType()
    object I16 : XrossType()
    object I32 : XrossType()
    object I64 : XrossType()
    object U16 : XrossType() // Java Char
    object F32 : XrossType()
    object F64 : XrossType()
    object Pointer : XrossType()
    object RustString : XrossType() // Rust String / &str

    @Serializable
    @SerialName("Struct")
    data class Struct(
        val name: String,
        val symbolPrefix: String,
        val isReference: Boolean
    ) : XrossType()

    @Serializable
    @SerialName("Slice")
    data class Slice(
        val elementType: XrossType,
        val isReference: Boolean
    ) : XrossType()

    val isNumber: Boolean
        get() = when (this) {
            I8, I16, I32, I64, U16, F32, F64 -> true
            else -> false
        }

    val isInteger: Boolean
        get() = when (this) {
            I8, I16, I32, I64, U16 -> true
            else -> false
        }
    /** KotlinPoet 用の型取得 */
    val kotlinType: TypeName
        get() = when (this) {
            I32 -> INT
            I64 -> LONG
            F32 -> FLOAT
            F64 -> DOUBLE
            Bool -> BOOLEAN
            I8 -> BYTE
            I16 -> SHORT
            U16 -> CHAR
            Void -> UNIT
            Pointer, RustString -> ClassName("java.lang.foreign", "MemorySegment")
            is Struct -> ClassName("", name) // パッケージ名は生成時に解決
            is Slice -> ClassName("java.lang.foreign", "MemorySegment") // ポインタ+長さ
        }

    /** FFM API (ValueLayout) へのマッピング */
    val layoutMember: MemberName
        get() = when (this) {
            I32 -> ValueLayouts.JAVA_INT
            I64 -> ValueLayouts.JAVA_LONG
            F32 -> ValueLayouts.JAVA_FLOAT
            F64 -> ValueLayouts.JAVA_DOUBLE
            Bool -> ValueLayouts.JAVA_BOOLEAN
            I8 -> ValueLayouts.JAVA_BYTE
            I16 -> ValueLayouts.JAVA_SHORT
            U16 -> ValueLayouts.JAVA_CHAR
            Void -> throw IllegalStateException("Void has no layout")
            Pointer, RustString, is Struct, is Slice -> ValueLayouts.ADDRESS
        }

    private object ValueLayouts {
        private const val PKG = "java.lang.foreign.ValueLayout"
        val JAVA_INT = MemberName(PKG, "JAVA_INT")
        val JAVA_LONG = MemberName(PKG, "JAVA_LONG")
        val JAVA_FLOAT = MemberName(PKG, "JAVA_FLOAT")
        val JAVA_DOUBLE = MemberName(PKG, "JAVA_DOUBLE")
        val JAVA_BOOLEAN = MemberName(PKG, "JAVA_BOOLEAN")
        val JAVA_BYTE = MemberName(PKG, "JAVA_BYTE")
        val JAVA_SHORT = MemberName(PKG, "JAVA_SHORT")
        val JAVA_CHAR = MemberName(PKG, "JAVA_CHAR")
        val ADDRESS = MemberName(PKG, "ADDRESS")
    }
}