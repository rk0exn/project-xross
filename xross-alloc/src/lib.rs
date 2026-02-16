use std::alloc::{GlobalAlloc, Layout};
use std::cell::UnsafeCell;
use std::ptr::null_mut;
use std::sync::OnceLock;
use std::sync::atomic::{AtomicPtr, Ordering};

mod parent;
use parent::ParentAlloc;

// サイズクラスの定義 (16B ~ 1024B)
const SIZE_CLASSES: [usize; 7] = [16, 32, 64, 128, 256, 512, 1024];
const CHUNK_SIZE: usize = 128 * 1024 * 1024; // 各サイズクラスごとに確保するメモリ量
const BATCH_SIZE: usize = 64; // 一度にグローバルから奪う量
const FLUSH_THRESHOLD: usize = 128; // ローカルがこれを超えたらグローバルへ戻す

#[repr(C)]
struct FreeNode {
    next: *mut FreeNode,
}

struct GlobalState {
    base_ptr: *mut u8,
    central_freelists: [AtomicPtr<FreeNode>; SIZE_CLASSES.len()],
}

// ポインタを含んでいるため、明示的に実装が必要
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
        Self { head: null_mut(), count: 0 }
    }
}

pub struct XrossAlloc;

impl XrossAlloc {
    pub const fn new() -> Self {
        Self
    }

    #[inline]
    fn get_size_class(size: usize) -> Option<usize> {
        if size > 1024 {
            return None;
        }
        if size <= 16 {
            return Some(0);
        }
        let power = (size - 1).next_power_of_two();
        match power {
            32 => Some(1),
            64 => Some(2),
            128 => Some(3),
            256 => Some(4),
            512 => Some(5),
            1024 => Some(6),
            _ => None,
        }
    }

    fn init_global() -> GlobalState {
        unsafe {
            let total_alloc_size = CHUNK_SIZE * SIZE_CLASSES.len();
            let layout = Layout::from_size_align(total_alloc_size, 4096).unwrap();
            let base_ptr = ParentAlloc.alloc(layout);

            // AtomicPtrの配列を初期化
            let central_freelists: [AtomicPtr<FreeNode>; SIZE_CLASSES.len()] =
                std::array::from_fn(|_| AtomicPtr::new(null_mut()));

            for (i, &size) in SIZE_CLASSES.iter().enumerate() {
                let mut head = null_mut();
                let class_offset = i * CHUNK_SIZE;
                let num_blocks = CHUNK_SIZE / size;

                for j in (0..num_blocks).rev() {
                    let node_ptr = base_ptr.add(class_offset + j * size) as *mut FreeNode;
                    (*node_ptr).next = head;
                    head = node_ptr;
                }
                central_freelists[i].store(head, Ordering::Relaxed);
            }

            GlobalState { base_ptr, central_freelists }
        }
    }

    fn get_global(&self) -> &GlobalState {
        GLOBAL.get_or_init(Self::init_global)
    }

    unsafe fn refill_from_global(&self, cache: &mut LocalCache, class_idx: usize, g: &GlobalState) {
        let mut current_global = g.central_freelists[class_idx].load(Ordering::Relaxed);
        loop {
            if current_global.is_null() {
                break;
            }

            let mut last = current_global;
            let mut actual_count = 1;
            unsafe {
                for _ in 0..(BATCH_SIZE - 1) {
                    let next = (*last).next;
                    if next.is_null() {
                        break;
                    }
                    last = next;
                    actual_count += 1;
                }

                let next_global = (*last).next;
                match g.central_freelists[class_idx].compare_exchange_weak(
                    current_global,
                    next_global,
                    Ordering::Acquire,
                    Ordering::Relaxed,
                ) {
                    Ok(_) => {
                        (*last).next = null_mut();
                        cache.head = current_global;
                        cache.count = actual_count;
                        break;
                    }
                    Err(actual) => current_global = actual,
                }
            }
        }
    }

    unsafe fn flush_to_global(&self, cache: &mut LocalCache, class_idx: usize, g: &GlobalState) {
        unsafe {
            let mut last = cache.head;
            for _ in 0..(BATCH_SIZE - 1) {
                last = (*last).next;
            }
            let to_global_head = cache.head;
            cache.head = (*last).next;

            let mut current_global = g.central_freelists[class_idx].load(Ordering::Relaxed);
            loop {
                (*last).next = current_global;
                match g.central_freelists[class_idx].compare_exchange_weak(
                    current_global,
                    to_global_head,
                    Ordering::Release,
                    Ordering::Relaxed,
                ) {
                    Ok(_) => break,
                    Err(actual) => current_global = actual,
                }
            }
        }
        cache.count -= BATCH_SIZE;
    }
}

impl Default for XrossAlloc {
    fn default() -> Self {
        Self::new()
    }
}

unsafe impl GlobalAlloc for XrossAlloc {
    #[inline]
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        let size = layout.size();
        if let Some(idx) = Self::get_size_class(size)
            && layout.align() <= 8
        {
            // 一般的なアライメント
            let g = self.get_global();
            return LOCAL_CACHES.with(|caches_cell| {
                let caches = unsafe { &mut *caches_cell.get() };
                let cache = &mut caches[idx];

                if cache.head.is_null() {
                    unsafe { self.refill_from_global(cache, idx, g) };
                }

                if !cache.head.is_null() {
                    let node = cache.head;
                    unsafe {
                        cache.head = (*node).next;
                    }
                    cache.count -= 1;
                    return node as *mut u8;
                }
                unsafe { ParentAlloc.alloc(layout) }
            });
        }
        unsafe { ParentAlloc.alloc(layout) }
    }

    #[inline]
    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        let size = layout.size();
        if let Some(idx) = Self::get_size_class(size)
            && layout.align() <= 8
        {
            let g = self.get_global();

            let total_managed = CHUNK_SIZE * SIZE_CLASSES.len();
            let offset = unsafe { ptr.offset_from(g.base_ptr) };

            if offset >= 0 && (offset as usize) < total_managed {
                LOCAL_CACHES.with(|caches_cell| {
                    let caches = unsafe { &mut *caches_cell.get() };
                    let cache = &mut caches[idx];

                    let node = ptr as *mut FreeNode;
                    unsafe {
                        (*node).next = cache.head;
                    }
                    cache.head = node;
                    cache.count += 1;

                    if cache.count >= FLUSH_THRESHOLD {
                        unsafe { self.flush_to_global(cache, idx, g) };
                    }
                });
                return;
            }
        }
        unsafe { ParentAlloc.dealloc(ptr, layout) }
    }
}
