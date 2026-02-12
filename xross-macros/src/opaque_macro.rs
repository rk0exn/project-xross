use proc_macro2::TokenStream;
use quote::{format_ident, quote};
use crate::utils::*;
use crate::metadata::save_definition;
use xross_metadata::Ownership;

pub fn impl_opaque_class(input: TokenStream) -> TokenStream {
    let input_str = input.to_string();
    let parts: Vec<String> = input_str.split(',').map(|s| s.replace(" ", "")).collect();

    let (package, name_str, is_clonable) = match parts.len() {
        1 => ("".to_string(), parts[0].as_str(), true),
        2 => {
            let second = parts[1].to_lowercase();
            if second == "true" || second == "false" {
                ("".to_string(), parts[0].as_str(), second.parse().unwrap())
            } else {
                (parts[0].clone(), parts[1].as_str(), true)
            }
        }
        3 => (parts[0].clone(), parts[1].as_str(), parts[2].to_lowercase().parse().unwrap_or(true)),
        _ => panic!(
            "opaque_class! expects 1 to 3 arguments: (ClassName), (Pkg, Class), or (Pkg, Class, IsClonable)"
        ),
    };
    let name_ident = format_ident!("{}", name_str);

    let crate_name = std::env::var("CARGO_PKG_NAME")
        .unwrap_or_else(|_| "unknown_crate".to_string())
        .replace("-", "_");

    let symbol_base = build_symbol_base(&crate_name, &package, name_str);

    let mut methods = vec![];
    if is_clonable {
        methods.push(xross_metadata::XrossMethod {
            name: "clone".to_string(),
            symbol: format!("{}_clone", symbol_base),
            method_type: xross_metadata::XrossMethodType::ConstInstance,
            is_constructor: false,
            args: vec![],
            ret: xross_metadata::XrossType::Object {
                signature: if package.is_empty() {
                    name_str.to_string()
                } else {
                    format!("{}.{}", package, name_str)
                },
                ownership: Ownership::Owned,
            },
            safety: xross_metadata::ThreadSafety::Lock,
            docs: vec!["Creates a clone of the native object.".to_string()],
        });
    }

    let definition = xross_metadata::XrossDefinition::Opaque(xross_metadata::XrossOpaque {
        signature: if package.is_empty() {
            name_str.to_string()
        } else {
            format!("{}.{}", package, name_str)
        },
        symbol_prefix: symbol_base.clone(),
        package_name: package,
        name: name_str.to_string(),
        methods,
        is_clonable,
        docs: vec![format!("Opaque wrapper for {}", name_str)],
        is_copy: false,
    });
    save_definition(&definition);

    let drop_fn = format_ident!("{}_drop", symbol_base);
    let size_fn = format_ident!("{}_size", symbol_base);

    let clone_ffi = if is_clonable {
        let clone_fn = format_ident!("{}_clone", symbol_base);
        quote! {
            #[unsafe(no_mangle)]
            pub unsafe extern "C" fn #clone_fn(ptr: *const #name_ident) -> *mut #name_ident {
                if ptr.is_null() { return std::ptr::null_mut(); }
                let mut temp = std::mem::MaybeUninit::<#name_ident>::uninit();
                std::ptr::copy_nonoverlapping(
                    ptr as *const u8,
                    temp.as_mut_ptr() as *mut u8,
                    std::mem::size_of::<#name_ident>()
                );
                let val_on_stack = temp.assume_init();
                let cloned_val = val_on_stack.clone();
                std::mem::forget(val_on_stack);
                Box::into_raw(Box::new(cloned_val))
            }
        }
    } else {
        quote! {}
    };

    quote! {
        #clone_ffi

        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #drop_fn(ptr: *mut #name_ident) {
            if !ptr.is_null() {
                let _ = Box::from_raw(ptr);
            }
        }

        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #size_fn() -> usize {
            std::mem::size_of::<#name_ident>()
        }
    }
}
