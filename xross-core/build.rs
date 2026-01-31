use std::{env, fs, path::Path};
use serde::{Serialize, Deserialize}; // serde を追加

// xross-macros/src/lib.rs で定義したメタデータ構造体をコピー
// あるいは、xross-core が xross-macros に依存するようにして、use xross_macros::... とする
// build.rs は Proc-macro に依存できないのでコピーする

#[derive(Debug, Serialize, Deserialize)]
struct FieldMetadata {
    pub name: String,
    pub rust_type: String,
    pub ffi_getter_name: String,
    pub ffi_setter_name: String,
    pub ffi_type: String,
}

#[derive(Debug, Serialize, Deserialize)]
struct MethodMetadata {
    pub name: String,
    pub ffi_name: String,
    pub args: Vec<String>,
    pub return_type: String,
    pub has_self: bool,
    pub is_static: bool,
}

#[derive(Debug, Serialize, Deserialize)]
struct StructMetadata {
    pub name: String,
    pub ffi_prefix: String,
    pub new_fn_name: String,
    pub drop_fn_name: String,
    pub clone_fn_name: String,
    pub fields: Vec<FieldMetadata>,
    pub methods: Vec<MethodMetadata>, // implブロックから生成されるメソッドはここに集約されるべきだが、現在の設計では別々に処理
}

// トップレベルの統合メタデータ構造体
#[derive(Debug, Serialize, Deserialize)]
struct XrossCombinedMetadata {
    pub structs: Vec<StructMetadata>,
    pub methods: Vec<MethodMetadata>, // implブロックから生成されるメソッドはここに集約
}


fn main() {
    println!("cargo:rerun-if-changed=build.rs");
    
    let out_dir = env::var("OUT_DIR").expect("OUT_DIR not set");
    let dest_path = Path::new(&out_dir).join("xross_metadata.json");

    let mut combined_structs: Vec<StructMetadata> = Vec::new();
    let mut combined_methods: Vec<MethodMetadata> = Vec::new();

    let out_dir_path = Path::new(&out_dir);

    for entry in fs::read_dir(out_dir_path).expect("Failed to read OUT_DIR") {
        let entry = entry.expect("Failed to read directory entry");
        let path = entry.path();
        
        if path.is_file() {
            if path.file_name().map_or(false, |name| name.to_string_lossy().ends_with("_struct_metadata.json")) {
                let content = fs::read_to_string(&path).expect(&format!("Failed to read metadata file: {:?}", path));
                let struct_meta: StructMetadata = serde_json::from_str(&content).expect(&format!("Failed to parse StructMetadata from {:?}", path));
                combined_structs.push(struct_meta);
            } else if path.file_name().map_or(false, |name| name.to_string_lossy().ends_with("_method_metadata.json")) {
                let content = fs::read_to_string(&path).expect(&format!("Failed to read metadata file: {:?}", path));
                let method_meta_list: Vec<MethodMetadata> = serde_json::from_str(&content).expect(&format!("Failed to parse MethodMetadata list from {:?}", path));
                combined_methods.extend(method_meta_list);
            }
        }
    }

    let final_combined_metadata = XrossCombinedMetadata {
        structs: combined_structs,
        methods: combined_methods,
    };

    let final_json = serde_json::to_string_pretty(&final_combined_metadata).expect("Failed to serialize combined metadata");
    fs::write(&dest_path, final_json).expect("Failed to write combined metadata to file");

    println!("cargo:warning=xross: Combined metadata written to {:?}", dest_path);
}