use std::alloc::{GlobalAlloc, Layout, System};

/// バックエンドとなるアロケータを抽象化する構造体。
/// macOS 以外のターゲットでは各フィーチャーに応じて外部アロケーターを使用し、
/// macOS では常に System アロケーターを使用します。
pub struct ParentAlloc;

unsafe impl GlobalAlloc for ParentAlloc {
    #[allow(unreachable_code)]
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        #[cfg(all(feature = "jemalloc", not(target_os = "macos")))]
        return unsafe { jemallocator::Jemalloc.alloc(layout) };

        #[cfg(all(feature = "mimalloc", not(target_os = "macos")))]
        return unsafe { mimalloc::MiMalloc.alloc(layout) };

        #[cfg(all(feature = "rpmalloc", not(target_os = "macos")))]
        return unsafe { rpmalloc::RpMalloc.alloc(layout) };

        #[cfg(all(feature = "snmalloc", not(target_os = "macos")))]
        return unsafe { snmalloc_rs::SnMalloc.alloc(layout) };

        #[cfg(all(feature = "tcmalloc", not(target_os = "macos")))]
        return unsafe { tcmalloc::TCMalloc.alloc(layout) };

        unsafe { System.alloc(layout) }
    }

    #[allow(unreachable_code)]
    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        #[cfg(all(feature = "jemalloc", not(target_os = "macos")))]
        return unsafe { jemallocator::Jemalloc.dealloc(ptr, layout) };

        #[cfg(all(feature = "mimalloc", not(target_os = "macos")))]
        return unsafe { mimalloc::MiMalloc.dealloc(ptr, layout) };

        #[cfg(all(feature = "rpmalloc", not(target_os = "macos")))]
        return unsafe { rpmalloc::RpMalloc.dealloc(ptr, layout) };

        #[cfg(all(feature = "snmalloc", not(target_os = "macos")))]
        return unsafe { snmalloc_rs::SnMalloc.dealloc(ptr, layout) };

        #[cfg(all(feature = "tcmalloc", not(target_os = "macos")))]
        return unsafe { tcmalloc::TCMalloc.dealloc(ptr, layout) };

        unsafe { System.dealloc(ptr, layout) };
    }
}
