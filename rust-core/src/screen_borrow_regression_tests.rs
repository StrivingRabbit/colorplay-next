//! Regression tests for repeated receiver Borrow; no camera/gear state is inferred.
use crate::{bplist::{self, Value}, modes::Modes};

fn borrow(unborrow: i64) -> Value {
    Value::dict(vec![("resources", Value::array(vec![Value::dict(vec![
        ("resourceID", Value::int(1)), ("transferType", Value::int(3)),
        ("transferPriority", Value::int(500)), ("unborrowConstraint", Value::int(unborrow)),
    ])]))])
}
fn unborrow() -> Value {
    Value::dict(vec![("resources", Value::array(vec![Value::dict(vec![
        ("resourceID", Value::int(1)), ("transferType", Value::int(4)),
    ])]))])
}

#[test]
fn repeated_borrow_tightening_return_constraint_blocks_request_ui() {
    let mut modes = Modes::initial();
    let audio = modes.audio_owned();
    assert!(modes.change(&borrow(100)));
    let grant = modes.screen_grant_generation();
    assert!(modes.change(&borrow(1000)));
    let revision = modes.screen_generation();
    modes.request_ui();
    assert!(!modes.screen_owned(), "must honour the newer NEVER return constraint");
    assert_eq!(modes.screen_grant_generation(), grant);
    assert_eq!(modes.screen_generation(), revision);
    assert_eq!(modes.audio_owned(), audio);
}

#[test]
fn latest_receiver_return_constraint_is_used_in_both_directions() {
    let mut modes = Modes::initial();
    assert!(modes.change(&borrow(1000)));
    assert!(modes.change(&borrow(100)));
    modes.request_ui();
    assert!(modes.screen_owned(), "receiver explicitly relaxed the current return constraint");
}

#[test]
fn nested_borrow_balances_returns_before_restoring_video_grant() {
    let mut modes = Modes::initial();
    assert!(modes.change(&borrow(100)));
    assert!(modes.change(&borrow(1000)));
    let grant = modes.screen_grant_generation();
    assert!(modes.change(&unborrow()));
    assert!(!modes.screen_owned());
    modes.request_ui();
    assert!(!modes.screen_owned());
    assert_eq!(modes.screen_grant_generation(), grant);
    assert!(modes.change(&unborrow()));
    assert!(modes.screen_owned());
    assert_ne!(modes.screen_grant_generation(), grant);
}

#[test]
fn repeated_receiver_borrow_ack_preserves_remote_ownership() {
    let mut modes = Modes::initial();
    for constraint in [100, 1000] {
        let command = Value::dict(vec![
            ("type", Value::str("changeModes")), ("params", borrow(constraint)),
        ]);
        let response = modes.response(&command, &Default::default()).unwrap();
        let body = bplist::parse(&response.body).unwrap();
        assert_eq!(body.get("status").and_then(Value::as_int), Some(0));
        assert!(!modes.screen_owned());
    }
    modes.request_ui();
    assert!(!modes.screen_owned());
}
