#![feature(offset_of_enum)]
#![deny(unsafe_op_in_unsafe_fn)]

pub mod all_types;
pub mod counters;
pub mod enums;
pub mod fast;
pub mod graphics;
pub mod heavy;
pub mod models;
pub mod services;
pub mod standalone;

pub use all_types::*;
pub use counters::*;
pub use enums::*;
pub use fast::*;
pub use graphics::*;
pub use heavy::*;
pub use models::*;
pub use services::*;
pub use standalone::*;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_hello_enum_logic() {
        let b = HelloEnum::B { i: 100 };
        let c = HelloEnum::C(Box::new(b));

        if let HelloEnum::C(inner) = c {
            if let HelloEnum::B { i } = *inner {
                assert_eq!(i, 100);
            } else {
                panic!("Expected variant B");
            }
        } else {
            panic!("Expected variant C");
        }
    }

    #[test]
    fn test_external_struct_logic() {
        let mut ext = ExternalStruct::new(10, "Test".to_string());
        assert_eq!(ext.get_value(), 10);
        ext.set_value(20);
        assert_eq!(ext.value, 20);
        assert_eq!(ext.greet("Hi".to_string()), "Hi Test");
    }
}
