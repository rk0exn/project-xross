use jemallocator::Jemalloc;
use mimalloc::MiMalloc;
use rand::prelude::*;
use rpmalloc::RpMalloc;
use snmalloc_rs::SnMalloc;
use std::alloc::{GlobalAlloc, Layout, System};
use std::thread;
use std::time::Instant;
use tcmalloc::TCMalloc;
use xross_alloc::XrossAlloc;

const THREADS: usize = 32;
const OPS_PER_THREAD: usize = 200_000;

const ITERS: usize = 100; // 試行回数

type Scenario = (&'static str, for<'a> fn(&'a dyn GlobalAlloc));
fn main() {
    let xross = Box::leak(Box::new(XrossAlloc));
    let sys = Box::leak(Box::new(System));

    let mi = Box::leak(Box::new(MiMalloc));
    let je = Box::leak(Box::new(Jemalloc));
    let tc = Box::leak(Box::new(TCMalloc));
    let sn = Box::leak(Box::new(SnMalloc));
    let rpm = Box::leak(Box::new(RpMalloc));

    let scenarios: [Scenario; 5] = [
        ("Scenario 1: Burst", burst_alloc_dealloc),
        ("Scenario 2: Mixed", mixed_size_alloc),
        ("Scenario 3: Shuffle", random_shuffle_alloc),
        ("Scenario 4: Locality", locality_sum_bench),
        ("Scenario 5: Frag", fragmentation_bench),
    ];
    for (name, func) in scenarios {
        println!("{}", name);
        run_averaged_bench("sys", sys, func);
        run_averaged_bench("Xross", xross, func);

        #[cfg(not(target_os = "macos"))]
        {
            run_averaged_bench("mimalloc", mi, func);
            run_averaged_bench("JE", je, func);
            run_averaged_bench("tc", tc, func);
            run_averaged_bench("Sn", sn, func);
            run_averaged_bench("RPM", rpm, func);
        }
        println!();
    }
}

fn run_averaged_bench<A: GlobalAlloc + 'static + Sync>(
    name: &str,
    alloc: &'static A,
    f: fn(&dyn GlobalAlloc),
) {
    let mut results = Vec::with_capacity(ITERS);

    for _ in 0..ITERS {
        let start = Instant::now();
        let mut handles = vec![];

        for _ in 0..THREADS {
            handles.push(thread::spawn(move || {
                f(alloc);
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

fn random_shuffle_alloc(alloc: &dyn GlobalAlloc) {
    let layout = Layout::from_size_align(64, 16).unwrap();
    let mut rng = StdRng::seed_from_u64(42);
    let chunk_size = 500;
    let mut ptrs = Vec::with_capacity(chunk_size);

    for _ in 0..(OPS_PER_THREAD / chunk_size) {
        (0..chunk_size).for_each(|_| unsafe {
            ptrs.push(alloc.alloc(layout));
        });
        ptrs.shuffle(&mut rng);
        for ptr in ptrs.drain(..) {
            unsafe {
                alloc.dealloc(ptr, layout);
            }
        }
    }
}

fn locality_sum_bench(alloc: &dyn GlobalAlloc) {
    let layout = Layout::from_size_align(64, 16).unwrap();
    let count = 1000;
    let mut ptrs = Vec::with_capacity(count);

    for _ in 0..(OPS_PER_THREAD / count) {
        for i in 0..count {
            unsafe {
                let ptr = alloc.alloc(layout);
                ptr.write(i as u8);
                ptrs.push(ptr);
            }
        }

        let mut sum: u64 = 0;
        for ptr in &ptrs {
            unsafe {
                sum += (**ptr) as u64;
            }
        }
        std::hint::black_box(sum);

        for ptr in ptrs.drain(..) {
            unsafe {
                alloc.dealloc(ptr, layout);
            }
        }
    }
}

fn fragmentation_bench(alloc: &dyn GlobalAlloc) {
    let layout = Layout::from_size_align(64, 16).unwrap();
    let persistent_count = 1500;
    let temp_count = 500;

    unsafe {
        let mut persistent = Vec::with_capacity(persistent_count);
        for _ in 0..persistent_count {
            persistent.push(alloc.alloc(layout));
        }

        for _ in 0..(OPS_PER_THREAD / temp_count) {
            let mut temps = Vec::with_capacity(temp_count);
            for _ in 0..temp_count {
                temps.push(alloc.alloc(layout));
            }
            for ptr in temps {
                alloc.dealloc(ptr, layout);
            }
        }

        for ptr in persistent {
            alloc.dealloc(ptr, layout);
        }
    }
}
