package org.xross.structures

import com.squareup.kotlinpoet.*
import kotlinx.serialization.Serializable
import org.xross.XrossTypeSerializer

/**
 * Represents the data types supported by Xross in Kotlin.
 */
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

    /**
     * Ownership model for bridged types.
     */
    enum class Ownership { Owned, Boxed, Ref, MutRef }

    /**
     * A user-defined object type.
     */
    data class Object(val signature: String, val ownership: Ownership = Ownership.Owned) : XrossType()

    /**
     * An optional type.
     */
    data class Optional(val inner: XrossType) : XrossType()

    /**
     * A result type.
     */
    data class Result(val ok: XrossType, val err: XrossType) : XrossType()

    /**
     * An asynchronous type.
     */
    data class Async(val inner: XrossType) : XrossType()

    /**
     * Returns the KotlinPoet [TypeName] for this type.
     */
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

    /**
     * Returns the [MemberName] for the Java FFM ValueLayout of this type.
     */
    val layoutMember: MemberName
        get() = when (this) {
            I32 -> org.xross.generator.FFMConstants.JAVA_INT
            I64 -> org.xross.generator.FFMConstants.JAVA_LONG
            ISize, USize -> if (java.lang.foreign.ValueLayout.ADDRESS.byteSize() <= 4L) org.xross.generator.FFMConstants.JAVA_INT else org.xross.generator.FFMConstants.JAVA_LONG
            F32 -> org.xross.generator.FFMConstants.JAVA_FLOAT
            F64 -> org.xross.generator.FFMConstants.JAVA_DOUBLE
            Bool -> org.xross.generator.FFMConstants.JAVA_BYTE
            I8 -> org.xross.generator.FFMConstants.JAVA_BYTE
            I16 -> org.xross.generator.FFMConstants.JAVA_SHORT
            U16 -> org.xross.generator.FFMConstants.JAVA_CHAR
            Void -> throw IllegalStateException("Void has no layout")
            else -> org.xross.generator.FFMConstants.ADDRESS
        }

    /**
     * Returns true if this type represents an owned value.
     */
    val isOwned: Boolean
        get() = when (this) {
            is Object -> ownership == Ownership.Owned || ownership == Ownership.Boxed
            is Result, is Async -> true
            else -> false
        }

    /**
     * Returns the size in bytes for the primitive type.
     */
    val kotlinSize
        get() = when (this) {
            is I32, is F32 -> 4L
            is I64, is F64, is Pointer, is RustString -> 8L
            is Bool, is I8 -> 1L
            is I16, is U16 -> 2L
            else -> 8L
        }
}
