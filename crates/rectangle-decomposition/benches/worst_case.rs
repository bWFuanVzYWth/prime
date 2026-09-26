use std::hint::black_box;

use criterion::{BenchmarkId, Criterion, criterion_group, criterion_main};
use rectangle_decomposition::{QuadLeaf64, Rectangle, SparseOptimalScratch64, SparseQuadImage64};

#[path = "../src/corpus.rs"]
mod corpus;

fn checked<T, E: std::fmt::Debug>(result: Result<T, E>) -> T {
    match result {
        Ok(value) => value,
        Err(error) => {
            eprintln!("rectangle benchmark failed: {error:?}");
            std::process::exit(1);
        }
    }
}

fn bench_corpus(criterion: &mut Criterion) {
    let mut group = criterion.benchmark_group("rectangle_64");
    for case in corpus::CASES {
        let leaves = checked(case.leaves());
        let image = checked(SparseQuadImage64::from_leaves(&leaves));
        let mut scratch = Box::new(SparseOptimalScratch64::new());
        let rectangles = checked(scratch.decompose_borrowed(&leaves));
        checked(corpus::verify(&leaves, rectangles));
        assert_eq!(rectangles.len(), case.count());

        // 输入、容量与语义验证在计时外；借用结果不复制到新 Vec。
        group.bench_with_input(
            BenchmarkId::new("leaves", case.name()),
            &leaves,
            |b, input| {
                b.iter(|| black_box(checked(scratch.decompose_borrowed(black_box(input))).len()));
            },
        );
        group.bench_with_input(
            BenchmarkId::new("prepared", case.name()),
            &image,
            |b, input| {
                b.iter(|| {
                    black_box(checked(scratch.decompose_quads_borrowed(black_box(input))).len())
                });
            },
        );
        group.bench_with_input(
            BenchmarkId::new("prepare", case.name()),
            &leaves,
            |b, input| {
                b.iter(|| black_box(checked(SparseQuadImage64::from_leaves(black_box(input)))));
            },
        );
    }
    group.finish();
}

fn bench_scratch(criterion: &mut Criterion) {
    criterion.bench_function("scratch_init_64", |b| {
        b.iter(|| black_box(Box::new(SparseOptimalScratch64::new())));
    });
}

criterion_group!(benches, bench_corpus, bench_scratch);
criterion_main!(benches);
