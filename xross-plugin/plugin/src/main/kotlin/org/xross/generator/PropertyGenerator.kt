package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.helper.StringHelper.escapeKotlinKeyword
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.XrossClass
import org.xross.structures.XrossThreadSafety

object PropertyGenerator {
    fun generateFields(classBuilder: TypeSpec.Builder, meta: XrossClass) {
        meta.fields.forEach { field ->
            // エスケープ前の生の名前（VH_ などの接頭辞用）
            val baseCamelName = field.name.toCamelCase()
            // キーワードを考慮したエスケープ済みの名前（プロパティ公開用）
            val escapedName = baseCamelName.escapeKotlinKeyword()

            val propType = field.ty.kotlinType
            val offsetName = "OFFSET_${field.name}"
            val safety = field.safety

            // 1. Atomic の場合は VarHandle を Companion に用意
            if (safety == XrossThreadSafety.Atomic) {
                generateAtomicProperty(classBuilder, baseCamelName, escapedName, propType, offsetName)
                return@forEach
            }

            // 2. 通常の Property (Unsafe, Lock, Immutable)
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
                }
            }

            val prop = PropertySpec.builder(escapedName, propType)
                .mutable(safety != XrossThreadSafety.Immutable)
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
        baseName: String,     // エスケープなし (val)
        escapedName: String,  // エスケープあり (`val`)
        propType: TypeName,
        offsetName: String
    ) {
        val vhName = "VH_$baseName" // VH_val となり安全

        // VarHandle を使った get/set
        // %N を使うことで、プロパティ名にバッククォートが必要なら自動で付けてくれる
        val getter = FunSpec.getterBuilder()
            .addStatement("return $vhName.getVolatile(segment, $offsetName.offset) as %T", propType)
            .build()

        val setter = FunSpec.setterBuilder()
            .addParameter("value", propType)
            .addStatement("$vhName.setVolatile(segment, $offsetName.offset, value)")
            .build()

        classBuilder.addProperty(
            PropertySpec.builder(escapedName, propType)
                .mutable(true)
                .getter(getter)
                .setter(setter)
                .build()
        )

        val capitalized = baseName.replaceFirstChar { it.uppercase() }

        // Atomic ヘルパー
        classBuilder.addFunction(
            FunSpec.builder("compareAndSet$capitalized")
                .addParameter("expected", propType)
                .addParameter("newValue", propType)
                .returns(Boolean::class)
                .addStatement("return $vhName.compareAndSet(segment, $offsetName.offset, expected, newValue)")
                .build()
        )

        if (propType == INT || propType == LONG) {
            classBuilder.addFunction(
                FunSpec.builder("getAndAdd$capitalized")
                    .addParameter("delta", propType)
                    .returns(propType)
                    .addStatement("return $vhName.getAndAdd(segment, $offsetName.offset, delta) as %T", propType)
                    .build()
            )
        }
    }
}
