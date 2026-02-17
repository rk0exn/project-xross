package org.example

import org.example.standalone.*
import kotlin.system.measureTimeMillis

data class BenchmarkResult(val category: String, val name: String, val xrossTime: Long, val pkTime: Long) {
    val ratio: Double get() = if (xrossTime > 0) pkTime.toDouble() / xrossTime else 0.0
}

fun main() {
    NativeLoader.load()
    val results = mutableListOf<BenchmarkResult>()

    println("🚀 Starting Comprehensive Benchmarks...")

    // 1. Heavy Computations
    results.addAll(runHeavyBenchmarks())

    // 2. Lifecycle
    results.addAll(runLifecycleBenchmarks())

    // 3. Integration Suite
    results.addAll(runIntegrationSuiteBenchmarks())

    // 4. String Handling
    results.addAll(runStringBenchmarks())

    // 5. Direct Mode
    results.addAll(runDirectModeBenchmarks())

    // 6. Complex Patterns
    results.addAll(runComplexPatternBenchmarks())

    // 7. Ownership/Reference
    results.addAll(runOwnershipBenchmarks())

    // 8. Graphics
    results.addAll(runGraphicsBenchmarks())

    // Final Report
    printFinalReport(results)
}

fun runHeavyBenchmarks(): List<BenchmarkResult> {
    val count = 1000
    val primeInput = 1000000000039L
    val matrixSize = 100
    val matrixIterations = 100

    val timeXrossPrimeBatch = measureTimeMillis {
        BatchHeavyPrimeFactorization.batchHeavyPrimeFactorization(primeInput, count)
    }
    val timeKotlinPrime = measureTimeMillis {
        repeat(count) { heavyPrimeFactorizationKotlin(primeInput) }
    }

    val timeXrossMatrixBatch = measureTimeMillis {
        BatchHeavyMatrixMultiplication.batchHeavyMatrixMultiplication(matrixSize.toLong(), matrixIterations)
    }
    val timeKotlinMatrix = measureTimeMillis {
        repeat(matrixIterations) { heavyMatrixMultiplicationKotlin(matrixSize) }
    }

    val timeXrossAdvanced = measureTimeMillis {
        repeat(count) {
            org.example.heavy.AdvancedResult(
                2.0f, 0f, 0f, 0f, 100f, 10f, 100f, 0f, 0f, 0f, 0.05f, 0.05f, 0.05f, 10, 100, 10,
            ).close()
        }
    }
    val timePKAdvanced = measureTimeMillis {
        repeat(count) {
            PKAdvancedResult(
                2.0f, 0f, 0f, 0f, 100f, 10f, 100f, 0f, 0f, 0f, 0.05f, 0.05f, 0.05f, 10, 100, 10,
            )
        }
    }

    return listOf(
        BenchmarkResult("Heavy", "Prime Factorization (Batch)", timeXrossPrimeBatch, timeKotlinPrime),
        BenchmarkResult("Heavy", "Matrix Multiplication (Batch)", timeXrossMatrixBatch, timeKotlinMatrix),
        BenchmarkResult("Heavy", "Advanced Result Simulation ($count)", timeXrossAdvanced, timePKAdvanced),
    )
}

fun runGraphicsBenchmarks(): List<BenchmarkResult> {
    val iterations = 1000
    val timeXrossPath = measureTimeMillis {
        repeat(iterations) {
            val path = org.example.graphics.Path2D()
            path.begin()
            path.setPen(2.0, 0xFF0000FF.toInt(), org.example.graphics.XrossLineCap.Round(), org.example.graphics.XrossLineJoin.Round(), true)
            path.moveTo(0.0, 0.0)
            path.bezierCurveTo(50.0, 100.0, 150.0, -100.0, 200.0, 0.0)
            path.arc(100.0, 100.0, 50.0, 0.0, Math.PI, false)
            path.tessellateStroke()
            path.close()
        }
    }

    val timePKPath = measureTimeMillis {
        repeat(iterations) {
            var sum = 0.0
            for (i in 0..1000) {
                sum += Math.sin(i.toDouble())
            }
        }
    }

    return listOf(
        BenchmarkResult("Graphics", "Path2D Tessellation ($iterations)", timeXrossPath, timePKPath),
    )
}

fun runLifecycleBenchmarks(): List<BenchmarkResult> {
    val iterations = 500000

    val timeXrossLifecycle = measureTimeMillis {
        val service = org.example.test.test2.MyService2(0)
        repeat(iterations) { i ->
            service.createClone().use { clone ->
                clone.`val`.update { i }
                clone.execute()
            }
        }
        service.close()
    }

    val timePKLifecycle = measureTimeMillis {
        val service = PKMyService2(0)
        repeat(iterations) { i ->
            service.createClone().use { clone ->
                clone.`val`.update { i }
                clone.execute()
            }
        }
        service.close()
    }

    return listOf(
        BenchmarkResult("Lifecycle", "Create/Clone/Drop ($iterations)", timeXrossLifecycle, timePKLifecycle),
    )
}

fun runIntegrationSuiteBenchmarks(): List<BenchmarkResult> {
    val iterations = 100

    val timeXrossSuite = measureTimeMillis {
        repeat(iterations) {
            try {
                executePrimitiveTypeTest(silent = true)
                executeReferenceAndOwnershipTest(silent = true)
                executeConcurrencyTest(silent = true)
                executeEnumTest(silent = true)
                executeCollectionAndOptionalTest(silent = true)
                executePropertyTest(silent = true)
                executeComplexFieldTest(silent = true)
                executeComplexStructPropertyTest(silent = true)
                executeHelloEnumTest(silent = true)
                executeFastStructTest(silent = true)
                executeAllTypesTest(silent = true)
                executePanicAndTrivialTest(silent = true)
                executeStandaloneFunctionTest(silent = true)
            } catch (e: Throwable) {
                // Ignore failures in benchmark suite to get final stats
            }
        }
    }

    val timePKSuite = measureTimeMillis {
        repeat(iterations) {
            executePKPrimitiveTypeTest(silent = true)
            executePKReferenceAndOwnershipTest(silent = true)
            executePKConcurrencyTest(silent = true)
            executePKEnumTest(silent = true)
            executePKCollectionAndOptionalTest(silent = true)
            executePKPropertyTest(silent = true)
            executePKComplexFieldTest(silent = true)
            executePKComplexStructPropertyTest(silent = true)
            executePKHelloEnumTest(silent = true)
            executePKFastStructTest(silent = true)
            executePKAllTypesTest(silent = true)
            executePKPanicAndTrivialTest(silent = true)
            executePKStandaloneFunctionTest(silent = true)
        }
    }

    return listOf(
        BenchmarkResult("Suite", "Full Integration Test ($iterations)", timeXrossSuite, timePKSuite),
    )
}

fun runStringBenchmarks(): List<BenchmarkResult> {
    val iterations = 1000000
    val testString = "Hello Xross".repeat(10)

    val timeXrossString = measureTimeMillis {
        val unknown = UnknownStruct(argOfi = 1, argOfs = "", argOff = 1.0f)
        repeat(iterations) {
            unknown.s = testString
            val s = unknown.s
        }
        unknown.close()
    }

    val timePKString = measureTimeMillis {
        val unknown = PKUnknownStruct(1, "", 1.0f)
        repeat(iterations) {
            unknown.s = testString
            val s = unknown.s
        }
        unknown.close()
    }

    return listOf(
        BenchmarkResult("String", "String Get/Set ($iterations)", timeXrossString, timePKString),
    )
}

fun runDirectModeBenchmarks(): List<BenchmarkResult> {
    val iterations = 50000000

    val timeXrossLock = measureTimeMillis {
        val unknown = UnknownStruct(argOfi = 1, argOfs = "Test", argOff = 1.0f)
        repeat(iterations) {
            unknown.i = it
            val x = unknown.i
        }
        unknown.close()
    }

    val timeXrossDirect = measureTimeMillis {
        val fast = org.example.fast.FastStruct(argOfdata = 1, argOfname = "Test")
        repeat(iterations) {
            fast.data = it
            val x = fast.data
        }
        fast.close()
    }

    val timePK = measureTimeMillis {
        val unknown = PKUnknownStruct(1, "Test", 1.0f)
        repeat(iterations) {
            unknown.i = it
            val x = unknown.i
        }
        unknown.close()
    }

    return listOf(
        BenchmarkResult("Direct", "Field Access (Lock) ($iterations)", timeXrossLock, timePK),
        BenchmarkResult("Direct", "Field Access (Direct) ($iterations)", timeXrossDirect, timePK),
    )
}

fun runComplexPatternBenchmarks(): List<BenchmarkResult> {
    val iterations = 100000

    val timeXrossRecursive = measureTimeMillis {
        repeat(iterations) {
            val e = org.example.some.HelloEnum.C(org.example.some.HelloEnum.C(org.example.some.HelloEnum.B(it)))
            e.close()
        }
    }

    val timePKRecursive = measureTimeMillis {
        repeat(iterations) {
            val e = PKHelloEnum.C(PKHelloEnum.C(PKHelloEnum.B(it)))
            e.close()
        }
    }

    return listOf(
        BenchmarkResult("Complex", "Recursive Enum ($iterations)", timeXrossRecursive, timePKRecursive),
    )
}

fun runOwnershipBenchmarks(): List<BenchmarkResult> {
    val iterations = 1000000
    val test = AllTypesTest()
    val node = ComprehensiveNode(argOfid = 1, argOfdata = "Benchmark")

    val timeOwned = measureTimeMillis {
        repeat(iterations) {
            test.takeOwnedNode(node.clone())
        }
    }

    val timeRef = measureTimeMillis {
        repeat(iterations) {
            test.takeRefNode(node)
        }
    }

    val pkTest = PKAllTypesTest()
    val pkNode = PKComprehensiveNode(1, "Benchmark")
    val timePK = measureTimeMillis {
        repeat(iterations) {
            pkTest.takeRefNode(pkNode)
        }
    }

    test.close()
    node.close()
    pkTest.close()
    pkNode.close()

    return listOf(
        BenchmarkResult("Ownership", "Pass by Value (Clone) ($iterations)", timeOwned, timePK),
        BenchmarkResult("Ownership", "Pass by Reference ($iterations)", timeRef, timePK),
    )
}

fun printFinalReport(results: List<BenchmarkResult>) {
    println("\n" + "=".repeat(80))
    println(String.format("%-15s | %-35s | %-10s | %-10s | %-8s", "Category", "Benchmark Name", "Xross", "PK", "Ratio"))
    println("-".repeat(80))
    results.forEach {
        println(String.format("%-15s | %-35s | %7d ms | %7d ms | %7.2fx", it.category, it.name, it.xrossTime, it.pkTime, it.ratio))
    }
    println("=".repeat(80))
}

fun runGcAnalysis() {
    println("\n--- Native Resource Analysis ---")
    println("[Before GC] Xross: ${UnknownStruct.displayAnalysis().trim()}")
    println("[Before GC] Pure Kotlin: ${PKCounter.displayAnalysis().trim()}")

    repeat(3) {
        System.gc()
        Thread.sleep(1000)
    }

    println("\n[After GC] Xross: ${UnknownStruct.displayAnalysis().trim()}")
    println("[After GC] Pure Kotlin: ${PKCounter.displayAnalysis().trim()}")
}

fun heavyPrimeFactorizationKotlin(nIn: Long): Int {
    var n = nIn
    var count = 0
    var d = 2L
    while (d * d <= n) {
        while (n % d == 0L) {
            count++
            n /= d
        }
        d++
    }
    if (n > 1) count++
    return count
}

fun heavyMatrixMultiplicationKotlin(size: Int): Double {
    val a = Array(size) { DoubleArray(size) { 1.1 } }
    val b = Array(size) { DoubleArray(size) { 2.2 } }
    val c = Array(size) { DoubleArray(size) { 0.0 } }

    for (i in 0 until size) {
        for (j in 0 until size) {
            for (k in 0 until size) {
                c[i][j] += a[i][k] * b[k][j]
            }
        }
    }
    return c[size - 1][size - 1]
}
