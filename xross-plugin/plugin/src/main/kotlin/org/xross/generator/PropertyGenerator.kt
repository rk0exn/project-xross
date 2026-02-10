package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.helper.StringHelper.escapeKotlinKeyword
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.XrossDefinition
import org.xross.structures.XrossField
import org.xross.structures.XrossThreadSafety
import org.xross.structures.XrossType
import java.lang.foreign.MemorySegment

object PropertyGenerator {
    fun generateFields(classBuilder: TypeSpec.Builder, meta: XrossDefinition.Struct, basePackage: String) {
        val selfType = XrossGenerator.getClassName(meta.signature, basePackage)
        meta.fields.forEach { field ->
            val baseName = field.name.toCamelCase()
            val escapedName = baseName.escapeKotlinKeyword()
            val vhName = "VH_$baseName"
            val kType = if (field.ty is XrossType.Object) {
                XrossGenerator.getClassName(field.ty.signature, basePackage)
            } else {
                field.ty.kotlinType
            }

            var backingFieldName: String? = null
            if (field.ty is XrossType.Object) {
                backingFieldName = "_$baseName"
                val backingProp = PropertySpec.builder(backingFieldName, kType.copy(nullable = true))
                    .mutable(true)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("null")
                    .build()
                classBuilder.addProperty(backingProp)
            }

            if (field.safety == XrossThreadSafety.Atomic) {
                generateAtomicProperty(classBuilder, baseName, escapedName, vhName, kType)
            } else {
                val isMutable = field.safety != XrossThreadSafety.Immutable

                val propBuilder = PropertySpec.builder(escapedName, kType)
                    .mutable(isMutable)
                    .getter(buildGetter(field, vhName, kType, backingFieldName, selfType)) 

                if (isMutable) propBuilder.setter(buildSetter(field, vhName, kType, backingFieldName, selfType))
                classBuilder.addProperty(propBuilder.build())
            }
        }
    }
    private fun buildGetter(field: XrossField, vhName: String, kType: TypeName, backingFieldName: String?, selfType: ClassName): FunSpec {
        val readCodeBuilder = CodeBlock.builder()
        readCodeBuilder.addStatement("if (this.segment == %T.NULL || !this.aliveFlag.isValid) throw %T(%S)", MemorySegment::class, NullPointerException::class, "Access error")

        val isSelf = kType == selfType
        val baseName = field.name.toCamelCase()
        val offsetName = "OFFSET_$baseName"

        when (field.ty) {
            is XrossType.Object -> {
                readCodeBuilder.beginControlFlow("if (this.$backingFieldName != null && this.$backingFieldName!!.aliveFlag.isValid)")
                readCodeBuilder.addStatement("res = this.$backingFieldName!!")
                readCodeBuilder.nextControlFlow("else")

                val sizeExpr = if (isSelf) "STRUCT_SIZE" else "%T.STRUCT_SIZE"
                val fromPointerExpr = if (isSelf) "fromPointer" else "%T.fromPointer"
                val isOwned = field.ty.ownership == XrossType.Ownership.Owned

                val resolveArgs = mutableListOf<Any?>()
                resolveArgs.add(isOwned)
                if (!isSelf) resolveArgs.add(kType)
                resolveArgs.add(isOwned)

                readCodeBuilder.addStatement(
                    "val resSeg = resolveFieldSegment(this.segment, if (%L) null else $vhName, $offsetName, $sizeExpr, %L)",
                    *resolveArgs.toTypedArray()
                )
                
                val fromPointerArgs = mutableListOf<Any?>()
                if (!isSelf) fromPointerArgs.add(kType)
                
                readCodeBuilder.addStatement(
                    "res = $fromPointerExpr(resSeg, this.autoArena)",
                    *fromPointerArgs.toTypedArray()
                )
                readCodeBuilder.addStatement("this.$backingFieldName = res")
                readCodeBuilder.endControlFlow()
            }
            is XrossType.Bool -> readCodeBuilder.addStatement("res = ($vhName.get(this.segment, $offsetName) as Byte) != (0).toByte()")
            is XrossType.RustString -> {
                readCodeBuilder.addStatement(
                    "val rawSegment = Companion.${baseName}StrGetHandle.invokeExact(this.segment) as %T",
                    MemorySegment::class
                )
                readCodeBuilder.addStatement(
                    "res = if (rawSegment == %T.NULL) \"\" else rawSegment.reinterpret(%T.MAX_VALUE).getString(0)",
                    MemorySegment::class,
                    Long::class
                )
                readCodeBuilder.addStatement(
                    "if (rawSegment != %T.NULL) Companion.xrossFreeStringHandle.invokeExact(rawSegment)",
                    MemorySegment::class
                )
            }

            else -> readCodeBuilder.addStatement("res = $vhName.get(this.segment, $offsetName) as %T", kType)
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
        """.trimIndent(), kType, readCodeBuilder.build(), readCodeBuilder.build()
        ).build()
    }
    private fun buildSetter(field: XrossField, vhName: String, kType: TypeName, backingFieldName: String?, selfType: ClassName): FunSpec {
        val writeCodeBuilder = CodeBlock.builder()
        writeCodeBuilder.addStatement(
            "if (this.segment == %T.NULL || !this.aliveFlag.isValid) throw %T(%S)",
            MemorySegment::class,
            NullPointerException::class,
            "Attempted to set field '${field.name}' on a NULL or invalid native object"
        )

        val isSelf = kType == selfType
        val baseName = field.name.toCamelCase()
        val offsetName = "OFFSET_$baseName"

        when (field.ty) {
            is XrossType.RustString -> {
                writeCodeBuilder.beginControlFlow("java.lang.foreign.Arena.ofConfined().use { arena ->")
                writeCodeBuilder.addStatement("val allocated = arena.allocateFrom(v)")
                writeCodeBuilder.addStatement("Companion.${baseName}StrSetHandle.invokeExact(this.segment, allocated) as Unit")
                writeCodeBuilder.endControlFlow()
            }
            is XrossType.Bool -> writeCodeBuilder.addStatement("$vhName.set(this.segment, $offsetName, if (v) 1.toByte() else 0.toByte())")
            is XrossType.Object -> {
                writeCodeBuilder.addStatement(
                    "if (v.segment == %T.NULL || !v.aliveFlag.isValid) throw %T(%S)",
                    MemorySegment::class,
                    NullPointerException::class,
                    "Cannot set field '${field.name}' with a NULL or invalid native reference"
                )

                if (field.ty.ownership == XrossType.Ownership.Owned) {
                    val sizeExpr = if (isSelf) "STRUCT_SIZE" else "%T.STRUCT_SIZE"
                    writeCodeBuilder.addStatement(
                        "this.segment.asSlice($offsetName, $sizeExpr).copyFrom(v.segment)",
                        *(if (isSelf) emptyArray() else arrayOf(kType))
                    )
                } else {
                    writeCodeBuilder.addStatement("$vhName.set(this.segment, $offsetName, v.segment)")
                }

                if (backingFieldName != null) {
                    writeCodeBuilder.addStatement("this.$backingFieldName = null")
                }
            }

            else -> writeCodeBuilder.addStatement("$vhName.set(this.segment, $offsetName, v)")
        }

        return FunSpec.setterBuilder().addParameter("v", kType).addCode(
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
        kType: TypeName
    ) {
        val innerClassName = "AtomicFieldOf${baseName.replaceFirstChar { it.uppercase() }}"
        val offsetName = "OFFSET_$baseName"
        val innerClass = TypeSpec.classBuilder(innerClassName).addModifiers(KModifier.INNER)
            .addProperty(
                PropertySpec.builder("value", kType)
                    .getter(
                        FunSpec.getterBuilder()
                            .addStatement("return $vhName.getVolatile(this@${className(classBuilder)}.segment, $offsetName) as %T", kType)
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
                    .beginControlFlow("if ($vhName.compareAndSet(this@${className(classBuilder)}.segment, $offsetName, current, next))")
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
