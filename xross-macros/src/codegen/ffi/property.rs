use proc_macro2::TokenStream;
use quote::{format_ident, quote};
use xross_metadata::XrossType;

pub fn generate_property_accessors(
    struct_name: &syn::Ident,
    field_ident: &syn::Ident,
    field_ty: &syn::Type,
    xross_ty: &XrossType,
    symbol_base: &str,
    extra_functions: &mut Vec<TokenStream>,
) {
    let suffix = match xross_ty {
        XrossType::String => "_str",
        XrossType::Option(_) => "_opt",
        XrossType::Result { .. } => "_res",
        _ => "",
    };

    let getter_name = format!("{}_property_{}{}_get", symbol_base, field_ident, suffix);
    let setter_name = format!("{}_property_{}{}_set", symbol_base, field_ident, suffix);
    let getter_ident = format_ident!("{}", getter_name);
    let setter_ident = format_ident!("{}", setter_name);

    let (ret_type, body) = match xross_ty {
        XrossType::String => (
            quote! { *mut std::ffi::c_char },
            quote! { std::ffi::CString::new(_self.#field_ident.clone()).unwrap_or_default().into_raw() },
        ),
        XrossType::Object { .. } => (
            quote! { *mut std::ffi::c_void },
            quote! { Box::into_raw(Box::new(_self.#field_ident.clone())) as *mut std::ffi::c_void },
        ),
        XrossType::Option(inner) => {
             match &**inner {
                XrossType::String => (
                    quote! { *mut std::ffi::c_char },
                    quote! {
                        match &_self.#field_ident {
                            Some(s) => std::ffi::CString::new(s.clone()).unwrap_or_default().into_raw(),
                            None => std::ptr::null_mut(),
                        }
                    },
                ),
                _ => (
                    quote! { *mut std::ffi::c_void },
                    quote! {
                        match &_self.#field_ident {
                            Some(v) => Box::into_raw(Box::new(v.clone())) as *mut std::ffi::c_void,
                            None => std::ptr::null_mut(),
                        }
                    },
                )
            }
        },
        XrossType::Result { ok, err } => {
            let ok_ptr_logic = crate::codegen::ffi::gen_single_value_to_ptr(ok, quote! { (*val).clone() });
            let err_ptr_logic = crate::codegen::ffi::gen_single_value_to_ptr(err, quote! { (*e).clone() });
            (
                quote! { xross_core::XrossResult },
                quote! {
                    match &_self.#field_ident {
                        Ok(val) => xross_core::XrossResult { is_ok: true, ptr: #ok_ptr_logic },
                        Err(e) => xross_core::XrossResult { is_ok: false, ptr: #err_ptr_logic },
                    }
                },
            )
        }
        _ => {
            let type_str = quote!(#field_ty).to_string();
            let is_ptr_or_ref = type_str.contains('*') || type_str.contains('&');
            let is_primitive = ["i8", "i16", "i32", "i64", "u8", "u16", "u32", "u64", "f32", "f64", "bool", "isize", "usize", "()"]
                .iter().any(|&p| type_str == p || (type_str.contains(p) && type_str.len() <= 5));

            if !is_primitive && !is_ptr_or_ref {
                (
                    quote! { *mut std::ffi::c_void },
                    quote! { Box::into_raw(Box::new(_self.#field_ident.clone())) as *mut std::ffi::c_void }
                )
            } else {
                (quote! { #field_ty }, quote! { _self.#field_ident })
            }
        }
    };

    extra_functions.push(quote! {
        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #getter_ident(ptr: *mut std::ffi::c_void) -> #ret_type {
            let _self = &*(ptr as *mut #struct_name);
            #body
        }
    });

    extra_functions.push(quote! {
        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #setter_ident(ptr: *mut std::ffi::c_void, _val: #ret_type) {
            // Setter is intentionally simplified or no-op for complex types to prevent accidental corruption
        }
    });
}
