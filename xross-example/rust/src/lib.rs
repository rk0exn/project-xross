#![feature(offset_of_enum)]
use std::sync::atomic::{AtomicIsize, Ordering};
use xross_core::{xross_class, XrossClass};

// --- グローバル・アナライザー・カウンター ---
static SERVICE_COUNT: AtomicIsize = AtomicIsize::new(0);
static UNKNOWN_STRUCT_COUNT: AtomicIsize = AtomicIsize::new(0);

fn report_leak(name: &str, count: isize) {
    if count > 0 {
        // println! は JNI 経由だと標準出力で見えない場合があるため、
        // 実際の実装では log crate 等を推奨します。
        println!("[Xross Analyzer] {} dropped. Remaining: {}", name, count);
    }
}

// --- 構造体定義 ---

#[derive(XrossClass, Clone)]
#[repr(C)]
pub struct MyService {
    _boxes: Vec<i32>,
    #[xross_field]
    pub unknown_struct: Box<UnknownStruct>,
}

// 手動ドロップ実装でカウントを減らす
impl Drop for MyService {
    fn drop(&mut self) {
        let count = SERVICE_COUNT.fetch_sub(1, Ordering::SeqCst) - 1;
        report_leak("MyService", count);
    }
}

#[derive(Clone, XrossClass)]
#[repr(C)]
pub struct UnknownStruct {
    #[xross_field]
    pub i: i32,
    #[xross_field]
    pub f: f32,
    #[xross_field]
    pub s: String,
}

impl Drop for UnknownStruct {
    fn drop(&mut self) {
        let count = UNKNOWN_STRUCT_COUNT.fetch_sub(1, Ordering::SeqCst) - 1;
        report_leak("UnknownStruct", count);
    }
}

#[xross_class]
impl UnknownStruct {
    #[xross_new]
    pub fn new(i: i32, s: String, f: f32) -> Self {
        UNKNOWN_STRUCT_COUNT.fetch_add(1, Ordering::SeqCst);
        Self { i, s, f }
    }

    /// 現在のネイティブ側での生存数を文字列として返す分析関数
    #[xross_method]
    pub fn display_analysis() -> String {
        let s_count = SERVICE_COUNT.load(Ordering::SeqCst);
        let u_count = UNKNOWN_STRUCT_COUNT.load(Ordering::SeqCst);
        format!(
            "--- Xross Native Analysis ---\n\
             Active MyService: {}\n\
             Active UnknownStruct: {}\n\
             Total Native Memory Pressure: ~{} MB\n\
             -----------------------------",
            s_count,
            u_count,
            s_count * 4 // MyServiceは約4MBのVecを持つため
        )
    }
}

// --- Enum 定義 ---

#[derive(Clone, Copy, XrossClass, Debug, PartialEq)]
#[repr(C)]
pub enum XrossSimpleEnum {
    V, W, X, Y, Z,
}

#[xross_class]
impl XrossSimpleEnum {
    #[xross_method]
    pub fn say_hello(self) {
        println!("Hello from Simple::{:?}!", self);
    }
}

#[derive(Clone, XrossClass)]
#[repr(C)]
pub enum XrossTestEnum {
    A,
    B { #[xross_field] i: i32 },
    C { #[xross_field] j: Box<UnknownStruct> },
}

// --- MyService 実装 ---

#[xross_class]
impl MyService {
    #[xross_new]
    pub fn new() -> Self {
        SERVICE_COUNT.fetch_add(1, Ordering::SeqCst);
        UNKNOWN_STRUCT_COUNT.fetch_add(1, Ordering::SeqCst); // Box内部の分
        let boxes = vec![0; 1_000_000]; // 約4MB
        MyService {
            _boxes: boxes,
            unknown_struct: Box::new(UnknownStruct::default()),
        }
    }

    #[xross_method]
    pub fn default() -> Self {
        Self::new()
    }

    #[xross_method(safety = Unsafe)]
    pub fn execute(&self, data: i32) -> i32 {
        data * 2
    }

    #[xross_method]
    pub fn str_test() -> String {
        "Hello from Rust!".to_string()
    }

    #[xross_method]
    pub fn consume_self(self) -> i32 {
        self._boxes.len() as i32
        // ここで self がドロップされ、カウンターが減る
    }

    #[xross_method]
    pub fn get_mut_ref(&mut self) -> &mut Self {
        self
    }

    #[xross_method]
    pub fn ret_enum(&self) -> XrossTestEnum {
        match rand::random_range(0..3) {
            0 => XrossTestEnum::A,
            1 => XrossTestEnum::B { i: rand::random() },
            2 => {
                XrossTestEnum::C {
                    j: Box::new(UnknownStruct::default()),
                }
            },
            _ => XrossTestEnum::A,
        }
    }

    #[xross_method]
    pub fn get_option_struct(&self, flag: bool) -> Option<UnknownStruct> {
        if flag {
            Some(UnknownStruct::default())
        } else {
            None
        }
    }

    #[xross_method]
    pub fn get_result_struct(&self, flag: bool) -> Result<UnknownStruct, String> {
        if flag {
            Ok(UnknownStruct::default())
        } else {
            Err("Error from Rust!".to_string())
        }
    }
}

impl Default for UnknownStruct {
    fn default() -> Self {
        UNKNOWN_STRUCT_COUNT.fetch_add(1, Ordering::SeqCst);
        Self {
            i: 32,
            f: 64.0,
            s: "Hello, World!".to_string(),
        }
    }
}

// --- サブモジュール ---

pub mod test {
    use super::*;
    static SERVICE2_COUNT: AtomicIsize = AtomicIsize::new(0);

    #[derive(XrossClass, Clone)]
    #[xross_package("test.test2")]
    #[repr(C)]
    pub struct MyService2 {
        #[xross_field(safety = Atomic)]
        pub val: i32,
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

        #[xross_method(safety = Atomic)]
        pub fn execute(&self) -> i64 {
            self.val as i64 * 2i64
        }

        #[xross_method]
        pub fn get_self_ref(&self) -> &Self {
            self
        }

        #[xross_method]
        pub fn create_clone(&self) -> Self {
            SERVICE2_COUNT.fetch_add(1, Ordering::SeqCst);
            self.clone()
        }
    }
}

// Opaque設定
pub enum UnClonable { S, Y, Z }
xross_core::opaque_class!(UnClonable, false);
