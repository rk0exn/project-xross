use xross_core::{JvmClass, jvm_export_impl};

#[derive(JvmClass, Clone)]
pub struct HelloStruct {
    pub i: i32,
    pub f: f64,
}
#[jvm_export_impl]
impl HelloStruct {
    #[jvm_new]
    pub fn new(i: i32, f: f64) -> Self {
        Self { i, f }
    }
    #[jvm_method]
    pub fn add(&self) -> f64 {
        self.i as f64 + self.f
    }
}
