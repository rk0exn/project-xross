package org.example

import org.example.standalone.BatchHeavyMatrixMultiplication
import org.example.standalone.BatchHeavyPrimeFactorization
import org.example.standalone.HeavyMatrixMultiplication
import org.example.standalone.HeavyPrimeFactorization
import kotlin.system.measureTimeMillis

fun main() {
    NativeLoader.load()
    try {
        runHeavyBenchmarks()
        runLifecycleBenchmarks()
        runIntegrationSuiteBenchmarks()
    } finally {
        println("\n--- Final Native Object Analysis (Xross) ---")
        println("[GC] Collecting garbage and running finalizers (Multiple cycles)...")
        
        println("\n[Before GC]")
        println(UnknownStruct.displayAnalysis())

        repeat(3) {
            System.gc()
            Thread.sleep(2000)
        }

        println("\n[After GC]")
        println(UnknownStruct.displayAnalysis())
        
        println("\n--- Final Simulated Analysis (Pure Kotlin) ---")
        println(PKCounter.displayAnalysis())
    }
}

fun runLifecycleBenchmarks() {
    val iterations = 100000
    println("\n[Benchmark] --- Object Lifecycle (Create/Clone/Drop, $iterations iterations) ---")

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
    println("[Benchmark] Xross Lifecycle            : ${timeXrossLifecycle}ms")

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
    println("[Benchmark] Pure Kotlin Lifecycle      : ${timePKLifecycle}ms")
}

fun runIntegrationSuiteBenchmarks() {
    val iterations = 100
    println("\n[Benchmark] --- Integration Suite (All features, $iterations iterations) ---")

    // Note: These functions are expected to be available in WithXrossApp.kt and PureKotlinApp.kt
    // We suppress the output during benchmark if possible, but for now we run them as is.

    val timeXrossSuite = measureTimeMillis {
        repeat(iterations) {
            executePrimitiveTypeTest()
            executeReferenceAndOwnershipTest()
            executeConcurrencyTest()
            executeEnumTest()
            executeCollectionAndOptionalTest()
            executePropertyTest()
            executeComplexFieldTest()
            executeComplexStructPropertyTest()
            executePanicAndTrivialTest()
            executeStandaloneFunctionTest()
            // executeAsyncTest() // Exclude async from tight loop benchmark to avoid coroutine overhead variance
        }
    }
    println("[Benchmark] Xross Integration Suite    : ${timeXrossSuite}ms")

    val timePKSuite = measureTimeMillis {
        repeat(iterations) {
            executePKPrimitiveTypeTest()
            executePKReferenceAndOwnershipTest()
            executePKConcurrencyTest()
            executePKEnumTest()
            executePKCollectionAndOptionalTest()
            executePKPropertyTest()
            executePKComplexFieldTest()
            executePKComplexStructPropertyTest()
            executePKPanicAndTrivialTest()
            executePKStandaloneFunctionTest()
            // executePKAsyncTest()
        }
    }
    println("[Benchmark] Pure Kotlin Simulated Suite : ${timePKSuite}ms")
}

fun runHeavyBenchmarks() {
    val count = 1000
    val primeInput = 1000000000039L
    val matrixSize = 100
    val matrixIterations = 100

    println("\n[Benchmark] --- Prime Factorization ($count iterations) ---")

    val timeXrossPrimePerCall = measureTimeMillis {
        repeat(count) {
            HeavyPrimeFactorization.heavyPrimeFactorization(primeInput)
        }
    }
    println("[Benchmark] Xross (Native Loop Outside): ${timeXrossPrimePerCall}ms")

    val timeXrossPrimeBatch = measureTimeMillis {
        BatchHeavyPrimeFactorization.batchHeavyPrimeFactorization(primeInput, count)
    }
    println("[Benchmark] Xross (Native Loop Inside) : ${timeXrossPrimeBatch}ms")

    val timeKotlinPrime = measureTimeMillis {
        repeat(count) {
            heavyPrimeFactorizationKotlin(primeInput)
        }
    }
    println("[Benchmark] Pure Kotlin                : ${timeKotlinPrime}ms")

    println("\n[Benchmark] --- Matrix Multiplication ($matrixSize x $matrixSize, $matrixIterations iterations) ---")

    val timeXrossMatrixPerCall = measureTimeMillis {
        repeat(matrixIterations) {
            HeavyMatrixMultiplication.heavyMatrixMultiplication(matrixSize.toLong())
        }
    }
    println("[Benchmark] Xross (Native Loop Outside): ${timeXrossMatrixPerCall}ms")

    val timeXrossMatrixBatch = measureTimeMillis {
        BatchHeavyMatrixMultiplication.batchHeavyMatrixMultiplication(matrixSize.toLong(), matrixIterations)
    }
    println("[Benchmark] Xross (Native Loop Inside) : ${timeXrossMatrixBatch}ms")

    val timeKotlinMatrix = measureTimeMillis {
        repeat(matrixIterations) {
            heavyMatrixMultiplicationKotlin(matrixSize)
        }
    }
    println("[Benchmark] Pure Kotlin                : ${timeKotlinMatrix}ms")
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
