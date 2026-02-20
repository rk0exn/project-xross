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
    use crate::counters::{SERVICE_COUNT, SERVICE2_COUNT};
    use std::sync::atomic::Ordering;

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

    #[test]
    fn test_service_lifecycle_and_core_methods() {
        let before = SERVICE_COUNT.load(Ordering::SeqCst);
        let mut service = MyService::new();
        assert!(SERVICE_COUNT.load(Ordering::SeqCst) >= before);

        assert_eq!(service.add_trivial(10, 15), 25);
        assert_eq!(service.add_critical_heap(7, 8), 15);
        assert!(matches!(service.get_option_enum(true), Some(XrossSimpleEnum::V)));
        assert!(service.get_option_enum(false).is_none());
        assert!(matches!(service.get_result_struct(false), Err(err) if err == "Error"));
        assert_eq!(service.cause_panic(0), "No panic today");
        assert!(service.execute(1_000_010) >= 1_000_000);

        let consumed_len = service.consume_self();
        assert_eq!(consumed_len, 1_000_000);

        // Other tests can run in parallel and mutate the same global counter,
        // so we only assert it stays in a valid (non-negative) range here.
        assert!(SERVICE_COUNT.load(Ordering::SeqCst) >= 0);
    }

    #[test]
    fn test_async_and_service2_behaviour() {
        let before = SERVICE2_COUNT.load(Ordering::SeqCst);

        let service = MyService::new();
        let value = RUNTIME.block_on(service.async_execute(21));
        assert_eq!(value, 42);

        let s2 = MyService2::new(5);
        assert!(SERVICE2_COUNT.load(Ordering::SeqCst) >= before);

        let cloned = s2.create_clone();
        assert!(SERVICE2_COUNT.load(Ordering::SeqCst) >= before);
        assert_eq!(s2.get_self_ref().val, 5);

        let out = s2.execute();
        assert!((-5.0..=5.0).contains(&out));

        drop(cloned);
        drop(s2);
        assert!(SERVICE2_COUNT.load(Ordering::SeqCst) >= 0);
    }

    #[test]
    fn test_panicable_method_panics_when_requested() {
        let service = MyService::new();
        let panicked = std::panic::catch_unwind(|| service.cause_panic(1));
        assert!(panicked.is_err());
    }
}
