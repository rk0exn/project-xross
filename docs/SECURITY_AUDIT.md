# Security & Robustness Audit (2026-02)

## Scope

This audit targets the Rust workspace (`xross-core`, `xross-macros`, `xross-metadata`, `xross-alloc`, `xross-example/rust`) with a focus on:

- FFI boundary memory safety.
- Unsafe code paths that can trigger Undefined Behavior (UB).
- Panic propagation behavior.
- Runtime and lifecycle handling for async tasks and owned pointers.

## Findings

### 1) High: Potential out-of-bounds read / UB in UTF-16 string bridge

**Location**: `xross-core/src/lib.rs` (`XrossStringView::to_string_lossy`).

#### Risk
The previous implementation casted a `*const u8` pointer to `*const u16` and then created a `u16` slice with `len` elements. If `len` represented byte length (as passed by Panama/JVM compact string internals), this could read up to 2x beyond valid memory. It also assumed alignment that may not hold for arbitrary byte pointers.

#### Exploitability
At FFI boundaries, malformed lengths can be triggered by integration bugs or hostile callers, causing memory disclosure or crashes.

#### Mitigation implemented
- Decode UTF-16 as raw LE bytes (`chunks_exact(2)`), not by pointer cast.
- Avoid unaligned pointer dereference entirely.
- Handle odd trailing byte by appending replacement character (`U+FFFD`) rather than touching invalid memory.

### 2) Medium: Limited automated coverage for FFI-adjacent logic

**Location**: Workspace-wide tests before this change.

#### Risk
Core conversion and runtime-sensitive paths had little direct regression coverage, increasing risk of reintroducing UB / lifecycle bugs.

#### Mitigation implemented
- Added unit tests in `xross-core` specifically for Latin1/UTF-16 decode correctness and odd-length robustness.
- Expanded `xross-example/rust` tests to cover async flow, panicable behavior, ownership/lifecycle counters, `Option`/`Result` methods, and critical methods.

## Residual risk and recommendations

1. Keep all FFI conversion logic under dedicated tests whenever metadata/runtime representation changes.
2. Add `cargo audit` and formatter/lint checks in CI to prevent dependency regressions and unsafe style drift.
3. Consider fuzzing `XrossStringView` decode entry points with random pointer/len/encoding tuples in a controlled harness.

## Verification commands

- `cargo test --workspace`
- `cargo fmt --all -- --check`
- `cargo clippy --workspace --all-targets --all-features -- -D warnings`
