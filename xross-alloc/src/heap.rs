use std::alloc::{GlobalAlloc, Layout, System};
use std::sync::OnceLock;

/// JVMから提供された生ポインタをスレッドセーフに扱うためのラッパー
#[derive(Clone, Copy)]
pub struct RawMemory {
    pub ptr: *mut u8,
    pub size: usize,
    pub align: usize,
}
impl RawMemory {
    pub unsafe fn new(size: usize, align: usize) -> Self {
        let ptr = unsafe { System.alloc(Layout::from_size_align(size, align).unwrap()) };
        Self { ptr, size, align }
    }
    pub fn is_own(&self, ptr: *mut u8) -> bool {
        let start = self.ptr as usize;
        let end = start + self.size;
        let target = ptr as usize;
        // start <= target < end の範囲にあるかを確認
        target >= start && target < end
    }
}
unsafe impl Send for RawMemory {}
unsafe impl Sync for RawMemory {}

/// JVMまたはシステムから提供される固定メモリ領域
static HEAP_SOURCE: OnceLock<RawMemory> = OnceLock::new();

/// ヒープ領域を取得します。JVM機能が有効な場合は初期化を待機し、
/// そうでない場合は OS (mmap) から直接、他のアロケータと隔離された領域を確保します。
pub fn heap() -> RawMemory {
    *HEAP_SOURCE.get_or_init(|| {
        let size = 512 * 1024 * 1024; // 512 MB
        let align = 4096;
        unsafe { RawMemory::new(size, align) }
    })
}

#[cfg(feature = "jvm")]
#[unsafe(no_mangle)]
pub unsafe extern "C" fn xross_alloc_init(ptr: *mut u8, size: usize, align: usize) {
    let _ = HEAP_SOURCE.set(RawMemory { ptr, size, align });
}
