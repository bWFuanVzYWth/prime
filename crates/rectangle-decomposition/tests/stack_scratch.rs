//! 安全资源回归：验证容量、复用、借用输出位置和 worker 栈，不检测分配次数。

use std::{hint::black_box, num::NonZeroU16};

use rectangle_decomposition::{QuadLeaf64, SparseOptimalScratch64, SparseQuadImage64};

type TestResult = Result<(), String>;

fn check_reused_capacity() -> TestResult {
    let mut scratch = SparseOptimalScratch64::new();
    // 大型输入使用堆后备；scratch 在 worker 栈上构造并复用。
    let mut leaves = Vec::with_capacity(4096);
    for holes in [false, true, false, true] {
        leaves.clear();
        for v in 0..64_u8 {
            for u in 0..64_u8 {
                if holes && u % 2 == 0 && v % 2 == 0 {
                    continue;
                }
                let value = if holes || (u + v) % 2 == 0 {
                    NonZeroU16::MIN
                } else {
                    NonZeroU16::MAX
                };
                leaves.push(QuadLeaf64 {
                    u,
                    v,
                    lod: 0,
                    value,
                });
            }
        }
        // 棋盘格达到 4096 个矩形；稠密孔洞达到每方向 1953 条 chord。
        let rectangles = scratch
            .decompose_borrowed(black_box(&leaves))
            .map_err(|error| format!("{error:?}"))?;
        if rectangles.len() != if holes { 1025 } else { 4096 } {
            return Err("reused scratch rectangle count mismatch".into());
        }
    }
    if !scratch
        .decompose_borrowed(&[])
        .map_err(|error| format!("{error:?}"))?
        .is_empty()
    {
        return Err("empty input retained previous rectangles".into());
    }
    Ok(())
}

#[test]
fn reused_scratch_handles_capacity_limits() -> TestResult {
    check_reused_capacity()
}

#[test]
fn borrowed_output_stays_inside_scratch() -> TestResult {
    let mut scratch = SparseOptimalScratch64::new();
    let leaves = [QuadLeaf64 {
        u: 0,
        v: 0,
        lod: 6,
        value: NonZeroU16::MIN,
    }];
    let rectangles = scratch
        .decompose_borrowed(&leaves)
        .map_err(|error| format!("{error:?}"))?;
    if rectangles.is_empty() {
        return Err("nonempty input produced no rectangles".into());
    }
    let output_start = rectangles.as_ptr() as usize;
    let output_end = output_start + std::mem::size_of_val(rectangles);
    let scratch_start = std::ptr::from_ref(&scratch) as usize;
    if output_start < scratch_start || output_end > scratch_start + std::mem::size_of_val(&scratch)
    {
        return Err("borrowed output is outside scratch storage".into());
    }
    Ok(())
}

#[test]
fn prepared_images_and_leaves_share_reused_scratch() -> TestResult {
    let fragmented =
        [(0, 0, 0), (1, 0, 0), (1, 1, 0), (2, 1, 0), (8, 8, 1)].map(|(u, v, lod)| QuadLeaf64 {
            u,
            v,
            lod,
            value: NonZeroU16::MIN,
        });
    let full = [QuadLeaf64 {
        u: 0,
        v: 0,
        lod: 6,
        value: NonZeroU16::MAX,
    }];
    let fragmented_image =
        SparseQuadImage64::from_leaves(&fragmented).map_err(|error| format!("{error:?}"))?;
    let full_image = SparseQuadImage64::from_leaves(&full).map_err(|error| format!("{error:?}"))?;
    let empty_image = SparseQuadImage64::from_leaves(&[]).map_err(|error| format!("{error:?}"))?;
    let mut scratch = SparseOptimalScratch64::new();
    for (leaves, image, count) in [
        (fragmented.as_slice(), &fragmented_image, 3),
        (&[], &empty_image, 0),
        (full.as_slice(), &full_image, 1),
        (fragmented.as_slice(), &fragmented_image, 3),
    ] {
        if scratch
            .decompose_quads_borrowed(image)
            .map_err(|error| format!("{error:?}"))?
            .len()
            != count
            || scratch
                .decompose_borrowed(leaves)
                .map_err(|error| format!("{error:?}"))?
                .len()
                != count
        {
            return Err("scratch reuse changed decomposition results".into());
        }
    }
    Ok(())
}

#[test]
fn independent_workers_reuse_stack_storage() -> TestResult {
    // 固定四个 worker，各自独占 scratch；不引入共享状态或自定义分配器。
    let workers = [(); 4].map(|()| {
        std::thread::Builder::new()
            .stack_size(2 * 1024 * 1024)
            .spawn(check_reused_capacity)
    });
    let mut outcome = Ok(());
    for worker in workers {
        let result: TestResult = match worker {
            Ok(worker) => worker
                .join()
                .unwrap_or_else(|_| Err("worker panicked".into())),
            Err(error) => Err(error.to_string()),
        };
        // 无论先前是否失败，都等待已经启动的 worker 收尾。
        outcome = outcome.and(result);
    }
    outcome
}
