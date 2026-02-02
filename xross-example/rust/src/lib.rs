use xross_core::{JvmClass, jvm_class};

#[derive(JvmClass, Clone)]
/// 巨大なメモリを確保してリークテストを行うためのサービス
pub struct MyService {
    _boxes: Vec<i32>,
}

#[jvm_class]
impl MyService {
    #[jvm_new]
    pub fn new() -> Self {
        let boxes = vec![0; 1_000_000]; // 約4MB
        MyService { _boxes: boxes }
    }

    /// Defaultトレイトの代わりに、Java側から利用しやすいよう明示的に統合
    #[jvm_method]
    pub fn default() -> Self {
        Self::new()
    }

    #[jvm_method(safety = Unsafe)] // 計算のみなのでLock不要
    pub fn execute(&self, data: i32) -> i32 {
        data * 2
    }

    #[jvm_method]
    pub fn str_test() -> String {
        "Hello from Rust!".to_string()
    }

    /// 所有権を消費して自分自身を消滅させる
    #[jvm_method]
    pub fn consume_self(self) -> i32 {
        self._boxes.len() as i32
    }

    #[jvm_method]
    pub fn get_mut_ref(&mut self) -> &mut Self {
        self
    }
}

pub mod test {
    use super::*;

    #[derive(JvmClass, Clone)]
    pub struct MyService2 {
        /// safety = Atomic を指定することで、Java側での VarHandle 生成を促す
        #[jvm_field(safety = Atomic)]
        pub val: i32,
    }

    #[jvm_class(test.test2)]
    impl MyService2 {
        #[jvm_new]
        pub fn new(val: i32) -> Self {
            MyService2 { val }
        }

        #[jvm_method(safety = Atomic)] // Atomic指定でLockをスキップ
        pub fn execute(&self) -> i32 {
            self.val * 2
        }

        #[jvm_method(safety = Atomic)] // 同様にAtomic指定
        pub fn mut_test(&mut self) {
            self.val += 1;
        }

        #[jvm_method]
        pub fn get_self_ref(&self) -> &Self {
            self
        }

        #[jvm_method]
        pub fn create_clone(&self) -> Self {
            self.clone()
        }
    }
}
