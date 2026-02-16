package org.example

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
        return "Active Objects - S1: $s1, S2: $s2, U: $u"
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

    fun sayHello() {}
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
        delay(1)
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

    fun causePanic(shouldPanic: Byte): String {
        if (shouldPanic != (0).toByte()) throw RuntimeException("Panic!")
        return "No panic"
    }

    fun execute(i: Int): Int {
        checkAlive()
        return i
    }

    fun getOptionEnum(shouldSome: Boolean): PKXrossSimpleEnum? = if (shouldSome) PKXrossSimpleEnum.V else null
    fun getResultStruct(shouldOk: Boolean): Result<PKMyService2> = if (shouldOk) Result.success(PKMyService2(1)) else Result.failure(RuntimeException("Error"))

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
        if (isClosed) throw NullPointerException("Invalid")
        return atomicValue.get().toDouble()
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

object PKStandalone {
    fun globalAdd(a: Int, b: Int): Int = a + b
    fun globalGreet(name: String): String = "Hello, $name!"
    fun globalMultiply(a: Int, b: Int): Int = a * b
    fun testUnsigned(a: Byte, b: Int, c: Long): Long = (a.toInt() and 0xFF).toLong() + (b.toLong() and 0xFFFFFFFFL) + c
    suspend fun asyncAdd(a: Int, b: Int): Int {
        delay(1)
        return a + b
    }
    suspend fun asyncGreet(name: String): String {
        delay(1)
        return "Hello, $name!"
    }
}

// --- 統合テスト用関数群 (PK版, silentパラメータ付き) ---

fun executePKPrimitiveTypeTest(silent: Boolean = false) {
    val pt = PKPrimitiveTest(5, 500, 5000L)
    pt.addU32(500)
    if (!silent) println("PK Primitive u32: ${pt.u32Val}")
    pt.close()
}

fun executePKReferenceAndOwnershipTest(silent: Boolean = false) {
    val parent = PKMyService2(100)
    val borrowed = parent.getSelfRef()
    if (!silent) println("PK Reference value: ${borrowed.`val`.value}")
    parent.close()
}

fun executePKConcurrencyTest(silent: Boolean = false) {
    val shared = PKMyService2(0)
    shared.`val`.update { it + 1 }
    if (!silent) println("PK Concurrency value: ${shared.`val`.value}")
    shared.close()
}

fun executePKEnumTest(silent: Boolean = false) {
    val myService = PKMyService()
    val e = myService.retEnum()
    if (!silent) println("PK Enum result: $e")
    e.close()
    myService.close()
}

fun executePKCollectionAndOptionalTest(silent: Boolean = false) {
    val service = PKMyService()
    val opt = service.getOptionEnum(true)
    if (!silent) println("PK Option: $opt")
    service.getResultStruct(true).getOrNull()?.close()
    service.close()
}

fun executePKPropertyTest(silent: Boolean = false) {
    val unknownStruct = PKUnknownStruct(1, "Hello", 1f)
    unknownStruct.s = "Modified"
    if (!silent) println("PK Struct string: ${unknownStruct.s}")
    unknownStruct.close()
}

fun executePKComplexFieldTest(silent: Boolean = false) {
    val ext = PKExternalStruct(100, "Xross Native")
    ext.value = 500
    ext.getValue()
    val greet = ext.greet("Hello")
    if (!silent) println("PK Greet: $greet")
    ext.close()
}

fun executePKComplexStructPropertyTest(silent: Boolean = false) {
    val cs = PKComplexStruct(42, Result.success(100))
    cs.opt = 100
    cs.res = Result.success(500)
    if (!silent) println("PK ComplexStruct res: ${cs.res}")
    cs.close()
}

fun executePKPanicAndTrivialTest(silent: Boolean = false) {
    val service = PKMyService()
    val sum = service.addTrivial(10, 20)
    if (!silent) println("PK Trivial add: $sum")
    service.addCriticalHeap(100, 200)
    try {
        service.causePanic(0.toByte())
    } catch (e: Exception) {}
    service.close()
}

fun executePKStandaloneFunctionTest(silent: Boolean = false) {
    val res = PKStandalone.globalAdd(10, 20)
    if (!silent) println("PK Global add: $res")
    PKStandalone.globalGreet("Xross")
    PKStandalone.globalMultiply(5, 6)
}

fun executePKAsyncTest(silent: Boolean = false) = runBlocking {
    val res = PKStandalone.asyncAdd(100, 200)
    if (!silent) println("PK Async add: $res")
    PKStandalone.asyncGreet("Coroutines")
}
