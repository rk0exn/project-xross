pub mod heap;

use crate::heap::heap;
use rlsf::Tlsf;
use std::alloc::{GlobalAlloc, Layout, System};
use std::cell::UnsafeCell;
use std::mem::MaybeUninit;
use std::ptr::{self, NonNull};
use std::sync::OnceLock;
use std::sync::atomic::{AtomicPtr, AtomicUsize, Ordering};

// --- 定数定義 (15ビットシフトで32KB境界を判定) ---
const CHUNK_SIZE: usize = 2 * 1024 * 1024;
const LARGE_THRESHOLD: usize = CHUNK_SIZE / 2;
const MAX_CHUNKS: usize = 256;

const SLAB_SIZES: [usize; 6] = [32, 64, 128, 256, 512, 1024];
const SLAB_LENS: [usize; 6] = [1024, 512, 256, 128, 64, 32];
const SLAB_OFFSETS: [usize; 7] = [0, 32768, 65536, 98304, 131072, 163840, 196608];

const SLAB_TOTAL_CAPACITY: usize = SLAB_OFFSETS[6];
const TLSF_CAPACITY: usize = CHUNK_SIZE - SLAB_TOTAL_CAPACITY;

struct FreeNode {
    next: *mut FreeNode,
}

pub struct XrossGlobalAllocator {
    base_ptr: *mut u8,
    size: usize,
    end_ptr: usize,
    next: AtomicUsize,
    free_chunks: AtomicPtr<FreeNode>,
    remote_free_queues: [AtomicPtr<FreeNode>; MAX_CHUNKS],
}

// GlobalAllocatorとしての安全性を保証
unsafe impl Send for XrossGlobalAllocator {}
unsafe impl Sync for XrossGlobalAllocator {}

impl XrossGlobalAllocator {
    pub fn new(ptr: *mut u8, size: usize) -> Self {
        const EMPTY_PTR: AtomicPtr<FreeNode> = AtomicPtr::new(ptr::null_mut());
        Self {
            base_ptr: ptr,
            size,
            end_ptr: ptr as usize + size,
            next: AtomicUsize::new(0),
            free_chunks: AtomicPtr::new(ptr::null_mut()),
            remote_free_queues: [EMPTY_PTR; MAX_CHUNKS],
        }
    }

    #[inline(always)]
    pub unsafe fn alloc_chunk(&self) -> *mut u8 {
        unsafe {
            let reused = self.pop_free_chunk();
            if !reused.is_null() {
                return reused;
            }

            let offset = self.next.fetch_add(CHUNK_SIZE, Ordering::Relaxed);
            if offset + CHUNK_SIZE <= self.size {
                self.base_ptr.add(offset)
            } else {
                ptr::null_mut()
            }
        }
    }

    #[inline(always)]
    pub unsafe fn push_free_chunk(&self, chunk_ptr: *mut u8) {
        unsafe {
            let node_ptr = chunk_ptr as *mut FreeNode;
            let mut head = self.free_chunks.load(Ordering::Acquire);
            loop {
                (*node_ptr).next = head;
                match self.free_chunks.compare_exchange_weak(
                    head,
                    node_ptr,
                    Ordering::Release,
                    Ordering::Acquire,
                ) {
                    Ok(_) => break,
                    Err(new_head) => head = new_head,
                }
            }
        }
    }

    unsafe fn pop_free_chunk(&self) -> *mut u8 {
        unsafe {
            let mut head = self.free_chunks.load(Ordering::Acquire);
            loop {
                if head.is_null() {
                    return ptr::null_mut();
                }
                let next = (*head).next;
                match self.free_chunks.compare_exchange_weak(
                    head,
                    next,
                    Ordering::Release,
                    Ordering::Acquire,
                ) {
                    Ok(_) => return head as *mut u8,
                    Err(new_head) => head = new_head,
                }
            }
        }
    }

    #[inline(always)]
    pub fn is_own(&self, ptr: *mut u8) -> bool {
        let addr = ptr as usize;
        addr >= self.base_ptr as usize && addr < self.end_ptr
    }

    #[inline(always)]
    pub unsafe fn push_remote_free(&self, ptr: *mut u8) {
        unsafe {
            let offset = ptr as usize - self.base_ptr as usize;
            let chunk_idx = offset / CHUNK_SIZE;
            if chunk_idx >= MAX_CHUNKS {
                return;
            }

            let node_ptr = ptr as *mut FreeNode;
            let queue = &self.remote_free_queues[chunk_idx];
            let mut head = queue.load(Ordering::Relaxed);
            loop {
                (*node_ptr).next = head;
                match queue.compare_exchange_weak(
                    head,
                    node_ptr,
                    Ordering::Release,
                    Ordering::Relaxed,
                ) {
                    Ok(_) => break,
                    Err(new_head) => head = new_head,
                }
            }
        }
    }
}

// --- スラブ割り当ての共通化 ---

struct LocalSlab {
    base: *mut u8,
    bump_idx: u32,
    free_list: *mut FreeNode,
}

pub struct XrossLocalAllocator {
    slabs: [LocalSlab; 6],
    tlsf: Tlsf<'static, usize, usize, 12, 16>,
    chunk_ptr: *mut u8,
    alloc_count: u32,
}

impl XrossLocalAllocator {
    #[inline(always)]
    unsafe fn new(chunk: *mut u8) -> Self {
        unsafe {
            let slabs = [
                LocalSlab {
                    base: chunk.add(SLAB_OFFSETS[0]),
                    bump_idx: 0,
                    free_list: ptr::null_mut(),
                },
                LocalSlab {
                    base: chunk.add(SLAB_OFFSETS[1]),
                    bump_idx: 0,
                    free_list: ptr::null_mut(),
                },
                LocalSlab {
                    base: chunk.add(SLAB_OFFSETS[2]),
                    bump_idx: 0,
                    free_list: ptr::null_mut(),
                },
                LocalSlab {
                    base: chunk.add(SLAB_OFFSETS[3]),
                    bump_idx: 0,
                    free_list: ptr::null_mut(),
                },
                LocalSlab {
                    base: chunk.add(SLAB_OFFSETS[4]),
                    bump_idx: 0,
                    free_list: ptr::null_mut(),
                },
                LocalSlab {
                    base: chunk.add(SLAB_OFFSETS[5]),
                    bump_idx: 0,
                    free_list: ptr::null_mut(),
                },
            ];
            let mut tlsf = Tlsf::new();
            let tlsf_slice =
                ptr::slice_from_raw_parts_mut(chunk.add(SLAB_TOTAL_CAPACITY), TLSF_CAPACITY)
                    as *mut [MaybeUninit<u8>];
            tlsf.insert_free_block(&mut *tlsf_slice);
            Self { slabs, tlsf, chunk_ptr: chunk, alloc_count: 0 }
        }
    }

    #[inline(always)]
    fn get_slab_idx_from_offset(offset: usize) -> usize {
        // 全てのスラブサイズは 32KB (32768 bytes) 単位で配置されているため
        // offset >> 15 で 0, 1, 2, 3... とインデックス化できる
        offset >> 15
    }

    #[inline(never)]
    unsafe fn process_remote_frees(&mut self) {
        unsafe {
            let g = xross_global_alloc();
            let chunk_idx = (self.chunk_ptr as usize - g.base_ptr as usize) / CHUNK_SIZE;
            let mut node = g.remote_free_queues[chunk_idx].swap(ptr::null_mut(), Ordering::Acquire);

            while !node.is_null() {
                let next = (*node).next;
                let ptr = node as *mut u8;
                let offset = ptr as usize - self.chunk_ptr as usize;

                if offset < SLAB_TOTAL_CAPACITY {
                    self.slabs[Self::get_slab_idx_from_offset(offset)].dealloc(ptr);
                } else {
                    self.tlsf.deallocate(NonNull::new_unchecked(ptr), 16);
                }
                node = next;
            }
        }
    }
}

impl LocalSlab {
    #[inline(always)]
    unsafe fn alloc(&mut self, idx: usize) -> *mut u8 {
        unsafe {
            if !self.free_list.is_null() {
                let node = self.free_list;
                self.free_list = (*node).next;
                return node as *mut u8;
            }
            if (self.bump_idx as usize) < SLAB_LENS[idx] {
                let ptr = self.base.add(self.bump_idx as usize * SLAB_SIZES[idx]);
                self.bump_idx += 1;
                return ptr;
            }
            ptr::null_mut()
        }
    }

    #[inline(always)]
    unsafe fn dealloc(&mut self, ptr: *mut u8) {
        unsafe {
            let node = ptr as *mut FreeNode;
            (*node).next = self.free_list;
            self.free_list = node;
        }
    }
}

impl Drop for XrossLocalAllocator {
    fn drop(&mut self) {
        unsafe {
            self.process_remote_frees();
            xross_global_alloc().push_free_chunk(self.chunk_ptr);
        }
    }
}

thread_local! {
    static LOCAL_ALLOC: UnsafeCell<Option<XrossLocalAllocator>> = const { UnsafeCell::new(None) };
}

static XROSS_GLOBAL_ALLOC: OnceLock<XrossGlobalAllocator> = OnceLock::new();

#[inline(always)]
fn xross_global_alloc() -> &'static XrossGlobalAllocator {
    XROSS_GLOBAL_ALLOC.get_or_init(|| {
        let h = heap();
        XrossGlobalAllocator::new(h.ptr, h.size)
    })
}

pub struct XrossAlloc;

unsafe impl GlobalAlloc for XrossAlloc {
    #[inline]
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        unsafe {
            let size = layout.size();

            // 分岐のヒント: 1024バイト以下が圧倒的に多いと想定
            if size <= 1024 {
                return LOCAL_ALLOC.with(|cell| {
                    let local_ptr = cell.get();
                    if (*local_ptr).is_none() {
                        let chunk = xross_global_alloc().alloc_chunk();
                        if chunk.is_null() {
                            return System.alloc(layout);
                        }
                        ptr::write(local_ptr, Some(XrossLocalAllocator::new(chunk)));
                    }
                    let local = (*local_ptr).as_mut().unwrap_unchecked();

                    // カウンタ更新 (ビット演算で分岐回避)
                    local.alloc_count = local.alloc_count.wrapping_add(1);
                    if local.alloc_count & 63 == 0 {
                        local.process_remote_frees();
                    }

                    // スラブインデックス計算 (CLZ命令を活用したビット魔法)
                    // 32, 64, 128, 256, 512, 1024 -> idx 0, 1, 2, 3, 4, 5
                    let idx = if size <= 32 {
                        0
                    } else {
                        (usize::BITS - (size - 1).leading_zeros()) as usize - 5
                    };

                    let p = local.slabs[idx].alloc(idx);
                    if !p.is_null() {
                        p
                    } else {
                        local
                            .tlsf
                            .allocate(layout)
                            .map_or_else(|| System.alloc(layout), |p| p.as_ptr())
                    }
                });
            }

            if size > LARGE_THRESHOLD {
                return System.alloc(layout);
            }

            // 中規模（1KB超）はTLSFへ
            LOCAL_ALLOC.with(|cell| {
                let local_ptr = cell.get();
                if let Some(local) = (*local_ptr).as_mut() {
                    local.tlsf.allocate(layout).map_or_else(|| System.alloc(layout), |p| p.as_ptr())
                } else {
                    System.alloc(layout)
                }
            })
        }
    }

    #[inline]
    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        unsafe {
            if ptr.is_null() {
                return;
            }
            let g = xross_global_alloc();

            if !g.is_own(ptr) {
                System.dealloc(ptr, layout);
                return;
            }

            LOCAL_ALLOC.with(|cell| {
                let local_ptr = cell.get();
                if let Some(local) = (*local_ptr).as_mut() {
                    let addr = ptr as usize;
                    let base = local.chunk_ptr as usize;
                    // 境界チェック: addr - base が CHUNK_SIZE 未満なら自分のチャンク
                    let off = addr.wrapping_sub(base);
                    if off < CHUNK_SIZE {
                        if off < SLAB_TOTAL_CAPACITY {
                            local.slabs[XrossLocalAllocator::get_slab_idx_from_offset(off)]
                                .dealloc(ptr);
                        } else {
                            local.tlsf.deallocate(NonNull::new_unchecked(ptr), layout.align());
                        }
                        return;
                    }
                }
                g.push_remote_free(ptr);
            });
        }
    }
}
