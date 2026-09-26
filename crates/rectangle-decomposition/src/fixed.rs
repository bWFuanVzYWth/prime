//! 全部元素已初始化的固定容量缓冲；清空仅重置长度，不调用分配器。

use std::ops::{Deref, DerefMut};

use crate::{copy, get_mut};

#[derive(Clone, Debug)]
pub struct FixedVec<T: Copy, const N: usize> {
    items: [T; N],
    len: usize,
}

impl<T: Copy, const N: usize> FixedVec<T, N> {
    pub(crate) const fn new(fill: T) -> Self {
        Self {
            items: [fill; N],
            len: 0,
        }
    }

    pub(crate) const fn len(&self) -> usize {
        self.len
    }

    pub(crate) const fn capacity(&self) -> usize {
        self.items.len()
    }

    pub(crate) const fn clear(&mut self) {
        self.len = 0;
    }

    pub(crate) const fn as_slice(&self) -> &[T] {
        self.items.split_at(self.len).0
    }

    pub(crate) const fn as_mut_slice(&mut self) -> &mut [T] {
        self.items.split_at_mut(self.len).0
    }

    pub(crate) fn push(&mut self, value: T) {
        *get_mut(&mut self.items, self.len) = value;
        self.len += 1;
    }

    pub(crate) fn pop(&mut self) -> Option<T> {
        self.len = self.len.checked_sub(1)?;
        Some(copy(&self.items, self.len))
    }

    pub(crate) fn resize(&mut self, len: usize, value: T) {
        if len > self.len {
            crate::slice_mut(&mut self.items, self.len..len).fill(value);
        }
        self.len = len;
    }

    pub(crate) fn extend_from_slice(&mut self, values: &[T]) {
        let end = self.len + values.len();
        crate::slice_mut(&mut self.items, self.len..end).copy_from_slice(values);
        self.len = end;
    }

    pub(crate) fn retain(&mut self, mut keep: impl FnMut(&T) -> bool) {
        let mut end = 0;
        for index in 0..self.len {
            let value = copy(&self.items, index);
            if keep(&value) {
                *get_mut(&mut self.items, end) = value;
                end += 1;
            }
        }
        self.len = end;
    }
}

impl<T: Copy, const N: usize> Deref for FixedVec<T, N> {
    type Target = [T];
    fn deref(&self) -> &[T] {
        self.as_slice()
    }
}

impl<T: Copy, const N: usize> DerefMut for FixedVec<T, N> {
    fn deref_mut(&mut self) -> &mut [T] {
        self.as_mut_slice()
    }
}

impl<'a, T: Copy, const N: usize> IntoIterator for &'a FixedVec<T, N> {
    type Item = &'a T;
    type IntoIter = std::slice::Iter<'a, T>;
    fn into_iter(self) -> Self::IntoIter {
        self.iter()
    }
}

impl<T: Copy, const N: usize> Extend<T> for FixedVec<T, N> {
    fn extend<I: IntoIterator<Item = T>>(&mut self, values: I) {
        for value in values {
            self.push(value);
        }
    }
}
