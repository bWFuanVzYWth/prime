use super::*;
use std::{process::Stdio, sync::Barrier};

const ARGS: &[&str] = &[
    "-entry",
    "main",
    "-stage",
    "compute",
    "-target",
    "spirv",
    "-profile",
    "sm_6_6",
    "-emit-spirv-directly",
    "-fvk-use-entrypoint-name",
    "-O3",
    "-g3",
];
const MAIN: &str = r#"#language slang 2026
#if VARIANT == 1
import "left.slang";
#else
import "right.slang";
#endif
#include "header.slangh"
RWStructuredBuffer<uint> output;
[numthreads(1, 1, 1)]
void main(uint3 id : SV_DispatchThreadID) { output[id.x] = selectedValue() + OFFSET; }
"#;

struct Fixture {
    root: TempDirectory,
    config: BuildConfig,
}
impl Fixture {
    fn new() -> Self {
        let parent =
            PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../target/prime-shader-build-tests");
        fs::create_dir_all(&parent).unwrap();
        let root = TempDirectory::new(&parent).unwrap();
        let source = root.0.join("源 files");
        fs::create_dir_all(source.join("shaders/nested")).unwrap();
        fs::create_dir_all(source.join("fallback")).unwrap();
        let mut config = BuildConfig::new(
            compiler(),
            &source,
            root.0.join("out-check"),
            root.0.join("cache"),
        );
        config.include_dirs = vec![PathBuf::from("shaders"), PathBuf::from("fallback")];
        config.dependency_roots = vec![PathBuf::from("shaders"), PathBuf::from("fallback")];
        config.args = ARGS.iter().map(OsString::from).collect();
        let fixture = Self { root, config };
        fixture.write("shaders/main.slang", MAIN);
        fixture.write(
            "shaders/left.slang",
            "#language slang 2026\npublic uint selectedValue() { return 17; }\n",
        );
        fixture.write(
            "shaders/right.slang",
            "#language slang 2026\npublic uint selectedValue() { return 29; }\n",
        );
        fixture.write("shaders/header.slangh", "#define OFFSET 7\n");
        fixture.write("shaders/other.slang", "#language slang 2026\nRWStructuredBuffer<uint> output;\n[numthreads(1,1,1)] void main(uint3 id : SV_DispatchThreadID) { output[id.x] = 31; }\n");
        fixture
    }
    fn write(&self, name: &str, bytes: &str) {
        let path = self.config.source_root.join(name);
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(path, bytes).unwrap();
    }
    fn run(&self, shaders: &[ShaderSpec]) -> BuildReport {
        build_with_jobserver(&self.config, shaders, None).unwrap()
    }
    fn binary(&self, name: &str) -> Vec<u8> {
        fs::read(self.config.output_dir.join(name)).unwrap()
    }
    fn recipe_paths(&self) -> Vec<PathBuf> {
        entries(&self.config.cache_root.join("v1/recipes"))
    }
    fn object_path(&self) -> PathBuf {
        let manifest: Manifest =
            serde_json::from_slice(&fs::read(self.recipe_paths().first().unwrap()).unwrap())
                .unwrap();
        self.config
            .cache_root
            .join("v1/objects")
            .join(manifest.object)
    }
}
fn compiler() -> PathBuf {
    env::var_os("SLANGC")
        .map(PathBuf::from)
        .or_else(|| {
            env::var_os("VULKAN_SDK").map(|sdk| {
                PathBuf::from(sdk).join(if cfg!(windows) {
                    "Bin/slangc.exe"
                } else {
                    "bin/slangc"
                })
            })
        })
        .unwrap_or_else(|| PathBuf::from("slangc"))
}
fn entries(path: &Path) -> Vec<PathBuf> {
    let mut entries: Vec<_> = fs::read_dir(path)
        .unwrap()
        .map(|e| e.unwrap().path())
        .collect();
    entries.sort();
    entries
}
fn main_spec() -> ShaderSpec {
    let mut spec = ShaderSpec::new("shaders/main.slang", "main.spv");
    spec.defines.push("VARIANT=1".into());
    spec
}

#[test]
#[ignore = "requires Slang compiler"]
fn real_slang_warm_cache_profile_sharing_and_active_closure() {
    let fixture = Fixture::new();
    let specs = [
        main_spec(),
        ShaderSpec::new("shaders/other.slang", "other.spv"),
    ];
    let cold = fixture.run(&specs);
    assert_eq!((cold.compiled, cold.cache_hits), (2, 0));
    assert!(cold.dependencies.iter().any(|p| p.ends_with("left.slang")));
    assert!(!cold.dependencies.iter().any(|p| p.ends_with("right.slang")));
    let original = fixture.binary("main.spv");
    let modified = fs::metadata(fixture.config.output_dir.join("main.spv"))
        .unwrap()
        .modified()
        .unwrap();
    let warm = fixture.run(&specs);
    assert_eq!(
        (warm.compiled, warm.cache_hits, warm.frontend_refreshes),
        (0, 2, 0)
    );
    assert_eq!(
        fs::metadata(fixture.config.output_dir.join("main.spv"))
            .unwrap()
            .modified()
            .unwrap(),
        modified
    );

    let mut test_config = fixture.config.clone();
    test_config.output_dir = fixture.root.0.join("out-test");
    let mut alias = main_spec();
    alias.output = PathBuf::from("renamed.spv");
    let report = build_with_jobserver(&test_config, &[alias], None).unwrap();
    assert_eq!(
        (
            report.compiled,
            report.cache_hits,
            report.frontend_refreshes
        ),
        (0, 1, 0)
    );
    assert_eq!(
        original,
        fs::read(test_config.output_dir.join("renamed.spv")).unwrap()
    );

    // Inactive module contents and unrelated Rust file contents are not active deps.
    fixture.write(
        "shaders/right.slang",
        "#language slang 2026\npublic uint selectedValue() { return 99; }\n",
    );
    fixture.write("rust_only.rs", "const X: u32 = 1;\n");
    let report = fixture.run(&specs);
    assert_eq!(
        (
            report.compiled,
            report.cache_hits,
            report.frontend_refreshes
        ),
        (0, 2, 0)
    );
    fixture.write("shaders/header.slangh", "#define OFFSET 19\n");
    let changed = fixture.run(&specs);
    assert_eq!(
        (
            changed.compiled,
            changed.cache_hits,
            changed.frontend_refreshes
        ),
        (1, 1, 1)
    );
    assert_ne!(original, fixture.binary("main.spv"));
    fixture.write("shaders/header.slangh", "#define OFFSET 7\n");
    let reverted = fixture.run(&specs);
    assert_eq!(
        (
            reverted.compiled,
            reverted.cache_hits,
            reverted.frontend_refreshes
        ),
        (0, 2, 1)
    );
    assert_eq!(original, fixture.binary("main.spv"));
    println!(
        "cold={:?}, warm={:?}, shared={:?}",
        cold.elapsed, warm.elapsed, report.elapsed
    );
}

#[test]
#[ignore = "requires Slang compiler"]
fn real_slang_conditional_import_changes_use_compiler_closure() {
    let fixture = Fixture::new();
    let spec = main_spec();
    fixture.run(std::slice::from_ref(&spec));
    let original = fixture.binary("main.spv");
    fixture.write(
        "shaders/main.slang",
        &MAIN.replace("#if VARIANT == 1", "#if VARIANT == 2"),
    );
    let changed = fixture.run(std::slice::from_ref(&spec));
    assert_eq!((changed.compiled, changed.frontend_refreshes), (1, 1));
    assert!(
        changed
            .dependencies
            .iter()
            .any(|p| p.ends_with("right.slang"))
    );
    assert!(
        !changed
            .dependencies
            .iter()
            .any(|p| p.ends_with("left.slang"))
    );
    assert_ne!(original, fixture.binary("main.spv"));
    fixture.write("shaders/left.slang", "invalid unused source\n");
    assert_eq!(
        fixture.run(std::slice::from_ref(&spec)).frontend_refreshes,
        0
    );
    let mut new_define = spec;
    new_define.defines = vec!["VARIANT=2".into()];
    assert!(build_with_jobserver(&fixture.config, &[new_define], None).is_err());
}

#[test]
#[ignore = "requires Slang compiler"]
fn real_slang_namespace_shadowing_and_unrelated_additions() {
    let fixture = Fixture::new();
    fixture.write(
        "fallback/shadow.slang",
        "#language slang 2026\npublic uint chosen() { return 23; }\n",
    );
    fixture.write("shaders/nested/shadow_main.slang", "#language slang 2026\nimport \"shadow.slang\";\nRWStructuredBuffer<uint> output;\n[numthreads(1,1,1)] void main(uint3 id : SV_DispatchThreadID) { output[id.x] = chosen(); }\n");
    let spec = ShaderSpec::new("shaders/nested/shadow_main.slang", "shadow.spv");
    let cold = fixture.run(std::slice::from_ref(&spec));
    assert!(
        cold.dependencies
            .iter()
            .any(|p| p.ends_with("fallback/shadow.slang"))
    );
    let original = fixture.binary("shadow.spv");
    fixture.write(
        "shaders/unrelated.slang",
        "#language slang 2026\npublic uint unused() { return 0; }\n",
    );
    let report = fixture.run(std::slice::from_ref(&spec));
    assert_eq!(
        (
            report.compiled,
            report.cache_hits,
            report.namespace_refreshes
        ),
        (0, 1, 1)
    );
    assert_eq!(original, fixture.binary("shadow.spv"));
    fixture.write(
        "shaders/nested/shadow.slang",
        "#language slang 2026\npublic uint chosen() { return 47; }\n",
    );
    let report = fixture.run(std::slice::from_ref(&spec));
    assert_eq!((report.compiled, report.namespace_refreshes), (1, 1));
    assert!(
        report
            .dependencies
            .iter()
            .any(|p| p.ends_with("nested/shadow.slang"))
    );
    assert!(
        !report
            .dependencies
            .iter()
            .any(|p| p.ends_with("fallback/shadow.slang"))
    );
    assert_ne!(original, fixture.binary("shadow.spv"));
    fs::remove_file(
        fixture
            .config
            .source_root
            .join("shaders/nested/shadow.slang"),
    )
    .unwrap();
    let report = fixture.run(std::slice::from_ref(&spec));
    assert_eq!(
        (
            report.compiled,
            report.cache_hits,
            report.namespace_refreshes
        ),
        (0, 1, 1)
    );
    assert_eq!(original, fixture.binary("shadow.spv"));
}

#[test]
#[ignore = "requires Slang compiler"]
fn real_slang_serialized_module_is_part_of_namespace_and_closure() {
    let mut fixture = Fixture::new();
    // Slang 2026.13.1 -emit-ir aborts with a Unicode working directory; normal
    // source compilation/depfile tests above still exercise Unicode paths.
    let ascii_source = fixture.root.0.join("source-serialized");
    fs::rename(&fixture.config.source_root, &ascii_source).unwrap();
    fixture.config.source_root = ascii_source;
    fixture.write(
        "shaders/left.slang",
        "#language slang 2026\nmodule left;\npublic uint selectedValue() { return 17; }\n",
    );
    let spec = main_spec();
    fixture.run(std::slice::from_ref(&spec));
    let original = fixture.binary("main.spv");
    fixture.write(
        "shaders/left.slang",
        "#language slang 2026\nmodule left;\npublic uint selectedValue() { return 53; }\n",
    );
    let module = PathBuf::from("shaders/left.slang-module");
    let status = Command::new(resolve_compiler(&fixture.config.compiler).unwrap())
        .current_dir(&fixture.config.source_root)
        .args(["shaders/left.slang", "-emit-ir", "-o"])
        .arg(&module)
        .status()
        .unwrap();
    assert!(status.success());
    fixture.write(
        "shaders/left.slang",
        "#language slang 2026\nmodule left;\npublic uint selectedValue() { return 17; }\n",
    );
    let report = fixture.run(std::slice::from_ref(&spec));
    assert_eq!((report.compiled, report.namespace_refreshes), (1, 1));
    assert!(
        report
            .dependencies
            .iter()
            .any(|p| p.ends_with("left.slang-module"))
    );
    assert_ne!(original, fixture.binary("main.spv"));
    let prepared = prepare(&fixture.config, std::slice::from_ref(&spec)).unwrap();
    let fresh = fixture.root.0.join("fresh.spv");
    assert!(
        command(&fixture.config, &prepared, &spec)
            .arg("-o")
            .arg(&fresh)
            .status()
            .unwrap()
            .success()
    );
    assert_eq!(fixture.binary("main.spv"), fs::read(&fresh).unwrap());
    // The compiler's dependency reports omit loaded serialized modules. Changing
    // only the binary must still miss, then agree with real fresh compilation.
    fixture.write(
        "shaders/left.slang",
        "#language slang 2026\nmodule left;\npublic uint selectedValue() { return 71; }\n",
    );
    assert!(
        Command::new(resolve_compiler(&fixture.config.compiler).unwrap())
            .current_dir(&fixture.config.source_root)
            .args(["shaders/left.slang", "-emit-ir", "-o"])
            .arg(&module)
            .status()
            .unwrap()
            .success()
    );
    fixture.write(
        "shaders/left.slang",
        "#language slang 2026\nmodule left;\npublic uint selectedValue() { return 17; }\n",
    );
    let changed = fixture.run(std::slice::from_ref(&spec));
    assert_eq!(
        (
            changed.compiled,
            changed.frontend_refreshes,
            changed.namespace_refreshes
        ),
        (1, 1, 0)
    );
    assert_ne!(fixture.binary("main.spv"), fs::read(&fresh).unwrap());
    assert!(
        command(&fixture.config, &prepared, &spec)
            .arg("-o")
            .arg(&fresh)
            .status()
            .unwrap()
            .success()
    );
    assert_eq!(fixture.binary("main.spv"), fs::read(fresh).unwrap());
}

#[test]
#[ignore = "requires Slang compiler"]
fn real_slang_corrupt_depfile_spirv_and_manifest_cannot_hit() {
    let fixture = Fixture::new();
    let spec = main_spec();
    fixture.run(std::slice::from_ref(&spec));
    let original = fixture.binary("main.spv");
    for file in ["dependencies.d", "shader.spv", "object.json"] {
        fs::write(fixture.object_path().join(file), "corrupt").unwrap();
        let report = fixture.run(std::slice::from_ref(&spec));
        assert_eq!(
            (
                report.compiled,
                report.cache_hits,
                report.frontend_refreshes
            ),
            (1, 0, 1),
            "{file}"
        );
        assert_eq!(original, fixture.binary("main.spv"));
    }
    fs::remove_file(fixture.object_path().join("dependencies.d")).unwrap();
    assert_eq!(fixture.run(std::slice::from_ref(&spec)).compiled, 1);
    fs::write(&fixture.recipe_paths()[0], "corrupt").unwrap();
    let recovered = fixture.run(std::slice::from_ref(&spec));
    assert_eq!(
        (
            recovered.compiled,
            recovered.cache_hits,
            recovered.frontend_refreshes
        ),
        (0, 1, 1)
    );
    assert_eq!(original, fixture.binary("main.spv"));
}

#[test]
#[ignore = "requires Slang compiler"]
fn real_slang_failures_are_not_cached() {
    let fixture = Fixture::new();
    let spec = main_spec();
    fixture.write(
        "shaders/left.slang",
        "#language slang 2026\npublic uint selectedValue() { return undefined_value; }\n",
    );
    for _ in 0..2 {
        let error =
            build_with_jobserver(&fixture.config, std::slice::from_ref(&spec), None).unwrap_err();
        assert!(error.to_string().contains("undefined_value"));
        assert!(fixture.recipe_paths().is_empty());
        assert!(entries(&fixture.config.cache_root.join("v1/objects")).is_empty());
        assert!(entries(&fixture.config.cache_root.join("v1/staging")).is_empty());
    }
    fixture.write(
        "shaders/left.slang",
        "#language slang 2026\npublic uint selectedValue() { return 17; }\n",
    );
    assert_eq!(fixture.run(std::slice::from_ref(&spec)).compiled, 1);
    assert_eq!(fixture.run(&[spec]).cache_hits, 1);
}

#[test]
#[ignore = "requires Slang compiler"]
fn real_slang_concurrent_callers_compile_same_recipe_once() {
    let fixture = Fixture::new();
    let barrier = Barrier::new(2);
    let mut second = fixture.config.clone();
    second.output_dir = fixture.root.0.join("out-second");
    let reports = std::thread::scope(|scope| {
        let child = scope.spawn(|| {
            barrier.wait();
            build_with_jobserver(&second, &[main_spec()], None).unwrap()
        });
        barrier.wait();
        let first = fixture.run(&[main_spec()]);
        (first, child.join().unwrap())
    });
    assert_eq!(reports.0.compiled + reports.1.compiled, 1);
    assert_eq!(reports.0.cache_hits + reports.1.cache_hits, 1);
    assert_eq!(
        fixture.binary("main.spv"),
        fs::read(second.output_dir.join("main.spv")).unwrap()
    );
}

#[test]
#[ignore = "requires Slang compiler"]
fn real_slang_cross_process_lock_and_exit_release() {
    let fixture = Fixture::new();
    let ready = fixture.root.0.join("ready");
    let go = fixture.root.0.join("go");
    let child = child_command(&fixture, "compile")
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .unwrap();
    wait_file(&ready);
    fs::write(&go, "go").unwrap();
    let parent = fixture.run(&[main_spec()]);
    let output = child.wait_with_output().unwrap();
    assert!(
        output.status.success(),
        "{}{}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
    let child_compiled: usize = fs::read_to_string(fixture.root.0.join("child-compiled"))
        .unwrap()
        .parse()
        .unwrap();
    assert_eq!(parent.compiled + child_compiled, 1);
    assert_eq!(
        fixture.binary("main.spv"),
        fs::read(fixture.root.0.join("out-child/main.spv")).unwrap()
    );
    fs::remove_file(&ready).unwrap();
    let status = child_command(&fixture, "lock-exit").status().unwrap();
    assert!(status.success());
    let file = fs::OpenOptions::new()
        .read(true)
        .write(true)
        .open(fixture.root.0.join("exit.lock"))
        .unwrap();
    file.try_lock().unwrap(); // process::exit skipped Drop, so this tests kernel release.
}

fn child_command(fixture: &Fixture, mode: &str) -> Command {
    let mut command = Command::new(env::current_exe().unwrap());
    command
        .args(["--exact", "tests::cache_child", "--nocapture"])
        .env("PRIME_SHADER_CACHE_CHILD", mode)
        .env("PRIME_SHADER_CACHE_ROOT", &fixture.root.0)
        .env(
            "PRIME_SHADER_CACHE_COMPILER",
            resolve_compiler(&fixture.config.compiler).unwrap(),
        );
    command
}
fn wait_file(path: &Path) {
    let start = Instant::now();
    while !path.exists() {
        assert!(
            start.elapsed() < Duration::from_secs(15),
            "child handshake timed out: {}",
            path.display()
        );
        std::thread::sleep(Duration::from_millis(10));
    }
}
#[test]
fn cache_child() {
    let Some(mode) = env::var_os("PRIME_SHADER_CACHE_CHILD") else {
        return;
    };
    let root = PathBuf::from(env::var_os("PRIME_SHADER_CACHE_ROOT").unwrap());
    if mode == "lock-exit" {
        let file = fs::OpenOptions::new()
            .create(true)
            .truncate(false)
            .read(true)
            .write(true)
            .open(root.join("exit.lock"))
            .unwrap();
        file.lock().unwrap();
        std::process::exit(0);
    }
    let mut config = BuildConfig::new(
        env::var_os("PRIME_SHADER_CACHE_COMPILER").unwrap(),
        root.join("源 files"),
        root.join("out-child"),
        root.join("cache"),
    );
    config.include_dirs = vec![PathBuf::from("shaders"), PathBuf::from("fallback")];
    config.dependency_roots = config.include_dirs.clone();
    config.args = ARGS.iter().map(OsString::from).collect();
    fs::write(root.join("ready"), "ready").unwrap();
    wait_file(&root.join("go"));
    let report = build_with_jobserver(&config, &[main_spec()], None).unwrap();
    fs::write(root.join("child-compiled"), report.compiled.to_string()).unwrap();
}

#[test]
fn toolchain_identity_includes_libraries_and_standard_modules() {
    let fixture = Fixture::new();
    let bin = fixture.root.0.join("toolchain/bin");
    fs::create_dir_all(bin.join("slang-standard-module-test/slang")).unwrap();
    fs::write(bin.join("slangc.exe"), "same executable").unwrap();
    fs::write(bin.join("slang-compiler.dll"), "version one").unwrap();
    fs::write(
        bin.join("slang-standard-module-test/slang/helper.slang-module"),
        "stdlib one",
    )
    .unwrap();
    let mut config = fixture.config.clone();
    config.compiler = bin.join("slangc.exe");
    let first = prepare(&config, &[main_spec()]).unwrap();
    fs::write(bin.join("slang-compiler.dll"), "version two").unwrap();
    let second = prepare(&config, &[main_spec()]).unwrap();
    assert_ne!(first.toolchain, second.toolchain);
    fs::write(
        bin.join("slang-standard-module-test/slang/helper.slang-module"),
        "stdlib two",
    )
    .unwrap();
    let third = prepare(&config, &[main_spec()]).unwrap();
    assert_ne!(second.toolchain, third.toolchain);
    fs::write(
        bin.join("slang-standard-module-test/slang/new.slang"),
        "new standard module",
    )
    .unwrap();
    assert_ne!(
        third.toolchain,
        prepare(&config, &[main_spec()]).unwrap().toolchain
    );
}

#[test]
#[ignore = "requires Slang compiler"]
fn jobserver_tokens_bound_compiler_workers_and_return_after_failure() {
    let fixture = Fixture::new();
    let client = jobserver::Client::new(1).unwrap();
    let specs = [
        main_spec(),
        ShaderSpec::new("shaders/other.slang", "other.spv"),
    ];
    assert_eq!(
        build_with_jobserver(&fixture.config, &specs, Some(&client))
            .unwrap()
            .compiled,
        2
    );
    let token = client.try_acquire().unwrap().unwrap();
    assert!(client.try_acquire().unwrap().is_none());
    drop(token);
    fixture.write("shaders/main.slang", "invalid shader");
    assert!(build_with_jobserver(&fixture.config, &specs, Some(&client)).is_err());
    assert!(client.try_acquire().unwrap().is_some());
}

#[test]
#[ignore = "requires Slang compiler"]
fn real_slang_changed_inputs_or_toolchain_cannot_publish() {
    let fixture = Fixture::new();
    let spec = main_spec();
    let mut prepared = prepare(&fixture.config, std::slice::from_ref(&spec)).unwrap();
    let expected = discover(&fixture.config, &prepared, &spec).unwrap();
    let recipe = recipe_key(&fixture.config, &prepared, &spec);
    fixture.write("shaders/header.slangh", "#define OFFSET 13\n");
    let error = compile(&fixture.config, &prepared, &spec, &recipe, &expected)
        .err()
        .unwrap();
    assert!(error.to_string().contains("dependencies changed"));
    assert!(entries(&fixture.config.cache_root.join("v1/objects")).is_empty());
    assert!(entries(&fixture.config.cache_root.join("v1/staging")).is_empty());
    fixture.write("shaders/header.slangh", "#define OFFSET 7\n");
    // Exercise the actual publication guard without modifying the user's SDK:
    // pretend its prior fingerprint differed from the current toolchain.
    prepared.toolchain = "previous toolchain contents".into();
    let error = compile(&fixture.config, &prepared, &spec, &recipe, &expected)
        .err()
        .unwrap();
    assert!(error.to_string().contains("toolchain changed"));
    assert!(entries(&fixture.config.cache_root.join("v1/objects")).is_empty());
    assert!(entries(&fixture.config.cache_root.join("v1/staging")).is_empty());
}

#[test]
fn make_depfile_escaping_and_discovery_quotes() {
    let base = Path::new("/source");
    let deps = parse_depfile(
        b"target\\ name.spv: dir/a\\ b.slang dir/hash\\#name.slang \\\n dir/dollar$$x.slang\n",
        base,
    )
    .unwrap();
    assert_eq!(
        deps,
        [
            base.join("dir/a b.slang"),
            base.join("dir/hash#name.slang"),
            base.join("dir/dollar$x.slang")
        ]
    );
    assert!(parse_depfile(b"no colon here", base).is_err());
    assert!(parse_depfile(b"t: x\\", base).is_err());
    let fixture = Fixture::new();
    fixture.write("shaders/a'b.slang", "");
    let paths = parse_includes(
        b"",
        b"note: include 'shaders/a'b.slang'\n",
        &fixture.config.source_root,
    )
    .unwrap();
    assert_eq!(
        paths,
        [canonical(&fixture.config.source_root.join("shaders/a'b.slang")).unwrap()]
    );
    #[cfg(windows)]
    assert_eq!(
        parse_depfile(
            b"C\\:\\\\out\\\\a.spv: C\\:\\\\source\\\\a\\ b.slang\n",
            Path::new("C:/unused")
        )
        .unwrap(),
        [PathBuf::from("C:\\source\\a b.slang")]
    );
}
