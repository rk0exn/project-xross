use xross_core::{JvmClass, jvm_class};

#[derive(JvmClass, Clone)]
/// This is my service struct.
struct MyService {
    _boxes: Vec<i32>,
}

#[jvm_class] // これで impl ブロック全体をスキャンする
impl MyService {
    #[jvm_new]
    pub fn new() -> Self {
        let boxes = vec![0; 1000000];
        MyService { _boxes: boxes }
    }
    #[jvm_method]
    pub fn execute(&self, data: i32) -> i32 {
        data * 2
    }
    #[jvm_method]
    pub fn str_test() -> String {
        "hello, world".to_string()
    }
}

pub mod test {
    use super::*;
    #[derive(JvmClass, Clone)]
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
        pub fn execute(&self) -> i32 {
            self.val * 2
        }
        #[jvm_method]
        pub fn mut_test(&mut self) {
            self.val += 1;
        }
    }
}
