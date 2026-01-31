package org.example

import org.xross.LibRust // クレート名に応じたLibオブジェクト
import org.xross.HelloStruct
import java.lang.foreign.Arena

fun main() {
    // 1. 最初の一回だけ実行（DLL/SOのロードとシンボル解決）
    LibRust.init()

    // 2. Panama APIのメモリ管理スコープ
    Arena.ofConfined().use { arena ->
        // Rust: HelloStruct::new(10, 20.5) を呼び出す
        // Kotlin 側では第1引数に arena を渡す設計にしています
        val hello = HelloStruct.new(arena, 10, 20.5)

        // Rust: hello.add() を呼び出す
        val result = hello.add(arena)

        println("Result from Rust: $result") // 30.5
    }
}