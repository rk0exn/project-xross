package org.xross.generator

import com.squareup.kotlinpoet.*
import org.xross.generator.util.*
import org.xross.helper.StringHelper.escapeKotlinKeyword
import org.xross.helper.StringHelper.toCamelCase
import org.xross.structures.XrossDefinition
import org.xross.structures.XrossField
import org.xross.structures.XrossThreadSafety
import org.xross.structures.XrossType

object PropertyGenerator {

    fun generateFields(classBuilder: TypeSpec.Builder, meta: XrossDefinition.Struct, basePackage: String) {
        val selfType = GeneratorUtils.getClassName(meta.signature, basePackage)
        val backingFields = mutableListOf<String>()
        meta.fields.forEach { field ->
            val baseName = field.name.toCamelCase()
            val escapedName = baseName.escapeKotlinKeyword()
            val isPrimitive = field.ty !is XrossType.Object && field.ty !is XrossType.Optional && field.ty !is XrossType.Result
            val vhName: String = when {
                isPrimitive -> "VH_$baseName"
                else -> "null"
            }
            val kType = GeneratorUtils.resolveReturnType(field.ty, basePackage)

            val backingFieldName = GeneratorUtils.addBackingPropertyIfNeeded(classBuilder, field, baseName, kType)
            if (backingFieldName != null) backingFields.add(backingFieldName)

            if (field.safety == XrossThreadSafety.Atomic) {
                AtomicPropertyGenerator.generateAtomicProperty(classBuilder, baseName, escapedName, vhName, kType)
            } else {
                val isMutable = field.safety != XrossThreadSafety.Immutable
                val useLocks = field.safety != XrossThreadSafety.Direct && field.safety != XrossThreadSafety.Unsafe

                val propBuilder = PropertySpec.builder(escapedName, kType)
                    .mutable(isMutable)
                    .getter(GeneratorUtils.buildFullGetter(kType, buildGetterBody(field, vhName, kType, backingFieldName, selfType, basePackage), safety = field.safety, useAsyncLock = useLocks))

                if (isMutable) propBuilder.setter(GeneratorUtils.buildFullSetter(field.safety, kType, buildSetterBody(field, vhName, kType, backingFieldName, selfType, basePackage), useAsyncLock = useLocks))
                classBuilder.addProperty(propBuilder.build())
            }
        }

        GeneratorUtils.addClearCacheFunction(classBuilder, backingFields)
    }
    private fun buildGetterBody(field: XrossField, vhName: String, kType: TypeName, backingFieldName: String?, selfType: ClassName, basePackage: String): CodeBlock {
        val baseName = field.name.toCamelCase()
        val offsetName = "OFFSET_$baseName"
        return FieldBodyGenerator.buildGetterBody(
            FieldBodyGenerator.FieldContext(
                field,
                baseName,
                vhName,
                offsetName,
                kType,
                selfType,
                backingFieldName,
                basePackage,
            ),
        )
    }

    private fun buildSetterBody(field: XrossField, vhName: String, kType: TypeName, backingFieldName: String?, selfType: ClassName, basePackage: String): CodeBlock {
        val baseName = field.name.toCamelCase()
        val offsetName = "OFFSET_$baseName"

        return FieldBodyGenerator.buildSetterBody(
            FieldBodyGenerator.FieldContext(
                field,
                baseName,
                vhName,
                offsetName,
                kType,
                selfType,
                backingFieldName,
                basePackage,
            ),
        )
    }
}
