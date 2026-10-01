//! Borrowed page stream. The engine owns pointers; this crate only sees safe slices.
pub const MAGIC: u32 = 0x5343_4d50;
pub const VERSION: u32 = 6;

pub(crate) struct Reader<'a> {
    pages: &'a [&'a [u8]],
    page: usize,
    offset: usize,
    remaining: usize,
}
impl<'a> Reader<'a> {
    pub fn new(pages: &'a [&'a [u8]]) -> Result<Self, String> {
        let remaining = pages
            .iter()
            .try_fold(0usize, |n, p| n.checked_add(p.len()))
            .ok_or("source byte count overflow")?;
        Ok(Self {
            pages,
            page: 0,
            offset: 0,
            remaining,
        })
    }
    fn bytes<const N: usize>(&mut self) -> Result<[u8; N], String> {
        if self.remaining < N {
            return Err("truncated Minecraft source stream".into());
        }
        let mut out = [0; N];
        let mut written = 0;
        while written < N {
            let page = self.pages[self.page];
            let n = (N - written).min(page.len() - self.offset);
            out[written..written + n].copy_from_slice(&page[self.offset..self.offset + n]);
            self.offset += n;
            written += n;
            if self.offset == page.len() {
                self.page += 1;
                self.offset = 0;
            }
        }
        self.remaining -= N;
        Ok(out)
    }
    pub fn u32(&mut self) -> Result<u32, String> {
        Ok(u32::from_le_bytes(self.bytes()?))
    }
    pub fn i32(&mut self) -> Result<i32, String> {
        Ok(self.u32()? as i32)
    }
    pub fn u64(&mut self) -> Result<u64, String> {
        Ok(u64::from_le_bytes(self.bytes()?))
    }
    /// Decode contiguous words a page at a time. Only a word crossing a page boundary uses
    /// the scalar byte gather; source memory is borrowed only until this owned Vec is filled.
    fn words<T, const N: usize>(
        &mut self,
        count: usize,
        decode: fn([u8; N]) -> T,
    ) -> Result<Vec<T>, String> {
        if count > self.remaining / N {
            return Err("truncated Minecraft source words".into());
        }
        let mut words = Vec::with_capacity(count);
        while words.len() < count {
            let page = self.pages[self.page];
            let available = ((page.len() - self.offset) / N).min(count - words.len());
            if available != 0 {
                let end = self.offset + available * N;
                words.extend(
                    page[self.offset..end]
                        .as_chunks::<N>()
                        .0
                        .iter()
                        .copied()
                        .map(decode),
                );
                self.offset = end;
                self.remaining -= available * N;
                if end == page.len() {
                    self.page += 1;
                    self.offset = 0;
                }
            } else {
                words.push(decode(self.bytes()?));
            }
        }
        Ok(words)
    }
    pub fn u32s(&mut self, count: usize) -> Result<Vec<u32>, String> {
        self.words(count, u32::from_le_bytes)
    }
    pub fn u64s(&mut self, count: usize) -> Result<Vec<u64>, String> {
        self.words(count, u64::from_le_bytes)
    }
    pub fn f32(&mut self) -> Result<f32, String> {
        let v = f32::from_bits(self.u32()?);
        if !v.is_finite() {
            return Err("nonfinite source float".into());
        }
        Ok(v)
    }
    pub fn f64(&mut self) -> Result<f64, String> {
        let v = f64::from_bits(self.u64()?);
        if !v.is_finite() {
            return Err("nonfinite source position".into());
        }
        Ok(v)
    }
    pub fn count(&mut self, stride: usize) -> Result<usize, String> {
        let count = self.u32()? as usize;
        if count > self.remaining / stride {
            return Err("invalid source record count".into());
        }
        Ok(count)
    }
    pub fn string(&mut self) -> Result<String, String> {
        let n = self.count(1)?;
        let mut bytes = Vec::with_capacity(n);
        for _ in 0..n {
            bytes.push(self.bytes::<1>()?[0]);
        }
        for _ in 0..(4 - n % 4) % 4 {
            if self.bytes::<1>()?[0] != 0 {
                return Err("nonzero source string padding".into());
            }
        }
        String::from_utf8(bytes).map_err(|_| "invalid source UTF-8".into())
    }
    pub fn header(&mut self, kind: u32) -> Result<(u32, u64, u64), String> {
        if self.u32()? != MAGIC || self.u32()? != VERSION {
            return Err("Minecraft source ABI mismatch".into());
        }
        let version = self.u32()?;
        if !matches!(version, 262 | 263) || self.u32()? != kind {
            return Err("unsupported Minecraft source version/kind".into());
        }
        let epoch = self.u64()?;
        let batch = self.u64()?;
        if epoch == 0 || batch == 0 {
            return Err("zero Minecraft source identity".into());
        }
        Ok((version, epoch, batch))
    }
    pub fn finish(self) -> Result<(), String> {
        if self.remaining != 0 {
            return Err("trailing Minecraft source bytes".into());
        }
        Ok(())
    }
}

pub(crate) fn u32_to(out: &mut Vec<u8>, v: u32) {
    out.extend(v.to_le_bytes());
}
pub(crate) fn u64_to(out: &mut Vec<u8>, v: u64) {
    out.extend(v.to_le_bytes());
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn bulk_words_preserve_endianness_unaligned_pages_and_scalar_cursor() {
        let words32: Vec<_> = (0..33_u32).map(|v| v.wrapping_mul(0x9e3779b9)).collect();
        let words64: Vec<_> = (0..17_u64)
            .map(|v| v.wrapping_mul(0x9e3779b97f4a7c15))
            .collect();
        let mut bytes = vec![17];
        for &word in &words32 {
            u32_to(&mut bytes, word);
        }
        u64_to(&mut bytes, 0xff01020304050607);
        for &word in &words64 {
            u64_to(&mut bytes, word);
        }
        u32_to(&mut bytes, 0xdeadbeef);
        for size in 1..=bytes.len() {
            let mut pages = vec![&[][..]];
            for part in bytes.chunks(size) {
                pages.extend([part, &[]]);
            }
            let mut r = Reader::new(&pages).unwrap();
            assert_eq!(r.bytes::<1>().unwrap(), [17]);
            assert!(r.u64s(0).unwrap().is_empty());
            assert_eq!(r.u32s(33).unwrap(), words32);
            assert_eq!(r.u64().unwrap(), 0xff01020304050607);
            assert_eq!(r.u64s(17).unwrap(), words64);
            assert_eq!(r.u32().unwrap(), 0xdeadbeef);
            assert!(r.u32s(0).unwrap().is_empty());
            r.finish().unwrap();
        }
        for end in 0..64 {
            let pages = [&bytes[..end]];
            let mut r = Reader::new(&pages).unwrap();
            assert!(r.u32s(end / 4 + 1).is_err());
            assert!(r.u64s(end / 8 + 1).is_err());
            // Failed length checks do not consume a prefix or allocate by the rejected count.
            assert!(r.u64s(usize::MAX).is_err());
            if end != 0 {
                assert_eq!(r.bytes::<1>().unwrap(), [17]);
            }
        }
    }
}
