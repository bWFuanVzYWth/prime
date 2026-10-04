//! Build-time Slang compilation with a disposable content-addressed cache.
//!
//! Include/search paths must be supplied through [`BuildConfig::include_dirs`].
//! The source root and dependency roots declare the source search namespace;
//! dependencies outside that inventory are rejected rather than cached unsafely.

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    collections::{BTreeSet, HashSet},
    env,
    ffi::OsString,
    fmt, fs,
    io::{self, Read, Write},
    path::{Path, PathBuf},
    process::Command,
    sync::{
        Mutex,
        atomic::{AtomicUsize, Ordering},
    },
    time::{Duration, Instant},
};

const SCHEMA: u32 = 1;
static NEXT_TEMP: AtomicUsize = AtomicUsize::new(0);

/// All paths other than the compiler may be relative to `source_root`.
#[derive(Clone, Debug)]
pub struct BuildConfig {
    pub compiler: PathBuf,
    pub source_root: PathBuf,
    pub output_dir: PathBuf,
    pub cache_root: PathBuf,
    pub include_dirs: Vec<PathBuf>,
    pub dependency_roots: Vec<PathBuf>,
    /// Ordered code-generation arguments, excluding source, `-I`, `-D`, `-o`
    /// and dependency/discovery arguments managed by this helper.
    pub args: Vec<OsString>,
    /// Upper bound, including the build script's implicit jobserver token.
    pub max_parallel: usize,
}

impl BuildConfig {
    pub fn new(
        compiler: impl Into<PathBuf>,
        source_root: impl Into<PathBuf>,
        output_dir: impl Into<PathBuf>,
        cache_root: impl Into<PathBuf>,
    ) -> Self {
        Self {
            compiler: compiler.into(),
            source_root: source_root.into(),
            output_dir: output_dir.into(),
            cache_root: cache_root.into(),
            include_dirs: Vec::new(),
            dependency_roots: Vec::new(),
            args: Vec::new(),
            max_parallel: 4,
        }
    }
}

#[derive(Clone, Debug)]
pub struct ShaderSpec {
    pub source: PathBuf,
    /// Relative paths are written underneath `output_dir`.
    pub output: PathBuf,
    /// Order is significant, including repeated macro definitions.
    pub defines: Vec<String>,
}

impl ShaderSpec {
    pub fn new(source: impl Into<PathBuf>, output: impl Into<PathBuf>) -> Self {
        Self {
            source: source.into(),
            output: output.into(),
            defines: Vec::new(),
        }
    }
}

#[derive(Debug, Default)]
pub struct BuildReport {
    pub cache_hits: usize,
    pub compiled: usize,
    pub frontend_refreshes: usize,
    pub namespace_refreshes: usize,
    pub elapsed: Duration,
    pub dependencies: Vec<PathBuf>,
    /// Watch these as well as source namespaces in the calling Cargo script.
    pub toolchain_files: Vec<PathBuf>,
    /// Watch directory membership too: a new Slang DLL/standard module matters.
    pub toolchain_dirs: Vec<PathBuf>,
}

#[derive(Debug)]
pub struct BuildError(String);

impl fmt::Display for BuildError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}
impl std::error::Error for BuildError {}
impl From<io::Error> for BuildError {
    fn from(error: io::Error) -> Self {
        Self(error.to_string())
    }
}
impl From<serde_json::Error> for BuildError {
    fn from(error: serde_json::Error) -> Self {
        Self(error.to_string())
    }
}
type Result<T> = std::result::Result<T, BuildError>;

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
struct Dependency {
    path: PathBuf,
    hash: String,
}

#[derive(Serialize, Deserialize)]
struct Manifest {
    schema: u32,
    recipe: String,
    namespace: String,
    object: String,
    dependencies: Vec<Dependency>,
}

#[derive(Serialize, Deserialize)]
struct Object {
    schema: u32,
    key: String,
    dependencies: Vec<Dependency>,
    spirv_hash: String,
    depfile_hash: String,
    stdout: Vec<u8>,
    stderr: Vec<u8>,
}

struct Prepared {
    compiler: PathBuf,
    source_root: PathBuf,
    output_dir: PathBuf,
    cache_root: PathBuf,
    roots: Vec<PathBuf>,
    namespace: String,
    namespace_files: BTreeSet<PathBuf>,
    // Slang's depfile and -output-includes both omit loaded serialized modules.
    // Conservatively include all such files in the declared search namespace.
    opaque_dependencies: Vec<PathBuf>,
    toolchain: String,
    toolchain_files: Vec<PathBuf>,
    toolchain_dirs: Vec<PathBuf>,
}

#[derive(Default)]
struct ShaderReport {
    hit: bool,
    compiled: bool,
    refreshed: bool,
    namespace_refreshed: bool,
    dependencies: Vec<PathBuf>,
}

/// Compile or reuse each entry. The caller's arguments and debug information
/// are retained; Cargo profile names and output destinations do not enter keys.
pub fn build(config: &BuildConfig, shaders: &[ShaderSpec]) -> Result<BuildReport> {
    // Read inherited descriptors before opening cache files. No new jobserver is
    // created: one worker uses Cargo's implicit token, extras borrow real tokens.
    // SAFETY: jobserver owns/duplicates the inherited descriptors it discovers.
    let jobserver = unsafe { jobserver::Client::from_env() };
    build_with_jobserver(config, shaders, jobserver.as_ref())
}

fn build_with_jobserver(
    config: &BuildConfig,
    shaders: &[ShaderSpec],
    jobserver: Option<&jobserver::Client>,
) -> Result<BuildReport> {
    let start = Instant::now();
    validate_args(&config.args)?;
    let prepared = prepare(config, shaders)?;
    let next = AtomicUsize::new(0);
    let results = Mutex::new(Vec::with_capacity(shaders.len()));
    let workers = config.max_parallel.max(1).min(shaders.len().max(1));
    let run = || loop {
        let index = next.fetch_add(1, Ordering::Relaxed);
        let Some(shader) = shaders.get(index) else {
            break;
        };
        let result = compile_one(config, &prepared, shader);
        results.lock().unwrap().push((index, result));
    };
    std::thread::scope(|scope| -> Result<()> {
        let mut tokens = Vec::new();
        if let Some(server) = jobserver {
            for _ in 1..workers {
                match server.try_acquire() {
                    Ok(Some(token)) => tokens.push(token),
                    Ok(None) => break,
                    Err(error) if error.kind() == io::ErrorKind::Unsupported => break,
                    Err(error) => return Err(BuildError(format!("jobserver: {error}"))),
                }
            }
        }
        let mut handles = Vec::new();
        for token in tokens {
            let run = &run;
            handles.push(scope.spawn(move || {
                let _token = token;
                run();
            }));
        }
        run();
        for handle in handles {
            handle
                .join()
                .map_err(|_| BuildError("shader build worker panicked".into()))?;
        }
        Ok(())
    })?;
    let mut results = results.into_inner().unwrap();
    results.sort_by_key(|(index, _)| *index);
    let mut report = BuildReport {
        toolchain_files: prepared.toolchain_files,
        toolchain_dirs: prepared.toolchain_dirs,
        ..BuildReport::default()
    };
    let mut dependencies = BTreeSet::new();
    for (_, result) in results {
        let result = result?;
        report.cache_hits += usize::from(result.hit);
        report.compiled += usize::from(result.compiled);
        report.frontend_refreshes += usize::from(result.refreshed);
        report.namespace_refreshes += usize::from(result.namespace_refreshed);
        dependencies.extend(result.dependencies);
    }
    report.dependencies = dependencies.into_iter().collect();
    report.elapsed = start.elapsed();
    Ok(report)
}

fn validate_args(args: &[OsString]) -> Result<()> {
    for arg in args {
        let text = arg.to_string_lossy();
        if text == "-o"
            || text == "-depfile"
            || text == "-no-codegen"
            || text == "-skip-codegen"
            || text == "-output-includes"
            || text == "-E"
            || text.starts_with("-I")
            || text.starts_with("-D")
        {
            return Err(BuildError(format!(
                "argument {text} is managed by prime-shader-build"
            )));
        }
    }
    Ok(())
}

fn prepare(config: &BuildConfig, shaders: &[ShaderSpec]) -> Result<Prepared> {
    let source_root = canonical(&config.source_root)?;
    let output_dir = absolute(&source_root, &config.output_dir);
    let cache_root = absolute(&source_root, &config.cache_root).join(format!("v{SCHEMA}"));
    fs::create_dir_all(&output_dir)?;
    for subdir in ["locks", "recipes", "objects", "staging"] {
        fs::create_dir_all(cache_root.join(subdir))?;
    }
    let output_dir = canonical(&output_dir)?;
    let cache_root = canonical(&cache_root)?;
    let compiler = resolve_compiler(&config.compiler)?;
    let toolchain_files = toolchain_files(&compiler)?;
    let toolchain_dirs = toolchain_directories(&compiler)?;
    let toolchain = toolchain_hash(&toolchain_files)?;
    // Slang searches the importing file's directory and explicit -I roots, not
    // the working directory. Do not inventory unrelated Rust/assets trees.
    let mut roots = BTreeSet::new();
    for path in config.include_dirs.iter().chain(&config.dependency_roots) {
        roots.insert(canonical(&absolute(&source_root, path))?);
    }
    for shader in shaders {
        let source = canonical(&absolute(&source_root, &shader.source))?;
        roots.insert(source.parent().unwrap().to_path_buf());
    }
    let roots: Vec<_> = roots
        .iter()
        .filter(|root| {
            !roots
                .iter()
                .any(|other| other != *root && root.starts_with(other))
        })
        .cloned()
        .collect();
    let (namespace, namespace_files) = inventory(&roots, &[&output_dir, &cache_root])?;
    let opaque_dependencies = namespace_files
        .iter()
        .filter(|path| {
            matches!(
                path.extension().and_then(|s| s.to_str()),
                Some("slang-module" | "slang-library")
            )
        })
        .cloned()
        .collect();
    Ok(Prepared {
        compiler,
        source_root,
        output_dir,
        cache_root,
        roots,
        namespace,
        namespace_files,
        opaque_dependencies,
        toolchain,
        toolchain_files,
        toolchain_dirs,
    })
}

fn compile_one(
    config: &BuildConfig,
    prepared: &Prepared,
    spec: &ShaderSpec,
) -> Result<ShaderReport> {
    let recipe = recipe_key(config, prepared, spec);
    let lock_path = prepared
        .cache_root
        .join("locks")
        .join(format!("{recipe}.lock"));
    let lock = fs::OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .open(&lock_path)?;
    lock.lock()
        .map_err(|error| BuildError(format!("{}: {error}", lock_path.display())))?;
    // Closing this handle releases the OS lock on success, failure or cancellation.
    let manifest_path = prepared
        .cache_root
        .join("recipes")
        .join(format!("{recipe}.json"));
    let previous = fs::read(&manifest_path)
        .ok()
        .and_then(|bytes| serde_json::from_slice::<Manifest>(&bytes).ok())
        .filter(|m| {
            m.schema == SCHEMA
                && m.recipe == recipe
                && m.object == object_key(&recipe, &m.dependencies)
        });
    let mut report = ShaderReport::default();
    if let Some(previous) = previous {
        let current = dependency_hashes(
            previous.dependencies.iter().map(|d| d.path.clone()),
            prepared,
        )
        .ok();
        let unchanged = current.as_ref() == Some(&previous.dependencies);
        let namespace_changed = previous.namespace != prepared.namespace;
        if unchanged
            && !namespace_changed
            && let Some(object) = read_object(prepared, &previous.object, &previous.dependencies)?
        {
            finish_output(prepared, spec, &previous.object, &object)?;
            report.hit = true;
            report.dependencies = object.dependencies.into_iter().map(|d| d.path).collect();
            return Ok(report);
        }
        // Missing/damaged objects and changed inputs are discovered by Slang.
        report.namespace_refreshed = namespace_changed;
    }
    report.refreshed = true;
    let dependencies = discover(config, prepared, spec)?;
    let key = object_key(&recipe, &dependencies);
    if let Some(object) = read_object(prepared, &key, &dependencies)? {
        publish_manifest(&manifest_path, &recipe, prepared, &key, dependencies)?;
        finish_output(prepared, spec, &key, &object)?;
        report.hit = true;
        report.dependencies = object.dependencies.into_iter().map(|d| d.path).collect();
        return Ok(report);
    }
    let (key, object) = compile(config, prepared, spec, &recipe, &dependencies)?;
    publish_manifest(
        &manifest_path,
        &recipe,
        prepared,
        &key,
        object.dependencies.clone(),
    )?;
    finish_output(prepared, spec, &key, &object)?;
    report.compiled = true;
    report.dependencies = object.dependencies.into_iter().map(|d| d.path).collect();
    Ok(report)
}

fn recipe_key(config: &BuildConfig, prepared: &Prepared, spec: &ShaderSpec) -> String {
    let mut hash = Sha256::new();
    for field in [SCHEMA.to_string(), prepared.toolchain.clone()] {
        hash_field(&mut hash, field.as_bytes());
    }
    hash_field(
        &mut hash,
        prepared.source_root.as_os_str().as_encoded_bytes(),
    );
    hash_field(&mut hash, spec.source.as_os_str().as_encoded_bytes());
    for define in &spec.defines {
        hash_field(&mut hash, define.as_bytes());
    }
    hash_field(&mut hash, b"arguments");
    for arg in &config.args {
        hash_field(&mut hash, arg.as_encoded_bytes());
    }
    hash_field(&mut hash, b"include-directories");
    for dir in &config.include_dirs {
        hash_field(&mut hash, dir.as_os_str().as_encoded_bytes());
    }
    hash_field(&mut hash, b"namespace-roots");
    for dir in &prepared.roots {
        hash_field(&mut hash, dir.as_os_str().as_encoded_bytes());
    }
    hex(hash.finalize())
}

fn object_key(recipe: &str, dependencies: &[Dependency]) -> String {
    let mut hash = Sha256::new();
    hash_field(&mut hash, recipe.as_bytes());
    for dependency in dependencies {
        hash_field(&mut hash, dependency.path.as_os_str().as_encoded_bytes());
        hash_field(&mut hash, dependency.hash.as_bytes());
    }
    hex(hash.finalize())
}

fn command(config: &BuildConfig, prepared: &Prepared, spec: &ShaderSpec) -> Command {
    let mut command = Command::new(&prepared.compiler);
    command.current_dir(&prepared.source_root);
    for define in &spec.defines {
        command.arg(format!("-D{define}"));
    }
    command.arg(&spec.source);
    for include in &config.include_dirs {
        command.arg("-I").arg(include);
    }
    command.args(&config.args);
    command
}

fn discover(
    config: &BuildConfig,
    prepared: &Prepared,
    spec: &ShaderSpec,
) -> Result<Vec<Dependency>> {
    let output = command(config, prepared, spec)
        .args(["-no-codegen", "-output-includes"])
        .output()?;
    if !output.status.success() {
        return Err(compiler_error(spec, &output));
    }
    let paths = parse_includes(&output.stdout, &output.stderr, &prepared.source_root)?;
    let dependencies = dependency_hashes(
        paths
            .into_iter()
            .chain(prepared.opaque_dependencies.iter().cloned()),
        prepared,
    )?;
    let source = canonical(&absolute(&prepared.source_root, &spec.source))?;
    if !dependencies.iter().any(|d| d.path == source) {
        return Err(BuildError(format!(
            "Slang did not report the entry {} during dependency discovery",
            source.display()
        )));
    }
    Ok(dependencies)
}

fn compile(
    config: &BuildConfig,
    prepared: &Prepared,
    spec: &ShaderSpec,
    recipe: &str,
    expected: &[Dependency],
) -> Result<(String, Object)> {
    let staging = TempDirectory::new(&prepared.cache_root.join("staging"))?;
    let spirv = staging.0.join("shader.spv");
    let depfile = staging.0.join("dependencies.d");
    let output = command(config, prepared, spec)
        .arg("-depfile")
        .arg(&depfile)
        .arg("-o")
        .arg(&spirv)
        .output()?;
    if !output.status.success() {
        return Err(compiler_error(spec, &output));
    }
    let depfile_bytes = fs::read(&depfile)
        .map_err(|error| BuildError(format!("Slang depfile {}: {error}", depfile.display())))?;
    let dependencies = dependency_hashes(
        parse_depfile(&depfile_bytes, &prepared.source_root)?
            .into_iter()
            .chain(prepared.opaque_dependencies.iter().cloned()),
        prepared,
    )?;
    verify_snapshot(expected, &dependencies)?;
    let current_namespace = inventory(
        &prepared.roots,
        &[&prepared.output_dir, &prepared.cache_root],
    )?
    .0;
    if current_namespace != prepared.namespace {
        return Err(BuildError(
            "shader search namespace changed during compilation; rerun the build".into(),
        ));
    }
    let current_toolchain_files = toolchain_files(&prepared.compiler)?;
    if current_toolchain_files != prepared.toolchain_files
        || toolchain_hash(&current_toolchain_files)? != prepared.toolchain
    {
        return Err(BuildError(
            "Slang toolchain changed during compilation; rerun the build".into(),
        ));
    }
    let source = canonical(&absolute(&prepared.source_root, &spec.source))?;
    if !dependencies.iter().any(|d| d.path == source) {
        return Err(BuildError(format!(
            "Slang depfile omits entry {}",
            source.display()
        )));
    }
    let bytes = fs::read(&spirv)?;
    validate_spirv(&bytes)?;
    let key = object_key(recipe, &dependencies);
    let object = Object {
        schema: SCHEMA,
        key: key.clone(),
        dependencies,
        spirv_hash: hash_bytes(&bytes),
        depfile_hash: hash_bytes(&depfile_bytes),
        stdout: output.stdout,
        stderr: output.stderr,
    };
    write_sync(
        &staging.0.join("object.json"),
        &serde_json::to_vec(&object)?,
    )?;
    let destination = prepared.cache_root.join("objects").join(&key);
    if destination.exists() {
        // Only this recipe can address this key, and its exclusive lock is held.
        fs::remove_dir_all(&destination)?;
    }
    fs::rename(&staging.0, &destination)?;
    Ok((key, object))
}

fn compiler_error(spec: &ShaderSpec, output: &std::process::Output) -> BuildError {
    BuildError(format!(
        "Slang failed for {} ({})\n{}{}",
        spec.source.display(),
        output.status,
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    ))
}

fn dependency_hashes(
    paths: impl IntoIterator<Item = PathBuf>,
    prepared: &Prepared,
) -> Result<Vec<Dependency>> {
    let mut unique = BTreeSet::new();
    for path in paths {
        let path = canonical(&absolute(&prepared.source_root, &path))?;
        if !prepared.namespace_files.contains(&path) && !prepared.toolchain_files.contains(&path) {
            return Err(BuildError(format!(
                "dependency {} is outside the declared source/include/dependency namespace",
                path.display()
            )));
        }
        unique.insert(path);
    }
    unique
        .into_iter()
        .map(|path| {
            Ok(Dependency {
                hash: hash_file(&path)?,
                path,
            })
        })
        .collect()
}

fn verify_snapshot(expected: &[Dependency], actual: &[Dependency]) -> Result<()> {
    if expected != actual {
        return Err(BuildError(
            "shader dependencies changed during compilation; rerun the build".into(),
        ));
    }
    Ok(())
}

fn toolchain_hash(files: &[PathBuf]) -> Result<String> {
    let mut hash = Sha256::new();
    for path in files {
        hash_field(&mut hash, path.as_os_str().as_encoded_bytes());
        hash_field(&mut hash, hash_file(path)?.as_bytes());
    }
    Ok(hex(hash.finalize()))
}

fn read_object(
    prepared: &Prepared,
    key: &str,
    dependencies: &[Dependency],
) -> Result<Option<Object>> {
    // Never use cache strings as unchecked path components.
    if key.len() != 64 || !key.bytes().all(|c| c.is_ascii_hexdigit()) {
        return Ok(None);
    }
    let root = prepared.cache_root.join("objects").join(key);
    let read = || -> Result<Object> {
        let object: Object = serde_json::from_slice(&fs::read(root.join("object.json"))?)?;
        if object.schema != SCHEMA || object.key != key || object.dependencies != dependencies {
            return Err(BuildError("object metadata mismatch".into()));
        }
        let depfile = fs::read(root.join("dependencies.d"))?;
        if hash_bytes(&depfile) != object.depfile_hash {
            return Err(BuildError("depfile checksum mismatch".into()));
        }
        let dep_paths: BTreeSet<_> = parse_depfile(&depfile, &prepared.source_root)?
            .into_iter()
            .chain(prepared.opaque_dependencies.iter().cloned())
            .map(|p| canonical(&absolute(&prepared.source_root, &p)))
            .collect::<Result<_>>()?;
        if dep_paths != object.dependencies.iter().map(|d| d.path.clone()).collect() {
            return Err(BuildError("depfile closure mismatch".into()));
        }
        let spirv = fs::read(root.join("shader.spv"))?;
        validate_spirv(&spirv)?;
        if hash_bytes(&spirv) != object.spirv_hash {
            return Err(BuildError("SPIR-V checksum mismatch".into()));
        }
        Ok(object)
    };
    Ok(read().ok())
}

fn finish_output(prepared: &Prepared, spec: &ShaderSpec, key: &str, object: &Object) -> Result<()> {
    if !object.stdout.is_empty() {
        io::stdout().lock().write_all(&object.stdout)?;
    }
    if !object.stderr.is_empty() {
        io::stderr().lock().write_all(&object.stderr)?;
    }
    let bytes = fs::read(
        prepared
            .cache_root
            .join("objects")
            .join(key)
            .join("shader.spv"),
    )?;
    let output = absolute(&prepared.output_dir, &spec.output);
    if fs::read(&output).ok().as_deref() != Some(&bytes) {
        atomic_write(&output, &bytes)?;
    }
    Ok(())
}

fn publish_manifest(
    path: &Path,
    recipe: &str,
    prepared: &Prepared,
    key: &str,
    dependencies: Vec<Dependency>,
) -> Result<()> {
    atomic_write(
        path,
        &serde_json::to_vec(&Manifest {
            schema: SCHEMA,
            recipe: recipe.into(),
            namespace: prepared.namespace.clone(),
            object: key.into(),
            dependencies,
        })?,
    )
}

fn validate_spirv(bytes: &[u8]) -> Result<()> {
    if bytes.len() < 20 || !bytes.len().is_multiple_of(4) || bytes[..4] != [3, 2, 35, 7] {
        return Err(BuildError(
            "Slang output is not a complete SPIR-V module".into(),
        ));
    }
    Ok(())
}

fn inventory(roots: &[PathBuf], excluded: &[&Path]) -> Result<(String, BTreeSet<PathBuf>)> {
    let mut visited = HashSet::new();
    let mut entries = BTreeSet::new();
    let mut files = BTreeSet::new();
    fn visit(
        path: &Path,
        excluded: &[&Path],
        visited: &mut HashSet<PathBuf>,
        entries: &mut BTreeSet<(PathBuf, PathBuf, bool)>,
        files: &mut BTreeSet<PathBuf>,
    ) -> Result<()> {
        let resolved = canonical(path)?;
        if excluded.iter().any(|p| resolved.starts_with(p)) {
            return Ok(());
        }
        let is_dir = resolved.is_dir();
        entries.insert((path.to_path_buf(), resolved.clone(), is_dir));
        if is_dir {
            if visited.insert(resolved) {
                for child in fs::read_dir(path)? {
                    visit(&child?.path(), excluded, visited, entries, files)?;
                }
            }
        } else {
            files.insert(resolved);
        }
        Ok(())
    }
    for root in roots {
        visit(root, excluded, &mut visited, &mut entries, &mut files)?;
    }
    let mut hash = Sha256::new();
    for (path, resolved, directory) in entries {
        hash_field(&mut hash, path.as_os_str().as_encoded_bytes());
        hash_field(&mut hash, resolved.as_os_str().as_encoded_bytes());
        hash_field(&mut hash, &[u8::from(directory)]);
    }
    Ok((hex(hash.finalize()), files))
}

fn toolchain_files(compiler: &Path) -> Result<Vec<PathBuf>> {
    let mut files = BTreeSet::from([compiler.to_path_buf()]);
    for dir in toolchain_directories(compiler)? {
        for entry in fs::read_dir(&dir)? {
            let path = entry?.path();
            let name = path
                .file_name()
                .unwrap()
                .to_string_lossy()
                .to_ascii_lowercase();
            if path.is_dir()
                && (name.starts_with("slang-standard-module") || dir.ends_with("slang"))
            {
                let (_, found) = inventory(&[path], &[])?;
                files.extend(found);
            } else if path.is_file()
                && ((name.starts_with("slang") || name.starts_with("libslang"))
                    && (name.ends_with(".dll")
                        || name.contains(".so")
                        || name.ends_with(".dylib")
                        || name.ends_with(".slang")
                        || name.ends_with(".slang-module")
                        || name.ends_with(".slang-library")))
            {
                files.insert(canonical(&path)?);
            }
        }
    }
    Ok(files.into_iter().collect())
}

fn toolchain_directories(compiler: &Path) -> Result<Vec<PathBuf>> {
    let directory = compiler
        .parent()
        .ok_or_else(|| BuildError("compiler has no directory".into()))?;
    let mut dirs = vec![directory.to_path_buf()];
    for sibling in ["../lib", "../lib64", "../share/slang"] {
        let path = directory.join(sibling);
        if path.is_dir() {
            dirs.push(canonical(&path)?);
        }
    }
    Ok(dirs)
}

fn resolve_compiler(path: &Path) -> Result<PathBuf> {
    if path.is_absolute() || path.components().count() > 1 {
        return canonical(path);
    }
    let mut names = vec![path.as_os_str().to_os_string()];
    if cfg!(windows) && path.extension().is_none() {
        for extension in env::var_os("PATHEXT")
            .unwrap_or_else(|| OsString::from(".EXE"))
            .to_string_lossy()
            .split(';')
        {
            let mut name = path.as_os_str().to_os_string();
            name.push(extension);
            names.push(name);
        }
    }
    for directory in env::split_paths(&env::var_os("PATH").unwrap_or_default()) {
        for name in &names {
            let candidate = directory.join(name);
            if candidate.is_file() {
                return canonical(&candidate);
            }
        }
    }
    Err(BuildError(format!(
        "Slang compiler {} was not found on PATH",
        path.display()
    )))
}

fn parse_includes(stdout: &[u8], stderr: &[u8], source_root: &Path) -> Result<Vec<PathBuf>> {
    let mut paths = BTreeSet::new();
    for bytes in [stdout, stderr] {
        let text = std::str::from_utf8(bytes)
            .map_err(|e| BuildError(format!("Slang include output is not UTF-8: {e}")))?;
        for line in text.lines() {
            let Some(rest) = line.trim().strip_prefix("note: include") else {
                continue;
            };
            let rest = rest.trim();
            let name = rest
                .strip_prefix('\'')
                .and_then(|s| s.strip_suffix('\''))
                .ok_or_else(|| BuildError(format!("unrecognized Slang include record: {line}")))?;
            paths.insert(canonical(&absolute(source_root, Path::new(name)))?);
        }
    }
    if paths.is_empty() {
        return Err(BuildError(
            "Slang dependency discovery reported no includes".into(),
        ));
    }
    Ok(paths.into_iter().collect())
}

/// Parse Make escaping, including escaped drive colons, spaces and backslashes.
fn parse_depfile(bytes: &[u8], source_root: &Path) -> Result<Vec<PathBuf>> {
    let text = std::str::from_utf8(bytes)
        .map_err(|e| BuildError(format!("Slang depfile is not UTF-8: {e}")))?;
    let mut chars = text.chars().peekable();
    let mut target = true;
    let mut token = String::new();
    let mut paths = Vec::new();
    while let Some(c) = chars.next() {
        match c {
            '\\' => match chars.next() {
                Some('\r') if chars.peek() == Some(&'\n') => {
                    chars.next();
                }
                Some('\n') => {}
                Some(escaped) => token.push(escaped),
                None => return Err(BuildError("depfile ends in an escape".into())),
            },
            ':' if target => {
                target = false;
                token.clear();
            }
            c if c.is_whitespace() => {
                if !target && !token.is_empty() {
                    paths.push(absolute(source_root, Path::new(&token)));
                }
                token.clear();
            }
            '$' if chars.peek() == Some(&'$') => {
                chars.next();
                token.push('$');
            }
            '#' => {
                return Err(BuildError(
                    "unexpected unescaped Make comment in Slang depfile".into(),
                ));
            }
            c => token.push(c),
        }
    }
    if !target && !token.is_empty() {
        paths.push(absolute(source_root, Path::new(&token)));
    }
    if target || paths.is_empty() {
        return Err(BuildError("Slang depfile has no dependency rule".into()));
    }
    Ok(paths)
}

fn canonical(path: &Path) -> Result<PathBuf> {
    fs::canonicalize(path).map_err(|error| BuildError(format!("{}: {error}", path.display())))
}
fn absolute(base: &Path, path: &Path) -> PathBuf {
    if path.is_absolute() {
        path.to_path_buf()
    } else {
        base.join(path)
    }
}
fn hash_field(hash: &mut Sha256, bytes: &[u8]) {
    hash.update((bytes.len() as u64).to_le_bytes());
    hash.update(bytes);
}
fn hash_bytes(bytes: &[u8]) -> String {
    hex(Sha256::digest(bytes))
}
fn hash_file(path: &Path) -> Result<String> {
    let mut file = fs::File::open(path)?;
    let mut hash = Sha256::new();
    let mut buffer = [0u8; 64 * 1024];
    loop {
        let count = file.read(&mut buffer)?;
        if count == 0 {
            break;
        }
        hash.update(&buffer[..count]);
    }
    Ok(hex(hash.finalize()))
}
fn hex(bytes: impl AsRef<[u8]>) -> String {
    use fmt::Write;
    let mut result = String::with_capacity(bytes.as_ref().len() * 2);
    for byte in bytes.as_ref() {
        write!(result, "{byte:02x}").unwrap();
    }
    result
}
fn write_sync(path: &Path, bytes: &[u8]) -> Result<()> {
    let mut file = fs::OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(path)?;
    file.write_all(bytes)?;
    file.sync_all()?;
    Ok(())
}
fn atomic_write(path: &Path, bytes: &[u8]) -> Result<()> {
    let parent = path
        .parent()
        .ok_or_else(|| BuildError("output has no parent directory".into()))?;
    fs::create_dir_all(parent)?;
    let temporary = parent.join(format!(
        ".prime-shader-{}-{}.tmp",
        std::process::id(),
        NEXT_TEMP.fetch_add(1, Ordering::Relaxed)
    ));
    write_sync(&temporary, bytes)?;
    // Windows rename does not replace a destination. The manifest is protected
    // by the recipe lock; a cancellation in this interval causes a safe miss.
    if path.exists() {
        fs::remove_file(path)?;
    }
    let result = fs::rename(&temporary, path);
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result.map_err(Into::into)
}
struct TempDirectory(PathBuf);
impl TempDirectory {
    fn new(parent: &Path) -> Result<Self> {
        let path = parent.join(format!(
            "{}-{}",
            std::process::id(),
            NEXT_TEMP.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir(&path)?;
        Ok(Self(path))
    }
}
impl Drop for TempDirectory {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

#[cfg(test)]
mod tests;
