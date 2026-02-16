use std::ffi::c_void;

pub use xross_macros::{
    XrossClass, xross_class, xross_function, xross_function_dsl, xross_methods,
};

#[cfg(feature = "xross-alloc")]
pub use xross_alloc::xross_alloc_init;

#[cfg(feature = "xross-alloc")]
#[global_allocator]
static ALLOC: xross_alloc::XrossAlloc = xross_alloc::XrossAlloc::new();

#[repr(C)]
pub struct XrossResult {
    pub is_ok: bool,
    pub ptr: *mut c_void,
}

unsafe impl Send for XrossResult {}
unsafe impl Sync for XrossResult {}

/// Represent a Rust String (Vec<u8>) passed to the JVM.
#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct XrossString {
    pub ptr: *mut u8,
    pub len: usize,
    pub cap: usize,
}

impl From<String> for XrossString {
    fn from(mut s: String) -> Self {
        let ptr = s.as_mut_ptr();
        let len = s.len();
        let cap = s.capacity();
        std::mem::forget(s);
        Self { ptr, len, cap }
    }
}

impl XrossString {
    /// Converts the `XrossString` back into a Rust `String`.
    ///
    /// # Safety
    ///
    /// This function is unsafe because it reconstructs a `String` from raw parts.
    /// The caller must ensure that:
    /// - The `ptr`, `len`, and `cap` were originally created by a valid `String`.
    /// - This function is called exactly once for a given set of raw parts to avoid double-free.
    /// - The memory was allocated by the same allocator Rust expects.
    pub unsafe fn into_string(self) -> String {
        unsafe { String::from_raw_parts(self.ptr, self.len, self.cap) }
    }
}

/// Represent a String view passed from the JVM to Rust.
/// ptr points to the raw internal bytes of the JVM String.
#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct XrossStringView {
    pub ptr: *const u8,
    pub len: usize,
    pub encoding: u8, // 0: Latin1, 1: UTF-16
}

impl XrossStringView {
    pub fn to_string_lossy(&self) -> String {
        if self.ptr.is_null() || self.len == 0 {
            return String::new();
        }
        match self.encoding {
            0 => {
                // Latin1 (ISO-8859-1) - Direct map to chars
                let slice = unsafe { std::slice::from_raw_parts(self.ptr, self.len) };
                slice.iter().map(|&b| b as char).collect()
            }
            1 => {
                // UTF-16 (Little Endian on most modern JVMs/Platforms)
                let slice = unsafe { std::slice::from_raw_parts(self.ptr as *const u16, self.len) };
                String::from_utf16_lossy(slice)
            }
            _ => String::new(),
        }
    }
}

#[repr(C)]
pub struct XrossTask {
    pub task_ptr: *mut c_void,
    pub poll_fn: unsafe extern "C" fn(*mut c_void) -> XrossResult,
    pub drop_fn: unsafe extern "C" fn(*mut c_void),
}

unsafe impl Send for XrossTask {}
unsafe impl Sync for XrossTask {}

#[cfg(feature = "tokio")]
use std::sync::OnceLock;
#[cfg(feature = "tokio")]
static RUNTIME: OnceLock<tokio::runtime::Runtime> = OnceLock::new();

#[cfg(feature = "tokio")]
fn get_runtime() -> &'static tokio::runtime::Runtime {
    RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .expect("Failed to create Tokio runtime")
    })
}

#[cfg(feature = "tokio")]
pub fn xross_spawn_task<F, T>(future: F, mapper: fn(T) -> XrossResult) -> XrossTask
where
    F: std::future::Future<Output = T> + Send + 'static,
    T: Send + 'static,
{
    let rt = get_runtime();
    let (tx, rx) = tokio::sync::mpsc::unbounded_channel();

    rt.spawn(async move {
        let res = future.await;
        let _ = tx.send(mapper(res));
    });

    unsafe extern "C" fn poll_task(ptr: *mut c_void) -> XrossResult {
        let rx = unsafe { &mut *(ptr as *mut tokio::sync::mpsc::UnboundedReceiver<XrossResult>) };
        match rx.try_recv() {
            Ok(res) => res,
            Err(tokio::sync::mpsc::error::TryRecvError::Empty) => {
                XrossResult { is_ok: true, ptr: std::ptr::null_mut() }
            }
            Err(_) => XrossResult { is_ok: false, ptr: std::ptr::null_mut() },
        }
    }

    unsafe extern "C" fn drop_task(ptr: *mut c_void) {
        let _ =
            unsafe { Box::from_raw(ptr as *mut tokio::sync::mpsc::UnboundedReceiver<XrossResult>) };
    }

    XrossTask {
        task_ptr: Box::into_raw(Box::new(rx)) as *mut c_void,
        poll_fn: poll_task,
        drop_fn: drop_task,
    }
}

pub trait XrossClass {
    fn xross_layout() -> String;
}

/// Frees a string allocated by Rust that was passed to the JVM.
///
/// # Safety
///
/// This function is unsafe because it takes a `XrossString` which contains a raw pointer
/// and deallocates the memory by reconstructing the `String`.
/// The caller must ensure that the `XrossString` was originally provided by the Xross bridge
/// and has not been freed yet.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn xross_free_string(xs: XrossString) {
    if !xs.ptr.is_null() {
        drop(unsafe { xs.into_string() });
    }
}
