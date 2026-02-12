use std::fs;
use std::path::PathBuf;
use xross_metadata::XrossDefinition;

pub fn get_xross_dir() -> PathBuf {
    if let Ok(val) = std::env::var("XROSS_METADATA_DIR") {
        return PathBuf::from(val);
    }

    if let Ok(out_dir) = std::env::var("OUT_DIR") {
        let path = PathBuf::from(out_dir);
        let mut target = path.as_path();
        while let Some(parent) = target.parent() {
            if target.file_name().and_then(|n| n.to_str()) == Some("target") {
                return target.join("xross");
            }
            target = parent;
        }
    }

    if let Ok(val) = std::env::var("CARGO_TARGET_DIR") {
        return PathBuf::from(val).join("xross");
    }

    let manifest_dir = std::env::var("CARGO_MANIFEST_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|_| std::env::current_dir().unwrap());

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
}

pub fn get_path_by_signature(signature: &str) -> PathBuf {
    get_xross_dir().join(format!("{}.json", signature))
}

pub fn save_definition(def: &XrossDefinition) {
    let xross_dir = get_xross_dir();
    fs::create_dir_all(&xross_dir).ok();
    let signature = def.signature();
    let path = get_path_by_signature(signature);

    if path.exists() {
        if let Ok(existing_content) = fs::read_to_string(&path) {
            if let Ok(existing_def) = serde_json::from_str::<XrossDefinition>(&existing_content) {
                if !is_structurally_compatible(&existing_def, def) {
                    panic!(
                        "
[Xross Error] Duplicate definition detected for signature: '{}'

                        The same signature is being defined multiple times with different structures.
",
                        signature
                    );
                }
            }
        }
    }

    if let Ok(json) = serde_json::to_string(def) {
        fs::write(&path, json).ok();
    }
}

fn is_structurally_compatible(a: &XrossDefinition, b: &XrossDefinition) -> bool {
    match (a, b) {
        (XrossDefinition::Struct(sa), XrossDefinition::Struct(sb)) => {
            sa.package_name == sb.package_name
                && sa.name == sb.name
                && sa.fields.len() == sb.fields.len()
        }
        (XrossDefinition::Enum(ea), XrossDefinition::Enum(eb)) => {
            ea.package_name == eb.package_name
                && ea.name == eb.name
                && ea.variants.len() == eb.variants.len()
        }
        (XrossDefinition::Opaque(oa), XrossDefinition::Opaque(ob)) => {
            oa.package_name == ob.package_name && oa.name == ob.name
        }
        _ => false,
    }
}

pub fn load_definition(ident: &syn::Ident) -> Option<XrossDefinition> {
    let xross_dir = get_xross_dir();
    if !xross_dir.exists() {
        return None;
    }

    if let Ok(entries) = fs::read_dir(xross_dir) {
        for entry in entries.flatten() {
            if let Ok(content) = fs::read_to_string(entry.path()) {
                if let Ok(def) = serde_json::from_str::<XrossDefinition>(&content) {
                    if def.name() == ident.to_string() {
                        return Some(def);
                    }
                }
            }
        }
    }
    None
}

pub fn discover_signature(type_name: &str) -> Option<String> {
    let xross_dir = get_xross_dir();
    if !xross_dir.exists() {
        return None;
    }

    let mut candidates = Vec::new();

    if let Ok(entries) = fs::read_dir(xross_dir) {
        for entry in entries.flatten() {
            if let Ok(content) = fs::read_to_string(entry.path()) {
                if let Ok(def) = serde_json::from_str::<XrossDefinition>(&content) {
                    if def.name() == type_name {
                        candidates.push(def.signature().to_string());
                    }
                }
            }
        }
    }

    candidates.sort();
    candidates.dedup();

    if candidates.len() == 1 {
        Some(candidates.remove(0))
    } else if candidates.len() > 1 {
        panic!(
            "
[Xross Error] Ambiguous type reference: '{}'

            Multiple types with the same name were found in different packages:

            {}
",
            type_name,
            candidates.iter().map(|s| format!("  - {}", s)).collect::<Vec<_>>().join("
")
        );
    } else {
        None
    }
}
