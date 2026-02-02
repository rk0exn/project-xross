use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Debug, Clone)]
pub enum XrossMethodType {
    /// staticな関数 (selfを取らない)
    Static,
    /// &self (不変参照)
    ConstInstance,
    /// &mut self (可変参照)
    MutInstance,
    /// self (所有権を消費する。呼んだ後はJava側のハンドルを無効化する必要がある)
    OwnedInstance,
}

