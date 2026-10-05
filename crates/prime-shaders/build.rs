mod build_support;

fn main() {
    let mut shaders = Vec::new();
    for stage in ["generate", "retrace", "shift", "temporal"] {
        build_support::samplers(
            &mut shaders,
            &format!("shaders/restir_{stage}.slang"),
            &format!("restir_{stage}"),
            &[],
        );
    }
    for stage in ["workload", "resolve"] {
        build_support::add(
            &mut shaders,
            &format!("shaders/restir_{stage}.slang"),
            &format!("restir_{stage}"),
            &["PRIME_RESTIR_MATERIAL_ONLY=1"],
        );
    }
    build_support::samplers(
        &mut shaders,
        "shaders/restir_generate_rr.slang",
        "restir_generate_rr",
        &["PRIME_RESTIR_RR=1"],
    );
    build_support::add(
        &mut shaders,
        "shaders/restir_resolve.slang",
        "restir_resolve_rr",
        &["PRIME_RESTIR_MATERIAL_ONLY=1", "PRIME_RESTIR_RR=1"],
    );
    for stage in [
        "indirect",
        "spatial",
        "sample_ids",
        "duplicate_map",
        "rr_statistics",
        "debug_display",
    ] {
        build_support::add(
            &mut shaders,
            &format!("shaders/restir_{stage}.slang"),
            &format!("restir_{stage}"),
            &[],
        );
    }
    for stage in ["path_trace", "realtime_transport", "realtime_transport_rr"] {
        build_support::add(
            &mut shaders,
            &format!("shaders/{stage}.slang"),
            &format!("{stage}_tree"),
            &["PRIME_LIGHT_TREE=1"],
        );
    }
    for stage in [
        "realtime_primary",
        "realtime_primary_rr",
        "realtime",
        "realtime_rr",
        "rr_display",
        "realtime_linear",
        "rr_linear",
    ] {
        build_support::add(&mut shaders, &format!("shaders/{stage}.slang"), stage, &[]);
    }
    for (source, output) in [
        ("from_linear", "display_from_linear"),
        ("stars", "stars"),
        ("exposure_histogram", "exposure_histogram"),
        ("exposure_update", "exposure_update"),
        ("hdr_present", "hdr_present"),
        ("frame_generation_present", "frame_generation_present"),
    ] {
        build_support::add(
            &mut shaders,
            &format!("shaders/display/{source}.slang"),
            output,
            &[],
        );
    }
    for stage in [
        "prepare",
        "sky_update",
        "transmittance_update",
        "aerial_update",
        "aerial_transmittance_update",
        "shadow_demand",
        "shadow_resolve",
    ] {
        build_support::add(
            &mut shaders,
            &format!("shaders/atmosphere/{stage}.slang"),
            &format!("atmosphere_{stage}"),
            &[],
        );
    }
    if std::env::var_os("CARGO_FEATURE_ATMOSPHERE_BAKE").is_some() {
        for stage in [
            "transmittance",
            "directions",
            "incident",
            "moments",
            "multi_scattering",
            "ground",
        ] {
            build_support::add(
                &mut shaders,
                &format!("shaders/atmosphere/bake/{stage}.slang"),
                &format!("atmosphere_bake_{stage}"),
                &[],
            );
        }
    }
    if std::env::var_os("CARGO_FEATURE_LIGHT_SAMPLING_BENCH").is_some() {
        build_support::add(
            &mut shaders,
            "tests/shaders/sampling_experiment.slang",
            "light_sampling",
            &[],
        );
    }
    build_support::build(&shaders);
}
