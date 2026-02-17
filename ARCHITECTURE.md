# Xross Architecture Specification (v3.0.1)

Xross は、Rust と JVM 間の究極の相互運用性を目指したフレームワークです。Java 25 で導入された **Foreign Function & Memory (FFM) API** を基盤とし、Rust の所有権セマンティクスを Java/Kotlin へ安全に持ち込むための高度な抽象化を提供します。

---

## 1. コア・デザイン・フィロソフィー

### 1.1 Zero-Overhead Bridge
JNI のような中間レイヤーや複雑な変換コードを最小化し、MethodHandle と MemorySegment を通じた直接的なネイティブアクセスを実現します。

### 1.2 Ownership-Aware Interop
Rust の最大の特徴である所有権（Ownership）と借用（Borrowing）の概念を、Kotlin の型システムとライフサイクル管理（AutoCloseable）にマッピングします。

### 1.3 Thread Safety by Design
ネイティブメモリへのアクセスに対し、Rust 側の型情報に基づいた適切な同期メカニズムを自動的に付与します。v3.0.1 では、オーバーヘッドをゼロにする `Direct` モードが導入されました。

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
| `String` | `String` | `XrossString` (Struct) |

### 3.2 文字列 (High-Performance String Bridge)
v3.0.1 では、String の受け渡しが大幅に最適化されました。

- **Rust -> JVM**: `String` は `XrossString` 構造体（ptr, len, cap）として値返しされます。Kotlin 側では `MemorySegment.reinterpret(len)` を使用して直接メモリを読み取るため、UTF-8 デコード以外の余分なコピーは発生しません。
- **JVM -> Rust**: JVM 内部の `byte[]`（Latin1 または UTF-16）を直接参照する `XrossStringView` を使用します。`critical(heap_access)` モードが有効な場合、ヒープ上の配列を直接ネイティブに渡す「真のゼロコピー」が実現されます。

---

## 4. メモリ管理モデル

Xross は `java.lang.foreign.Arena` を活用してメモリのライフサイクルを管理します。

### 4.1 所有権のマッピング

- **Owned (`T`)**: 
    - Rust 側から「所有権付き」で返されたオブジェクト。
    - ライフサイクル管理用に `XrossRuntime.ofSmart()` による共有 Arena が割り当てられ、GC 時に Cleaner によって自動解放されるか、`close()` によって明示的に解放されます。
- **External Arena Support**: v3.0.1 では、コンストラクタに外部 `Arena` を渡すことで、Cleaner への自動登録をスキップし、ユーザーが完全にライフサイクルを制御できるようになりました。
- **DSL (Companion use)**: `MyStruct.use { ... }` 形式のコンストラクタをサポート。ブロック終了時に確実に `drop` が実行されます。

---

## 5. スレッド安全性 (Thread Safety)

v3.0.1 では、最高速を実現するための `Direct` レベルが追加されました。

1. **Direct**: **[New]** 同期チェックを一切行わず、生メモリへの直接アクセスを生成します。最も高速ですが、マルチスレッド環境での安全性はユーザーが保証する必要があります。
2. **Unsafe**: 同期なし。生成される Kotlin 側のプロパティアクセサにおいてロックチェックをスキップします。
3. **Lock**: `java.util.concurrent.locks.StampedLock` を使用した安全な並行アクセス。
4. **Atomic**: `java.lang.invoke.VarHandle` を使用した CAS 操作。
5. **Immutable**: 不変であることを保証。同期コストなしでスレッド間共有が可能。

**注意**: `Unsafe` または `Direct` モードを使用する場合、Rust 側で `unsafe` アトリビュートを明示的に付与することが必須となりました。
例: `#[xross_field(safety = Direct, unsafe)]`

---

## 6. ビルドプロセス

1. **Rust Analysis**: `xross-macros` がコードを解析。環境変数 `XROSS_METADATA_DIR` で指定された場所に JSON メタデータを出力。
2. **Gradle Task**: `xross-plugin` が JSON を読み込み、KotlinPoet を使用してバインディングクラスを生成。
3. **Linking**: 実行時に Panama の `SymbolLookup` を使用して、ネイティブシンボルを直接解決します。

---

## 7. パフォーマンス最適化の原則

### 7.1 Heap Access Optimizations
`critical(heap_access)` 属性を使用することで、JVM ヒープ上のデータをコピーせずにネイティブ側へ直接公開できます。これは大量のデータ転送や文字列処理において劇的な効果を発揮します。

### 7.2 Flattening Argument passing
v3.0.1 以降、`String` 引数は内部的に `(pointer, length, encoding)` のフラットな引数として渡されます。これにより FFM リンカーがレジスタを最大限に活用でき、スタック操作のオーバーヘッドが軽減されます。
