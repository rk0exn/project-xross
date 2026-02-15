import java.lang.foreign.Arena
fun main() {
    val arena = Arena.ofGlobal()
    println("Arena: $arena")
}
