//! Production instance publication versus the diagnostic snapshot, same build and input.
use super::{BYTES, CALLS, COUNT, header};
use prime_scene::{
    Scene,
    instances::InstanceContext,
    translation::{BatchLimits, Planner},
};
use std::{
    collections::BTreeMap, error::Error, fmt::Write, hint::black_box, sync::atomic::Ordering,
    time::Instant,
};

fn batch_header(sequence: u64, counts: [u32; 4]) -> Vec<u8> {
    let mut bytes = header(7);
    bytes.extend(sequence.to_le_bytes());
    for value in counts {
        bytes.extend(value.to_le_bytes());
    }
    bytes
}

fn write_prototype(bytes: &mut Vec<u8>) {
    bytes.extend(1_u64.to_le_bytes()); // prototype id
    bytes.extend(1_u64.to_le_bytes()); // source revision
    bytes.extend(1_u32.to_le_bytes()); // one span
    bytes.extend(0_u32.to_le_bytes());
    for value in [0_u32, 0, 4, 96, 24, 0, 12, 16] {
        bytes.extend(value.to_le_bytes());
    }
    // Four closed cuboids (48 triangles), a complexity proxy, not a captured MC model.
    for (lo, hi) in [
        ([0.0, 0.0, 0.0], [1.0, 0.7, 1.0]),
        ([0.0, 0.7, 0.0], [1.0, 1.0, 1.0]),
        ([0.44, 0.5, -0.06], [0.56, 0.8, 0.0]),
        ([0.05, 0.05, 0.05], [0.95, 0.15, 0.95]),
    ] {
        let [x0, y0, z0] = lo;
        let [x1, y1, z1] = hi;
        for face in [
            [[x0, y0, z1], [x1, y0, z1], [x1, y1, z1], [x0, y1, z1]],
            [[x1, y0, z0], [x0, y0, z0], [x0, y1, z0], [x1, y1, z0]],
            [[x0, y0, z0], [x0, y0, z1], [x0, y1, z1], [x0, y1, z0]],
            [[x1, y0, z1], [x1, y0, z0], [x1, y1, z0], [x1, y1, z1]],
            [[x0, y1, z1], [x1, y1, z1], [x1, y1, z0], [x0, y1, z0]],
            [[x0, y0, z0], [x1, y0, z0], [x1, y0, z1], [x0, y0, z1]],
        ] {
            for (position, uv) in
                face.into_iter()
                    .zip([[0.0_f32, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 1.0]])
            {
                for value in position {
                    bytes.extend(f32::to_le_bytes(value));
                }
                bytes.extend([255; 4]);
                for value in uv {
                    bytes.extend(value.to_le_bytes());
                }
            }
        }
    }
}

fn write_instance(bytes: &mut Vec<u8>, id: u64, revision: u64, position: u32, moved: bool) {
    for value in [id, revision, 1] {
        bytes.extend(value.to_le_bytes());
    }
    for value in [
        29_999_744.0 + f64::from(position % 128) * 1.25,
        64.0,
        -16.0 + f64::from(position / 128) * 1.25,
    ] {
        bytes.extend(value.to_le_bytes());
    }
    for value in [
        1.0_f32,
        0.0,
        0.0,
        0.0,
        0.0,
        1.0,
        0.0,
        if moved { 0.03125 } else { 0.0 },
        0.0,
        0.0,
        1.0,
        0.0,
    ] {
        bytes.extend(value.to_le_bytes());
    }
    bytes.extend(u32::MAX.to_le_bytes());
    bytes.extend(u32::MAX.to_le_bytes());
    bytes.extend([180, 128, 72, 255]);
    bytes.extend(0_u32.to_le_bytes());
    for value in [1.0_f32, 1.0, 0.0, 0.0] {
        bytes.extend(value.to_le_bytes());
    }
}

fn initial_packet(objects: u32) -> Vec<u8> {
    let mut bytes = batch_header(1, [1, 0, objects, 0]);
    bytes.reserve(24 + 32 + 96 * 24 + objects as usize * 128);
    write_prototype(&mut bytes);
    for position in 0..objects {
        write_instance(&mut bytes, u64::from(position) + 1, 1, position, false);
    }
    bytes
}

pub(super) fn measure(samples: usize) -> Result<String, Box<dyn Error>> {
    let mut csv = String::from(
        "mode,resident,changed,sample,warmup,allocation_instrumentation,submit_ns,plan_ns,total_ns,allocation_requests,requested_bytes,instances_visited,poses_evaluated,material_records,tlas_changed\n",
    );
    for instrument in [false, true] {
        for resident in [1000_u32, 10000, 50000] {
            for changed in [0, 1, resident / 100, resident] {
                for incremental in [false, true] {
                    let mut context = InstanceContext::new(1);
                    let textures = BTreeMap::new();
                    context.submit(&initial_packet(resident), &textures, 0)?;
                    context.publish();
                    let scene = Scene {
                        epoch: 1,
                        anchor: [29_999_744.0, 64.0, -16.0],
                        ..Default::default()
                    };
                    let mut planner = Planner::new(BatchLimits {
                        triangles: 1 << 25,
                        placements: 1 << 23,
                    })?;
                    let initial = if incremental {
                        planner.plan(&scene, context.input())?
                    } else {
                        planner.plan(&scene, context.scene())?
                    };
                    planner.recycle(initial);
                    for sample in 0..samples + 10 {
                        let sequence = sample as u64 + 2;
                        let mut packet = batch_header(sequence, [0, 0, changed, 0]);
                        for index in 0..changed {
                            write_instance(
                                &mut packet,
                                u64::from(index) + 1,
                                sequence,
                                index,
                                sample % 2 == 0,
                            );
                        }
                        CALLS.store(0, Ordering::Relaxed);
                        BYTES.store(0, Ordering::Relaxed);
                        COUNT.store(instrument, Ordering::Relaxed);
                        let start = Instant::now();
                        if changed != 0 {
                            context.submit(black_box(&packet), &textures, 0)?;
                        }
                        context.publish();
                        let submitted = Instant::now();
                        let plan = if incremental {
                            planner.plan(&scene, context.input())?
                        } else {
                            planner.plan(&scene, context.scene())?
                        };
                        let work = (
                            plan.instances_visited,
                            plan.poses_evaluated,
                            plan.changes.iter().filter(|c| c.material).count(),
                            plan.tlas_changed,
                        );
                        black_box(&plan);
                        planner.recycle(plan);
                        let end = Instant::now();
                        COUNT.store(false, Ordering::Relaxed);
                        if incremental {
                            assert_eq!(work.0, changed as usize);
                            assert_eq!(work.1, changed as usize);
                        }
                        let calls = CALLS.load(Ordering::Relaxed);
                        let bytes = BYTES.load(Ordering::Relaxed);
                        let mode = if incremental {
                            "incremental"
                        } else {
                            "snapshot"
                        };
                        writeln!(
                            csv,
                            "{mode},{resident},{changed},{sample},{},{instrument},{},{},{},{calls},{bytes},{},{},{},{}",
                            sample < 10,
                            (submitted - start).as_nanos(),
                            (end - submitted).as_nanos(),
                            (end - start).as_nanos(),
                            work.0,
                            work.1,
                            work.2,
                            work.3
                        )?;
                    }
                }
            }
        }
    }
    Ok(csv)
}
