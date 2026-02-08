package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.helper.StringHelper.escapeKotlinKeyword
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.XrossDefinition
import org.xross.structures.XrossField
import org.xross.structures.XrossThreadSafety
import org.xross.structures.XrossType
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment

object PropertyGenerator {
    private fun resolveFqn(type: XrossType, meta: XrossDefinition, targetPackage: String): String {
        val signature = when (type) {
            is XrossType.Object -> type.signature
            else -> return (type.kotlinType as ClassName).canonicalName
        }
        return listOf(targetPackage, meta.packageName, signature)
            .filter { it.isNotEmpty() }
            .joinToString(".")
    }

    fun generateFields(classBuilder: TypeSpec.Builder, meta: XrossDefinition.Struct, targetPackage: String) {
        meta.fields.forEach { field ->
            val baseName = field.name.toCamelCase()
            val escapedName = baseName.escapeKotlinKeyword()
            val vhName = "VH_$baseName"
            val fqn = resolveFqn(field.ty, meta, targetPackage)

            // --- 修正箇所: バッキングフィールドの追加 (Object型の場合のみ) ---
            var backingFieldName: String? = null
            if (field.ty is XrossType.Object) {
                backingFieldName = "_$baseName"
                val backingProp = PropertySpec.builder(backingFieldName, TypeVariableName("$fqn?"))
                    .mutable(true)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("null")
                    .build()
                classBuilder.addProperty(backingProp)
            }

            if (field.safety == XrossThreadSafety.Atomic) {
                generateAtomicProperty(classBuilder, baseName, escapedName, vhName, fqn)
            } else {
                val kType = TypeVariableName(" $fqn")
                val isMutable = field.safety != XrossThreadSafety.Immutable

                val propBuilder = PropertySpec.builder(escapedName, kType)
                    .mutable(isMutable)
                    .getter(buildGetter(field, vhName, fqn, backingFieldName)) // 引数追加

                if (isMutable) propBuilder.setter(buildSetter(field, vhName, fqn, backingFieldName))
                classBuilder.addProperty(propBuilder.build())
            }
        }
    }
    private fun buildGetter(field: XrossField, vhName: String, fqn: String, backingFieldName: String?): FunSpec {
        val readCodeBuilder = CodeBlock.builder()
        readCodeBuilder.addStatement("if (this.segment == %T.NULL || !this.aliveFlag.isValid) throw %T(%S)", MemorySegment::class, NullPointerException::class, "Access error")

        when (field.ty) {
            is XrossType.Object -> {
                // キャッシュチェック
                readCodeBuilder.beginControlFlow("if (this.$backingFieldName != null && this.$backingFieldName!!.aliveFlag.isValid)")
                readCodeBuilder.addStatement("res = this.$backingFieldName!!")
                readCodeBuilder.nextControlFlow("else")

                if (field.ty.ownership == XrossType.Ownership.Owned) {
                    // インライン構造体
                    readCodeBuilder.addStatement("val offset = Companion.LAYOUT.byteOffset(%T.PathElement.groupElement(%S))", MemoryLayout::class, field.name)
                    readCodeBuilder.addStatement("val resSeg = this.segment.asSlice(offset, %L.Companion.STRUCT_SIZE)", fqn)
                    readCodeBuilder.addStatement("res = %L(resSeg, arena = this.arena, isArenaOwner = false)", fqn)
                } else {
                    // Boxed または Ref (両方とも親のアリーナを利用し、ドロップしない設定に統合)
                    readCodeBuilder.addStatement("val rawSegment = Companion.$vhName.get(this.segment, 0L) as %T", MemorySegment::class)
                    readCodeBuilder.addStatement("val resSeg = if (rawSegment == %T.NULL) %T.NULL else rawSegment.reinterpret(%L.Companion.STRUCT_SIZE)", MemorySegment::class, MemorySegment::class, fqn)
                    readCodeBuilder.addStatement("res = %L(resSeg, arena = this.arena, isArenaOwner = false)", fqn)
                }
                // キャッシュに保存
                readCodeBuilder.addStatement("this.$backingFieldName = res")
                readCodeBuilder.endControlFlow()
            }
            is XrossType.Bool -> readCodeBuilder.addStatement("res = (Companion.$vhName.get(this.segment, 0L) as Byte) != (0).toByte()")
            is XrossType.RustString -> {
                readCodeBuilder.addStatement(
                    "val rawSegment = Companion.$vhName.get(this.segment, 0L) as %T",
                    MemorySegment::class
                )
                readCodeBuilder.addStatement(
                    "res = if (rawSegment == %T.NULL) \"\" else rawSegment.reinterpret(%T.MAX_VALUE).getString(0)",
                    MemorySegment::class,
                    Long::class
                )
            }

            else -> readCodeBuilder.addStatement("res = Companion.$vhName.get(this.segment, 0L) as %L", fqn)
        }

        return FunSpec.getterBuilder().addCode(
            """
            var stamp = this.sl.tryOptimisticRead()
            var res: %T
            // Optimistic read
            %L
            if (!this.sl.validate(stamp)) {
                stamp = this.sl.readLock()
                try { 
                    // Pessimistic read
                    %L
                } finally { this.sl.unlockRead(stamp) }
            }
            return res
        """.trimIndent(), TypeVariableName(" $fqn"), readCodeBuilder.build(), readCodeBuilder.build()
        ).build()
    }
    private fun buildSetter(field: XrossField, vhName: String, fqn: String, backingFieldName: String?): FunSpec {
        val writeCodeBuilder = CodeBlock.builder()
        writeCodeBuilder.addStatement(
            "if (this.segment == %T.NULL || !this.aliveFlag.isValid) throw %T(%S)",
            MemorySegment::class,
            NullPointerException::class,
            "Attempted to set field '${field.name}' on a NULL or invalid native object"
        )

        when (field.ty) {
            is XrossType.Bool -> writeCodeBuilder.addStatement("Companion.$vhName.set(this.segment, 0L, if (v) 1.toByte() else 0.toByte())")
            is XrossType.Object -> {
                writeCodeBuilder.addStatement(
                    "if (v.segment == %T.NULL || !v.aliveFlag.isValid) throw %T(%S)",
                    MemorySegment::class,
                    NullPointerException::class,
                    "Cannot set field '${field.name}' with a NULL or invalid native reference"
                )
                // ネイティブポインタを更新
                writeCodeBuilder.addStatement("Companion.$vhName.set(this.segment, 0L, v.segment)")

                // --- 修正点: キャッシュをクリア ---
                if (backingFieldName != null) {
                    writeCodeBuilder.addStatement("this.$backingFieldName = null")
                }
            }

            else -> writeCodeBuilder.addStatement("Companion.$vhName.set(this.segment, 0L, v)")
        }

        return FunSpec.setterBuilder().addParameter("v", TypeVariableName(" $fqn")).addCode(
            """
        val stamp = this.sl.writeLock()
        try { %L } finally { this.sl.unlockWrite(stamp) }
    """.trimIndent(), writeCodeBuilder.build()
        ).build()
    }
    private fun generateAtomicProperty(
        classBuilder: TypeSpec.Builder,
        baseName: String,
        escapedName: String,
        vhName: String,
        fqn: String
    ) {
        val kType = TypeVariableName(" $fqn")
        val innerClassName = "AtomicFieldOf${baseName.replaceFirstChar { it.uppercase() }}"
        val innerClass = TypeSpec.classBuilder(innerClassName).addModifiers(KModifier.INNER)
            .addProperty(
                PropertySpec.builder("value", kType)
                    .getter(
                        FunSpec.getterBuilder()
                            .addStatement("return Companion.$vhName.getVolatile(this@${className(classBuilder)}.segment, 0L) as $fqn")
                            .build()
                    ).build()
            )
            .addFunction(
                FunSpec.builder("update")
                    .addParameter("block", LambdaTypeName.get(null, kType, returnType = kType)).returns(kType)
                    .beginControlFlow("while (true)")
                    .beginControlFlow("try")
                    .addStatement("val current = value")
                    .addStatement("val next = block(current)")
                    .beginControlFlow("if (Companion.$vhName.compareAndSet(this@${className(classBuilder)}.segment, 0L, current, next))")
                    .addStatement("return next")
                    .endControlFlow()
                    .nextControlFlow("catch (e: %T)", Throwable::class)
                    .addStatement("throw e")
                    .endControlFlow()
                    .endControlFlow().build()
            )
            .build()
        classBuilder.addType(innerClass)
        classBuilder.addProperty(
            PropertySpec.builder(escapedName, ClassName("", innerClassName)).initializer("%L()", innerClassName).build()
        )
    }

    private fun className(builder: TypeSpec.Builder): String = builder.build().name!!
}
