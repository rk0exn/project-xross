# Project Xross (2.0.1)

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Version](https://img.shields.io/badge/version-2.0.1-blue.svg)](https://github.com/the-infinitys/xross)

**Xross** (Cross) is a high-performance, memory-safe cross-language framework designed to dissolve the boundary between Rust and JVM (Kotlin/Java).

By leveraging **Project Panama (Foreign Function & Memory API)**, which is standardized in Java 25, it fundamentally resolves the performance limits and development complexity inherent in traditional JNI (Java Native Interface).

## 🚀 Key Features

*   **⚡️ Extreme Performance**: Eliminates JNI-specific overhead via `MethodHandle` and inline memory access. Minimizes the cost of native calls.
*   **🛡️ Rust Safety for JVM**: Extracts Rust's ownership model (Owned, Ref, MutRef) as metadata and integrates it into Kotlin's lifecycle management and type system.
*   **🛠️ Fully Automated Bindings**: Simply annotate your Rust code, and thread-safe, idiomatic Kotlin code is automatically generated.
*   **🔒 Robust Thread Safety**: Automatically selects synchronization mechanisms such as `StampedLock`, `VarHandle`, or `Atomic` based on data nature to prevent data races.
*   **🌐 Async/Await Integration**: Seamlessly bridges Rust's `Future` and Kotlin's `Coroutines`. Native async logic can be called as `suspend` functions.
*   **💎 Advanced Type Support**: Handles structs, Rust-specific enums (Algebraic Data Types), and opaque types seamlessly.

## 🏗️ Architecture

Xross consists of the following components. See [ARCHITECTURE.md](./ARCHITECTURE.md) for detailed specifications.

*   `xross-core`: Rust-side runtime foundation and annotations.
*   `xross-macros`: Procedural macros for proxy code generation.
*   `xross-plugin`: A powerful Gradle plugin that generates Kotlin bindings.
*   `xross-metadata`: High-precision type definition schema shared across languages.

## 📦 Installation and Usage

### 1. Introducing the Gradle Plugin

You can introduce the plugin in several ways depending on your project.

#### A. Direct Use from GitHub Repository (Composite Build - Recommended)
Best for trying out the latest version during development or contributing to Xross itself.

1.  Clone the `xross` repository.
2.  Add the following to your project's `settings.gradle.kts`:
    ```kotlin
    pluginManagement {
        includeBuild("../path/to/xross/xross-plugin")
    }
    ```
3.  Apply it in `build.gradle.kts`:
    ```kotlin
    plugins {
        id("org.xross")
    }
    ```

#### B. Using JitPack (Remote)
Use the `main` branch directly from GitHub without cloning.

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

### 2. Rust-side Configuration (`Cargo.toml`)

```toml
[dependencies]
xross-core = "2.0.1"
```

## 🛠️ Rust and Kotlin Mapping

Xross analyzes Rust type definitions and generates optimal Kotlin code.

| Rust Code | Generated Kotlin Code | Characteristics |
| :--- | :--- | :--- |
| `#[derive(XrossClass)] struct S` | `class S : AutoCloseable` | Class managing native memory |
| `#[xross_new] fn new() -> Self` | `constructor(...)` | Creates a Rust instance |
| `&self` / `&mut self` | Ordinary methods | Thread safety automatically applied |
| `async fn foo()` | `suspend fun foo()` | Async function integrated with Coroutines |
| `self` (Ownership consumption) | `fun consume()...` | Invalidated on Kotlin side after call |
| `Option<T>` | `T?` (Nullable) | Natural expression using `null` |
| `Result<T, E>` | `Result<T>` | Standard Result type containing exceptions |

### Example Conversion

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

**Kotlin (Generated):**
```kotlin
val result: Result<String> = runBlocking {
    service.process("hello")
}
```

## 🔍 Developer Notes: Internal Mechanism

Xross automatically generates `extern "C"` functions internally and calls them via Java 25's FFM API (`MethodHandle`).

### Symbol Naming Convention
Generated symbols follow this format:
`{crate}_{package}_{type}_{method}`

Example: `my_lib_com_example_MyService_process`

### Automatically Generated Common Functions
For every `XrossClass`, the following management functions are generated:
- `_drop`: Calls `Box::from_raw` to release Rust-side memory.
- `_size`: Returns `size_of` of the type, used for `MemorySegment` allocation on the Kotlin side.
- `_clone`: If `Clone` is implemented, creates a new instance on the heap.

### Advanced Extension
If you want to call specific functions directly via the FFM API, you can interoperate with Xross-managed objects by performing a `SymbolLookup` following these naming conventions.

## 💡 Usage

### 1. Write Logic in Rust

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

### 2. Run Gradle Task

```bash
./gradlew generateXrossBindings
```

### 3. Use from Kotlin

```kotlin
import com.example.generated.Calculator

fun main() {
    // Safely create a Rust instance (automatically released via AutoCloseable)
    Calculator(10).use { calc ->
        // Atomic update
        calc.value.update { it + 5 }
        println("Result: ${calc.value.value}") // 15
    }
}
```

## 🔥 Advanced Features

### 🌐 Async/Await Integration
Rust `async fn` is generated as a `suspend` function on the Kotlin side. Internally, it builds an efficient bridge that polls the Rust `Future` and resumes the Coroutine upon completion.

### 🧵 Thread Safety
Xross brings Rust's borrow checker concepts to Kotlin.
- **Atomic**: Provides CAS operations via `VarHandle`.
- **Lock**: Uses `StampedLock` to apply optimistic reads for immutable references and exclusive locks for mutable references.

### 🧬 Algebraic Data Types (ADTs)
Rust `enum` is generated as a Kotlin `sealed class`, allowing safe pattern matching via `when` expressions.

### 🔍 Standalone Functions
Using `#[xross_function]`, you can bind global functions that do not belong to a class.

### 🔎 Opaque Types
Using `#[xross_core::opaque_class]`, you can safely pass pointers to Kotlin while hiding Rust-side details.

## 🛡️ Best Practices

1.  **Ownership Awareness**: Objects returned as `Owned` must be released using a `use` block or by calling `close()`.
2.  **Package Management**: Use `#[xross_package("com.example")]` to organize your Kotlin package structure.
3.  **Error Handling**: Rust's `Result<T, E>` is converted to Kotlin's `Result<T>`. Handle exceptions appropriately using `onFailure`, etc.
4.  **Minimize Allocation**: Frequently allocating/releasing memory on the native side may be slower than JVM's memory management (TLAB) in some cases.
5.  **Be Cache-Aware**: Flatten data and use memory access patterns that are easy for the CPU to prefetch to realize the true value of Native.
6.  **Heavier Processing per Call**: If the execution time on the Rust side is long enough, the overhead of the FFI boundary becomes negligible.

## ⚠️ Requirements and Runtime Settings

*   **Rust**: 1.80+ (Edition 2024 recommended)
*   **Java**: 25+ (Project Panama / FFM API)
*   **Gradle**: 8.0+

At runtime, the following JVM argument is required to permit FFM API access:

```bash
--enable-native-access=ALL-UNNAMED
```

## 📜 License

This project is licensed under the MIT License.
