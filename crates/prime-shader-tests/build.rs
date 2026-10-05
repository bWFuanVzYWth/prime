#[path = "../prime-shaders/build_support.rs"]
mod build_support;

fn main() {
    if std::env::var_os("CARGO_FEATURE_COMPILED").is_none() {
        return;
    }
    let mut shaders = Vec::new();
    for stage in [
        "foundations",
        "intersection",
        "display",
        "emitter_sampling",
        "light_tree",
        "optics",
        "texture",
        "roulette",
        "pbr",
        "pbr_delta",
        "full_openpbr",
        "pbr_texture",
        "primary",
        "primary_rr",
    ] {
        build_support::add(
            &mut shaders,
            &format!("tests/shaders/{stage}.slang"),
            stage,
            &[],
        );
    }
    for stage in [
        "restir_adapter",
        "restir_history",
        "restir_rc_view",
        "restir_medium_support",
    ] {
        build_support::samplers(
            &mut shaders,
            &format!("tests/shaders/{stage}.slang"),
            stage,
            &[],
        );
    }
    build_support::add(
        &mut shaders,
        "tests/shaders/atmosphere.slang",
        "atmosphere_test",
        &[],
    );
    build_support::build(&shaders);
}
