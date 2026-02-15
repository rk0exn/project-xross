use jemallocator::Jemalloc;
use mimalloc::MiMalloc;
use rand::prelude::*;
use rpmalloc::RpMalloc;
use snmalloc_rs::SnMalloc;
use std::alloc::{GlobalAlloc, Layout, System};
use std::thread;
use std::time::Instant;
use tcmalloc::TCMalloc;
use xross_alloc::XrossAllocator;

const THREADS: usize = 32;
const OPS_PER_THREAD: usize = 200_000;

const ITERS: usize = 100; // 試行回数

type Scenario = (&'static str, for<'a> fn(&'a dyn GlobalAlloc));
fn main() {
    XrossAllocator::setup(&[
        (64, 8192),  // 小さな構造体、エンティティのフラグ、座標など
        (128, 4096), // 中くらいの構造体、アイテムスタック、NBTの一部
        (256, 2048), // チャンクセクションの一部、ブロック状態、大きなNBT
        (512, 1024), // 比較的大きなオブジェクト（稀）
        (1024, 512), // とても大きなもの（ほとんど使わないかも）
    ]);
    let xross = Box::leak(Box::new(XrossAllocator));
    let mi = Box::leak(Box::new(MiMalloc));
    let sys = Box::leak(Box::new(System));
    let je = Box::leak(Box::new(Jemalloc));
    let tc = Box::leak(Box::new(TCMalloc));
    let sn = Box::leak(Box::new(SnMalloc));
    let rpm = Box::leak(Box::new(RpMalloc));
    // --- main 内のループ ---
    // 関数ポインタの型を dyn GlobalAlloc 向けに固定します
    let scenarios: [Scenario; 5] = [
        ("Scenario 1: Burst", burst_alloc_dealloc),
        ("Scenario 2: Mixed", mixed_size_alloc),
        ("Scenario 3: Shuffle", random_shuffle_alloc),
        ("Scenario 4: Locality", locality_sum_bench),
        ("Scenario 5: Frag", fragmentation_bench),
    ];
    for (name, func) in scenarios {
        println!("{}", name);
        // これで A が何であっても、関数が要求するのは &dyn GlobalAlloc なので一致します
        run_averaged_bench("sys", sys, func);
        run_averaged_bench("mimalloc", mi, func);
        run_averaged_bench("Xross", xross, func);
        run_averaged_bench("JE", je, func);
        run_averaged_bench("tc", tc, func);
        run_averaged_bench("Sn", sn, func);
        run_averaged_bench("RPM", rpm, func);
        println!();
    }
}

// --- ベンチマークランナーの修正 ---
fn run_averaged_bench<A: GlobalAlloc + 'static + Sync>(
    name: &str,
    alloc: &'static A,
    f: fn(&dyn GlobalAlloc), // ここを具体的な A ではなく dyn に変更
) {
    let mut results = Vec::with_capacity(ITERS);

    for _ in 0..ITERS {
        let start = Instant::now();
        let mut handles = vec![];

        for _ in 0..THREADS {
            handles.push(thread::spawn(move || {
                f(alloc); // A は GlobalAlloc を実装しているので dyn として渡せる
            }));
        }
        for h in handles {
            h.join().unwrap();
        }

        let duration = start.elapsed();
        let total_ops = THREADS * OPS_PER_THREAD;
        results.push((duration.as_nanos() as f64) / (total_ops as f64));
    }

    results.sort_by(|a, b| a.partial_cmp(b).unwrap());
    let avg: f64 = results.iter().sum::<f64>() / (ITERS as f64);
    let min = results[0];

    println!("  {:<15}: {:>10.2} ns/op (min: {:>6.2} ns/op)", name, avg, min);
}

// --- シナリオ1: 一括確保・一括解放 ---
fn burst_alloc_dealloc(alloc: &dyn GlobalAlloc) {
    let layout = Layout::from_size_align(64, 16).unwrap();
    let mut ptrs = Vec::with_capacity(1000);
    for _ in 0..(OPS_PER_THREAD / 1000) {
        for _ in 0..1000 {
            unsafe {
                ptrs.push(alloc.alloc(layout));
            }
        }
        for ptr in ptrs.drain(..) {
            unsafe {
                alloc.dealloc(ptr, layout);
            }
        }
    }
}

// --- シナリオ2: 複数サイズの混在 ---
fn mixed_size_alloc(alloc: &dyn GlobalAlloc) {
    let sizes = [64, 128, 256, 1024];
    let layouts: Vec<_> = sizes.iter().map(|&s| Layout::from_size_align(s, 16).unwrap()).collect();

    for i in 0..OPS_PER_THREAD {
        let layout = layouts[i % 4];
        unsafe {
            let ptr = alloc.alloc(layout);
            alloc.dealloc(ptr, layout);
        }
    }
}

// --- シナリオ3: ランダムシャッフル解放 ---
fn random_shuffle_alloc(alloc: &dyn GlobalAlloc) {
    let layout = Layout::from_size_align(64, 16).unwrap();
    let mut rng = StdRng::seed_from_u64(42);
    let chunk_size = 500;
    let mut ptrs = Vec::with_capacity(chunk_size);

    for _ in 0..(OPS_PER_THREAD / chunk_size) {
        for _ in 0..chunk_size {
            unsafe {
                ptrs.push(alloc.alloc(layout));
            }
        }
        ptrs.shuffle(&mut rng); // 解放順をバラバラにする
        for ptr in ptrs.drain(..) {
            unsafe {
                alloc.dealloc(ptr, layout);
            }
        }
    }
}

// --- シナリオ 4: 局所性テスト ---
// 確保した領域に値を書き込み、その合計を計算する。
// ビットマップ方式（Xross）は物理的に近い位置を返すため、キャッシュヒット率で勝る。
fn locality_sum_bench(alloc: &dyn GlobalAlloc) {
    let layout = Layout::from_size_align(64, 16).unwrap();
    let count = 1000;
    let mut ptrs = Vec::with_capacity(count);

    for _ in 0..(OPS_PER_THREAD / count) {
        // 1. 連続確保
        for i in 0..count {
            unsafe {
                let ptr = alloc.alloc(layout);
                ptr.write(i as u8);
                ptrs.push(ptr);
            }
        }

        // 2. 読み取り（CPUキャッシュ効率の測定）
        let mut sum: u64 = 0;
        for ptr in &ptrs {
            unsafe {
                sum += (**ptr) as u64;
            }
        }
        std::hint::black_box(sum); // 最適化での削除を防止

        // 3. 解放
        for ptr in ptrs.drain(..) {
            unsafe {
                alloc.dealloc(ptr, layout);
            }
        }
    }
}

// --- シナリオ 5: 断片化テスト ---
// メモリの 75% を保持したまま、残り 25% で激しく Alloc/Dealloc を行う
fn fragmentation_bench(alloc: &dyn GlobalAlloc) {
    let layout = Layout::from_size_align(64, 16).unwrap();
    let persistent_count = 1500; // プールの多くを占有
    let temp_count = 500;

    unsafe {
        // 常駐メモリの確保
        let mut persistent = Vec::with_capacity(persistent_count);
        for _ in 0..persistent_count {
            persistent.push(alloc.alloc(layout));
        }

        // 一時メモリの激しい入れ替え
        for _ in 0..(OPS_PER_THREAD / temp_count) {
            let mut temps = Vec::with_capacity(temp_count);
            for _ in 0..temp_count {
                temps.push(alloc.alloc(layout));
            }
            for ptr in temps {
                alloc.dealloc(ptr, layout);
            }
        }

        // 最後に常駐メモリを解放
        for ptr in persistent {
            alloc.dealloc(ptr, layout);
        }
    }
}
