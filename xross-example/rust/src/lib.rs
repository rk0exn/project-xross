#![feature(offset_of_enum)]
use xross_core::{XrossClass, xross_class};

/// 巨大なメモリを確保してリークテストを行うためのサービス
#[derive(XrossClass, Clone)]
pub struct MyService {
    _boxes: Vec<i32>,

    // XrossClassが付与されていない外部構造体。
    // 明示的に opaque 指定を行うことで、Java側へ signature を伝える。
    #[xross_field]
    pub unknown_struct: Box<UnknownStruct>,
}

#[derive(Clone, XrossClass)]
pub struct UnknownStruct {
    #[xross_field]
    pub i: i32,
    #[xross_field]
    pub f: f32,
    #[xross_field]
    pub s: String,
}
#[xross_class]
impl UnknownStruct {
    #[xross_new]
    pub fn new(i: i32, s: String, f: f32) -> Self {
        Self { i, s, f }
    }
}
pub enum UnClonable {
    S,
    Y,
    Z,
}

xross_core::opaque_class!(UnClonable, false);
#[derive(Clone, Copy, XrossClass, Debug, PartialEq)]
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
    pub fn say_hello(self) {
        println!("Hello from Simple::{:?}!", self);
    }
}

#[derive(Clone, XrossClass)]
pub enum XrossTestEnum {
    A,
    B {
        #[xross_field]
        i: i32,
    },
    C {
        #[xross_field]
        j: UnknownStruct,
    },
}
impl Default for UnknownStruct {
    fn default() -> Self {
        Self {
            i: 32,
            f: 64.0,
            s: "Hello, World!".to_string(),
        }
    }
}

#[xross_class]
impl MyService {
    #[xross_new]
    pub fn new() -> Self {
        let boxes = vec![0; 1_000_000]; // 約4MB
        MyService {
            _boxes: boxes,
            unknown_struct: Box::new(UnknownStruct::default()),
        }
    }

    /// Self は自動的に RustStruct { signature: "MyService" } として解析されます
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

    /// 所有権を消費して自分自身を消滅させる
    #[xross_method]
    pub fn consume_self(self) -> i32 {
        self._boxes.len() as i32
    }

    /// &mut Self も RustStruct として正しくポインタ経由で扱われます
    #[xross_method]
    pub fn get_mut_ref(&mut self) -> &mut Self {
        self
    }

    /// Enumを返すテスト
    #[xross_method]
    pub fn ret_enum(&self) -> XrossTestEnum {
        match rand::random_range(0..3) {
            0 => XrossTestEnum::A,
            1 => XrossTestEnum::B { i: rand::random() },
            2 => XrossTestEnum::C {
                j: UnknownStruct::default(),
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

pub mod test {
    use super::*;

    #[derive(XrossClass, Clone)]
    #[xross_package("test.test2")]
    pub struct MyService2 {
        #[xross_field(safety = Atomic)]
        pub val: i32,
    }

    #[xross_class]
    impl MyService2 {
        #[xross_new]
        pub fn new(val: i32) -> Self {
            MyService2 { val }
        }

        #[xross_method(safety = Atomic)]
        pub fn execute(&self) -> i64 {
            self.val as i64 * 2i64
        }

        #[xross_method(safety = Atomic)]
        pub fn mut_test(&mut self) {
            self.val += 1;
        }

        /// パッケージ化された Self (test.test2.MyService2) を自動解決します
        #[xross_method]
        pub fn get_self_ref(&self) -> &Self {
            self
        }

        #[xross_method]
        pub fn create_clone(&self) -> Self {
            self.clone()
        }
    }
}
