# Project Xross (2.0.1)

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Version](https://img.shields.io/badge/version-2.0.1-blue.svg)](https://github.com/the-infinitys/xross)

**Xross** (クロス) は、Rust と JVM (Kotlin/Java) の境界を消滅させるために設計された、高性能・メモリ安全なクロス言語フレームワークです。

Java 25 で標準化された **Project Panama (Foreign Function & Memory API)** を最大限に活用し、従来の JNI (Java Native Interface) が抱えていたパフォーマンスの限界と開発の複雑さを根本から解決します。

## 🚀 主な特徴

*   **⚡️ 極限のパフォーマンス**: MethodHandle とインラインメモリアクセスにより、JNI 特有のオーバーヘッドを排除。ネイティブ呼び出しのコストを最小化します。
*   **🛡️ Rust の安全性を JVM へ**: Rust の所有権モデル（Owned, Ref, MutRef）をメタデータとして抽出し、Kotlin 側のライフサイクル管理と型システムに統合。
*   **🛠️ 完全自動バインディング**: Rust のコードにアノテーションを付けるだけで、スレッドセーフで慣習的な Kotlin コードが自動生成されます。
*   **🔒 強固なスレッド安全性**: データの性質に合わせて `StampedLock`, `VarHandle`, `Atomic` 等の同期機構を自動選択し、データ競合を防ぎます。
*   **🌐 非同期処理の統合 (Async/Await)**: Rust の `Future` と Kotlin の `Coroutines` をシームレスにブリッジ。ネイティブの非同期ロジックを `suspend` 関数として呼び出せます。
*   **💎 高度な型サポート**: 構造体はもちろん、Rust 特有の列挙型 (Algebraic Data Types) や不透明型 (Opaque Types) もシームレスに扱えます。

## 🏗️ アーキテクチャ

Xross は以下のコンポーネントで構成されています。詳細な仕様については [ARCHITECTURE.md](./ARCHITECTURE.md) を参照してください。

*   `xross-core`: Rust 側のランタイム基盤とアノテーション。
*   `xross-macros`: プロキシコード生成のための手続き型マクロ。
*   `xross-plugin`: Kotlin バインディングを生成する強力な Gradle プラグイン。
*   `xross-metadata`: 言語間で共有される高精度な型定義スキーマ。

## 📦 インストールと利用方法

### 1. Gradle プラグインの導入

プロジェクトの状況に合わせて、以下のいずれかの方法でプラグインを導入できます。

#### A. GitHub レポジトリを直接使用 (Composite Build - 推奨)
開発中の最新バージョンを試す場合や、Xross 自体の開発に携わる場合に最適です。

1.  `xross` レポジトリをクローンします。
2.  自分のプロジェクトの `settings.gradle.kts` に以下を追記します。
    ```kotlin
    pluginManagement {
        includeBuild("../path/to/xross/xross-plugin")
    }
    ```
3.  `build.gradle.kts` で適用します。
    ```kotlin
    plugins {
        id("org.xross")
    }
    ```

#### B. JitPack を使用 (リモートから直接)
クローンなしで GitHub の `main` ブランチをそのまま利用できます。

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
        gradlePluginPortal()
    }
}

// build.gradle.kts
buildscript {
    repositories { maven { url = uri("https://jitpack.io") } }
    dependencies { classpath("com.github.the-infinitys:xross:2.0.1") }
}
apply(plugin = "org.xross")
```

### 2. Rust 側の設定 (`Cargo.toml`)

```toml
[dependencies]
xross-core = "2.0.1"
```

## 🛠️ Rust と Kotlin の対応関係

Xross は Rust の型定義を解析し、最適な Kotlin コードを生成します。

| Rust コード | 生成される Kotlin コード | 特徴 |
| :--- | :--- | :--- |
| `#[derive(XrossClass)] struct S` | `class S : AutoCloseable` | ネイティブメモリを管理するクラス |
| `#[xross_new] fn new() -> Self` | `constructor(...)` | Rust のインスタンスを生成 |
| `&self` / `&mut self` | 普通のメソッド | スレッド安全性が自動的に付与される |
| `async fn foo()` | `suspend fun foo()` | Coroutines 統合された非同期関数 |
| `self` (所有権消費) | `fun consume()...` | 呼び出し後に Kotlin 側でも無効化される |
| `Option<T>` | `T?` (Nullable) | `null` を使った自然な表現 |
| `Result<T, E>` | `Result<T>` | 例外を内包した標準の Result 型 |

### 実際の変換例

**Rust:**
```rust
#[xross_class]
impl MyService {
    #[xross_method]
    pub async fn process(&self, input: String) -> Result<String, String> {
        tokio::time::sleep(Duration::from_millis(10)).await;
        Ok(format!("Processed: {}", input))
    }
}
```

**Kotlin (生成後):**
```kotlin
val result: Result<String> = runBlocking {
    service.process("hello")
}
```

## 🔍 開発者向けノート: 内部実装の仕組み

Xross は内部的に以下のような `extern "C"` 関数を自動生成し、Java 25 の FFM API (MethodHandle) を介して呼び出します。

### シンボル命名規則
生成されるシンボルは以下の形式になります：
`{crate}_{package}_{type}_{method}`

例: `my_lib_com_example_MyService_process`

### 自動生成される共通関数
すべての `XrossClass` に対して、以下の管理用関数が生成されます：
- `_drop`: `Box::from_raw` を呼び出し、Rust 側のメモリを解放します。
- `_size`: 型の `size_of` を返し、Kotlin 側の `MemorySegment` 割り当てに使用されます。
- `_clone`: `Clone` トレイトが実装されている場合、新しいインスタンスをヒープに作成します。

### 高度な拡張
自分で特定の関数を FFM API から直接呼び出したい場合は、これらの命名規則に従って `SymbolLookup` を行うことで、Xross が管理するオブジェクトと相互運用することが可能です。

## 💡 使用方法

### 1. Rust でロジックを記述

```rust
use xross_core::{XrossClass, xross_class};

#[derive(XrossClass)]
pub struct Calculator {
    #[xross_field(safety = Atomic)]
    pub value: i32,
}

#[xross_class]
impl Calculator {
    #[xross_new]
    pub fn new(value: i32) -> Self {
        Self { value }
    }

    #[xross_method]
    pub fn add(&mut self, amount: i32) {
        self.value += amount;
    }
}
```

### 2. Gradle タスクを実行

```bash
./gradlew generateXrossBindings
```

### 3. Kotlin から利用

```kotlin
import com.example.generated.Calculator

fun main() {
    // Rust 側のインスタンスを安全に生成 (AutoCloseable により自動解放)
    Calculator(10).use { calc ->
        // アトミックな更新
        calc.value.update { it + 5 }
        println("Result: ${calc.value.value}") // 15
    }
}
```

## 🔥 高度な機能

### 🌐 Async/Await 統合
Rust 側の `async fn` は、Kotlin 側では `suspend` 関数として生成されます。内部的には Rust の `Future` をポーリングし、完了時に Coroutine を再開する効率的なブリッジが構築されます。

### 🧵 スレッド安全性 (Thread Safety)
Xross は Rust の借用チェッカーの概念を Kotlin に持ち込みます。
- **Atomic**: `VarHandle` による CAS 操作を提供。
- **Lock**: `StampedLock` を使用し、不変参照には楽観的読み取りを、可変参照には排他ロックを適用します。

### 🧬 代数的データ型 (ADTs)
Rust の `enum` は Kotlin の `sealed class` として生成され、`when` 式による安全なパターンマッチングが可能です。

### 🔍 スタンドアロン関数
`#[xross_function]` を使用することで、クラスに属さないグローバルな関数もバインディング可能です。

### 🔎 不透明型 (Opaque Types)
`#[xross_core::opaque_class]` を使用することで、Rust 側の詳細を隠蔽したまま Kotlin へポインタを安全に渡すことができます。

## 🛡️ ベストプラクティス

1.  **所有権の意識**: `Owned` として返されたオブジェクトは必ず `use` ブロックまたは `close()` で解放してください。
2.  **パッケージ管理**: `#[xross_package("com.example")]` を活用して、Kotlin 側のパッケージ構成を整理しましょう。
3.  **エラーハンドリング**: Rust 側の `Result<T, E>` は Kotlin の `Result<T>` に変換されます。適切に `onFailure` 等で例外処理を行ってください。
4.  **アロケーションを最小化せよ**: Native 側で頻繁にメモリを確保・解放すると、JVM のメモリ管理（TLAB）の方が速い場合があります。
5.  **キャッシュを意識せよ**: データを平坦化し、CPU が先読みしやすいメモリアクセスを行うことで、Native の真価が出ます。
6.  **1 回の処理を重くせよ**: Rust 側での実行時間が十分長ければ、FFI 境界のオーバーヘッドは誤差の範囲になります。

## ⚠️ 必要条件と実行時設定

*   **Rust**: 1.80+ (Edition 2024 推奨)
*   **Java**: 25+ (Project Panama / FFM API)
*   **Gradle**: 8.0+

実行時には、FFM API へのアクセスを許可するために以下の JVM 引数が必要です。

```bash
--enable-native-access=ALL-UNNAMED
```

## 📜 ライセンス

このプロジェクトは MIT ライセンスの下で公開されています。
