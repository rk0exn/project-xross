package org.example

import java.lang.foreign.Arena

fun main() {
    // 1. 最初の一回だけ実行（DLL/SOのロードとシンボル解決
    // 2. Panama APIのメモリ管理スコープ
    Arena.ofConfined().use { arena ->
        val hello = HelloStruct.new(arena, 10, 20)
    }
}