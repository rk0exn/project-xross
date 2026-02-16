use proc_macro2::TokenStream;
use quote::{format_ident, quote};
use xross_metadata::HandleMode;

/// Generates common FFI functions (drop, clone, layout).
pub fn generate_common_ffi(
    name: &syn::Ident,
    base: &str,
    layout_logic: TokenStream,
    toks: &mut Vec<TokenStream>,
    is_clonable: bool,
    clone_mode: HandleMode,
    drop_mode: HandleMode,
) {
    let drop_id = format_ident!("{}_drop", base);
    let clone_id = format_ident!("{}_clone", base);
    let layout_id = format_ident!("{}_layout", base);
    let trait_name = format_ident!("Xross{}Class", name);

    toks.push(quote! {
        pub trait #trait_name { fn xross_layout() -> String; }
        impl #trait_name for #name { fn xross_layout() -> String { #layout_logic } }
    });

    if drop_mode == HandleMode::Panicable {
        toks.push(quote! {
            #[unsafe(no_mangle)]
            pub unsafe extern "C" fn #drop_id(ptr: *mut #name) -> xross_core::XrossResult {
                let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
                    if !ptr.is_null() { drop(unsafe { Box::from_raw(ptr) }); }
                }));
                match result {
                    Ok(_) => xross_core::XrossResult { is_ok: true, ptr: std::ptr::null_mut() },
                    Err(panic_err) => {
                        let msg = if let Some(s) = panic_err.downcast_ref::<&str>() { s.to_string() }
                        else if let Some(s) = panic_err.downcast_ref::<String>() { s.clone() }
                        else { "Unknown panic during drop".to_string() };
                        let xs = xross_core::XrossString::from(msg);
                        xross_core::XrossResult { is_ok: false, ptr: Box::into_raw(Box::new(xs)) as *mut std::ffi::c_void }
                    }
                }
            }
        });
    } else {
        toks.push(quote! {
            #[unsafe(no_mangle)]
            pub unsafe extern "C" fn #drop_id(ptr: *mut #name) {
                if !ptr.is_null() { drop(unsafe { Box::from_raw(ptr) }); }
            }
        });
    }

    if is_clonable {
        if clone_mode == HandleMode::Panicable {
            toks.push(quote! {
                #[unsafe(no_mangle)]
                pub unsafe extern "C" fn #clone_id(ptr: *const #name) -> xross_core::XrossResult {
                    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
                        if ptr.is_null() { return std::ptr::null_mut(); }
                        let val_on_stack: #name = std::ptr::read_unaligned(ptr);
                        let cloned_val = val_on_stack.clone();
                        std::mem::forget(val_on_stack);
                        Box::into_raw(Box::new(cloned_val))
                    }));
                    match result {
                        Ok(p) => xross_core::XrossResult { is_ok: true, ptr: p as *mut std::ffi::c_void },
                        Err(panic_err) => {
                            let msg = if let Some(s) = panic_err.downcast_ref::<&str>() { s.to_string() }
                            else if let Some(s) = panic_err.downcast_ref::<String>() { s.clone() }
                            else { "Unknown panic during clone".to_string() };
                            let xs = xross_core::XrossString::from(msg);
                            xross_core::XrossResult { is_ok: false, ptr: Box::into_raw(Box::new(xs)) as *mut std::ffi::c_void }
                        }
                    }
                }
            });
        } else {
            toks.push(quote! {
                #[unsafe(no_mangle)]
                pub unsafe extern "C" fn #clone_id(ptr: *const #name) -> *mut #name {
                    if ptr.is_null() { return std::ptr::null_mut(); }
                    let val_on_stack: #name = std::ptr::read_unaligned(ptr);
                    let cloned_val = val_on_stack.clone();
                    std::mem::forget(val_on_stack);
                    Box::into_raw(Box::new(cloned_val))
                }
            });
        }
    }

    toks.push(quote! {
        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #layout_id() -> xross_core::XrossString {
            let s = <#name as #trait_name>::xross_layout();
            xross_core::XrossString::from(s)
        }
    });
}

/// Generates helper functions for Enums (tag and variant name).
pub fn generate_enum_aux_ffi(
    type_ident: &syn::Ident,
    symbol_base: &str,
    variant_name_arms: Vec<TokenStream>,
    toks: &mut Vec<TokenStream>,
) {
    let tag_fn_id = format_ident!("{}_get_tag", symbol_base);
    let variant_name_fn_id = format_ident!("{}_get_variant_name", symbol_base);
    toks.push(quote! {
        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #tag_fn_id(ptr: *const #type_ident) -> i32 {
            if ptr.is_null() { return -1; }
            *(ptr as *const i32)
        }

        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #variant_name_fn_id(ptr: *const #type_ident) -> xross_core::XrossString {
            if ptr.is_null() { return xross_core::XrossString::from(String::new()); }
            let val = &*ptr;
            let name = match val { #(#variant_name_arms),* };
            xross_core::XrossString::from(name.to_string())
        }
    });
}
