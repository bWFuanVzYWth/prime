//! The C headers are the only authored ABI layout contract.
mod generated;
pub use generated::*;
pub mod scene;

#[derive(Default)]
pub struct Budget(u64);
impl Budget {
    pub fn add(&mut self, bytes: u64) -> Result<(), String> {
        self.0 = self.0.checked_add(bytes).ok_or("ABI batch size overflow")?;
        if self.0 > PRIME_MAX_BATCH_BYTES as u64 {
            return Err("ABI batch exceeds 256 MiB".into());
        }
        Ok(())
    }
    pub fn array<T>(&mut self, count: u64) -> Result<(), String> {
        self.add(
            count
                .checked_mul(std::mem::size_of::<T>() as u64)
                .ok_or("ABI array size overflow")?,
        )
    }
}
pub mod minecraft;

/// Read a caller-owned POD after checking its alignment and required common header.
/// # Safety
/// `T` is an initialized C POD with a leading `PrimeHeader`. `pointer` addresses a
/// readable header, and a full `T` when its declared size matches. The allocation
/// remains live and immutable for `'a` and every borrow derived from the result.
pub unsafe fn input<'a, T>(pointer: *const T) -> Result<&'a T, String> {
    if pointer.is_null() || !(pointer as usize).is_multiple_of(std::mem::align_of::<T>()) {
        return Err("Null or misaligned ABI input".into());
    }
    let header = unsafe { &*pointer.cast::<PrimeHeader>() };
    if header.abi_version != PRIME_ABI_VERSION
        || header.struct_size as usize != std::mem::size_of::<T>()
    {
        return Err("ABI version or structure size mismatch".into());
    }
    Ok(unsafe { &*pointer })
}

/// # Safety
/// A nonempty span addresses `count` initialized POD values in one allocation,
/// kept live and immutable for `'a` and every borrow derived from the result.
pub unsafe fn slice<'a, T>(pointer: *const T, count: u64) -> Result<&'a [T], String> {
    let bytes = count
        .checked_mul(std::mem::size_of::<T>() as u64)
        .ok_or("ABI span overflow")?;
    if bytes > PRIME_MAX_BATCH_BYTES as u64 || bytes > isize::MAX as u64 {
        return Err("ABI span exceeds batch capacity".into());
    }
    if count == 0 {
        return Ok(&[]);
    }
    if pointer.is_null() || !(pointer as usize).is_multiple_of(std::mem::align_of::<T>()) {
        return Err("Null or misaligned ABI span".into());
    }
    Ok(unsafe { std::slice::from_raw_parts(pointer, count as usize) })
}
