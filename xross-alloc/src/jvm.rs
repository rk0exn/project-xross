use std::sync::OnceLock;

/// JVMから提供された生ポインタをスレッドセーフに扱うためのラッパー
pub struct RawPtr(pub *mut u8);
unsafe impl Send for RawPtr {}
unsafe impl Sync for RawPtr {}

/// JVMから提供される固定メモリ領域
pub static EXTERNAL_HEAP: OnceLock<(RawPtr, usize)> = OnceLock::new();

#[cfg(feature = "jvmalloc")]
#[unsafe(no_mangle)]
pub unsafe extern "C" fn xross_runtime_init(ptr: *mut u8, size: usize) {
    let _ = EXTERNAL_HEAP.set((RawPtr(ptr), size));
    // アロケーターの初期化をトリガー
    crate::XrossAlloc::new().get_global();
}
