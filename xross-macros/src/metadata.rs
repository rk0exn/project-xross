use std::fs;
use std::path::PathBuf;
use xross_metadata::XrossDefinition;

/// Returns the directory where xross metadata files are stored.
/// It tries to find the target directory of the cargo project.
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

/// Returns the file path for a given signature.
pub fn get_path_by_signature(signature: &str) -> PathBuf {
    get_xross_dir().join(format!("{}.json", signature))
}

/// Saves the type definition to a JSON file in the metadata directory.
/// Performs compatibility checks if a definition already exists.
pub fn save_definition(def: &XrossDefinition) {
    let xross_dir = get_xross_dir();
    fs::create_dir_all(&xross_dir).ok();
    let signature = def.signature();
    let path = get_path_by_signature(signature);

    let mut final_def = def.clone();

    // Deduplicate methods before saving
    match &mut final_def {
        XrossDefinition::Struct(s) => deduplicate_methods(&mut s.methods),
        XrossDefinition::Enum(e) => deduplicate_methods(&mut e.methods),
        XrossDefinition::Opaque(o) => deduplicate_methods(&mut o.methods),
        XrossDefinition::Function(_) => {}
    }

    if path.exists()
        && let Ok(existing_content) = fs::read_to_string(&path)
        && let Ok(existing_def) = serde_json::from_str::<XrossDefinition>(&existing_content)
        && !is_structurally_compatible(&existing_def, &final_def)
    {
        panic!(
            "\n[Xross Error] Duplicate definition detected for signature: '{}'\n\
             The same signature is being defined multiple times with different structures.\n",
            signature
        );
    }

    if let Ok(json) = serde_json::to_string(&final_def) {
        fs::write(&path, json).ok();
    }
}

fn deduplicate_methods(methods: &mut Vec<xross_metadata::XrossMethod>) {
    let mut seen = std::collections::HashSet::new();
    methods.retain(|m| {
        let key = (m.name.clone(), m.symbol.clone());
        if seen.contains(&key) {
            false
        } else {
            seen.insert(key);
            true
        }
    });
}

/// Checks if two definitions are structurally compatible.
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
        (XrossDefinition::Function(fa), XrossDefinition::Function(fb)) => {
            fa.package_name == fb.package_name && fa.name == fb.name
        }
        _ => false,
    }
}

/// Loads a definition from the metadata directory by its identifier name.
pub fn load_definition(ident: &syn::Ident) -> Option<XrossDefinition> {
    let xross_dir = get_xross_dir();
    if !xross_dir.exists() {
        return None;
    }

    if let Ok(entries) = fs::read_dir(xross_dir) {
        for entry in entries.flatten() {
            if let Ok(content) = fs::read_to_string(entry.path())
                && let Ok(def) = serde_json::from_str::<XrossDefinition>(&content)
                && *ident == def.name()
            {
                return Some(def);
            }
        }
    }
    None
}

/// Discovers the signature of a type by its name.
/// Panics if multiple types with the same name are found in different packages.
pub fn discover_signature(type_name: &str) -> Option<String> {
    let xross_dir = get_xross_dir();
    if !xross_dir.exists() {
        return None;
    }

    let mut candidates = Vec::new();

    if let Ok(entries) = fs::read_dir(xross_dir) {
        for entry in entries.flatten() {
            if let Ok(content) = fs::read_to_string(entry.path())
                && let Ok(def) = serde_json::from_str::<XrossDefinition>(&content)
                && def.name() == type_name
            {
                candidates.push(def.signature().to_string());
            }
        }
    }

    candidates.sort();
    candidates.dedup();

    if candidates.len() == 1 {
        Some(candidates.remove(0))
    } else if candidates.len() > 1 {
        panic!(
            "\n[Xross Error] Ambiguous type reference: '{}'\n\
             Multiple types with the same name were found in different packages:\n\
             {}\n",
            type_name,
            candidates.iter().map(|s| format!("  - {}", s)).collect::<Vec<_>>().join("\n")
        );
    } else {
        None
    }
}
