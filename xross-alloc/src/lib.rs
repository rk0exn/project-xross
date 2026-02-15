use mimalloc::MiMalloc;
use std::alloc::{GlobalAlloc, Layout};
use std::cell::UnsafeCell;
use std::ptr::null_mut;
use std::sync::atomic::{AtomicPtr, AtomicU64, Ordering};

// ── コンパイル時設定 ────────────────────────────────────────────────────
const LIFO_CAPACITY: usize = 1; // 1でシンプルに（増やすとShuffle改善するかも）

// ── 定数 ────────────────────────────────────────────────────────────────
const CHUNK_SIZE: usize = 512 * 1024;
const CHUNK_MASK: usize = !(CHUNK_SIZE - 1);
const NUM_CLASSES: usize = 5;
const SIZE_CLASSES: [usize; NUM_CLASSES] = [64, 128, 256, 512, 1024];

const SLOTS_PER_CHUNK: [usize; NUM_CLASSES] = [
    (CHUNK_SIZE - 16384) / 64,
    (CHUNK_SIZE - 16384) / 128,
    (CHUNK_SIZE - 16384) / 256,
    (CHUNK_SIZE - 16384) / 512,
    (CHUNK_SIZE - 16384) / 1024,
];

type BitmapArray = [u64; 64];

#[repr(align(64))]
struct ChunkHeader {
    magic: u64,
    class_idx: usize,
    #[cfg(feature = "remote-free")]
    owner_id: u64,
    bitmap: BitmapArray,
    summary: [u64; 2],
    free_count: usize,
    next: *mut ChunkHeader,
    #[cfg(feature = "remote-free")]
    remote_free: AtomicPtr<u8>,
}

impl ChunkHeader {
    const MAGIC: u64 = 0x58524F5353414C43;
    const DATA_OFFSET: usize = 16384;

    #[inline(always)]
    unsafe fn from_ptr(ptr: *mut u8) -> *mut Self {
        ((ptr as usize) & CHUNK_MASK) as *mut Self
    }
}

struct XrossLocalRuntime {
    heads: [*mut ChunkHeader; NUM_CLASSES],
    last_freed: [[*mut u8; LIFO_CAPACITY]; NUM_CLASSES],
    freed_count: [usize; NUM_CLASSES],
    used_chunks: *mut ChunkHeader,
}

thread_local! {
    static THREAD_ID: u64 = {
        static COUNTER: AtomicU64 = AtomicU64::new(1);
        COUNTER.fetch_add(1, Ordering::Relaxed)
    };
    static POOL_RUNTIME: UnsafeCell<Option<XrossLocalRuntime>> = const { UnsafeCell::new(None) };
}

#[inline(always)]
fn get_thread_id() -> u64 {
    THREAD_ID.with(|id| *id)
}

// ── 設定管理 ────────────────────────────────────────────────────────────
static CONFIG_HEAD: AtomicPtr<ConfigNode> = AtomicPtr::new(null_mut());

struct ConfigNode {
    size: usize,
    count: usize,
    next: *mut ConfigNode,
}

pub struct XrossAllocator;

impl XrossLocalRuntime {
    pub unsafe fn init() -> Self {
        unsafe {
            let mut heads = [null_mut(); NUM_CLASSES];
            let last_freed = [[null_mut(); LIFO_CAPACITY]; NUM_CLASSES];
            let freed_count = [0; NUM_CLASSES];
            let mut used_chunks = null_mut();
            let tid = get_thread_id();

            let mut curr = CONFIG_HEAD.load(Ordering::Acquire);
            while !curr.is_null() {
                let node = &*curr;
                if let Some(idx) = XrossAllocator::get_class_idx(node.size) {
                    let slots_needed = node.count;
                    let slots_per = SLOTS_PER_CHUNK[idx];
                    let chunks_needed = slots_needed.div_ceil(slots_per);

                    for _ in 0..chunks_needed {
                        let chunk = Self::alloc_chunk(idx, tid);
                        if !chunk.is_null() {
                            (*chunk).next = heads[idx];
                            heads[idx] = chunk;
                            (*chunk).next = used_chunks;
                            used_chunks = chunk;
                        }
                    }
                }
                curr = (*curr).next;
            }

            Self { heads, last_freed, freed_count, used_chunks }
        }
    }

    unsafe fn alloc_chunk(class_idx: usize, _tid: u64) -> *mut ChunkHeader {
        unsafe {
            let layout = Layout::from_size_align(CHUNK_SIZE, CHUNK_SIZE).unwrap();
            let ptr = MiMalloc.alloc(layout) as *mut ChunkHeader;
            if ptr.is_null() {
                return null_mut();
            }

            ptr.write(ChunkHeader {
                magic: ChunkHeader::MAGIC,
                class_idx,
                #[cfg(feature = "remote-free")]
                owner_id: tid,
                bitmap: [!0u64; 64],
                summary: [!0u64; 2],
                free_count: SLOTS_PER_CHUNK[class_idx],
                next: null_mut(),
                #[cfg(feature = "remote-free")]
                remote_free: AtomicPtr::new(null_mut()),
            });
            ptr
        }
    }
}

impl Drop for XrossLocalRuntime {
    fn drop(&mut self) {
        let mut curr = self.used_chunks;
        let layout = Layout::from_size_align(CHUNK_SIZE, CHUNK_SIZE).unwrap();
        while !curr.is_null() {
            unsafe {
                let next = (*curr).next;
                MiMalloc.dealloc(curr as *mut u8, layout);
                curr = next;
            }
        }
    }
}

impl XrossAllocator {
    pub fn setup(configs: &[(usize, usize)]) {
        for &(size, count) in configs {
            let layout = Layout::new::<ConfigNode>();
            let node = unsafe { MiMalloc.alloc(layout) as *mut ConfigNode };
            if !node.is_null() {
                unsafe {
                    node.write(ConfigNode {
                        size,
                        count,
                        next: CONFIG_HEAD.load(Ordering::Relaxed),
                    });
                    CONFIG_HEAD.store(node, Ordering::Release);
                }
            }
        }
    }

    #[inline]
    pub fn get_class_idx(size: usize) -> Option<usize> {
        match size {
            64 => Some(0),
            128 => Some(1),
            256 => Some(2),
            512 => Some(3),
            1024 => Some(4),
            _ => None,
        }
    }

    #[inline(always)]
    unsafe fn find_free_in_chunk(chunk_ptr: *mut ChunkHeader) -> Option<*mut u8> {
        unsafe {
            let chunk = &mut *chunk_ptr;
            if chunk.free_count == 0 {
                return None;
            }

            for i in 0..2 {
                let mut s = chunk.summary[i];
                while s != 0 {
                    let l1 = s.trailing_zeros() as usize;
                    let b_idx = (i << 6) | l1; // 0..63 or 64..127
                    let b = chunk.bitmap[b_idx];
                    if b != 0 {
                        let bit = b.trailing_zeros() as usize;
                        chunk.bitmap[b_idx] &= !(1u64 << bit);
                        if chunk.bitmap[b_idx] == 0 {
                            chunk.summary[i] &= !(1u64 << l1);
                        }
                        chunk.free_count -= 1;
                        let offset = (b_idx << 6) | bit;
                        return Some(
                            (chunk_ptr as usize
                                + ChunkHeader::DATA_OFFSET
                                + offset * SIZE_CLASSES[chunk.class_idx])
                                as *mut u8,
                        );
                    }
                    s &= !(1u64 << l1);
                }
            }
            None
        }
    }

    #[cfg(feature = "remote-free")]
    #[inline(always)]
    unsafe fn remote_dealloc(&self, header: *mut ChunkHeader, ptr: *mut u8) {
        unsafe {
            let mut head = (*header).remote_free.load(Ordering::Relaxed);
            loop {
                *(ptr as *mut *mut u8) = head;
                match (*header).remote_free.compare_exchange_weak(
                    head,
                    ptr,
                    Ordering::Release,
                    Ordering::Relaxed,
                ) {
                    Ok(_) => return,
                    Err(next) => head = next,
                }
            }
        }
    }

    #[cfg(feature = "remote-free")]
    #[inline(always)]
    unsafe fn scavenge_remote(&self, chunk_ptr: *mut ChunkHeader) -> usize {
        unsafe {
            let chunk = &mut *chunk_ptr;
            let mut ptr = chunk.remote_free.swap(null_mut(), Ordering::Acquire);
            if ptr.is_null() {
                return 0;
            }

            let mut count = 0;
            while !ptr.is_null() {
                let next = *(ptr as *mut *mut u8);
                let offset = (ptr as usize - (chunk_ptr as usize + ChunkHeader::DATA_OFFSET))
                    / SIZE_CLASSES[chunk.class_idx];
                let b_idx = offset >> 6;
                let bit = offset & 63;
                chunk.bitmap[b_idx] |= 1u64 << bit;
                chunk.summary[b_idx >> 6] |= 1u64 << (b_idx & 63);
                count += 1;
                ptr = next;
            }
            chunk.free_count += count;
            count
        }
    }
}

unsafe impl GlobalAlloc for XrossAllocator {
    #[inline(always)]
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        unsafe {
            let size = layout.size();
            if let Some(idx) = Self::get_class_idx(size) {
                POOL_RUNTIME.with(|cell| {
                    let rt_ptr = cell.get();
                    if (*rt_ptr).is_none() {
                        *rt_ptr = Some(XrossLocalRuntime::init());
                    }
                    let rt = (*rt_ptr).as_mut().unwrap_unchecked();

                    if rt.freed_count[idx] > 0 {
                        rt.freed_count[idx] -= 1;
                        return rt.last_freed[idx][rt.freed_count[idx]];
                    }

                    let chunk_ptr = rt.heads[idx];
                    if chunk_ptr.is_null() {
                        return MiMalloc.alloc(layout);
                    }

                    #[cfg(feature = "remote-free")]
                    {
                        if (*chunk_ptr).free_count == 0 {
                            let _ = self.scavenge_remote(chunk_ptr);
                        }
                    }

                    if let Some(ptr) = Self::find_free_in_chunk(chunk_ptr) {
                        // MRU移動（テストのためコメントアウト中）
                        // rt.heads[idx] = (*chunk_ptr).next;
                        // (*chunk_ptr).next = rt.heads[idx];
                        // rt.heads[idx] = chunk_ptr;
                        ptr
                    } else {
                        MiMalloc.alloc(layout)
                    }
                })
            } else {
                panic!("XrossAllocator: unsupported allocation size: {}", size);
            }
        }
    }

    #[inline(always)]
    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        unsafe {
            if ptr.is_null() {
                return;
            }

            let header = ChunkHeader::from_ptr(ptr);
            if (*header).magic != ChunkHeader::MAGIC {
                MiMalloc.dealloc(ptr, layout);
                return;
            }

            let _tid = get_thread_id();

            POOL_RUNTIME.with(|cell| {
                let rt_ptr = cell.get();
                if let Some(rt) = (*rt_ptr).as_mut() {
                    let idx = (*header).class_idx;

                    #[cfg(feature = "remote-free")]
                    {
                        if (*header).owner_id != tid {
                            self.remote_dealloc(header, ptr);
                            return;
                        }
                    }

                    if rt.freed_count[idx] < LIFO_CAPACITY {
                        rt.last_freed[idx][rt.freed_count[idx]] = ptr;
                        rt.freed_count[idx] += 1;
                        return;
                    }

                    let chunk = &mut *header;
                    let offset = (ptr as usize - (header as usize + ChunkHeader::DATA_OFFSET))
                        / SIZE_CLASSES[idx];
                    let b_idx = offset >> 6;
                    let bit = offset & 63;
                    chunk.bitmap[b_idx] |= 1u64 << bit;
                    chunk.summary[b_idx >> 6] |= 1u64 << (b_idx & 63);
                    chunk.free_count += 1;
                }
            });
        }
    }
}

unsafe impl Sync for XrossAllocator {}
unsafe impl Send for XrossAllocator {}
