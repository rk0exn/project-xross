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
    object U16 : XrossType()
    object F32 : XrossType()
    object F64 : XrossType()
    object Pointer : XrossType()
    object RustString : XrossType() // Rust String

    enum class Ownership { Owned, Boxed, Ref, MutRef }

    data class Object(val signature: String, val ownership: Ownership = Ownership.Owned) : XrossType()

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
            Pointer, RustString, is Object ->
                ClassName("java.lang.foreign", "MemorySegment")
        }

    /** FFM API (ValueLayout) へのマッピング */
    val layoutMember: MemberName
        get() = when (this) {
            I32 -> ValueLayouts.JAVA_INT
            I64 -> ValueLayouts.JAVA_LONG
            F32 -> ValueLayouts.JAVA_FLOAT
            F64 -> ValueLayouts.JAVA_DOUBLE
            Bool -> ValueLayouts.JAVA_BYTE // FFMにBooleanLayoutはないためByteで代用
            I8 -> ValueLayouts.JAVA_BYTE
            I16 -> ValueLayouts.JAVA_SHORT
            U16 -> ValueLayouts.JAVA_CHAR
            Void -> throw IllegalStateException("Void has no layout")
            Pointer, RustString, is Object -> ValueLayouts.ADDRESS
        }

    private object ValueLayouts {
        private const val PKG = "java.lang.foreign.ValueLayout"
        val JAVA_INT:MemberName = MemberName(PKG, "JAVA_INT")
        val JAVA_LONG = MemberName(PKG, "JAVA_LONG")
        val JAVA_FLOAT = MemberName(PKG, "JAVA_FLOAT")
        val JAVA_DOUBLE = MemberName(PKG, "JAVA_DOUBLE")
        val JAVA_BYTE = MemberName(PKG, "JAVA_BYTE")
        val JAVA_SHORT = MemberName(PKG, "JAVA_SHORT")
        val JAVA_CHAR = MemberName(PKG, "JAVA_CHAR")
        val ADDRESS = MemberName(PKG, "ADDRESS")
    }

    val isOwned: Boolean
        get() = when (this) {
            is Object -> ownership == Ownership.Owned || ownership == Ownership.Boxed
            else -> false
        }
    val isCopy: Boolean
        get() = when (this) {
            I8, I16, I32, I64, U16, F32, F64, Bool -> true
            // RustString はポインタ(MemorySegment)からKotlin Stringへ
            // 変換（コピー）して取り出すため true
            // ※取り出し後にRust側のバッファを管理する必要がないため
            RustString -> true
            // ポインタそのものはコピーしてやり取りする
            Pointer -> isOwned
            // 構造体、Enum、不透明オブジェクトは参照（MemorySegment）として
            // 扱うためコピーではない
            is Object -> false
            // Voidはデータがない
            Void -> true
        }
}
