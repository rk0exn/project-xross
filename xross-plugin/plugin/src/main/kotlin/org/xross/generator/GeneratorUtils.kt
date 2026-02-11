package org.xross.generator

import com.squareup.kotlinpoet.ClassName
import org.xross.structures.XrossDefinition

object GeneratorUtils {
    fun isPureEnum(meta: XrossDefinition): Boolean {
        return meta is XrossDefinition.Enum && meta.variants.all { it.fields.isEmpty() }
    }

    fun getClassName(signature: String, basePackage: String): ClassName {
        val fqn = if (basePackage.isEmpty() || signature.startsWith(basePackage)) {
            signature
        } else {
            "$basePackage.$signature"
        }
        val lastDot = fqn.lastIndexOf('.')
        return if (lastDot == -1) ClassName("", fqn)
        else ClassName(fqn.substring(0, lastDot), fqn.substring(lastDot + 1))
    }
}
