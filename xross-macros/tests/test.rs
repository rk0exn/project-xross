use xross_macros::{XrossClass, xross_class, xross_methods};

#[derive(Clone)]
pub struct DslService {
    pub value: i32,
}

xross_class! {
    package test_dsl;
    class struct DslService;
    is_clonable true;
    field value: i32;
    method &self.get_value() -> i32;
}

impl DslService {
    pub fn get_value(&self) -> i32 {
        self.value
    }
}

#[derive(XrossClass, Clone)]
struct MyService;

#[xross_methods] // これで impl ブロック全体をスキャンする
impl MyService {
    #[xross_new]
    pub fn new() -> Self {
        MyService
    }

    #[xross_method]
    pub fn execute(&self, data: i32) -> i32 {
        data * 2
    }

    #[xross_method(critical(heap_access))]
    pub fn critical_with_heap(&self, a: i32) -> i32 {
        a
    }
}

use xross_macros::xross_function;
#[xross_function(package = "test_func")]
pub fn global_add(a: i32, b: i32) -> i32 {
    a + b
}

use xross_macros::xross_function_dsl;
xross_function_dsl! {
    package test_func;
    fn global_concat(a: String, b: String) -> String;
}

pub fn global_concat(a: String, b: String) -> String {
    format!("{}{}", a, b)
}

pub mod test {
    use super::*;
    #[derive(XrossClass, Clone)]
    pub struct MyService2 {
        pub val: i32,
    }

    #[xross_methods]
    impl MyService2 {
        #[xross_new]
        pub fn new(val: i32) -> Self {
            MyService2 { val }
        }

        #[xross_method]
        pub fn execute(&self, data: i32) -> i32 {
            data * 2
        }
    }
}
