use std::alloc::{GlobalAlloc, Layout};
use std::cell::UnsafeCell;
use std::ptr::null_mut;
use std::sync::OnceLock;
use std::sync::atomic::{AtomicPtr, Ordering};

#[cfg(feature = "jvmalloc")]
use spin::Mutex;
#[cfg(feature = "jvmalloc")]
use rlsf::Tlsf;

mod parent;
pub mod jvm;
use parent::ParentAlloc;
#[cfg(feature = "jvmalloc")]
pub use jvm::xross_runtime_init as xross_alloc_init;

// --- 定数調整 ---
const SIZE_CLASSES: [usize; 7] = [16, 32, 64, 128, 256, 512, 1024];
const CHUNK_SIZE: usize = 64 * 1024; // 64KB
const BATCH_SIZE: usize = 32;
const FLUSH_THRESHOLD: usize = 64;

#[repr(C)]
struct FreeNode {
    next: *mut FreeNode,
}

pub(crate) struct GlobalState {
    #[cfg(feature = "jvmalloc")]
    tlsf: Mutex<Tlsf<'static, usize, usize, 32, 32>>,
    central_freelists: [AtomicPtr<FreeNode>; SIZE_CLASSES.len()],
    managed_range: (usize, usize),
}

unsafe impl Send for GlobalState {}
unsafe impl Sync for GlobalState {}

static GLOBAL: OnceLock<GlobalState> = OnceLock::new();

thread_local! {
    static LOCAL_CACHES: UnsafeCell<[LocalCache; SIZE_CLASSES.len()]> = const {
        UnsafeCell::new([LocalCache::new(); SIZE_CLASSES.len()])
    };
}

#[derive(Copy, Clone)]
struct LocalCache {
    head: *mut FreeNode,
    count: usize,
}

impl LocalCache {
    const fn new() -> Self {
        LocalCache { head: null_mut(), count: 0 }
    }
}

#[derive(Default)]
pub struct XrossAlloc;

impl XrossAlloc {
    pub const fn new() -> Self {
        Self
    }

    #[inline(always)]
    pub(crate) fn get_global(&self) -> &GlobalState {
        if let Some(g) = GLOBAL.get() {
            g
        } else {
            GLOBAL.get_or_init(Self::init_global)
        }
    }

    #[inline(always)]
    fn get_size_class_idx(size: usize) -> Option<usize> {
        if size > 1024 {
            return None;
        }
        if size <= 16 {
            return Some(0);
        }
        let power = usize::BITS - (size - 1).leading_zeros();
        let idx = (power as usize).saturating_sub(4);
        if idx < SIZE_CLASSES.len() {
            Some(idx)
        } else {
            None
        }
    }

    fn init_global() -> GlobalState {
        #[cfg(feature = "jvmalloc")]
        {
            if let Some(&(jvm::RawPtr(ptr), size)) = jvm::EXTERNAL_HEAP.get() {
                let mut tlsf = Tlsf::new();
                unsafe {
                    let slice = std::slice::from_raw_parts_mut(ptr as *mut std::mem::MaybeUninit<u8>, size);
                    tlsf.insert_free_block(slice);
                }
                return GlobalState {
                    tlsf: Mutex::new(tlsf),
                    central_freelists: std::array::from_fn(|_| AtomicPtr::new(null_mut())),
                    managed_range: (ptr as usize, ptr as usize + size),
                };
            }
        }

        let total_init = 128 * 1024 * 1024;
        let layout = Layout::from_size_align(total_init, 4096).unwrap();
        let base_ptr = unsafe { ParentAlloc.alloc(layout) };

        #[cfg(feature = "jvmalloc")]
        {
            let mut tlsf = Tlsf::new();
            unsafe {
                let slice = std::slice::from_raw_parts_mut(base_ptr as *mut std::mem::MaybeUninit<u8>, total_init);
                tlsf.insert_free_block(slice);
            }
            GlobalState {
                tlsf: Mutex::new(tlsf),
                central_freelists: std::array::from_fn(|_| AtomicPtr::new(null_mut())),
                managed_range: (base_ptr as usize, base_ptr as usize + total_init),
            }
        }
        #[cfg(not(feature = "jvmalloc"))]
        {
            GlobalState {
                central_freelists: std::array::from_fn(|_| AtomicPtr::new(null_mut())),
                managed_range: (base_ptr as usize, base_ptr as usize + total_init),
            }
        }
    }

    #[cold]
    unsafe fn refill_from_global(&self, cache: &mut LocalCache, idx: usize, g: &GlobalState) {
        let size = SIZE_CLASSES[idx];
        let mut current = g.central_freelists[idx].load(Ordering::Acquire);

        if current.is_null() {
            let chunk = unsafe { self.alloc_from_backend(g, CHUNK_SIZE, 16) };
            if chunk.is_null() { return; }

            let num_blocks = CHUNK_SIZE / size;
            let mut head = null_mut();
            
            unsafe {
                for i in (BATCH_SIZE..num_blocks).rev() {
                    let node = chunk.add(i * size) as *mut FreeNode;
                    (*node).next = head;
                    head = node;
                }
                
                loop {
                    let old = g.central_freelists[idx].load(Ordering::Relaxed);
                    let last_node = chunk.add((num_blocks - 1) * size) as *mut FreeNode;
                    (*last_node).next = old;
                    if g.central_freelists[idx].compare_exchange_weak(old, head, Ordering::Release, Ordering::Relaxed).is_ok() {
                        break;
                    }
                }

                let mut l_head = null_mut();
                for i in (0..BATCH_SIZE).rev() {
                    let node = chunk.add(i * size) as *mut FreeNode;
                    (*node).next = l_head;
                    l_head = node;
                }
                cache.head = l_head;
                cache.count = BATCH_SIZE;
            }
            return;
        }

        loop {
            if current.is_null() { break; }
            let mut last = current;
            let mut count = 1;
            unsafe {
                for _ in 0..(BATCH_SIZE - 1) {
                    let next = (*last).next;
                    if next.is_null() { break; }
                    last = next;
                    count += 1;
                }
                let next_global = (*last).next;
                if g.central_freelists[idx].compare_exchange_weak(current, next_global, Ordering::Acquire, Ordering::Relaxed).is_ok() {
                    (*last).next = null_mut();
                    cache.head = current;
                    cache.count = count;
                    break;
                } else {
                    current = g.central_freelists[idx].load(Ordering::Acquire);
                }
            }
        }
    }

    #[cold]
    unsafe fn flush_to_global(&self, cache: &mut LocalCache, idx: usize, g: &GlobalState) {
        unsafe {
            let mut last = cache.head;
            for _ in 0..(BATCH_SIZE - 1) {
                last = (*last).next;
            }
            let to_flush = cache.head;
            cache.head = (*last).next;
            cache.count -= BATCH_SIZE;

            loop {
                let old = g.central_freelists[idx].load(Ordering::Relaxed);
                (*last).next = old;
                if g.central_freelists[idx].compare_exchange_weak(old, to_flush, Ordering::Release, Ordering::Relaxed).is_ok() {
                    break;
                }
            }
        }
    }

    #[inline(always)]
    unsafe fn alloc_from_backend(&self, g: &GlobalState, size: usize, align: usize) -> *mut u8 {
        #[cfg(feature = "jvmalloc")]
        {
            let layout = Layout::from_size_align(size, align).unwrap();
            g.tlsf.lock().allocate(layout).map(|p| p.as_ptr()).unwrap_or(null_mut())
        }
        #[cfg(not(feature = "jvmalloc"))]
        {
            let _ = g;
            let layout = Layout::from_size_align(size, align).unwrap();
            unsafe { ParentAlloc.alloc(layout) }
        }
    }

    #[inline(always)]
    unsafe fn dealloc_to_backend(&self, g: &GlobalState, ptr: *mut u8, layout: Layout) {
        #[cfg(feature = "jvmalloc")]
        {
            use std::ptr::NonNull;
            if let Some(p) = NonNull::new(ptr) {
                unsafe { g.tlsf.lock().deallocate(p, layout.align()); }
            }
        }
        #[cfg(not(feature = "jvmalloc"))]
        {
            let _ = g;
            unsafe { ParentAlloc.dealloc(ptr, layout); }
        }
    }
}

unsafe impl GlobalAlloc for XrossAlloc {
    #[inline(always)]
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        let size = layout.size();
        if size <= 1024 && layout.align() <= 8 {
            if let Some(idx) = Self::get_size_class_idx(size) {
                return LOCAL_CACHES.with(|caches_cell| {
                    let caches = unsafe { &mut *caches_cell.get() };
                    let cache = &mut caches[idx];

                    if cache.head.is_null() {
                        unsafe { self.refill_from_global(cache, idx, self.get_global()) };
                    }

                    let node = cache.head;
                    if !node.is_null() {
                        unsafe {
                            cache.head = (*node).next;
                            cache.count -= 1;
                        }
                        return node as *mut u8;
                    }
                    unsafe { self.alloc_from_backend(self.get_global(), size, layout.align()) }
                });
            }
        }
        unsafe { self.alloc_from_backend(self.get_global(), size, layout.align()) }
    }

    #[inline(always)]
    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        let g = self.get_global();
        let ptr_u = ptr as usize;

        if ptr_u >= g.managed_range.0 && ptr_u < g.managed_range.1 {
            let size = layout.size();
            if size <= 1024 && layout.align() <= 8 {
                if let Some(idx) = Self::get_size_class_idx(size) {
                    LOCAL_CACHES.with(|caches_cell| {
                        let caches = unsafe { &mut *caches_cell.get() };
                        let cache = &mut caches[idx];

                        let node = ptr as *mut FreeNode;
                        unsafe {
                            (*node).next = cache.head;
                            cache.head = node;
                            cache.count += 1;
                        }

                        if cache.count >= FLUSH_THRESHOLD {
                            unsafe { self.flush_to_global(cache, idx, g) };
                        }
                    });
                    return;
                }
            }
            unsafe { self.dealloc_to_backend(g, ptr, layout) };
        } else {
            unsafe { ParentAlloc.dealloc(ptr, layout) };
        }
    }
}
