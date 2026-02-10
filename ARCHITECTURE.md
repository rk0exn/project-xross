# Xross Architecture Specification (v1.0.0)

Xross は、Rust と JVM 間の究極の相互運用性を目指したフレームワークです。Java 25 で導入された **Foreign Function & Memory (FFM) API** を基盤とし、Rust の所有権セマンティクスを Java/Kotlin へ安全に持ち込むための高度な抽象化を提供します。

---

## 1. コア・デザイン・フィロソフィー

### 1.1 Zero-Overhead Bridge
JNI のような中間レイヤーや複雑な変換コードを最小化し、MethodHandle と MemorySegment を通じた直接的なネイティブアクセスを実現します。

### 1.2 Ownership-Aware Interop
Rust の最大の特徴である所有権（Ownership）と借用（Borrowing）の概念を、Kotlin の型システムとライフサイクル管理（AutoCloseable）にマッピングします。

### 1.3 Thread Safety by Design
ネイティブメモリへのアクセスに対し、Rust 側の型情報に基づいた適切な同期メカニズム（Lock, Atomic, Immutable）を自動的に付与します。

---

## 2. 技術スタック

- **Java/Kotlin**: Java 25+ (FFM API / Project Panama)
- **Rust**: Edition 2024
- **Build System**: Gradle + Cargo
- **Code Generation**: KotlinPoet + `syn`/`quote` (Proc-macros)
- **Serialization**: Serde (Metadata exchange)

---

## 3. 型システムとマッピング

### 3.1 プリミティブ
Rust の基本型は FFM API の `ValueLayout` を通じて直接マッピングされます。

| Rust Type | Kotlin Type | ValueLayout |
| :--- | :--- | :--- |
| `i8` / `u8` | `Byte` | `JAVA_BYTE` |
| `i32` | `Int` | `JAVA_INT` |
| `i64` / `isize` | `Long` | `JAVA_LONG` |
| `bool` | `Boolean` | `JAVA_BYTE` (0/1) |
| `f32` | `Float` | `JAVA_FLOAT` |
| `f64` | `Double` | `JAVA_DOUBLE` |
| `String` | `String` | `ADDRESS` (UTF-8) |

### 3.2 複合型

#### 構造体 (Struct)
`#[derive(XrossClass)]` を付与された Rust 構造体は、Kotlin のクラスとして生成されます。
- **インラインフィールド**: 構造体内に直接配置されるデータ。
- **ポインタフィールド**: 他のオブジェクトへの参照。

#### 列挙型 (Enum / ADT)
Rust の列挙型は、Kotlin の `sealed class`（データを持つ場合）または `enum class`（純粋な列挙型の場合）に変換されます。

#### 不透明型 (Opaque Types)
内部構造を JVM 側に露出させず、ポインタとしてのみ管理する型です。

---

## 4. メモリ管理モデル

Xross は `java.lang.foreign.Arena` を活用してメモリのライフサイクルを管理します。

### 4.1 所有権のマッピング

- **Owned (`T`)**: 
    - Rust 側から「所有権付き」で返されたオブジェクト。
    - Kotlin 側で `Arena.ofConfined()` が生成され、`close()` 時に Rust 側の `drop` 関数が呼ばれます。
- **Ref (`&T`)**:
    - 他のオブジェクトから借用された不変参照。
    - 親オブジェクトの `AliveFlag` を共有し、親が解放された後のアクセスは `NullPointerException` をスローします。
- **MutRef (`&mut T`)**:
    - 可変参照。Kotlin 側で書き込み操作が許可されます。

### 4.2 文字列の扱い
Rust の `String` はヒープ確保されるため、呼び出しごとに `Arena` を通じてコピーまたは確保が行われます。戻り値としての `String` は、Xross が提供する専用の Free 関数によって解放されます。

---

## 5. スレッド安全性 (Thread Safety)

メタデータとして定義された `ThreadSafety` レベルに基づき、生成されるコードの挙動が変わります。

1. **Unsafe**: 同期なし。最高速ですが、スレッド間での共有はユーザーの責任となります。
2. **Lock**: `java.util.concurrent.locks.StampedLock` を使用。
    - `&T` (Ref) によるアクセスは楽観的読み取りまたは共有ロック。
    - `&mut T` (MutRef) または Owned によるアクセスは排他ロック。
3. **Atomic**: `java.lang.invoke.VarHandle` を使用した CAS 操作。
4. **Immutable**: 初期化後は不変であることを保証。同期コストなしでスレッド間共有が可能。

---

## 6. ビルドプロセス

1. **Rust Analysis**: `xross-macros` がコードを解析し、`xross-metadata` 形式の JSON を出力。
2. **Gradle Task**: `xross-plugin` が JSON を読み込み、KotlinPoet を使用してバインディングクラスを生成。
3. **Linking**: 実行時に `System.loadLibrary`（または Panama の `SymbolLookup`）を使用して Rust 側のシンボルを解決。

---

## 7. 今後の展望

- **Async/Await**: Rust の `Future` と Kotlin の `Coroutines` の統合。
- **Callback**: JVM 側の関数を Rust 側から呼び出すための Upcall サポート。
- **Collection**: `Vec<T>` と `List<T>` のシームレスな共有。
