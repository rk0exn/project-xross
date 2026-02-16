use std::alloc::{GlobalAlloc, Layout, System};

/// バックエンドとなるアロケータを抽象化する構造体
pub struct ParentAlloc;

unsafe impl GlobalAlloc for ParentAlloc {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        #[cfg(feature = "jemalloc")]
        return unsafe { jemallocator::Jemalloc.alloc(layout) };

        #[cfg(feature = "mimalloc")]
        return unsafe { mimalloc::MiMalloc.alloc(layout) };

        #[cfg(feature = "rpmalloc")]
        return unsafe { rpmalloc::RpMalloc.alloc(layout) };

        #[cfg(feature = "snmalloc")]
        return unsafe { snmalloc_rs::SnMalloc.alloc(layout) };

        #[cfg(feature = "tcmalloc")]
        return unsafe { tcmalloc::TcMalloc.alloc(layout) };

        #[allow(unreachable_code)]
        unsafe {
            System.alloc(layout)
        }
    }

    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        #[cfg(feature = "jemalloc")]
        return unsafe { jemallocator::Jemalloc.dealloc(ptr, layout) };

        #[cfg(feature = "mimalloc")]
        return unsafe { mimalloc::MiMalloc.dealloc(ptr, layout) };

        #[cfg(feature = "rpmalloc")]
        return unsafe { rpmalloc::RpMalloc.dealloc(ptr, layout) };

        #[cfg(feature = "snmalloc")]
        return unsafe { snmalloc_rs::SnMalloc.dealloc(ptr, layout) };

        #[cfg(feature = "tcmalloc")]
        return unsafe { tcmalloc::TcMalloc.dealloc(ptr, layout) };
        #[allow(unreachable_code)]
        unsafe {
            System.dealloc(ptr, layout)
        };
    }
}
