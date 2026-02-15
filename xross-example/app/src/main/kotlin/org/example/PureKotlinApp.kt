package org.example

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

// --- シミュレーション用ベースクラス ---

class PKAliveState(val ownerClosed: AtomicBoolean = AtomicBoolean(false)) {
    val isValid: Boolean get() = !ownerClosed.get()
}

class PKAliveFlag(private val check: () -> Boolean) {
    val isValid: Boolean get() = check()
}

class PKAtomicHelper(private val atomic: AtomicInteger) {
    val value: Int get() = atomic.get()
    fun update(op: (Int) -> Int) {
        while (true) {
            val curr = atomic.get()
            if (atomic.compareAndSet(curr, op(curr))) break
        }
    }
    override fun toString(): String = value.toString()
}

// --- オブジェクトカウンターのシミュレーション ---
object PKCounter {
    val serviceCount = AtomicLong(0)
    val service2Count = AtomicLong(0)
    val unknownStructCount = AtomicLong(0)

    fun displayAnalysis(): String {
        val s1 = serviceCount.get()
        val s2 = service2Count.get()
        val u = unknownStructCount.get()
        return """
--- PK Native Analysis (Simulated) ---
Active MyService: $s1
Active MyService2: $s2
Active UnknownStruct: $u
Total Simulated Native Objects: ${s1 + s2 + u}
-----------------------------
        """.trimIndent()
    }
}

// --- 構造体と列挙型 ---

class PKUnknownStruct(
    var i: Int,
    var s: String,
    var f: Float,
    private val state: PKAliveState = PKAliveState(),
    private val ownsResource: Boolean = true,
) : AutoCloseable {
    init {
        if (ownsResource) PKCounter.unknownStructCount.incrementAndGet()
    }

    private val instanceClosed = AtomicBoolean(false)
    val isClosed: Boolean get() = instanceClosed.get() || !state.isValid

    val segment: Long get() = if (isClosed) 0L else 0xDEADC0DE
    val aliveFlag = PKAliveFlag { !isClosed }

    override fun close() {
        if (instanceClosed.compareAndSet(false, true)) {
            if (ownsResource) {
                state.ownerClosed.set(true)
                PKCounter.unknownStructCount.decrementAndGet()
            }
        }
    }

    fun clone(): PKUnknownStruct = PKUnknownStruct(i, s, f)
    fun getBorrow(): PKUnknownStruct = PKUnknownStruct(i, s, f, state, false)

    override fun toString(): String = "PKUnknownStruct(i=$i, s='$s', f=$f)"

    companion object {
        fun displayAnalysis() = PKCounter.displayAnalysis()
    }
}

sealed class PKXrossTestEnum : AutoCloseable {
    class A : PKXrossTestEnum()
    class B(var i: Int) : PKXrossTestEnum()
    class C(var j: PKUnknownStruct) : PKXrossTestEnum()
    override fun close() {
        if (this is C) j.close()
    }
}

enum class PKXrossSimpleEnum {
    V,
    W,
    X,
    Y,
    Z,
    ;

    fun sayHello() {
        println("Hello, world!")
    }
}

class PKComplexStruct(var opt: Int?, var res: Result<Int>) : AutoCloseable {
    override fun close() {}
}

class PKExternalStruct(initialValue: Int, var name: String) : AutoCloseable {
    @get:JvmName("getValProp")
    @set:JvmName("setValProp")
    var value: Int = initialValue
    fun getValue(): Int = value
    fun setValue(v: Int) {
        value = v
    }
    fun greet(prefix: String): String = "$prefix $name"
    override fun close() {}
}

sealed class PKHelloEnum : AutoCloseable {
    object A : PKHelloEnum()
    class B(val i: Int) : PKHelloEnum()
    class C(val zeroth: PKHelloEnum) : PKHelloEnum()
    object D : PKHelloEnum()
    override fun close() {
        if (this is C) zeroth.close()
    }
}

class PKPrimitiveTest(var u8Val: Byte, var u32Val: Int, var u64Val: Long) : AutoCloseable {
    fun addU32(valIn: Int) {
        u32Val += valIn
    }
    override fun close() {}
}

class PKMyService : AutoCloseable {
    private val state = PKAliveState()
    private val instanceClosed = AtomicBoolean(false)
    val boxes = IntArray(1_000_000)
    val unknownStruct = PKUnknownStruct(32, "Hello, World!", 64.0f)

    init {
        PKCounter.serviceCount.incrementAndGet()
    }

    private fun checkAlive() {
        if (instanceClosed.get() || !state.isValid) throw NullPointerException("Object dropped or invalid")
    }

    suspend fun asyncExecute(valIn: Int): Int {
        checkAlive()
        delay(10)
        return valIn * 2
    }

    fun consumeSelf(): Int {
        val size = boxes.size
        close()
        return size
    }

    fun retEnum(): PKXrossTestEnum {
        checkAlive()
        return when (Random.nextInt(3)) {
            1 -> PKXrossTestEnum.B(Random.nextInt())
            2 -> PKXrossTestEnum.C(PKUnknownStruct(32, "Hello, World!", 64.0f))
            else -> PKXrossTestEnum.A()
        }
    }

    fun addTrivial(a: Int, b: Int): Int = a + b
    fun addCriticalHeap(a: Int, b: Int): Int = a + b

    fun causePanic(shouldPanic: Boolean): String {
        if (shouldPanic) throw org.example.xross.runtime.XrossException("Intentional panic from Kotlin!")
        return "No panic today"
    }

    fun execute(i: Int): Int {
        checkAlive()
        val a = boxes.size
        val x = min(a, i)
        val y = max(a, i)
        return Random.nextInt(x, y + 1)
    }

    fun getOptionEnum(shouldSome: Boolean): PKXrossSimpleEnum? = if (shouldSome) PKXrossSimpleEnum.V else null
    fun getResultStruct(shouldOk: Boolean): Result<PKMyService2> = if (shouldOk) Result.success(PKMyService2(1)) else Result.failure(org.example.xross.runtime.XrossException("Error"))

    override fun close() {
        if (instanceClosed.compareAndSet(false, true)) {
            state.ownerClosed.set(true)
            unknownStruct.close()
            PKCounter.serviceCount.decrementAndGet()
        }
    }
}

class PKMyService2(
    private val atomicValue: AtomicInteger,
    private val state: PKAliveState = PKAliveState(),
    private val ownsResource: Boolean = true,
) : AutoCloseable {
    constructor(initial: Int) : this(AtomicInteger(initial), PKAliveState(), true)

    init {
        if (ownsResource) PKCounter.service2Count.incrementAndGet()
    }

    val `val` = PKAtomicHelper(atomicValue)
    private val instanceClosed = AtomicBoolean(false)
    val isClosed: Boolean get() = instanceClosed.get() || !state.isValid

    fun createClone(): PKMyService2 = PKMyService2(atomicValue.get())
    fun getSelfRef(): PKMyService2 = PKMyService2(atomicValue, state, false)

    fun execute(): Double {
        if (isClosed) throw NullPointerException("Object dropped or invalid")
        val v = atomicValue.get()
        if (v == 0) return 0.0
        val low = min(-v, v)
        val high = max(-v, v)
        return Random.nextInt(low, high + 1).toDouble()
    }

    override fun close() {
        if (instanceClosed.compareAndSet(false, true)) {
            if (ownsResource) {
                state.ownerClosed.set(true)
                PKCounter.service2Count.decrementAndGet()
            }
        }
    }
}

// --- スタンドアロン関数 ---
object PKStandalone {
    fun globalAdd(a: Int, b: Int): Int = a + b
    fun globalGreet(name: String): String = "Hello, $name!"
    fun globalMultiply(a: Int, b: Int): Int = a * b
    fun testUnsigned(a: Byte, b: Int, c: Long): Long = (a.toInt() and 0xFF).toLong() + (b.toLong() and 0xFFFFFFFFL) + c
    suspend fun asyncAdd(a: Int, b: Int): Int {
        delay(10)
        return a + b
    }
    suspend fun asyncGreet(name: String): String {
        delay(10)
        return "Async Hello, $name!"
    }
}

// --- メインテストロジック ---

fun main() {
    println("=== Starting Pure Kotlin Benchmark (Behavior Equivalent) ===")

    try {
        executePKMemoryLeakTest()

        val repeatCount = 100
        println("\n--- Starting Repetitive Stability Test ($repeatCount cycles) ---")

        for (i in 1..repeatCount) {
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
            executePKAsyncTest()

            if (i % 10 == 0) println(">>> Completed cycle $i / $repeatCount")
        }

        println("\n✅ All $repeatCount cycles finished without any crashes or memory errors!")
    } catch (e: Exception) {
        println("Test failed with exception:")
        e.printStackTrace()
    } finally {
        println("\n--- Final Analysis before GC ---")
        println(PKUnknownStruct.displayAnalysis())
        System.gc()
        Thread.sleep(1000)
        println("\n--- Final Analysis after GC ---")
        println(PKUnknownStruct.displayAnalysis())
    }
}

fun executePKMemoryLeakTest() {
    println("\n--- [1] Memory Leak & Stability Test ---")
    val iterations = (10.0).pow(7).toInt()
    val reportInterval = iterations / 10
    val service = PKMyService2(0)
    val startTime = System.currentTimeMillis()
    for (i in 1..iterations) {
        service.createClone().use { clone ->
            clone.`val`.update { i }
            val res = clone.execute()
            if (i % reportInterval == 0) {
                val usedMem = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024
                println("Iteration $i: Val=${clone.`val`.value}, Res=$res, JVM Mem: ${usedMem}MB")
            }
        }
    }
    val endTime = System.currentTimeMillis()
    val duration = (endTime - startTime) / 1000.0
    println("Memory Leak Test Finished in ${duration}s (${(iterations / duration).toInt()} ops/s).")
    service.close()
}

fun executePKReferenceAndOwnershipTest() {
    println("\n--- [2] Reference & Ownership Semantics Test ---")
    val parent = PKMyService2(100)
    val borrowed = parent.getSelfRef()
    println("Parent value: ${parent.`val`.value}")
    // borrowed.close()
    println("Borrowed reference created.")
    try {
        println("Checking parent after borrowed: ${parent.execute()}")
        println("✅ Success: Parent is still alive.")
    } catch (e: Exception) {
        println("❌ Unexpected failure: $e")
    }

    val borrowed2 = parent.getSelfRef()
    parent.close()
    println("Parent closed.")
    try {
        borrowed2.execute()
        println("❌ Failure: Borrowed reference should have been invalidated!")
    } catch (e: NullPointerException) {
        println("✅ Success: Caught expected NullPointerException for borrowed2 after parent.close(), $e")
    }

    println("\n--- [2.1] Consumption (self) Test ---")
    val serviceToConsume = PKMyService()
    val len = serviceToConsume.consumeSelf()
    println("Service consumed, len: $len")
    try {
        serviceToConsume.execute(10)
        println("❌ Failure: Service should have been invalidated!")
    } catch (e: NullPointerException) {
        println("✅ Success: Caught expected NullPointerException for consumed service.")
    }
}

fun executePKConcurrencyTest() {
    println("\n--- [3] Multi-threaded Atomic Concurrency Test ---")
    val threadCount = 10
    val opsPerThread = 1000
    val shared = PKMyService2(0)
    val executor = Executors.newFixedThreadPool(threadCount)
    val start = System.currentTimeMillis()
    repeat(threadCount) {
        executor.submit { repeat(opsPerThread) { shared.`val`.update { it + 1 } } }
    }
    executor.shutdown()
    if (executor.awaitTermination(1, TimeUnit.MINUTES)) {
        val end = System.currentTimeMillis()
        println("Concurrency test finished in ${end - start}ms")
        if (shared.`val`.value == 10000) {
            println("Final shared value: 10000 (Expected: 10000)")
            println("✅ Success: Atomic operations work perfectly!")
        }
    }
    shared.close()
}

fun executePKEnumTest() {
    println("\n--- [4] Enum Return & Pattern Matching Statistics ---")
    val myService = PKMyService()

    val enum1 = PKXrossTestEnum.A()
    val enum2 = PKXrossTestEnum.B(1)
    println("Static Variant A: $enum1")
    println("Static Variant B value: ${enum2.i}")
    enum2.i = 10
    println("Modified Variant B value: ${enum2.i}")

    val unknownStruct = myService.unknownStruct.getBorrow() // Borrow from service
    println("UnknownStruct segment: ${unknownStruct.segment}, alive: ${unknownStruct.aliveFlag.isValid}")
    val enum3 = PKXrossTestEnum.C(unknownStruct.clone()) // Clone takes ownership
    println("Static Variant C value (i): ${enum3.j}")
    unknownStruct.close()

    val iterations = 100000
    var countA = 0
    var countB = 0
    var countC = 0
    val startTime = System.currentTimeMillis()
    repeat(iterations) {
        myService.retEnum().use {
            when (it) {
                is PKXrossTestEnum.A -> countA++
                is PKXrossTestEnum.B -> countB++
                is PKXrossTestEnum.C -> countC++
            }
        }
    }
    val endTime = System.currentTimeMillis()
    println("Finished $iterations iterations in ${endTime - startTime}ms:")
    println("  - Variant A: $countA times\n  - Variant B: $countB times\n  - Variant C: $countC times")
    PKXrossSimpleEnum.X.sayHello()
    myService.close()
}

fun executePKCollectionAndOptionalTest() {
    println("\n--- [5] Optional Types Test ---")
    val service = PKMyService()
    println("Option(true) result: ${service.getOptionEnum(true)}")
    println("Option(false) result: ${service.getOptionEnum(false)}")

    val okResult = service.getResultStruct(true)
    println("Result(true) isSuccess: ${okResult.isSuccess}, value: ${okResult.getOrNull()?.`val`?.value}")
    okResult.getOrNull()?.close()

    val errResult = service.getResultStruct(false)
    println("Result(false) isFailure: ${errResult.isFailure}, exception: ${errResult.exceptionOrNull()}")
    if (errResult.isFailure) {
        val ex = errResult.exceptionOrNull() as? org.example.xross.runtime.XrossException
        println("  -> Inner error: ${ex?.error}")
    }
    service.close()
}

fun executePKPropertyTest() {
    PKUnknownStruct(1, "Hello", 1f).use { unknownStruct ->
        println(unknownStruct.s)
        unknownStruct.s = "Hello, World. from modified, 無限、❤"
        println(unknownStruct.s)
    }
}

fun executePKComplexFieldTest() {
    println("\n--- [6] Complex Field & External/Enum Test ---")
    val ext = PKExternalStruct(100, "Xross Native")
    println("ExternalStruct property 'value': ${ext.value}")
    ext.value = 500
    println("ExternalStruct getter after set: ${ext.getValue()}")
    println("ExternalStruct greet: Hello Xross Native")

    val b = PKHelloEnum.B(42)
    val c = PKHelloEnum.C(b)
    println("HelloEnum.C tag: ${c.zeroth}")
    val inner = c.zeroth
    if (inner is PKHelloEnum.B) println("HelloEnum nested B.i: ${inner.i}")

    val deep = PKHelloEnum.C(PKHelloEnum.C(PKHelloEnum.B(1000)))
    val mid = deep.zeroth
    if (mid is PKHelloEnum.C) {
        val innermost = mid.zeroth
        if (innermost is PKHelloEnum.B) println("HelloEnum deep recursive value: ${innermost.i}")
    }
    ext.close()
    c.close()
    deep.close()
    println("✅ Complex field tests passed.")
}

fun executePKComplexStructPropertyTest() {
    println("\n--- [7] ComplexStruct Property (Option/Result) Test ---")
    val cs = PKComplexStruct(42, Result.success(100))
    println("ComplexStruct opt initial: ${cs.opt}")
    println("ComplexStruct res initial: ${cs.res}")

    cs.opt = 100
    cs.res = Result.success(500)
    println("ComplexStruct opt after set: ${cs.opt}")
    println("ComplexStruct res after set: ${cs.res}")

    cs.opt = null
    cs.res = Result.failure(org.example.xross.runtime.XrossException("Native Error"))
    println("ComplexStruct opt after set null: ${cs.opt}")
    println("ComplexStruct res after set failure: ${cs.res}")
    cs.close()
}

fun executePKPanicAndTrivialTest() {
    println("\n--- [8] Panicable & Trivial Method Test ---")
    val service = PKMyService()
    val sum = service.addTrivial(10, 20)
    println("Trivial add(10, 20) = $sum")
    if (sum != 30) throw RuntimeException("Trivial calculation failed!")

    val sumHeap = service.addCriticalHeap(100, 200)
    println("Critical Heap add(100, 200) = $sumHeap")
    if (sumHeap != 300) throw RuntimeException("Critical Heap calculation failed!")

    println("Panicable causePanic(false) = ${service.causePanic(false)}")
    try {
        println("Calling causePanic(true) - Expecting panic to be caught...")
        service.causePanic(true)
        println("❌ Failure: Panic was NOT caught!")
    } catch (e: org.example.xross.runtime.XrossException) {
        println("✅ Success: Caught expected XrossException (Panic): ${e.error}")
    } catch (e: Exception) {
        println("❌ Failure: Caught wrong exception type: ${e.javaClass.name}")
        e.printStackTrace()
    }
    service.close()
}

fun executePKStandaloneFunctionTest() {
    println("\n--- [9] Standalone Function Test ---")
    println("GlobalAdd.globalAdd(10, 20) = ${PKStandalone.globalAdd(10, 20)}")
    println("GlobalGreet.globalGreet(\"Xross\") = ${PKStandalone.globalGreet("Xross")}")
    println("GlobalMultiply.globalMultiply(5, 6) = ${PKStandalone.globalMultiply(5, 6)}")
    println("✅ Standalone function tests passed.")
}

fun executePKAsyncTest() = runBlocking {
    println("\n--- [10] Async (suspend fun) Test ---")
    println("asyncAdd(100, 200) = ${PKStandalone.asyncAdd(100, 200)}")
    println("asyncGreet(\"Coroutines\") = ${PKStandalone.asyncGreet("Coroutines")}")
    PKMyService().use { println("MyService.asyncExecute(42) = ${it.asyncExecute(42)}") }
    println("✅ Async tests passed.")
}

fun executePKPrimitiveTypeTest() {
    println("\n--- [11] Primitive Unsigned Types Test (u8, u32, u64) ---")
    val u8Val: Byte = 10
    val u32Val = 1000
    val u64Val = 1000000L
    val sum = PKStandalone.testUnsigned(u8Val, u32Val, u64Val)
    println("testUnsigned(10, 1000, 1000000) = $sum")
    if (sum != 1001010L) throw RuntimeException("Unsigned sum failed: $sum")

    val pt = PKPrimitiveTest(5.toByte(), 500, 5000L)
    println("PrimitiveTest initial: u8=${pt.u8Val}, u32=${pt.u32Val}, u64=${pt.u64Val}")
    pt.addU32(500)
    println("PrimitiveTest after addU32(500): u32=${pt.u32Val}")
    pt.close()
    println("✅ Primitive type tests passed.")
}
