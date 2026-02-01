use xross_core::{JvmClass, jvm_class};

#[derive(JvmClass, Clone)]
#[repr(C)]
/// This is my service struct.
struct MyService;

#[jvm_class] // これで impl ブロック全体をスキャンする
impl MyService {
    #[jvm_new]
    pub fn new() -> Self {
        MyService
    }
    #[jvm_method]
    pub fn execute(&self, data: i32) -> i32 {
        data * 2
    }
}

pub mod test {
    use super::*;
    #[derive(JvmClass, Clone)]
    #[repr(C)]
    pub struct MyService2 {
        #[jvm_field]
        pub val: i32,
    }

    #[jvm_class(test.test2)] // これで impl ブロック全体をスキャンする
    impl MyService2 {
        #[jvm_new]
        pub fn new(val: i32) -> Self {
            MyService2 { val }
        }

        #[jvm_method]
        pub fn execute(&self, data: i32) -> i32 {
            data * 2
        }
    }
}
