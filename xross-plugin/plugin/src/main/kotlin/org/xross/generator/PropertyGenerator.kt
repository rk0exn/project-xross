package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.helper.StringHelper.escapeKotlinKeyword
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.*

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

    /**
     * Struct用のフィールド生成
     */
    fun generateFields(classBuilder: TypeSpec.Builder, meta: XrossDefinition.Struct, targetPackage: String) {
        meta.fields.forEach { field ->
            val baseName = field.name.toCamelCase()
            val escapedName = baseName.escapeKotlinKeyword()
            val vhName = "VH_$baseName"
            val fqn = resolveFqn(field.ty, meta, targetPackage)

            if (field.safety == XrossThreadSafety.Atomic) {
                generateAtomicProperty(classBuilder, baseName, escapedName, vhName, fqn)
            } else {
                val kType = TypeVariableName(" $fqn")
                val isLocked = field.safety == XrossThreadSafety.Lock
                val isMutable = field.safety != XrossThreadSafety.Immutable

                val propBuilder = PropertySpec.builder(escapedName, kType)
                    .mutable(isMutable)
                    .getter(buildGetter(field, vhName, isLocked, fqn))

                if (isMutable) {
                    propBuilder.setter(buildSetter(field, vhName, isLocked, fqn))
                }
                classBuilder.addProperty(propBuilder.build())
            }
        }
    }

    private fun buildGetter(field: XrossField, vhName: String, isLocked: Boolean, fqn: String): FunSpec {
        val parent = if (field.ty.isCopy) {
            "null"
        } else {
            "this"
        }
        val segmentRef = "segment"
        val memSegmentClass = ClassName("java.lang.foreign", "MemorySegment")
        val nullPointerExceptionClass = NullPointerException::class
        val longClass = Long::class

        fun buildReadLogic(builder: CodeBlock.Builder, resultVarName: String) {
            if (!field.ty.isCopy) { // Check if the instance itself is NULL for non-copy types
                builder.addStatement(
                    "if ($segmentRef == %T.NULL) throw %T(%S)",
                    memSegmentClass,
                    nullPointerExceptionClass,
                    "Attempted to access field '${field.name}' on a NULL native object"
                )
            }

            when (field.ty) {
                is XrossType.Bool -> builder.addStatement(
                    "$resultVarName = ($vhName.get($segmentRef, 0L) as %T) != (0).toByte()",
                    Byte::class
                )

                is XrossType.RustString -> {
                    builder.addStatement("val rawSegment = $vhName.get($segmentRef, 0L) as %T", memSegmentClass)
                    builder.addStatement(
                        "$resultVarName = if (rawSegment == %T.NULL) \"\" else rawSegment.reinterpret(%T.MAX_VALUE).getString(0)",
                        memSegmentClass,
                        longClass
                    )
                }

                is XrossType.Object -> {
                    builder.addStatement("val rawSegment = $vhName.get($segmentRef, 0L) as %T", memSegmentClass)
                    builder.addStatement(
                        "if (rawSegment == %T.NULL) throw %T(%S)",
                        memSegmentClass,
                        nullPointerExceptionClass,
                        "Native reference for field '${field.name}' is NULL"
                    )
                    builder.addStatement("$resultVarName = %L(rawSegment, parent = $parent)", fqn)
                }

                else -> builder.addStatement("$resultVarName = $vhName.get($segmentRef, 0L) as %L", fqn)
            }
        }

        return FunSpec.getterBuilder().apply {
            if (!isLocked) {
                val bodyBuilder = CodeBlock.builder()
                buildReadLogic(bodyBuilder, "val value")
                addCode(bodyBuilder.build())
                addStatement("return value")
            } else {
                addStatement("var stamp = sl.tryOptimisticRead()")
                addStatement("var res: %T", TypeVariableName(" $fqn")) // Declare res with appropriate type

                addComment("Optimistic read")
                val optimisticReadBody = CodeBlock.builder()
                buildReadLogic(optimisticReadBody, "res")
                addCode(optimisticReadBody.build())

                beginControlFlow("if (!sl.validate(stamp))")
                addStatement("stamp = sl.readLock()")
                beginControlFlow("try")
                addComment("Pessimistic read")
                val pessimisticReadBody = CodeBlock.builder()
                buildReadLogic(pessimisticReadBody, "res")
                addCode(pessimisticReadBody.build())
                nextControlFlow("finally")
                addStatement("sl.unlockRead(stamp)")
                endControlFlow()
                endControlFlow()
                addStatement("return res")
            }
        }.build()
    }

    private fun buildSetter(field: XrossField, vhName: String, isLocked: Boolean, fqn: String): FunSpec {
        val segmentRef = "segment"
        val memSegmentClass = ClassName("java.lang.foreign", "MemorySegment")
        val nullPointerExceptionClass = NullPointerException::class

        fun buildWriteLogic(builder: CodeBlock.Builder) {
            if (!field.ty.isCopy) { // Check if the instance itself is NULL for non-copy types
                builder.addStatement(
                    "if ($segmentRef == %T.NULL) throw %T(%S)",
                    memSegmentClass,
                    nullPointerExceptionClass,
                    "Attempted to set field '${field.name}' on a NULL native object"
                )
            }

            when (field.ty) {
                is XrossType.Bool -> builder.addStatement("$vhName.set($segmentRef, 0L, if (v) 1.toByte() else 0.toByte())")
                is XrossType.Object -> {
                    builder.addStatement(
                        "if (v.segment == %T.NULL) throw %T(%S)",
                        memSegmentClass,
                        nullPointerExceptionClass,
                        "Cannot set field '${field.name}' with a NULL native reference"
                    )
                    builder.addStatement("$vhName.set($segmentRef, 0L, v.segment)")
                }

                else -> builder.addStatement("$vhName.set($segmentRef, 0L, v)")
            }
        }

        return FunSpec.setterBuilder().addParameter("v", TypeVariableName(" $fqn")).apply {
            if (!isLocked) {
                val bodyBuilder = CodeBlock.builder()
                buildWriteLogic(bodyBuilder)
                addCode(bodyBuilder.build())
            } else {
                addStatement("val stamp = sl.writeLock()")
                beginControlFlow("try")
                val writeBody = CodeBlock.builder()
                buildWriteLogic(writeBody)
                addCode(writeBody.build())
                nextControlFlow("finally")
                addStatement("sl.unlockWrite(stamp)")
                endControlFlow()
            }
        }.build()
    }

    private fun generateAtomicProperty(
        classBuilder: TypeSpec.Builder,
        baseName: String,
        escapedName: String,
        vhName: String,
        fqn: String
    ) {
        val innerClassName = "AtomicFieldOf${baseName.replaceFirstChar { it.uppercase() }}"
        val kType = TypeVariableName(" $fqn")

        val innerClass = TypeSpec.classBuilder(innerClassName).addModifiers(KModifier.INNER)
            .addProperty(
                PropertySpec.builder("value", kType)
                    .getter(
                        FunSpec.getterBuilder()
                            .addStatement("return $vhName.getVolatile(segment, 0L) as $fqn").build()
                    )
                    .build()
            )
            .addFunction(
                FunSpec.builder("update")
                    .addParameter("block", LambdaTypeName.get(null, kType, returnType = kType))
                    .returns(kType)
                    .beginControlFlow("while (true)")
                    .addStatement("val current = value")
                    .addStatement("val next = block(current)")
                    .beginControlFlow("if ($vhName.compareAndSet(segment, 0L, current, next))")
                    .addStatement("return next")
                    .endControlFlow()
                    .endControlFlow().build()
            )
            .addFunction(
                FunSpec.builder("compareAndSet")
                    .addParameter("expected", kType)
                    .addParameter("newValue", kType)
                    .returns(BOOLEAN)
                    .addStatement("return $vhName.compareAndSet(segment, 0L, expected, newValue)").build()
            )
            .build()

        classBuilder.addType(innerClass)
        classBuilder.addProperty(
            PropertySpec.builder(escapedName, ClassName("", innerClassName))
                .initializer("%L()", innerClassName).build()
        )
    }
}
