package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.helper.StringHelper.escapeKotlinKeyword
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.*
import java.lang.foreign.MemorySegment

object PropertyGenerator {
    fun generateFields(classBuilder: TypeSpec.Builder, meta: XrossClass) {
        meta.fields.forEach { field ->
            val baseCamelName = field.name.toCamelCase()
            val escapedName = baseCamelName.escapeKotlinKeyword()
            val vhName = "VH_$baseCamelName"

            // 1. Atomic の場合は、専用の内部クラス + そのプロパティを生成
            if (field.safety == XrossThreadSafety.Atomic) {
                generateAtomicProperty(classBuilder, field, baseCamelName, escapedName, vhName)
            } else {
                // 2. それ以外は、通常のプロパティとして生成
                when (field.ty) {
                    is XrossType.RustString -> generateStringProperty(classBuilder, field, escapedName, vhName)
                    is XrossType.Slice -> generateSliceProperty(classBuilder, field, escapedName, vhName)
                    else -> {
                        when (field.safety) {
                            XrossThreadSafety.Lock -> generateLockProperty(classBuilder, field, escapedName, vhName)
                            XrossThreadSafety.Unsafe -> generateUnsafeProperty(classBuilder, field, escapedName, vhName)
                            XrossThreadSafety.Immutable -> generateImmutableProperty(
                                classBuilder,
                                field,
                                escapedName,
                                vhName
                            )
                        }
                    }
                }
            }
        }
    }

    private fun generateAtomicProperty(
        classBuilder: TypeSpec.Builder,
        field: XrossField,
        baseName: String,
        escapedName: String,
        vhName: String
    ) {
        val type = field.ty.kotlinType
        val innerClassName = "AtomicField_${baseName.replaceFirstChar { it.uppercase() }}"

        val innerClass = TypeSpec.classBuilder(innerClassName)
            .addModifiers(KModifier.INNER)
            // 明示的な取得: .get()
            .addFunction(
                FunSpec.builder("get")
                    .returns(type)
                    .addStatement("return $vhName.getVolatile(segment, 0L) as %T", type)
                    .build()
            )
            // 明示的な代入: .set(value)
            .addFunction(
                FunSpec.builder("set")
                    .addParameter("value", type)
                    .addStatement("$vhName.setVolatile(segment, 0L, value)")
                    .build()
            )
            // ラムダによるアトミック更新: .set { it + 1 }
            .addFunction(
                FunSpec.builder("set")
                    .addParameter(
                        "block",
                        LambdaTypeName.get(parameters = listOf(ParameterSpec.unnamed(type)), returnType = type)
                    )
                    .beginControlFlow("while (true)")
                    .addStatement("val expect = get()")
                    .addStatement("val update = block(expect)")
                    .beginControlFlow("if (compareAndSet(expect, update))")
                    .addStatement("break")
                    .endControlFlow()
                    .endControlFlow()
                    .build()
            )
            // 数値型用の高速な増減: .add(delta)
            .apply {
                if (field.ty.isNumber) {
                    addFunction(
                        FunSpec.builder("add")
                            .addParameter("delta", type)
                            .addStatement("$vhName.getAndAdd(segment, 0L, delta)")
                            .build()
                    )
                }
            }
            // CAS操作
            .addFunction(
                FunSpec.builder("compareAndSet")
                    .addParameter("expected", type)
                    .addParameter("newValue", type)
                    .returns(BOOLEAN)
                    .addStatement("return $vhName.compareAndSet(segment, 0L, expected, newValue)")
                    .build()
            )
            // toStringで現在の値を表示
            .addFunction(
                FunSpec.builder("toString")
                    .addModifiers(KModifier.OVERRIDE)
                    .returns(String::class)
                    .addStatement("return get().toString()")
                    .build()
            )
            .build()

        classBuilder.addType(innerClass)

        // プロパティとして露出 (val で不変にし、AtomicFieldオブジェクト自体が置き換わらないようにする)
        classBuilder.addProperty(
            PropertySpec.builder(escapedName, ClassName("", innerClassName))
                .initializer("%L()", innerClassName)
                .build()
        )
    }

    private fun generateUnsafeProperty(
        classBuilder: TypeSpec.Builder,
        field: XrossField,
        name: String,
        vhName: String
    ) {
        val type = field.ty.kotlinType
        val prop = PropertySpec.builder(name, type)
            .mutable(true)
            .getter(FunSpec.getterBuilder().addStatement("return $vhName.get(segment, 0L) as %T", type).build())
            .setter(FunSpec.setterBuilder().addParameter("v", type).addStatement("$vhName.set(segment, 0L, v)").build())
            .build()
        classBuilder.addProperty(prop)
    }

    private fun generateLockProperty(classBuilder: TypeSpec.Builder, field: XrossField, name: String, vhName: String) {
        val type = field.ty.kotlinType
        val getter = FunSpec.getterBuilder()
            .addStatement("var stamp = sl.tryOptimisticRead()")
            .addStatement("var res = $vhName.get(segment, 0L) as %T", type)
            .beginControlFlow("if (!sl.validate(stamp))")
            .addStatement("stamp = sl.readLock()")
            .beginControlFlow("try")
            .addStatement("res = $vhName.get(segment, 0L) as %T", type)
            .nextControlFlow("finally")
            .addStatement("sl.unlockRead(stamp)")
            .endControlFlow()
            .endControlFlow()
            .addStatement("return res")
            .build()

        val setter = FunSpec.setterBuilder()
            .addParameter("v", type)
            .addStatement("val stamp = sl.writeLock()")
            .beginControlFlow("try")
            .addStatement("$vhName.set(segment, 0L, v)")
            .nextControlFlow("finally")
            .addStatement("sl.unlockWrite(stamp)")
            .endControlFlow()
            .build()

        classBuilder.addProperty(PropertySpec.builder(name, type).mutable(true).getter(getter).setter(setter).build())
    }

    private fun generateImmutableProperty(
        classBuilder: TypeSpec.Builder,
        field: XrossField,
        name: String,
        vhName: String
    ) {
        val getter =
            FunSpec.getterBuilder().addStatement("return $vhName.get(segment, 0L) as %T", field.ty.kotlinType).build()
        classBuilder.addProperty(PropertySpec.builder(name, field.ty.kotlinType).mutable(false).getter(getter).build())
    }

    private fun generateStringProperty(
        classBuilder: TypeSpec.Builder,
        field: XrossField,
        name: String,
        vhName: String
    ) {
        val getter = FunSpec.getterBuilder()
            .addStatement("val ptr = $vhName.get(segment, 0L) as %T", MemorySegment::class)
            .addStatement(
                "return if (ptr == %T.NULL) %T.NULL else ptr.reinterpret(%T.MAX_VALUE)",
                MemorySegment::class,
                MemorySegment::class,
                Long::class
            )
            .build()
        classBuilder.addProperty(PropertySpec.builder(name, field.ty.kotlinType).getter(getter).build())
    }

    private fun generateSliceProperty(classBuilder: TypeSpec.Builder, field: XrossField, name: String, vhName: String) {
        val getter =
            FunSpec.getterBuilder().addStatement("return $vhName.get(segment, 0L) as %T", MemorySegment::class).build()
        classBuilder.addProperty(PropertySpec.builder(name, field.ty.kotlinType).getter(getter).build())
    }
}
