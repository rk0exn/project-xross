use proc_macro2::TokenStream;
use quote::{format_ident, quote};
use xross_metadata::{Ownership, XrossType};

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

    let mut setter_args = vec![quote! { ptr: *mut std::ffi::c_void }];

    let (ret_type, get_body, set_body) = match xross_ty {
        XrossType::String => {
            setter_args.push(quote! { _val_ptr: *const u8 });
            setter_args.push(quote! { _val_len: usize });
            setter_args.push(quote! { _val_enc: u8 });
            (
                quote! { xross_core::XrossString },
                quote! { xross_core::XrossString::from(_self.#field_ident.clone()) },
                quote! {
                    _self.#field_ident = xross_core::XrossStringView {
                        ptr: _val_ptr,
                        len: _val_len,
                        encoding: _val_enc,
                    }.to_string_lossy();
                },
            )
        }
        XrossType::Object { ownership, .. } => {
            setter_args.push(quote! { _val: *mut std::ffi::c_void });
            (
                quote! { *mut std::ffi::c_void },
                quote! { Box::into_raw(Box::new(_self.#field_ident.clone())) as *mut std::ffi::c_void },
                if *ownership == Ownership::Owned {
                    quote! { _self.#field_ident = unsafe { std::ptr::read(_val as *const _) }; }
                } else {
                    quote! { /* Ref setter not supported */ }
                },
            )
        }
        XrossType::Option(inner) => {
            setter_args.push(quote! { _val: *mut std::ffi::c_void });
            let ok_ptr_logic =
                crate::codegen::ffi::gen_single_value_to_ptr(inner, quote! { v.clone() });
            (
                quote! { *mut std::ffi::c_void },
                quote! {
                    match &_self.#field_ident {
                        Some(v) => #ok_ptr_logic,
                        None => std::ptr::null_mut(),
                    }
                },
                quote! {
                    if _val.is_null() {
                        _self.#field_ident = None;
                    } else {
                        // Only support Some(val) for Object/Boxed types for now
                    }
                },
            )
        }
        XrossType::Result { ok, err } => {
            setter_args.push(quote! { _val: xross_core::XrossResult });
            let ok_ptr_logic =
                crate::codegen::ffi::gen_single_value_to_ptr(ok, quote! { val.clone() });
            let err_ptr_logic =
                crate::codegen::ffi::gen_single_value_to_ptr(err, quote! { e.clone() });
            (
                quote! { xross_core::XrossResult },
                quote! {
                    match &_self.#field_ident {
                        Ok(val) => xross_core::XrossResult { is_ok: true, ptr: #ok_ptr_logic },
                        Err(e) => xross_core::XrossResult { is_ok: false, ptr: #err_ptr_logic },
                    }
                },
                quote! { /* Result setter is complex, simplified for now */ },
            )
        }
        _ => {
            let type_str = quote!(#field_ty).to_string();
            let is_ptr_or_ref = type_str.contains('*') || type_str.contains('&');
            let is_primitive = [
                "i8", "i16", "i32", "i64", "u8", "u16", "u32", "u64", "f32", "f64", "bool",
                "isize", "usize", "()",
            ]
            .iter()
            .any(|&p| type_str == p || (type_str.contains(p) && type_str.len() <= 5));

            setter_args.push(if !is_primitive && !is_ptr_or_ref {
                quote! { _val: *mut std::ffi::c_void }
            } else {
                quote! { _val: #field_ty }
            });

            if !is_primitive && !is_ptr_or_ref {
                (
                    quote! { *mut std::ffi::c_void },
                    quote! { Box::into_raw(Box::new(_self.#field_ident.clone())) as *mut std::ffi::c_void },
                    quote! { _self.#field_ident = unsafe { std::ptr::read(_val as *const _) }; },
                )
            } else {
                (
                    quote! { #field_ty },
                    quote! { _self.#field_ident },
                    quote! { _self.#field_ident = _val; },
                )
            }
        }
    };

    extra_functions.push(quote! {
        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #getter_ident(ptr: *mut std::ffi::c_void) -> #ret_type {
            let _self = &*(ptr as *mut #struct_name);
            #get_body
        }
    });

    extra_functions.push(quote! {
        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #setter_ident(#(#setter_args),*) {
            let _self = &mut *(ptr as *mut #struct_name);
            #set_body
        }
    });
}
