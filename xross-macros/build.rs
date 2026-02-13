use std::fs;
use std::path::PathBuf;

fn main() {
    // 1. 手動設定の環境変数を最優先
    let mut xross_dir = None;

    if let Ok(val) = std::env::var("XROSS_METADATA_DIR") {
        xross_dir = Some(PathBuf::from(val));
    }

    // 2. OUT_DIR から遡って target ディレクトリを特定する
    if xross_dir.is_none()
        && let Ok(out_dir) = std::env::var("OUT_DIR")
    {
        let path = PathBuf::from(out_dir);
        let mut target = path.as_path();
        while let Some(parent) = target.parent() {
            if target.file_name().and_then(|n| n.to_str()) == Some("target") {
                xross_dir = Some(target.join("xross"));
                break;
            }
            target = parent;
        }
    }

    // 3. CARGO_TARGET_DIR 環境変数をチェック
    if xross_dir.is_none()
        && let Ok(val) = std::env::var("CARGO_TARGET_DIR")
    {
        xross_dir = Some(PathBuf::from(val).join("xross"));
    }

    // 4. フォールバック: ワークスペースルートを探索
    let xross_dir = xross_dir.unwrap_or_else(|| {
        let manifest_dir = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").unwrap());
        let mut current = manifest_dir.as_path();
        let mut root = current;
        while let Some(parent) = current.parent() {
            if parent.join("Cargo.toml").exists() {
                root = parent;
                current = parent;
            } else {
                break;
            }
        }
        root.join("target").join("xross")
    });

    if xross_dir.exists()
        && let Err(e) = fs::remove_dir_all(&xross_dir)
    {
        eprintln!("cargo:warning=Failed to delete directory {:?}: {}", xross_dir, e);
    }

    if let Err(e) = fs::create_dir_all(&xross_dir) {
        eprintln!("cargo:warning=Failed to create directory {:?}: {}", xross_dir, e);
    }

    println!("cargo:rerun-if-env-changed=XROSS_METADATA_DIR");
    println!("cargo:rerun-if-env-changed=CARGO_TARGET_DIR");
    println!("cargo:rerun-if-changed=src/lib.rs");
    println!("cargo:rerun-if-changed=src/utils.rs");
}
