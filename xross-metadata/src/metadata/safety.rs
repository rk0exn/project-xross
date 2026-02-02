use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Debug, Clone, Copy)]
pub enum ThreadSafety {
    /// 1. 全くの無防備。
    /// JVM側でのLockを一切行わず、生ポインタに近い速度でアクセスする。
    /// シングルスレッド専用、または外部で別のロック制御が必要。
    Unsafe,

    /// 2. 読み書き排他（現在のデフォルト）。
    /// Kotlin側で ReentrantReadWriteLock を使用する。
    /// 複数の不変参照(&)は通すが、可変参照(&mut)は一人だけ。
    Lock,

    /// 3. アトミック操作（特定のプリミティブのみ）。
    /// ロックを介さず、CPU命令（CAS: Compare-And-Swap）で同期する。
    /// += 1 などの操作が、他のスレッドを止めずにアトミックに完了する。
    Atomic,

    /// 4. 不変・フリーズ（最強の安全）。
    /// 生成後に一度だけ値をセットし、以降は読み取り専用。
    /// Rust側の Arc<T> のような共有定数データに最適。
    Immutable,
}
