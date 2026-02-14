package org.xross.generator.util

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.asTypeName
import java.lang.foreign.*

object FFMConstants {
    val MEMORY_SEGMENT = MemorySegment::class.asTypeName()
    val MEMORY_LAYOUT = MemoryLayout::class.asTypeName()
    val ARENA = Arena::class.asTypeName()
    val FUNCTION_DESCRIPTOR = FunctionDescriptor::class.asTypeName()
    val VAL_LAYOUT = ClassName("java.lang.foreign", "ValueLayout")

    val ADDRESS = MemberName(VAL_LAYOUT, "ADDRESS")
    val JAVA_INT = MemberName(VAL_LAYOUT, "JAVA_INT")
    val JAVA_LONG = MemberName(VAL_LAYOUT, "JAVA_LONG")
    val JAVA_BYTE = MemberName(VAL_LAYOUT, "JAVA_BYTE")
    val JAVA_SHORT = MemberName(VAL_LAYOUT, "JAVA_SHORT")
    val JAVA_FLOAT = MemberName(VAL_LAYOUT, "JAVA_FLOAT")
    val JAVA_DOUBLE = MemberName(VAL_LAYOUT, "JAVA_DOUBLE")
    val JAVA_CHAR = MemberName(VAL_LAYOUT, "JAVA_CHAR")

    val XROSS_RESULT_LAYOUT_CODE = com.squareup.kotlinpoet.CodeBlock.of(
        "%T.structLayout(%M.withName(%S), %T.paddingLayout(7), %M.withName(%S))",
        MEMORY_LAYOUT,
        JAVA_BYTE,
        "isOk",
        MEMORY_LAYOUT,
        ADDRESS,
        "ptr",
    )

    val XROSS_TASK_LAYOUT_CODE = com.squareup.kotlinpoet.CodeBlock.of(
        "%T.structLayout(%M.withName(%S), %M.withName(%S), %M.withName(%S))",
        MEMORY_LAYOUT,
        ADDRESS,
        "taskPtr",
        ADDRESS,
        "pollFn",
        ADDRESS,
        "dropFn",
    )
}
