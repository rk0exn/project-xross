package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.helper.StringHelper.escapeKotlinKeyword
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.*

object PropertyGenerator {
    private fun resolveFqn(type: XrossType, meta: XrossDefinition, targetPackage: String): String {
        val signature = when (type) {
            is XrossType.RustStruct -> type.signature
            is XrossType.RustEnum -> type.signature
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
        // 内部クラスからのアクセスの場合は this@Parent.segment、そうでない場合は segment
        val segmentRef =
            "segment"
        // VarHandleの衝突を避けるため常に Companion 経由でアクセス
        val vhRef =
            vhName

        val rawRead = when (field.ty) {
            is XrossType.Bool -> "($vhRef.get($segmentRef, 0L) as kotlin.Byte) != (0).toByte()"
            is XrossType.RustString -> "(($vhRef.get($segmentRef, 0L) as java.lang.foreign.MemorySegment).let { if (it == java.lang.foreign.MemorySegment.NULL) it else it.reinterpret(kotlin.Long.MAX_VALUE) })"
            is XrossType.RustStruct, is XrossType.RustEnum, is XrossType.Object ->
                "$fqn($vhRef.get($segmentRef, 0L) as java.lang.foreign.MemorySegment, parent = $parent)"

            else -> "$vhRef.get($segmentRef, 0L) as $fqn"
        }

        return FunSpec.getterBuilder().apply {
            if (!isLocked) {
                addStatement("return $rawRead")
            } else {
                addCode(
                    """
                    var stamp = sl.tryOptimisticRead()
                    var res = $rawRead
                    if (!sl.validate(stamp)) {
                        stamp = sl.readLock()
                        try { res = $rawRead } finally { sl.unlockRead(stamp) }
                    }
                    return res
                """.trimIndent() + "\n"
                )
            }
        }.build()
    }

    private fun buildSetter(field: XrossField, vhName: String, isLocked: Boolean, fqn: String): FunSpec {
        val segmentRef = "segment"

        val rawWrite = when (field.ty) {
            is XrossType.Bool -> "$vhName.set($segmentRef, 0L, if (v) 1.toByte() else 0.toByte())"
            is XrossType.RustStruct, is XrossType.RustEnum, is XrossType.Object -> "$vhName.set($segmentRef, 0L, v.segment)"
            else -> "$vhName.set($segmentRef, 0L, v)"
        }

        return FunSpec.setterBuilder().addParameter("v", TypeVariableName(" $fqn")).apply {
            if (!isLocked) {
                addStatement(rawWrite)
            } else {
                addCode("val stamp = sl.writeLock(); try { $rawWrite } finally { sl.unlockWrite(stamp) }\n")
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
