package org.xross.structures

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import kotlinx.serialization.Serializable
import org.xross.generator.util.FFMConstants

/**
 * Represents the data types supported by Xross in Kotlin.
 */
@Serializable(with = XrossTypeSerializer::class)
sealed class XrossType {
    object Void : XrossType()
    object Bool : XrossType()
    object I8 : XrossType()
    object U8 : XrossType()
    object I16 : XrossType()
    object U16 : XrossType()
    object I32 : XrossType()
    object U32 : XrossType()
    object I64 : XrossType()
    object U64 : XrossType()
    object ISize : XrossType()
    object USize : XrossType()
    object F32 : XrossType()
    object F64 : XrossType()
    object Pointer : XrossType()
    object RustString : XrossType()

    /**
     * A slice of values (&[*]).
     */
    data class Slice(val inner: XrossType) : XrossType()

    /**
     * An owned vector of values (Vec<T>).
     */
    data class Vec(val inner: XrossType) : XrossType()
    data class VecDeque(val inner: XrossType) : XrossType()
    data class LinkedList(val inner: XrossType) : XrossType()
    data class HashSet(val inner: XrossType) : XrossType()
    data class BTreeSet(val inner: XrossType) : XrossType()
    data class BinaryHeap(val inner: XrossType) : XrossType()
    data class HashMap(val key: XrossType, val value: XrossType) : XrossType()
    data class BTreeMap(val key: XrossType, val value: XrossType) : XrossType()

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
            I32, U32 -> INT
            I64, U64 -> LONG
            ISize, USize -> if (java.lang.foreign.ValueLayout.ADDRESS.byteSize() <= 4L) INT else LONG
            F32 -> FLOAT
            F64 -> DOUBLE
            Bool -> BOOLEAN
            I8, U8 -> BYTE
            I16 -> SHORT
            U16 -> CHAR
            Void -> UNIT
            RustString -> String::class.asTypeName()
            is Slice -> when (inner) {
                I32 -> IntArray::class.asTypeName()
                I64 -> LongArray::class.asTypeName()
                F32 -> FloatArray::class.asTypeName()
                F64 -> DoubleArray::class.asTypeName()
                I8 -> ByteArray::class.asTypeName()
                I16 -> ShortArray::class.asTypeName()
                Bool -> BooleanArray::class.asTypeName()
                else -> List::class.asClassName().parameterizedBy(inner.kotlinType)
            }
            is Vec -> when (inner) {
                I32 -> IntArray::class.asTypeName()
                I64 -> LongArray::class.asTypeName()
                F32 -> FloatArray::class.asTypeName()
                F64 -> DoubleArray::class.asTypeName()
                I8 -> ByteArray::class.asTypeName()
                I16 -> ShortArray::class.asTypeName()
                Bool -> BooleanArray::class.asTypeName()
                else -> List::class.asClassName().parameterizedBy(inner.kotlinType)
            }
            is VecDeque -> ClassName("kotlin.collections", "ArrayDeque").parameterizedBy(inner.kotlinType)
            is LinkedList -> List::class.asClassName().parameterizedBy(inner.kotlinType)
            is HashSet -> Set::class.asClassName().parameterizedBy(inner.kotlinType)
            is BTreeSet -> Set::class.asClassName().parameterizedBy(inner.kotlinType)
            is BinaryHeap -> List::class.asClassName().parameterizedBy(inner.kotlinType)
            is HashMap -> Map::class.asClassName().parameterizedBy(key.kotlinType, value.kotlinType)
            is BTreeMap -> Map::class.asClassName().parameterizedBy(key.kotlinType, value.kotlinType)
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
            I32, U32 -> FFMConstants.JAVA_INT
            I64, U64 -> FFMConstants.JAVA_LONG
            ISize, USize -> if (java.lang.foreign.ValueLayout.ADDRESS.byteSize() <= 4L) FFMConstants.JAVA_INT else FFMConstants.JAVA_LONG
            F32 -> FFMConstants.JAVA_FLOAT
            F64 -> FFMConstants.JAVA_DOUBLE
            Bool -> FFMConstants.JAVA_BYTE
            I8, U8 -> FFMConstants.JAVA_BYTE
            I16 -> FFMConstants.JAVA_SHORT
            U16 -> FFMConstants.JAVA_CHAR
            Void -> throw IllegalStateException("Void has no layout")
            is Slice,
            is Vec,
            is VecDeque,
            is LinkedList,
            is HashSet,
            is BTreeSet,
            is BinaryHeap,
            is HashMap,
            is BTreeMap
            -> FFMConstants.ADDRESS
            else -> FFMConstants.ADDRESS
        }

    val layoutCode: CodeBlock
        get() = when (this) {
            is Result -> FFMConstants.XROSS_RESULT_LAYOUT_CODE
            is RustString -> FFMConstants.XROSS_STRING_LAYOUT_CODE
            is Async -> FFMConstants.XROSS_TASK_LAYOUT_CODE
            else -> CodeBlock.of("%M", layoutMember)
        }

    /**
     * Returns true if this type represents an owned value.
     */
    val isOwned: Boolean
        get() = when (this) {
            is Object -> ownership == Ownership.Owned || ownership == Ownership.Boxed
            is Result,
            is Async,
            is Vec,
            is VecDeque,
            is LinkedList,
            is HashSet,
            is BTreeSet,
            is BinaryHeap,
            is HashMap,
            is BTreeMap
            -> true
            else -> false
        }

    val isComplex: Boolean
        get() =
            this is Object ||
                this is Optional ||
                this is Result ||
                this is RustString ||
                this is Async ||
                this is Slice ||
                this is Vec ||
                this is VecDeque ||
                this is LinkedList ||
                this is HashSet ||
                this is BTreeSet ||
                this is BinaryHeap ||
                this is HashMap ||
                this is BTreeMap
    val isPrimitive: Boolean get() = !isComplex

    /**
     * Returns the size in bytes for the primitive type.
     */
    val kotlinSize
        get() = when (this) {
            is I32, is U32, is F32 -> 4L
            is I64, is U64, is F64, is Pointer, is RustString -> 8L
            is ISize, is USize -> if (java.lang.foreign.ValueLayout.ADDRESS.byteSize() <= 4L) 4L else 8L
            is Result -> 16L
            is Async -> 24L
            is Slice, is Vec -> 16L
            is VecDeque, is LinkedList, is HashSet, is BTreeSet, is BinaryHeap, is HashMap, is BTreeMap -> 8L
            is Object -> 8L
            is Bool, is I8, is U8 -> 1L
            is I16, is U16 -> 2L
            is Void -> 0L
            else -> 8L
        }
}
