use std::ffi::c_void;

pub use xross_macros::{XrossClass, xross_class, opaque_class};

#[repr(C)]
pub struct XrossResult {
    pub ok_ptr: *mut c_void,
    pub err_ptr: *mut c_void,
}

// マクロで生成される共通FFIのためのマーカートレイト
pub trait XrossClass {
    fn xross_layout() -> String;
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn xross_free_string(ptr: *mut std::ffi::c_char) {
    if !ptr.is_null() {
        unsafe {
            let _ = std::ffi::CString::from_raw(ptr);
        }
    }
}