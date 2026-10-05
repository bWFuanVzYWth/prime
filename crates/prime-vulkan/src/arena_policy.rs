//! Completion and idle-retention policy used by the actual arena.
use std::collections::VecDeque;

pub(super) fn completed_leases<T>(
    retired: &mut VecDeque<(u64, T)>,
    completed: u64,
) -> impl Iterator<Item = T> + '_ {
    std::iter::from_fn(move || {
        retired
            .front()
            .is_some_and(|(value, _)| *value <= completed)
            .then(|| retired.pop_front().unwrap().1)
    })
}

// A standard idle page bounds retained scratch without preserving a burst's
// oversized allocation. Collection never changes an occupied sparse page index.
pub(super) fn keep_idle_page(
    pages: impl Iterator<Item = (usize, u64, bool)>,
    budget: u64,
) -> Option<usize> {
    pages
        .filter(|(_, bytes, idle)| *idle && *bytes <= budget)
        .min_by_key(|(index, bytes, _)| (*bytes, *index))
        .map(|(index, _, _)| index)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::plan::Slots;
    #[test]
    fn completion_gate_keeps_ranges_live_and_idle_budget_discards_burst_pages() {
        let mut slots = Slots::with_limit(32);
        let first = slots.allocate(16).unwrap();
        let mut retired = VecDeque::from([(7, (first, 16))]);
        for (first, count) in completed_leases(&mut retired, 6) {
            slots.release(first, count);
        }
        assert_eq!(
            slots.allocate(16).unwrap(),
            16,
            "CPU return is not completion"
        );
        assert_eq!(retired.len(), 1);
        for (first, count) in completed_leases(&mut retired, 7) {
            slots.release(first, count);
        }
        assert_eq!(slots.allocate(16).unwrap(), 0);
        assert!(retired.is_empty());
        let pages = [(0, 100, true), (1, 32, true), (2, 32, false), (3, 32, true)];
        assert_eq!(keep_idle_page(pages.into_iter(), 32), Some(1));
        assert_eq!(keep_idle_page([(0, 100, true)].into_iter(), 32), None);
        assert_eq!(
            keep_idle_page([(0, 32, false)].into_iter(), 32),
            None,
            "external/in-flight owners forbid collection"
        );
    }
}
