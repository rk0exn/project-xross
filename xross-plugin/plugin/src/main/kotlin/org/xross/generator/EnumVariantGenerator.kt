package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.helper.StringHelper.escapeKotlinKeyword
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.XrossDefinition
import org.xross.structures.XrossField
import org.xross.structures.XrossThreadSafety
import org.xross.structures.XrossType
import java.lang.foreign.MemorySegment

object EnumVariantGenerator {

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

    fun generateVariants(
        classBuilder: TypeSpec.Builder,
        meta: XrossDefinition.Enum,
        targetPackage: String
    ) {
        val baseClassName = ClassName(targetPackage, meta.name)

        meta.variants.forEach { variant ->
            val isObject = variant.fields.isEmpty()
            val variantTypeBuilder = if (isObject) {
                TypeSpec.objectBuilder(variant.name)
            } else {
                TypeSpec.classBuilder(variant.name)
            }

            variantTypeBuilder.superclass(baseClassName)

            // --- コンストラクタ / 親クラスへの値渡し ---
            if (isObject) {
                variantTypeBuilder.addSuperclassConstructorParameter(
                    "(new_${variant.name}Handle.invokeExact() as %T).reinterpret(STRUCT_SIZE)",
                    MemorySegment::class
                )
                variantTypeBuilder.addSuperclassConstructorParameter("false")
            } else {
                val constructorBuilder = FunSpec.constructorBuilder()
                val argsList = mutableListOf<String>()

                variant.fields.forEach { field ->
                    val fqn = resolveFqn(field.ty, meta, targetPackage)
                    val kType = TypeVariableName(" $fqn")
                    val camelName = field.name.toCamelCase().escapeKotlinKeyword()
                    constructorBuilder.addParameter(camelName, kType)

                    // 型に応じた引数変換
                    val argExpr = when (field.ty) {
                        is XrossType.RustStruct, is XrossType.RustEnum, is XrossType.Object -> "$camelName.segment"
                        is XrossType.Bool -> "if ($camelName) 1.toByte() else 0.toByte()"
                        else -> camelName
                    }
                    argsList.add(argExpr)
                }

                variantTypeBuilder.primaryConstructor(constructorBuilder.build())
                variantTypeBuilder.addSuperclassConstructorParameter(
                    "(new_${variant.name}Handle.invokeExact(${argsList.joinToString()}) as %T).reinterpret(STRUCT_SIZE)",
                    MemorySegment::class
                )
                variantTypeBuilder.addSuperclassConstructorParameter("false")

                // --- フィールドプロパティ生成 ---
                variant.fields.forEach { field ->
                    val baseCamelName = field.name.toCamelCase()
                    val vhName = "VH_${variant.name}_$baseCamelName"
                    val fqn = resolveFqn(field.ty, meta, targetPackage)
                    val kType = TypeVariableName(" $fqn")

                    if (field.safety == XrossThreadSafety.Atomic) {
                        generateAtomicPropertyInVariant(variantTypeBuilder, baseCamelName, vhName, fqn)
                    } else {
                        val isMutable = field.safety != XrossThreadSafety.Immutable
                        val propBuilder = PropertySpec.builder(baseCamelName.escapeKotlinKeyword(), kType)
                            .mutable(isMutable)
                            .getter(buildVariantGetter(field, vhName, fqn))

                        if (isMutable) {
                            propBuilder.setter(buildVariantSetter(field, vhName, fqn))
                        }
                        variantTypeBuilder.addProperty(propBuilder.build())
                    }
                }
            }
            classBuilder.addType(variantTypeBuilder.build())
        }
    }

    private fun buildVariantGetter(field: XrossField, vhName: String, fqn: String): FunSpec {
        val parent = if (field.ty.isCopy){
            "null"
        }else{
            "this"
        }

        val segRef = "segment"

        val rawReadExpr = when (field.ty) {
            is XrossType.Bool -> "($vhName.get($segRef, 0L) as Byte) != (0).toByte()"
            is XrossType.RustStruct, is XrossType.RustEnum, is XrossType.Object ->
                "$fqn($vhName.get($segRef, 0L) as java.lang.foreign.MemorySegment, parent = $parent)"
            else -> "$vhName.get($segRef, 0L) as $fqn"
        }

        return FunSpec.getterBuilder().addCode("""
            var stamp = sl.tryOptimisticRead()
            var res = $rawReadExpr
            if (!sl.validate(stamp)) {
                stamp = sl.readLock()
                try { res = $rawReadExpr } finally { sl.unlockRead(stamp) }
            }
            return res
        """.trimIndent() + "\n").build()
    }

    private fun buildVariantSetter(field: XrossField, vhName: String, fqn: String): FunSpec {
        val segRef = "segment"

        val rawWriteExpr = when (field.ty) {
            is XrossType.Bool -> "$vhName.set($segRef, 0L, if (v) 1.toByte() else 0.toByte())"
            is XrossType.RustStruct, is XrossType.RustEnum, is XrossType.Object -> "$vhName.set($segRef, 0L, v.segment)"
            else -> "$vhName.set($segRef, 0L, v)"
        }

        return FunSpec.setterBuilder().addParameter("v", TypeVariableName(" $fqn")).addCode("""
            val stamp = sl.writeLock()
            try { $rawWriteExpr } finally { sl.unlockWrite(stamp) }
        """.trimIndent() + "\n").build()
    }

    private fun generateAtomicPropertyInVariant(
        builder: TypeSpec.Builder,
        baseName: String,
        vhName: String,
        fqn: String
    ) {
        val kType = TypeVariableName(" $fqn")
        val innerClassName = "AtomicFieldOf${baseName.replaceFirstChar { it.uppercase() }}"
        val segRef = "segment"

        val innerClass = TypeSpec.classBuilder(innerClassName).addModifiers(KModifier.INNER)
            .addProperty(PropertySpec.builder("value", kType)
                .getter(FunSpec.getterBuilder()
                    .addStatement("return $vhName.getVolatile($segRef, 0L) as $fqn").build())
                .build())
            .addFunction(FunSpec.builder("update")
                .addParameter("block", LambdaTypeName.get(null, kType, returnType = kType))
                .returns(kType)
                .beginControlFlow("while (true)")
                .addStatement("val current = value")
                .addStatement("val next = block(current)")
                .beginControlFlow("if ($vhName.compareAndSet($segRef, 0L, current, next))")
                .addStatement("return next")
                .endControlFlow()
                .endControlFlow().build())
            .build()

        builder.addType(innerClass)
        builder.addProperty(
            PropertySpec.builder(baseName.escapeKotlinKeyword(), ClassName("", innerClassName))
                .initializer("%L()", innerClassName)
                .build()
        )
    }
}
