//! Primary column/page storage: local invalidations do not scan unrelated resident pages.
use crate::schedule::Section;
use std::collections::HashMap;

pub(crate) struct ColumnCache<T> {
    columns: HashMap<(i32, i32), HashMap<i32, T>>,
    #[cfg(test)]
    pub visited_pages: usize,
}
impl<T> Default for ColumnCache<T> {
    fn default() -> Self {
        Self {
            columns: HashMap::new(),
            #[cfg(test)]
            visited_pages: 0,
        }
    }
}
impl<T> ColumnCache<T> {
    pub fn clear(&mut self) {
        self.columns.clear();
    }
    pub fn get(&self, section: &Section) -> Option<&T> {
        self.columns.get(&(section.0, section.2))?.get(&section.1)
    }
    pub fn insert(&mut self, section: Section, value: T) {
        self.columns
            .entry((section.0, section.2))
            .or_default()
            .insert(section.1, value);
    }
    pub fn remove(&mut self, section: &Section) {
        let key = (section.0, section.2);
        if let Some(column) = self.columns.get_mut(&key) {
            column.remove(&section.1);
            if column.is_empty() {
                self.columns.remove(&key);
            }
        }
    }
    pub fn remove_column(&mut self, key: (i32, i32)) {
        if let Some(_column) = self.columns.remove(&key) {
            #[cfg(test)]
            {
                self.visited_pages += _column.len();
            }
        }
    }
    pub fn retain_column(&mut self, key: (i32, i32), mut keep: impl FnMut(&mut T) -> bool) {
        if let Some(column) = self.columns.get_mut(&key) {
            #[cfg(test)]
            {
                self.visited_pages += column.len();
            }
            column.retain(|_, value| keep(value));
            if column.is_empty() {
                self.columns.remove(&key);
            }
        }
    }
    #[cfg(test)]
    pub fn is_empty(&self) -> bool {
        self.columns.is_empty()
    }
    #[cfg(test)]
    pub fn keys(&self) -> impl Iterator<Item = Section> + '_ {
        self.columns
            .iter()
            .flat_map(|(&(x, z), ys)| ys.keys().map(move |&y| Section(x, y, z)))
    }
}
impl<T: Default> ColumnCache<T> {
    pub fn get_or_default(&mut self, section: Section) -> &mut T {
        self.columns
            .entry((section.0, section.2))
            .or_default()
            .entry(section.1)
            .or_default()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn local_retention_visits_only_one_column_and_reclaims_empty_columns() {
        let mut cache = ColumnCache::default();
        for x in -1000..1000 {
            for y in -2..2 {
                cache.insert(Section(x, y, -x), 1);
            }
        }
        cache.retain_column((0, 0), |_| false);
        assert_eq!(cache.visited_pages, 4);
        assert!(!cache.columns.contains_key(&(0, 0)));
        assert_eq!(cache.get(&Section(999, 0, -999)), Some(&1));
        cache.remove(&Section(999, 0, -999));
        assert!(cache.columns.contains_key(&(999, -999)));
        cache.remove_column((999, -999));
        assert_eq!(cache.visited_pages, 7);
    }
}
