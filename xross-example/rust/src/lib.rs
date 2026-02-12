#![feature(offset_of_enum)]

use std::cmp::{max, min};
use std::sync::atomic::{AtomicIsize, Ordering};
use xross_core::{XrossClass, xross_class};

// --- グローバル・カウンター ---
static SERVICE_COUNT: AtomicIsize = AtomicIsize::new(0);
static UNKNOWN_STRUCT_COUNT: AtomicIsize = AtomicIsize::new(0);
static SERVICE2_COUNT: AtomicIsize = AtomicIsize::new(0);

// --- UnknownStruct ---

#[derive(XrossClass)]
#[repr(C)]
pub struct UnknownStruct {
    #[xross_field]
    pub i: i32,
    #[xross_field]
    pub f: f32,
    #[xross_field]
    pub s: String,
}

// Clone時にもカウントを増やす
impl Clone for UnknownStruct {
    fn clone(&self) -> Self {
        UNKNOWN_STRUCT_COUNT.fetch_add(1, Ordering::SeqCst);
        Self { i: self.i, f: self.f, s: self.s.clone() }
    }
}

impl Drop for UnknownStruct {
    fn drop(&mut self) {
        UNKNOWN_STRUCT_COUNT.fetch_sub(1, Ordering::SeqCst);
    }
}

impl Default for UnknownStruct {
    fn default() -> Self {
        UNKNOWN_STRUCT_COUNT.fetch_add(1, Ordering::SeqCst);
        Self { i: 32, f: 64.0, s: "Hello, World!".to_string() }
    }
}

#[xross_class]
impl UnknownStruct {
    #[xross_new]
    pub fn new(i: i32, s: String, f: f32) -> Self {
        UNKNOWN_STRUCT_COUNT.fetch_add(1, Ordering::SeqCst);
        Self { i, s, f }
    }

    #[xross_method]
    pub fn display_analysis() -> String {
        let s1 = SERVICE_COUNT.load(Ordering::SeqCst);
        let s2 = SERVICE2_COUNT.load(Ordering::SeqCst);
        let u = UNKNOWN_STRUCT_COUNT.load(Ordering::SeqCst);
        format!(
            "--- Xross Native Analysis ---\n\
             Active MyService: {}\n\
             Active MyService2: {}\n\
             Active UnknownStruct: {}\n\
             Total Native Objects: {}\n\
             -----------------------------",
            s1,
            s2,
            u,
            s1 + s2 + u
        )
    }
}

// --- Enum 定義 ---

#[derive(Clone, XrossClass)]
pub enum XrossTestEnum {
    A,
    B {
        #[xross_field]
        i: i32,
    },
    C {
        #[xross_field]
        j: Box<UnknownStruct>,
    },
}

// --- MyService ---

#[derive(XrossClass)]
pub struct MyService {
    _boxes: Vec<i32>,
    #[xross_field]
    pub unknown_struct: Box<UnknownStruct>,
}

impl Clone for MyService {
    fn clone(&self) -> Self {
        SERVICE_COUNT.fetch_add(1, Ordering::SeqCst);
        // 内包する UnknownStruct はその Clone 実装でカウントされる
        Self { _boxes: self._boxes.clone(), unknown_struct: self.unknown_struct.clone() }
    }
}

impl Drop for MyService {
    fn drop(&mut self) {
        SERVICE_COUNT.fetch_sub(1, Ordering::SeqCst);
    }
}

#[derive(Clone, Copy, XrossClass)]
pub enum XrossSimpleEnum {
    V,
    W,
    X,
    Y,
    Z,
}

#[xross_class]
impl XrossSimpleEnum {
    #[xross_method]
    pub fn say_hello(&mut self) {
        println!("Hello, world!");
    }
}

#[xross_class]
impl MyService {
    #[xross_new]
    pub fn new() -> Self {
        SERVICE_COUNT.fetch_add(1, Ordering::SeqCst);
        // UnknownStruct::default() 内でカウント +1 済み
        MyService { _boxes: vec![0; 1_000_000], unknown_struct: Box::new(UnknownStruct::default()) }
    }

    #[xross_method]
    pub fn consume_self(self) -> i32 {
        self._boxes.len() as i32
    }

    #[xross_method]
    pub fn ret_enum(&self) -> XrossTestEnum {
        match rand::random_range(0..3) {
            1 => XrossTestEnum::B { i: rand::random() },
            2 => XrossTestEnum::C {
                // ここで生成される際、UnknownStruct::default()によりカウンターが増える
                j: Box::new(UnknownStruct::default()),
            },
            _ => XrossTestEnum::A,
        }
    }

    #[xross_method]
    pub fn execute(&mut self, i: usize) -> i32 {
        let a = self._boxes.len();
        let x = min(a, i);
        let y = max(a, i);
        rand::random_range(x..y + 1) as i32
    }

    #[xross_method]
    pub fn get_option_enum(&self, should_some: bool) -> Option<XrossSimpleEnum> {
        if should_some { Some(XrossSimpleEnum::V) } else { None }
    }

    #[xross_method]
    pub fn get_result_struct(&self, should_ok: bool) -> Result<test::MyService2, String> {
        if should_ok { Ok(test::MyService2::new(1)) } else { Err("Error".to_string()) }
    }
}

// --- サブモジュール ---

pub mod test {
    use super::*;

    #[derive(XrossClass)]
    #[xross_package("test.test2")]
    #[repr(C)]
    pub struct MyService2 {
        #[xross_field(safety = Atomic)]
        pub val: i32,
    }

    impl Clone for MyService2 {
        fn clone(&self) -> Self {
            SERVICE2_COUNT.fetch_add(1, Ordering::SeqCst);
            Self { val: self.val }
        }
    }

    impl Drop for MyService2 {
        fn drop(&mut self) {
            SERVICE2_COUNT.fetch_sub(1, Ordering::SeqCst);
        }
    }

    #[xross_class]
    impl MyService2 {
        #[xross_new]
        pub fn new(val: i32) -> Self {
            SERVICE2_COUNT.fetch_add(1, Ordering::SeqCst);
            MyService2 { val }
        }

        #[xross_method]
        pub fn create_clone(&self) -> Self {
            self.clone()
        }

        #[xross_method]
        pub fn get_self_ref(&self) -> &Self {
            self
        }

        #[xross_method]
        pub fn execute(&self) -> f64 {
            if self.val == 0 {
                return 0.0;
            }
            let low = min(-self.val, self.val);
            let high = max(-self.val, self.val);
            rand::random_range(low..high + 1) as f64
        }
    }
}

pub enum UnClonable {
    S,
    Y,
    Z,
}
xross_core::opaque_class!(UnClonable, false);
