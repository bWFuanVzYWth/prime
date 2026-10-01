//! Common branch-independent transport fixtures, frozen from the RT baseline.
use super::*;
use prime_scene::Texture;
use prime_scene::geometry::{CompiledQuad, MeshGeometry};
use prime_scene::scene::{SceneMesh, TextureMaterial};
use prime_scene::spatial::Cell;
use prime_scene::surface::{
    Emission, LayerMode, Medium, Optics, SurfaceDetail, SurfaceFace, SurfaceLayer, SurfaceMesh,
};
use std::collections::BTreeMap;

#[derive(Clone, Copy, Debug)]
pub(super) enum Case {
    Empty,
    Opaque,
    MirrorMiss,
    EmissiveMirror,
    NormalRoughMirror,
    GlassLanding,
    ThinGlass,
    TirLanding,
    RoughGlass,
    EqualIorRoughGlass,
    ThinEqualIorRoughGlass,
    SmoothMixed,
    Subsurface,
    ThinSubsurface,
    CoverageNormal,
    Overlay,
    RouletteRoom,
    GlassLights,
    RoughGlassLights,
    OpaqueGlassLights,
}
impl Case {
    pub(super) const ALL: [Self; 20] = [
        Self::Empty,
        Self::Opaque,
        Self::MirrorMiss,
        Self::EmissiveMirror,
        Self::NormalRoughMirror,
        Self::GlassLanding,
        Self::ThinGlass,
        Self::TirLanding,
        Self::RoughGlass,
        Self::EqualIorRoughGlass,
        Self::ThinEqualIorRoughGlass,
        Self::SmoothMixed,
        Self::Subsurface,
        Self::ThinSubsurface,
        Self::CoverageNormal,
        Self::Overlay,
        Self::RouletteRoom,
        Self::GlassLights,
        Self::RoughGlassLights,
        Self::OpaqueGlassLights,
    ];
}

fn texel(pixel: [u8; 4]) -> Texture {
    Texture {
        width: 1,
        height: 1,
        pixels: Arc::from(pixel),
        region: None,
        sampling: None,
        material: None,
    }
}

fn sheet(z: f32, texture_id: u32) -> SurfaceFace {
    SurfaceFace::from_quad(CompiledQuad {
        positions: [
            [-20., -20., z],
            [20., -20., z],
            [20., 20., z],
            [-20., 20., z],
        ],
        uvs: [[0., 0.], [1., 0.], [1., 1.], [0., 1.]],
        color: [0.65, 0.75, 0.85, 1.],
        texture_id,
        flags: 0,
    })
}

fn glass(z: f32, reverse: bool, thin: bool) -> SurfaceFace {
    let mut face = sheet(z, 1);
    if reverse {
        face.geometry.positions.reverse();
        face.geometry.uvs.reverse();
    }
    face.media = [7, 0];
    face.optics = Some(Optics {
        negative: Medium {
            ior: 1.5,
            extinction: [0.07, 0.03, 0.01],
        },
        positive: Medium::default(),
        ior_textures: [None; 2],
        transmit: true,
        thin,
    });
    face
}

pub(super) fn fixture(case: Case, revision: u64) -> (Scene, Camera) {
    let mut scene = Scene {
        epoch: 1,
        revision,
        ready_terrain: [[0.; 3], [128., 0., 0.]]
            .map(|origin| Cell::containing(origin).unwrap())
            .into(),
        ..Default::default()
    };
    let mut camera = Camera {
        position: [0., 0., 2.],
        forward: [0., 0., -1.],
        right: [1., 0., 0.],
        up: [0., 1., 0.],
        vertical_fov_radians: 0.45,
    };
    let mut source = texel([255; 4]);
    let mut faces = Vec::new();
    let specular = match case {
        Case::MirrorMiss | Case::NormalRoughMirror => Some([255, 231, 0, 255]),
        Case::EmissiveMirror => Some([255, 231, 0, 128]),
        Case::GlassLanding | Case::GlassLights | Case::ThinGlass | Case::TirLanding => {
            Some([255, 5, 0, 255])
        }
        Case::RoughGlass
        | Case::RoughGlassLights
        | Case::EqualIorRoughGlass
        | Case::ThinEqualIorRoughGlass => Some([150, 5, 0, 255]),
        Case::SmoothMixed => Some([255, 5, 0, 255]),
        Case::Subsurface | Case::ThinSubsurface => Some([120, 5, 254, 255]),
        Case::CoverageNormal => Some([160, 5, 0, 255]),
        _ => None,
    };
    let normal = match case {
        Case::CoverageNormal => Some(texel([170, 110, 255, 0])),
        Case::NormalRoughMirror => Some(texel([128, 128, 255, 128])),
        _ => None,
    };
    if specular.is_some() || normal.is_some() {
        source.material = Some(Arc::new(TextureMaterial {
            specular: specular.map(texel),
            normal,
            authored_emission: matches!(case, Case::EmissiveMirror),
            ..Default::default()
        }));
    }
    scene.textures.insert(1, source);
    scene.textures.insert(2, texel([255; 4]));
    scene.textures.insert(3, texel([70, 210, 100, 140]));
    match case {
        Case::Empty => {}
        Case::GlassLanding | Case::GlassLights | Case::RoughGlass | Case::RoughGlassLights => {
            faces.extend([
                glass(0., false, false),
                glass(-0.5, true, false),
                sheet(-1., 2),
            ]);
        }
        Case::ThinGlass => {
            faces.extend([glass(0., false, true), sheet(-1., 2)]);
        }
        Case::EqualIorRoughGlass | Case::ThinEqualIorRoughGlass => {
            let thin = matches!(case, Case::ThinEqualIorRoughGlass);
            let mut front = glass(0., false, thin);
            front.optics.as_mut().unwrap().negative.ior = 1.;
            faces.push(front);
            if !thin {
                let mut back = glass(-0.5, true, false);
                back.optics.as_mut().unwrap().negative.ior = 1.;
                faces.push(back);
            }
            faces.push(sheet(-1., 2));
        }
        Case::TirLanding => {
            faces.push(glass(0., false, false));
            let mut landing = sheet(-2., 2);
            landing.optics = Some(Optics {
                negative: Medium {
                    ior: 1.5,
                    extinction: [0.07, 0.03, 0.01],
                },
                positive: Medium {
                    ior: 1.5,
                    extinction: [0.07, 0.03, 0.01],
                },
                ior_textures: [None; 2],
                transmit: false,
                thin: false,
            });
            landing.media = [7; 2];
            faces.push(landing);
            camera.position = [0., 0., -1.];
            camera.forward = [0.9, 0., 0.4358899];
            camera.right = [0.4358899, 0., -0.9];
            camera.vertical_fov_radians = 0.04;
        }
        Case::CoverageNormal => {
            let mut face = sheet(0., 1);
            face.geometry.flags = 2;
            face.geometry.colors.iter_mut().for_each(|c| c[3] = 0.5);
            faces.extend([face, sheet(-1., 2)]);
        }
        Case::ThinSubsurface => {
            let mut face = sheet(0., 1);
            face.geometry.flags = 1;
            faces.push(face);
        }
        Case::Overlay => {
            let mut face = sheet(0., 1);
            face.detail = Some(Arc::new(SurfaceDetail {
                mode: LayerMode::OverlayBoth,
                layer: SurfaceLayer {
                    colors: [[0.8, 0.35, 0.55, 1.]; 4],
                    uvs: face.geometry.uvs,
                    texture_id: 3,
                    flags: 2,
                    repeat: None,
                    emission: Emission::default(),
                },
            }));
            faces.push(face);
        }
        Case::RouletteRoom => {
            for axis in 0..3 {
                for sign in [-1., 1.] {
                    let mut face = sheet(0., 1);
                    let corners = [[-3., -3.], [3., -3.], [3., 3.], [-3., 3.]];
                    face.geometry.positions = corners.map(|uv| {
                        let mut p = [0.; 3];
                        p[axis] = sign * 3.;
                        p[(axis + 1) % 3] = uv[0];
                        p[(axis + 2) % 3] = uv[1];
                        p
                    });
                    if sign > 0. {
                        face.geometry.positions.reverse();
                    }
                    if axis == 1 && sign > 0. {
                        face.emission = Emission {
                            radiance: [0.4, 0.6, 0.8],
                            two_sided: false,
                            textured: false,
                        };
                    }
                    faces.push(face);
                }
            }
        }
        _ => faces.push(sheet(0., 1)),
    }
    if matches!(
        case,
        Case::Opaque
            | Case::OpaqueGlassLights
            | Case::GlassLights
            | Case::RoughGlassLights
            | Case::SmoothMixed
            | Case::Subsurface
            | Case::ThinSubsurface
            | Case::CoverageNormal
            | Case::Overlay
    ) {
        // A finite front emitter exercises light selection, coverage, reverse hit PDF and MIS.
        let mut emitter = sheet(1., 2);
        emitter.geometry.positions = [[-2., -1., 1.], [-1., -1., 1.], [-1., 1., 1.], [-2., 1., 1.]];
        emitter.emission = Emission {
            radiance: [2., 1., 0.5],
            two_sided: true,
            textured: false,
        };
        faces.push(emitter);
        if matches!(case, Case::Subsurface | Case::ThinSubsurface) {
            let mut rear = sheet(-1., 2);
            rear.emission = Emission {
                radiance: [0.5, 1., 2.],
                two_sided: true,
                textured: false,
            };
            faces.push(rear);
        }
    }
    if matches!(case, Case::OpaqueGlassLights) {
        // Off-screen optical geometry keeps ordinary opaque hits on the full
        // scene-wide (surface=2, light=1, optical=1) production specialization.
        let mut boundary = glass(0., false, false);
        for position in &mut boundary.geometry.positions {
            position[0] += 100.;
        }
        faces.push(boundary);
    }
    let mut groups = BTreeMap::<u32, Vec<SurfaceFace>>::new();
    for face in faces {
        groups.entry(face.flags()).or_default().push(face);
    }
    for (flags, faces) in groups {
        scene.meshes.insert(
            (1, flags),
            SceneMesh {
                revision,
                flags,
                origin: [0.; 3],
                triangles: MeshGeometry::Surfaces(Arc::new(
                    SurfaceMesh::from_resolved(revision, faces).unwrap(),
                )),
            },
        );
    }
    (scene, camera)
}
