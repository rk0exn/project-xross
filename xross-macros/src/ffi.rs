use quote::{format_ident, quote};

pub fn generate_common_ffi(
    name: &syn::Ident,
    base: &str,
    layout_logic: proc_macro2::TokenStream,
    toks: &mut Vec<proc_macro2::TokenStream>,
) {
    let drop_id = format_ident!("{}_drop", base);
    let clone_id = format_ident!("{}_clone", base);
    let layout_id = format_ident!("{}_layout", base);
    let trait_name = format_ident!("Xross{}Class", name);

    toks.push(quote! {
        pub trait #trait_name {
            fn xross_layout() -> String;
        }

        impl #trait_name for #name {
            fn xross_layout() -> String {
                #layout_logic
            }
        }

        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #drop_id(ptr: *mut #name) {
            if !ptr.is_null() {
                drop(unsafe { Box::from_raw(ptr) });
            }
        }

        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #clone_id(ptr: *const #name) -> *mut #name {
            if ptr.is_null() { return std::ptr::null_mut(); }
            let val_on_stack: #name = std::ptr::read_unaligned(ptr);
            let cloned_val = val_on_stack.clone();
            std::mem::forget(val_on_stack);
            Box::into_raw(Box::new(cloned_val))
        }

        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #layout_id() -> *mut std::ffi::c_char {
            let s = <#name as #trait_name>::xross_layout();
            std::ffi::CString::new(s).unwrap().into_raw()
        }
    });
}

pub fn generate_struct_layout(s: &syn::ItemStruct) -> proc_macro2::TokenStream {
    let name = &s.ident;
    let mut field_parts = Vec::new();

    if let syn::Fields::Named(fields) = &s.fields {
        for field in &fields.named {
            let f_name = field.ident.as_ref().unwrap();
            let f_ty = &field.ty;
            field_parts.push(quote! {
                {
                    let offset = std::mem::offset_of!(#name, #f_name) as u64;
                    let size = std::mem::size_of::<#f_ty>() as u64;
                    format!("{}:{}:{}", stringify!(#f_name), offset, size)
                }
            });
        }
    }

    quote! {
        let mut parts = vec![format!("{}", std::mem::size_of::<#name>() as u64)];
        #(parts.push(#field_parts);)*
        parts.join(";")
    }
}

pub fn generate_enum_layout(e: &syn::ItemEnum) -> proc_macro2::TokenStream {
    let name = &e.ident;
    let mut variant_specs = Vec::new();

    for v in &e.variants {
        let v_name = &v.ident;

        if v.fields.is_empty() {
            variant_specs.push(quote! {
                stringify!(#v_name).to_string()
            });
        } else {
            let mut fields_info = Vec::new();
            for (i, field) in v.fields.iter().enumerate() {
                let f_ty = &field.ty;
                let f_access = if let Some(ident) = &field.ident {
                    quote! { #v_name . #ident }
                } else {
                    let index = syn::Index::from(i);
                    quote! { #v_name . #index }
                };

                let f_display_name =
                    field.ident.as_ref().map(|id| id.to_string()).unwrap_or_else(|| i.to_string());

                fields_info.push(quote! {
                    {
                        let offset = std::mem::offset_of!(#name, #f_access) as u64;
                        let size = std::mem::size_of::<#f_ty>() as u64;
                        format!("{}:{}:{}", #f_display_name, offset, size)
                    }
                });
            }

            variant_specs.push(quote! {
                format!("{}{{{}}}", stringify!(#v_name), vec![#(#fields_info),*].join(";"))
            });
        }
    }

    quote! {
        let mut parts = vec![format!("{}", std::mem::size_of::<#name>() as u64)];
        let variants: Vec<String> = vec![#(#variant_specs),*];
        parts.push(variants.join(";"));
        parts.join(";")
    }
}
