use super::*;
use crate::model::{Catalog, Model};
fn face() -> Quad {
    Quad {
        sprite: 0,
        emission: 0,
        positions: [[0., 0., 0.], [1., 0., 0.], [1., 1., 0.], [0., 1., 0.]],
        uvs: [[0., 0.], [1., 0.], [1., 1.], [0., 1.]],
        face: 6,
        tint: -1,
        layer: 1,
    }
}
fn reverse(mut q: Quad) -> Quad {
    q.positions = [0, 3, 2, 1].map(|i| q.positions[i]);
    q.uvs = [0, 3, 2, 1].map(|i| q.uvs[i]);
    q
}
fn emit(
    c: &Catalog,
    name: &str,
    visible: u32,
) -> (Vec<CompiledQuad>, Vec<SurfaceFace>, crate::tint::Deferred) {
    let state = State {
        id: 7,
        name: name.into(),
        model: 1,
        ..Default::default()
    };
    let mut layers = Default::default();
    let mut surfaces = Default::default();
    let mut tints = crate::tint::Deferred::default();
    tints.begin(7, [3, 4, 5]);
    c.emit(
        &state,
        [3, 4, 5],
        visible,
        &mut layers,
        &mut Default::default(),
        &mut tints,
        &mut surfaces,
    );
    (
        layers.into_iter().flatten().collect(),
        surfaces.into_iter().flatten().collect(),
        tints,
    )
}
fn catalog(quads: Vec<Quad>) -> Catalog {
    let mut c = Catalog::default();
    c.models.insert(1, Model::Mesh(quads));
    c.prepare();
    c
}
#[test]
fn duplicates_reverse_and_known_inner_shell_are_prepared_once_with_negative_cases() {
    let a = face();
    let mut c = catalog(vec![a.clone(), a.clone(), reverse(a.clone())]);
    assert_eq!(emit(&c, "test:sheet", 127).0.len(), 1);
    let first = c.prepared_for_test(1);
    c.prepare();
    assert_eq!(first, c.prepared_for_test(1));
    let mut inner = reverse(a.clone());
    for p in &mut inner.positions {
        for x in p {
            *x = if *x == 0. {
                0.002 / 16.
            } else {
                1. - 0.002 / 16.
            };
        }
    }
    assert_eq!(
        emit(&catalog(vec![a.clone(), inner.clone()]), "test:sheet", 127)
            .1
            .len(),
        1
    );
    inner.positions[0][2] += 0.00001;
    assert_eq!(
        emit(&catalog(vec![a.clone(), inner]), "test:sheet", 127)
            .0
            .len(),
        2
    );
    let mut warped = a.clone();
    warped.positions[2][2] = 0.1;
    let mut other = reverse(warped.clone());
    other.positions.rotate_left(1);
    other.uvs.rotate_left(1);
    assert_eq!(
        emit(&catalog(vec![warped, other]), "test:sheet", 127)
            .0
            .len(),
        2
    );
}
#[test]
fn independent_cull_conditions_and_side_uvs_survive_definition_preparation() {
    let mut a = face();
    a.face = 2;
    a.tint = 0;
    let mut b = reverse(a.clone());
    b.face = 3;
    b.tint = 1;
    b.uvs[0][0] = 0.25;
    let c = catalog(vec![a.clone(), b.clone()]);
    for visible in [1 << 2, 1 << 3] {
        let (plain, rich, tints) = emit(&c, "test:flower", visible);
        assert_eq!(plain.len(), 1);
        assert!(rich.is_empty());
        assert_eq!(tints.requests.len(), 1);
    }
    let (plain, rich, tints) = emit(&c, "test:flower", 127);
    assert!(plain.is_empty());
    assert_eq!(rich.len(), 1);
    assert_eq!(tints.requests.len(), 2);
    let d = rich[0].detail.as_ref().unwrap();
    assert_eq!(d.mode, LayerMode::Bilateral);
    assert_eq!(d.layer.uvs[0], [0.25, 0.]);
    assert_eq!(rich[0].geometry.uvs[0], [0., 0.]);
}
#[test]
fn state_bound_overlay_keeps_each_tint_and_does_not_pollute_shared_model() {
    let mut base = face();
    base.layer = 0;
    let mut top = base.clone();
    top.tint = 0;
    top.layer = 1;
    top.uvs = [[0.25, 0.5]; 4];
    let c = catalog(vec![base, top]);
    let (plain, rich, _) = emit(&c, "test:ordinary", 127);
    assert_eq!(plain.len(), 2);
    assert!(rich.is_empty());
    let (plain, rich, tints) = emit(&c, "minecraft:grass_block", 127);
    assert!(plain.is_empty());
    assert_eq!(rich[0].flags(), 0);
    let mut surfaces = [rich, vec![], vec![]];
    let mut layers = Default::default();
    tints.apply(&[0xff40a080], &mut layers, &mut surfaces);
    assert_eq!(surfaces[0][0].geometry.colors, [[1.; 4]; 4]);
    assert_eq!(
        surfaces[0][0].detail.as_ref().unwrap().layer.colors[0],
        [64. / 255., 160. / 255., 128. / 255., 1.]
    );
    let mut solid_torch = face();
    solid_torch.layer = 0;
    assert_eq!(
        flags(
            &State {
                name: "minecraft:redstone_torch".into(),
                ..Default::default()
            },
            &solid_torch
        ),
        1
    );
}

#[test]
fn deterministic_multipart_prepares_cross_child_relations_but_weighted_keeps_selection() {
    let a = face();
    let mut b = reverse(a.clone());
    b.sprite = 2;
    let mut c = Catalog::default();
    c.models.insert(1, Model::Multipart(vec![2, 3]));
    c.models.insert(2, Model::Mesh(vec![a]));
    c.models.insert(3, Model::Alias(4));
    c.models.insert(4, Model::Mesh(vec![b]));
    c.states.insert(
        7,
        State {
            id: 7,
            model: 1,
            ..Default::default()
        },
    );
    c.prepare();
    let (plain, rich, _) = emit(&c, "test:multipart", 127);
    assert!(plain.is_empty());
    assert_eq!(rich.len(), 1);
    assert_eq!(rich[0].detail.as_ref().unwrap().mode, LayerMode::Bilateral);
    let prepared = c.prepared_for_test(1);
    c.prepare();
    assert_eq!(c.prepared_for_test(1), prepared);

    let mut selected = Catalog::default();
    selected.models.insert(1, Model::Multipart(vec![2, 3]));
    selected.models.insert(2, Model::Mesh(vec![face()]));
    selected.models.insert(3, Model::Weighted(vec![(1, 4)], 1));
    selected
        .models
        .insert(4, Model::Mesh(vec![reverse(face())]));
    selected.states.insert(
        7,
        State {
            id: 7,
            model: 1,
            ..Default::default()
        },
    );
    selected.prepare();
    let (plain, rich, _) = emit(&selected, "test:weighted", 127);
    assert_eq!(plain.len(), 2);
    assert!(rich.is_empty());
}
