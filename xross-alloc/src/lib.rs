use mimalloc::MiMalloc;
use std::alloc::{GlobalAlloc, Layout};
use std::cell::UnsafeCell;
use std::ptr::null_mut;
use std::sync::atomic::{AtomicPtr, AtomicU64, Ordering};

// --- Constants ---
const CHUNK_SIZE: usize = 2 * 1024 * 1024;
const CHUNK_MASK: usize = !(CHUNK_SIZE - 1);
const NUM_CLASSES: usize = 5;
const SIZE_CLASSES: [usize; 5] = [64, 128, 256, 512, 1024];

// --- Structures ---

#[repr(align(64))]
struct ChunkHeader {
    magic: u64,
    slot_size: usize,
    owner_id: u64,
    bitmap: [u64; 512],
    summary: [u64; 8],
    free_count: usize,
    next: *mut ChunkHeader,
    remote_free: AtomicPtr<u8>,
}

impl ChunkHeader {
    const MAGIC: u64 = 0x58524F5353414C43;
    const DATA_OFFSET: usize = 16384;

    #[inline(always)]
    unsafe fn from_ptr(ptr: *mut u8) -> *mut Self {
        (ptr as usize & CHUNK_MASK) as *mut Self
    }
}

struct XrossLocalRuntime {
    magazine_heads: [*mut ChunkHeader; NUM_CLASSES],
    last_freed: [*mut u8; NUM_CLASSES],
    all_chunks: *mut ChunkHeader,
}

// --- Thread Locals ---

thread_local! {
    static THREAD_ID: u64 = {
        static COUNTER: AtomicU64 = AtomicU64::new(1);
        COUNTER.fetch_add(1, Ordering::Relaxed)
    };
    static POOL_RUNTIME: UnsafeCell<Option<XrossLocalRuntime>> = const { UnsafeCell::new(None) };
    static THREAD_MARKER: u8 = const { 0 };
}

#[inline(always)]
fn get_thread_id() -> u64 {
    THREAD_ID.with(|id| *id)
}

// --- Config Management ---

static CONFIG_HEAD: AtomicPtr<ConfigNode> = AtomicPtr::new(null_mut());
struct ConfigNode {
    size: usize,
    count: usize,
    next: *mut ConfigNode,
}

// --- Allocator Implementation ---

pub struct XrossAllocator;

impl XrossLocalRuntime {
    unsafe fn init() -> Self {
        let mut magazine_heads = [null_mut(); NUM_CLASSES];
        let last_freed = [null_mut(); NUM_CLASSES];
        let mut all_chunks = null_mut();
        let tid = get_thread_id();

        let mut curr_config = CONFIG_HEAD.load(Ordering::Acquire);
        while !curr_config.is_null() {
            let config = &*curr_config;
            if let Some(idx) = XrossAllocator::get_class_idx_fast(config.size) {
                let chunks_needed = (config.count * config.size).div_ceil(CHUNK_SIZE - ChunkHeader::DATA_OFFSET) / (SIZE_CLASSES[idx] / config.size);
                for _ in 0..chunks_needed.max(1) {
                    let chunk = Self::alloc_chunk(config.size, tid);
                    if !chunk.is_null() {
                        (*chunk).next = all_chunks;
                        all_chunks = chunk;
                        (*chunk).next = magazine_heads[idx];
                        magazine_heads[idx] = chunk;
                    }
                }
            }
            curr_config = (*curr_config).next;
        }
        Self { magazine_heads, last_freed, all_chunks }
    }

    unsafe fn alloc_chunk(slot_size: usize, owner_id: u64) -> *mut ChunkHeader {
        let layout = Layout::from_size_align(CHUNK_SIZE, CHUNK_SIZE).unwrap();
        let ptr = MiMalloc.alloc(layout) as *mut ChunkHeader;
        if ptr.is_null() { return null_mut(); }

        let num_slots = (CHUNK_SIZE - ChunkHeader::DATA_OFFSET) / slot_size;

        ptr.write(ChunkHeader {
            magic: ChunkHeader::MAGIC,
            slot_size,
            owner_id,
            bitmap: [!0u64; 512],
            summary: [!0u64; 8],
            free_count: num_slots,
            next: null_mut(),
            remote_free: AtomicPtr::new(null_mut()),
        });
        ptr
    }
}

impl Drop for XrossLocalRuntime {
    fn drop(&mut self) {
        let mut curr = self.all_chunks;
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
            let node_ptr = unsafe { MiMalloc.alloc(layout) as *mut ConfigNode };
            if !node_ptr.is_null() {
                unsafe {
                    node_ptr.write(ConfigNode {
                        size,
                        count,
                        next: CONFIG_HEAD.load(Ordering::Relaxed)
                    });
                    CONFIG_HEAD.store(node_ptr, Ordering::Release);
                }
            }
        }
    }

    pub fn init_thread_pool() {
        POOL_RUNTIME.with(|p| unsafe {
            let opt = &mut *p.get();
            if opt.is_none() {
                *opt = Some(XrossLocalRuntime::init());
            }
        });
    }

    #[inline(always)]
    fn get_class_idx_fast(size: usize) -> Option<usize> {
        if size == 0 || size > 1024 { return None; }
        Some((size.next_power_of_two().trailing_zeros() as usize).saturating_sub(6).min(4))
    }

    unsafe fn remote_dealloc(&self, header: *mut ChunkHeader, ptr: *mut u8) {
        let mut head = (*header).remote_free.load(Ordering::Relaxed);
        loop {
            *(ptr as *mut *mut u8) = head;
            match (*header).remote_free.compare_exchange_weak(
                head, ptr, Ordering::Release, Ordering::Relaxed
            ) {
                Ok(_) => break,
                Err(next_head) => head = next_head,
            }
        }
    }

    #[inline(always)]
    unsafe fn scavenge_remote(&self, chunk_ptr: *mut ChunkHeader) -> bool {
        let chunk = &mut *chunk_ptr;
        let mut ptr = chunk.remote_free.swap(null_mut(), Ordering::Acquire);
        if ptr.is_null() { return false; }

        let mut count = 0;
        while !ptr.is_null() {
            let next = *(ptr as *mut *mut u8);
            let offset = (ptr as usize - (chunk_ptr as usize + ChunkHeader::DATA_OFFSET)) / chunk.slot_size;
            let b_idx = offset >> 6;
            let bit = offset & 63;
            chunk.bitmap[b_idx] |= 1 << bit;
            chunk.summary[b_idx >> 6] |= 1 << (b_idx & 63);
            count += 1;
            ptr = next;
        }
        chunk.free_count += count;
        true
    }

    #[cold]
    unsafe fn alloc_slow(&self, rt: &mut XrossLocalRuntime, idx: usize, layout: Layout) -> *mut u8 {
        // 全てのchunkを走査して空きを探す
        let mut current = rt.magazine_heads[idx];
        while !current.is_null() {
            if self.scavenge_remote(current) {
                // 回収できたので、fast pathで再試行
                return self.alloc(layout);
            }
            if (*current).free_count > 0 {
                // このchunkをheadに移動して優先
                // (簡易round-robin: 使ったchunkをheadに)
                rt.magazine_heads[idx] = current;
                return self.alloc_from_chunk(current, layout);
            }
            current = (*current).next;
        }

        // 新chunk確保
        let slot_size = SIZE_CLASSES[idx];
        let chunk = XrossLocalRuntime::alloc_chunk(slot_size, get_thread_id());
        if chunk.is_null() {
            return MiMalloc.alloc(layout);
        }

        (*chunk).next = rt.magazine_heads[idx];
        rt.magazine_heads[idx] = chunk;
        (*chunk).next = rt.all_chunks;
        rt.all_chunks = chunk;

        // 最初のスロットを返す
        (*chunk).bitmap[0] &= !1;
        (*chunk).summary[0] &= !1;
        (*chunk).free_count -= 1;
        (chunk as usize + ChunkHeader::DATA_OFFSET) as *mut u8
    }

    #[inline(always)]
    unsafe fn alloc_from_chunk(&self, chunk_ptr: *mut ChunkHeader, _layout: Layout) -> *mut u8 {
        let chunk = &mut *chunk_ptr;
        // summaryから高速探索
        let mut s_union: u64 = 0;
        for &s in &chunk.summary {
            if s != 0 {
                s_union |= s;
            }
        }
        if s_union == 0 {
            return null_mut(); // これは起きないはず
        }

        let l0 = s_union.trailing_zeros() as usize;
        let summary_idx = l0 >> 3; // 仮にu64を8つとして調整
        let s = chunk.summary[summary_idx];
        let l1 = s.trailing_zeros() as usize;
        let b_idx = (summary_idx << 6) | l1;
        let b = chunk.bitmap[b_idx];
        let bit = b.trailing_zeros() as usize;

        chunk.bitmap[b_idx] = b & !(1 << bit);
        if chunk.bitmap[b_idx] == 0 {
            chunk.summary[summary_idx] = s & !(1 << l1);
        }
        chunk.free_count -= 1;

        (chunk_ptr as usize + ChunkHeader::DATA_OFFSET + (((b_idx << 6) | bit) * chunk.slot_size)) as *mut u8
    }
}

unsafe impl GlobalAlloc for XrossAllocator {
    #[inline(always)]
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        let size = layout.size();
        if let Some(idx) = XrossAllocator::get_class_idx_fast(size) {
            POOL_RUNTIME.with(|p| {
                let rt_ptr = p.get();
                if (*rt_ptr).is_none() {
                    *rt_ptr = Some(XrossLocalRuntime::init());
                }
                let rt = (*rt_ptr).as_mut().unwrap_unchecked();

                // 1. LIFO Cache
                let cached = rt.last_freed[idx];
                if !cached.is_null() {
                    rt.last_freed[idx] = null_mut();
                    return cached;
                }

                // 2. Magazine Head Scan (fast path)
                let head = rt.magazine_heads[idx];
                if !head.is_null() {
                    if (*head).free_count > 0 {
                        return self.alloc_from_chunk(head, layout);
                    }
                    // 軽くscavenge試行 (再帰なし)
                    self.scavenge_remote(head);
                    if (*head).free_count > 0 {
                        return self.alloc_from_chunk(head, layout);
                    }
                }

                // 3. Slow path
                self.alloc_slow(rt, idx, layout)
            })
        } else {
            MiMalloc.alloc(layout)
        }
    }

    #[inline(always)]
    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        if ptr.is_null() { return; }
        let header_ptr = ChunkHeader::from_ptr(ptr);

        if (*header_ptr).magic == ChunkHeader::MAGIC {
            let tid = get_thread_id();

            if (*header_ptr).owner_id == tid {
                let handled = POOL_RUNTIME.with(|p| {
                    let rt_ptr = p.get();
                    if let Some(rt) = (*rt_ptr).as_mut() {
                        let idx = XrossAllocator::get_class_idx_fast((*header_ptr).slot_size).unwrap_unchecked();
                        if rt.last_freed[idx].is_null() {
                            rt.last_freed[idx] = ptr;
                            return true;
                        }
                    }
                    false
                });
                if handled { return; }

                let chunk = &mut *header_ptr;
                let offset = (ptr as usize - (header_ptr as usize + ChunkHeader::DATA_OFFSET)) / chunk.slot_size;
                let b_idx = offset >> 6;
                let bit = offset & 63;
                chunk.bitmap[b_idx] |= 1 << bit;
                chunk.summary[b_idx >> 6] |= 1 << (b_idx & 63);
                chunk.free_count += 1;
            } else {
                self.remote_dealloc(header_ptr, ptr);
            }
        } else {
            MiMalloc.dealloc(ptr, layout);
        }
    }
}

unsafe impl Sync for XrossAllocator {}
unsafe impl Send for XrossAllocator {}