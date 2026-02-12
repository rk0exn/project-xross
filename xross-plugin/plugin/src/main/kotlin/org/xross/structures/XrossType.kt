package org.xross.structures

import com.squareup.kotlinpoet.*
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
    object ISize : XrossType()
    object U16 : XrossType()
    object USize : XrossType()
    object F32 : XrossType()
    object F64 : XrossType()
    object Pointer : XrossType()
    object RustString : XrossType()

    enum class Ownership { Owned, Boxed, Ref, MutRef }

    data class Object(val signature: String, val ownership: Ownership = Ownership.Owned) : XrossType()
    data class Optional(val inner: XrossType) : XrossType()
    data class Result(val ok: XrossType, val err: XrossType) : XrossType()
    data class Async(val inner: XrossType) : XrossType()

    val kotlinType: TypeName
        get() = when (this) {
            I32 -> INT
            I64 -> LONG
            ISize, USize -> if (java.lang.foreign.ValueLayout.ADDRESS.byteSize() <= 4L) INT else LONG
            F32 -> FLOAT
            F64 -> DOUBLE
            Bool -> BOOLEAN
            I8 -> BYTE
            I16 -> SHORT
            U16 -> CHAR
            Void -> UNIT
            RustString -> String::class.asTypeName()
            is Optional -> inner.kotlinType.copy(nullable = true)
            is Result -> ok.kotlinType
            is Async -> inner.kotlinType
            Pointer, is Object -> ClassName("java.lang.foreign", "MemorySegment")
        }

    val layoutMember: MemberName
        get() = when (this) {
            I32 -> ValueLayouts.JAVA_INT
            I64 -> ValueLayouts.JAVA_LONG
            ISize, USize -> if (java.lang.foreign.ValueLayout.ADDRESS.byteSize() <= 4L) ValueLayouts.JAVA_INT else ValueLayouts.JAVA_LONG
            F32 -> ValueLayouts.JAVA_FLOAT
            F64 -> ValueLayouts.JAVA_DOUBLE
            Bool -> ValueLayouts.JAVA_BYTE
            I8 -> ValueLayouts.JAVA_BYTE
            I16 -> ValueLayouts.JAVA_SHORT
            U16 -> ValueLayouts.JAVA_CHAR
            Void -> throw IllegalStateException("Void has no layout")
            else -> ValueLayouts.ADDRESS
        }

    private object ValueLayouts {
        private val VAL_LAYOUT = ClassName("java.lang.foreign", "ValueLayout")
        val JAVA_INT = MemberName(VAL_LAYOUT, "JAVA_INT")
        val JAVA_LONG = MemberName(VAL_LAYOUT, "JAVA_LONG")
        val JAVA_FLOAT = MemberName(VAL_LAYOUT, "JAVA_FLOAT")
        val JAVA_DOUBLE = MemberName(VAL_LAYOUT, "JAVA_DOUBLE")
        val JAVA_BYTE = MemberName(VAL_LAYOUT, "JAVA_BYTE")
        val JAVA_SHORT = MemberName(VAL_LAYOUT, "JAVA_SHORT")
        val JAVA_CHAR = MemberName(VAL_LAYOUT, "JAVA_CHAR")
        val ADDRESS = MemberName(VAL_LAYOUT, "ADDRESS")
    }

    val isOwned: Boolean
        get() = when (this) {
            is Object -> ownership == Ownership.Owned || ownership == Ownership.Boxed
            is Result, is Async -> true
            else -> false
        }
}
