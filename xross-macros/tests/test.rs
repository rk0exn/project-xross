use xross_macros::JvmClass;
use xross_macros::jvm_class;

#[derive(JvmClass, Clone)]
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
    pub struct MyService2 {
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
