//! Borrowed page stream. The engine owns pointers; this crate only sees safe slices.
pub const MAGIC: u32 = 0x5343_4d50;
pub const VERSION: u32 = 1;

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
