package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.helper.StringHelper.escapeKotlinKeyword
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.XrossClass
import org.xross.structures.XrossThreadSafety

object PropertyGenerator {
    fun generateFields(classBuilder: TypeSpec.Builder, meta: XrossClass) {
        meta.fields.forEach { field ->
            val camelName = field.name.toCamelCase().escapeKotlinKeyword()
            val propType = field.ty.kotlinType
            val offsetName = "OFFSET_${field.name}"
            val safety = field.safety

            // 1. Atomic の場合は VarHandle を Companion に用意し、専用メソッドを生成
            if (safety == XrossThreadSafety.Atomic) {
                generateAtomicProperty(classBuilder, camelName, propType, offsetName)
                return@forEach
            }

            // 2. 通常の Property (Unsafe, Lock, Immutable) の生成
            val getterBuilder = FunSpec.getterBuilder()
            val setterBuilder = FunSpec.setterBuilder().addParameter("value", propType)

            when (safety) {
                XrossThreadSafety.Unsafe -> {
                    getterBuilder.addStatement("return segment.get(%M, $offsetName.offset)", field.ty.layoutMember)
                    setterBuilder.addStatement("segment.set(%M, $offsetName.offset, value)", field.ty.layoutMember)
                }
                XrossThreadSafety.Lock -> {
                    getterBuilder.addStatement("return lock.readLock().withLock { segment.get(%M, $offsetName.offset) }", field.ty.layoutMember)
                    setterBuilder.addStatement("lock.writeLock().withLock { segment.set(%M, $offsetName.offset, value) }", field.ty.layoutMember)
                }
                XrossThreadSafety.Immutable -> {
                    getterBuilder.addStatement("return segment.get(%M, $offsetName.offset)", field.ty.layoutMember)
                    // setter は生成しない
                }
            }

            val prop = PropertySpec.builder(camelName, propType)
                .mutable(safety != XrossThreadSafety.Immutable) // Immutable は val
                .getter(getterBuilder.build())
                .apply {
                    if (safety != XrossThreadSafety.Immutable) setter(setterBuilder.build())
                }
                .build()

            classBuilder.addProperty(prop)
        }
    }

    private fun generateAtomicProperty(
        classBuilder: TypeSpec.Builder,
        camelName: String,
        propType: TypeName,
        offsetName: String
    ) {
        // VarHandle を使った get/set プロパティ
        val getter = FunSpec.getterBuilder()
            .addStatement("return VH_$camelName.getVolatile(segment, $offsetName.offset) as %T", propType)
            .build()

        val setter = FunSpec.setterBuilder()
            .addParameter("value", propType)
            .addStatement("VH_$camelName.setVolatile(segment, $offsetName.offset, value)")
            .build()

        classBuilder.addProperty(
            PropertySpec.builder(camelName, propType)
                .mutable(true)
                .getter(getter)
                .setter(setter)
                .build()
        )

        // Atomic 特有のヘルパーメソッド (CASなど) を追加
        classBuilder.addFunction(
            FunSpec.builder("compareAndSet${camelName.replaceFirstChar { it.uppercase() }}")
                .addParameter("expected", propType)
                .addParameter("newValue", propType)
                .returns(Boolean::class)
                .addStatement("return VH_$camelName.compareAndSet(segment, $offsetName.offset, expected, newValue)")
                .build()
        )

        // 数値型なら getAndAdd も追加したいところ
        if (propType == INT || propType == LONG) {
            classBuilder.addFunction(
                FunSpec.builder("getAndAdd${camelName.replaceFirstChar { it.uppercase() }}")
                    .addParameter("delta", propType)
                    .returns(propType)
                    .addStatement("return VH_$camelName.getAndAdd(segment, $offsetName.offset, delta) as %T", propType)
                    .build()
            )
        }
    }
}
