package org.xross

import kotlinx.serialization.Serializable

@Serializable
enum class XrossMethodType {
    /** selfを取らない (Javaでは static メソッド) */
    Static,
    /** &self (Javaでは通常のインスタンスメソッド) */
    ConstInstance,
    /** &mut self (Javaでは通常のインスタンスメソッド) */
    MutInstance,
    /** self (所有権を消費。Java側では呼び出し後にインスタンスを無効化すべき) */
    OwnedInstance
}
