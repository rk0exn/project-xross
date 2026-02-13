use proc_macro2::TokenStream;
use quote::{format_ident, quote};

/// Generates common FFI functions (drop, clone, layout).
pub fn generate_common_ffi(
    name: &syn::Ident,
    base: &str,
    layout_logic: TokenStream,
    toks: &mut Vec<TokenStream>,
    is_clonable: bool,
) {
    let drop_id = format_ident!("{}_drop", base);
    let clone_id = format_ident!("{}_clone", base);
    let layout_id = format_ident!("{}_layout", base);
    let trait_name = format_ident!("Xross{}Class", name);

    toks.push(quote! {
        pub trait #trait_name { fn xross_layout() -> String; }
        impl #trait_name for #name { fn xross_layout() -> String { #layout_logic } }

        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #drop_id(ptr: *mut #name) {
            if !ptr.is_null() { drop(unsafe { Box::from_raw(ptr) }); }
        }
    });

    if is_clonable {
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

    toks.push(quote! {
        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #layout_id() -> *mut std::ffi::c_char {
            let s = <#name as #trait_name>::xross_layout();
            std::ffi::CString::new(s).unwrap().into_raw()
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
        pub unsafe extern "C" fn #variant_name_fn_id(ptr: *const #type_ident) -> *mut std::ffi::c_char {
            if ptr.is_null() { return std::ptr::null_mut(); }
            let val = &*ptr;
            let name = match val { #(#variant_name_arms),* };
            std::ffi::CString::new(name).unwrap().into_raw()
        }
    });
}

/// Generates the layout specification for a single field.
pub fn gen_field_layout_spec(
    type_ident: &syn::Ident,
    field_access: TokenStream,
    field_name: &str,
    field_ty: &syn::Type,
) -> TokenStream {
    quote! {
        {
            let offset = std::mem::offset_of!(#type_ident, #field_access) as u64;
            let size = std::mem::size_of::<#field_ty>() as u64;
            format!("{}:{}:{}", #field_name, offset, size)
        }
    }
}

/// Generates the layout metadata logic for a struct.
pub fn generate_struct_layout(s: &syn::ItemStruct) -> TokenStream {
    let name = &s.ident;
    let mut field_parts = Vec::new();
    if let syn::Fields::Named(fields) = &s.fields {
        for field in &fields.named {
            let f_name = field.ident.as_ref().unwrap();
            let f_ty = &field.ty;
            field_parts.push(gen_field_layout_spec(
                name,
                quote! { #f_name },
                &f_name.to_string(),
                f_ty,
            ));
        }
    }
    quote! {
        let mut parts = vec![format!("{}", std::mem::size_of::<#name>() as u64)];
        #(parts.push(#field_parts);)*
        parts.join(";")
    }
}

/// Generates the layout metadata logic for an enum.
pub fn generate_enum_layout(e: &syn::ItemEnum) -> TokenStream {
    let name = &e.ident;
    let mut variant_specs = Vec::new();
    for v in &e.variants {
        let v_name = &v.ident;
        if v.fields.is_empty() {
            variant_specs.push(quote! { stringify!(#v_name).to_string() });
        } else {
            let mut fields_info = Vec::new();
            for (i, field) in v.fields.iter().enumerate() {
                let f_ty = &field.ty;
                let f_display_name = field
                    .ident
                    .as_ref()
                    .map(|id| id.to_string())
                    .unwrap_or_else(|| crate::utils::ordinal_name(i));
                let f_access = if let Some(ident) = &field.ident {
                    quote! { #v_name . #ident }
                } else {
                    let index = syn::Index::from(i);
                    quote! { #v_name . #index }
                };
                fields_info.push(gen_field_layout_spec(name, f_access, &f_display_name, f_ty));
            }
            variant_specs.push(quote! { format!("{}{{{}}}", stringify!(#v_name), vec![#(#fields_info),*].join(";")) });
        }
    }
    quote! {
        let mut parts = vec![format!("{}", std::mem::size_of::<#name>() as u64)];
        let variants: Vec<String> = vec![#(#variant_specs),*];
        parts.push(variants.join(";"));
        parts.join(";")
    }
}

/// Generates property accessors for types that need wrapping/unwrapping (Option, Result).
fn gen_complex_property_accessors(
    type_name: &syn::Ident,
    field_ident: &syn::Ident,
    field_ty: &syn::Type,
    xross_ty: &xross_metadata::XrossType,
    symbol_base: &str,
    suffix: &str,
    toks: &mut Vec<TokenStream>,
) {
    let field_name = field_ident.to_string();
    let get_fn = format_ident!("{}_property_{}_{}_get", symbol_base, field_name, suffix);
    let set_fn = format_ident!("{}_property_{}_{}_set", symbol_base, field_name, suffix);

    let (ret_ffi_ty, ret_wrap) = super::conversion::gen_ret_wrapping(
        xross_ty,
        &syn::ReturnType::Default,
        quote! { obj.#field_ident.clone() },
    );
    let arg_id = format_ident!("val");
    let (arg_ffi_ty, arg_conv, arg_call) =
        super::conversion::gen_arg_conversion(field_ty, &arg_id, xross_ty);

    toks.push(quote! {
        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #get_fn(ptr: *const #type_name) -> #ret_ffi_ty {
            if ptr.is_null() { panic!("NULL pointer in property get"); }
            let obj = &*ptr;
            #ret_wrap
        }
        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #set_fn(ptr: *mut #type_name, #arg_ffi_ty) {
            if ptr.is_null() { panic!("NULL pointer in property set"); }
            let obj = &mut *ptr;
            #arg_conv
            obj.#field_ident = #arg_call;
        }
    });
}

/// Generates property accessors (getter/setter) for a field.
pub fn generate_property_accessors(
    type_name: &syn::Ident,
    field_ident: &syn::Ident,
    field_ty: &syn::Type,
    xross_ty: &xross_metadata::XrossType,
    symbol_base: &str,
    toks: &mut Vec<TokenStream>,
) {
    let field_name = field_ident.to_string();
    match xross_ty {
        xross_metadata::XrossType::String => {
            let get_fn = format_ident!("{}_property_{}_str_get", symbol_base, field_name);
            let set_fn = format_ident!("{}_property_{}_str_set", symbol_base, field_name);
            toks.push(quote! {
                #[unsafe(no_mangle)]
                pub unsafe extern "C" fn #get_fn(ptr: *const #type_name) -> *mut std::ffi::c_char {
                    if ptr.is_null() { panic!("NULL pointer in property get"); }
                    let obj = &*ptr;
                    std::ffi::CString::new(obj.#field_ident.as_str()).unwrap().into_raw()
                }
                #[unsafe(no_mangle)]
                pub unsafe extern "C" fn #set_fn(ptr: *mut #type_name, val: *const std::ffi::c_char) {
                    if ptr.is_null() || val.is_null() { return; }
                    let obj = &mut *ptr;
                    obj.#field_ident = std::ffi::CStr::from_ptr(val).to_string_lossy().into_owned();
                }
            });
        }
        xross_metadata::XrossType::Option(_inner) => {
            gen_complex_property_accessors(
                type_name,
                field_ident,
                field_ty,
                xross_ty,
                symbol_base,
                "opt",
                toks,
            );
        }
        xross_metadata::XrossType::Result { .. } => {
            gen_complex_property_accessors(
                type_name,
                field_ident,
                field_ty,
                xross_ty,
                symbol_base,
                "res",
                toks,
            );
        }
        _ => {
            let get_fn = format_ident!("{}_property_{}_get", symbol_base, field_name);
            let set_fn = format_ident!("{}_property_{}_set", symbol_base, field_name);
            toks.push(quote! {
                #[unsafe(no_mangle)]
                pub unsafe extern "C" fn #get_fn(ptr: *const #type_name) -> #field_ty {
                    if ptr.is_null() { panic!("NULL pointer in property get"); }
                    (*ptr).#field_ident.clone()
                }
                #[unsafe(no_mangle)]
                pub unsafe extern "C" fn #set_fn(ptr: *mut #type_name, val: #field_ty) {
                    if ptr.is_null() { panic!("NULL pointer in property set"); }
                    (*ptr).#field_ident = val;
                }
            });
        }
    }
}
