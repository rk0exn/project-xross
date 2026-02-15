use syn::braced;
use syn::parenthesized;
use syn::parse::{Parse, ParseStream};
use syn::{FnArg, ReturnType, Signature, Token, Type};

syn::custom_keyword!(package);
syn::custom_keyword!(class);
syn::custom_keyword!(variants);
syn::custom_keyword!(clonable);
syn::custom_keyword!(is_clonable);
syn::custom_keyword!(iscopy);
syn::custom_keyword!(is_copy);
syn::custom_keyword!(field);
syn::custom_keyword!(method);
syn::custom_keyword!(drop);
syn::custom_keyword!(critical);
syn::custom_keyword!(panicable);
syn::custom_keyword!(heap_access);

pub enum VariantFieldInfo {
    Unit,
    Named(Vec<(String, Type)>),
    Unnamed(Vec<Type>),
}

pub struct VariantInfo {
    pub name: String,
    pub fields: VariantFieldInfo,
}

pub enum XrossClassItem {
    Package(String),
    Class(String),
    Enum(String),
    IsClonable(bool, xross_metadata::HandleMode),
    IsCopy(bool),
    Field { name: String, ty: Type },
    Method(Signature, Option<String>, xross_metadata::HandleMode),
    Variants(Vec<VariantInfo>),
    DropMode(xross_metadata::HandleMode),
}

pub struct XrossClassInput {
    pub items: Vec<XrossClassItem>,
}

impl Parse for XrossClassInput {
    fn parse(input: ParseStream) -> syn::Result<Self> {
        let mut items = Vec::new();
        while !input.is_empty() {
            if input.peek(package) {
                input.parse::<package>()?;
                let pkg = input.parse::<syn::Ident>()?.to_string();
                input.parse::<Token![;]>()?;
                items.push(XrossClassItem::Package(pkg));
            } else if input.peek(class) {
                input.parse::<class>()?;
                input.parse::<Token![struct]>()?;
                let name = input.parse::<syn::Ident>()?.to_string();
                input.parse::<Token![;]>()?;
                items.push(XrossClassItem::Class(name));
            } else if input.peek(Token![enum]) {
                input.parse::<Token![enum]>()?;
                let name = input.parse::<syn::Ident>()?.to_string();
                input.parse::<Token![;]>()?;
                items.push(XrossClassItem::Enum(name));
            } else if input.peek(variants) {
                input.parse::<variants>()?;
                let content;
                braced!(content in input);
                let mut v_list = Vec::new();
                while !content.is_empty() {
                    let v_name = content.parse::<syn::Ident>()?.to_string();
                    let v_fields = if content.peek(syn::token::Brace) {
                        let field_content;
                        braced!(field_content in content);
                        let mut named = Vec::new();
                        while !field_content.is_empty() {
                            let f_name = field_content.parse::<syn::Ident>()?.to_string();
                            field_content.parse::<Token![:]>()?;
                            let f_ty = field_content.parse::<Type>()?;
                            if field_content.peek(Token![;]) {
                                field_content.parse::<Token![;]>()?;
                            }
                            named.push((f_name, f_ty));
                        }
                        VariantFieldInfo::Named(named)
                    } else if content.peek(syn::token::Paren) {
                        let field_content;
                        parenthesized!(field_content in content);
                        let mut unnamed = Vec::new();
                        while !field_content.is_empty() {
                            unnamed.push(field_content.parse::<Type>()?);
                            if field_content.peek(Token![,]) {
                                field_content.parse::<Token![,]>()?;
                            }
                        }
                        VariantFieldInfo::Unnamed(unnamed)
                    } else {
                        VariantFieldInfo::Unit
                    };
                    if content.peek(Token![;]) {
                        content.parse::<Token![;]>()?;
                    }
                    v_list.push(VariantInfo { name: v_name, fields: v_fields });
                }
                if input.peek(Token![;]) {
                    input.parse::<Token![;]>()?;
                }
                items.push(XrossClassItem::Variants(v_list));
            } else if input.peek(clonable) || input.peek(is_clonable) {
                if input.peek(clonable) {
                    input.parse::<clonable>()?;
                } else {
                    input.parse::<is_clonable>()?;
                }
                let val: syn::LitBool = input.parse()?;
                let mut mode = xross_metadata::HandleMode::Normal;
                if input.peek(syn::token::Paren) {
                    let content;
                    parenthesized!(content in input);
                    if content.peek(critical) {
                        content.parse::<critical>()?;
                        let mut allow_heap_access = false;
                        if content.peek(syn::token::Paren) {
                            let inner;
                            parenthesized!(inner in content);
                            if inner.peek(heap_access) {
                                inner.parse::<heap_access>()?;
                                allow_heap_access = true;
                            }
                        }
                        mode = xross_metadata::HandleMode::Critical { allow_heap_access };
                    } else if content.peek(panicable) {
                        content.parse::<panicable>()?;
                        mode = xross_metadata::HandleMode::Panicable;
                    }
                }
                input.parse::<Token![;]>()?;
                items.push(XrossClassItem::IsClonable(val.value, mode));
            } else if input.peek(iscopy) || input.peek(is_copy) {
                if input.peek(iscopy) {
                    input.parse::<iscopy>()?;
                } else {
                    input.parse::<is_copy>()?;
                }
                let val: syn::LitBool = input.parse()?;
                input.parse::<Token![;]>()?;
                items.push(XrossClassItem::IsCopy(val.value));
            } else if input.peek(field) {
                input.parse::<field>()?;
                let name = input.parse::<syn::Ident>()?.to_string();
                input.parse::<Token![:]>()?;
                let ty: Type = input.parse()?;
                input.parse::<Token![;]>()?;
                items.push(XrossClassItem::Field { name, ty });
            } else if input.peek(drop) {
                input.parse::<drop>()?;
                let mut mode = xross_metadata::HandleMode::Normal;
                if input.peek(critical) {
                    input.parse::<critical>()?;
                    let mut allow_heap_access = false;
                    if input.peek(syn::token::Paren) {
                        let inner;
                        parenthesized!(inner in input);
                        if inner.peek(heap_access) {
                            inner.parse::<heap_access>()?;
                            allow_heap_access = true;
                        }
                    }
                    mode = xross_metadata::HandleMode::Critical { allow_heap_access };
                } else if input.peek(panicable) {
                    input.parse::<panicable>()?;
                    mode = xross_metadata::HandleMode::Panicable;
                }
                input.parse::<Token![;]>()?;
                items.push(XrossClassItem::DropMode(mode));
            } else if input.peek(method) {
                input.parse::<method>()?;
                let mut inputs = syn::punctuated::Punctuated::<FnArg, Token![,]>::new();
                let mut type_override = None;
                if input.peek(Token![&]) || input.peek(Token![mut]) || input.peek(Token![self]) {
                    let receiver = input.parse::<FnArg>()?;
                    inputs.push(receiver);
                    if input.peek(Token![.]) {
                        input.parse::<Token![.]>()?;
                    }
                } else if input.peek(syn::Ident) && input.peek2(Token![.]) {
                    let type_name = input.parse::<syn::Ident>()?.to_string();
                    type_override = Some(type_name);
                    input.parse::<Token![.]>()?;
                }
                let ident = input.parse::<syn::Ident>()?;
                let content;
                parenthesized!(content in input);
                let args = content.parse_terminated(FnArg::parse, Token![,])?;
                for arg in args {
                    inputs.push(arg);
                }
                let output = input.parse::<ReturnType>()?;
                let mut mode = xross_metadata::HandleMode::Normal;
                if input.peek(syn::token::Paren) {
                    let m_content;
                    parenthesized!(m_content in input);
                    if m_content.peek(critical) {
                        m_content.parse::<critical>()?;
                        let mut allow_heap_access = false;
                        if m_content.peek(syn::token::Paren) {
                            let inner;
                            parenthesized!(inner in m_content);
                            if inner.peek(heap_access) {
                                inner.parse::<heap_access>()?;
                                allow_heap_access = true;
                            }
                        }
                        mode = xross_metadata::HandleMode::Critical { allow_heap_access };
                    } else if m_content.peek(panicable) {
                        m_content.parse::<panicable>()?;
                        mode = xross_metadata::HandleMode::Panicable;
                    }
                }
                if input.peek(Token![;]) {
                    input.parse::<Token![;]>()?;
                }
                items.push(XrossClassItem::Method(
                    Signature {
                        constness: None,
                        asyncness: None,
                        unsafety: None,
                        abi: None,
                        fn_token: <Token![fn]>::default(),
                        ident,
                        generics: syn::Generics::default(),
                        paren_token: syn::token::Paren::default(),
                        inputs,
                        variadic: None,
                        output,
                    },
                    type_override,
                    mode,
                ));
            } else {
                return Err(input.error("expected one of: package, class, enum, variants, clonable, iscopy, field, method, drop"));
            }
        }
        Ok(XrossClassInput { items })
    }
}
