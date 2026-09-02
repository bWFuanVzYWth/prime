package dev.prime.gradle.shader

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Validates the renderer-data IR and emits narrow Java/Slang contract leaves plus a byte ledger. */
@CacheableTask
abstract class GenerateRendererDataContracts extends DefaultTask {
    private static final List<String> SEMANTIC_FIELDS = [
            'id', 'valueKind', 'components', 'domain', 'space', 'unit',
            'colorimetry', 'validity', 'minimumEncoding']
    private static final List<String> ENCODING_FIELDS = [
            'id', 'scalarOrFormat', 'bytes', 'channelMap', 'encode', 'decode',
            'exactCodes', 'errorContract']
    private static final List<String> BINDING_FIELDS = [
            'id', 'semantic', 'encoding', 'resourceKind', 'extentSource', 'usage',
            'access', 'sampling', 'descriptorOrOffset', 'lifetime', 'conversion',
            'verification']

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract RegularFileProperty getSchemaFile()

    @OutputDirectory
    abstract DirectoryProperty getJavaOutputDirectory()

    @OutputDirectory
    abstract DirectoryProperty getSlangOutputDirectory()

    @OutputFile
    abstract RegularFileProperty getMemoryLedgerFile()

    private static String quote(String value) {
        return '"' + value.replace('\\', '\\\\').replace('"', '\\"') + '"'
    }

    private static String decimal(Object value) {
        return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
    }

    private static void requireFields(Map<?, ?> value, List<String> fields, String owner) {
        def missing = fields.findAll { !value.containsKey(it) || value[it] == null }
        if (!missing.empty) {
            throw new GradleException("${owner} is missing required fields ${missing}")
        }
    }

    private static Map<String, Map<?, ?>> uniqueById(List<?> values, String owner) {
        def result = new LinkedHashMap<String, Map<?, ?>>()
        values.each { raw ->
            if (!(raw instanceof Map) || raw.id == null || raw.id.toString().isBlank()) {
                throw new GradleException("${owner} entries require a non-empty id")
            }
            def id = raw.id.toString()
            if (result.put(id, raw) != null) {
                throw new GradleException("Duplicate ${owner} id ${id}")
            }
        }
        return result
    }

    private static int phaseIndex(List<String> phases, String value, String owner) {
        int index = phases.indexOf(value)
        if (index < 0) {
            throw new GradleException("${owner} references unknown phase ${value}")
        }
        return index
    }

    private static void validate(Object schema) {
        if (!(schema instanceof Map)) {
            throw new GradleException('Renderer-data schema must be a JSON object')
        }
        if (schema.schemaVersion != 1 || schema.abiId != 'prime-renderer-data-v1'
                || schema.status != 'stage1-contract-only') {
            throw new GradleException('Renderer-data schema identity or stage changed without a versioned migration')
        }
        ['coordinates', 'color', 'phases', 'semantics', 'encodings', 'bindings',
                'memoryPlans', 'benchmarks'].each { key ->
            if (!schema.containsKey(key) || schema[key] == null) {
                throw new GradleException("Renderer-data schema is missing ${key}")
            }
        }

        def coordinates = schema.coordinates as Map<?, ?>
        ['imageOrigin', 'pixelCenterOffset', 'uvToClipScale', 'uvToClipBias',
                'sampleJitterUnit', 'sampleJitterPositive', 'projectionJitterFromSample',
                'visibleMotion', 'visibleMotionUnit', 'visibleMotionIncludesJitter',
                'coreMatrixVectorConvention', 'coreMatrixStorage',
                'streamlineMatrixStorage'].each { key ->
            if (!coordinates.containsKey(key)) {
                throw new GradleException("Coordinate contract is missing ${key}")
            }
        }
        if (coordinates.imageOrigin != 'top-left'
                || coordinates.pixelCenterOffset != [0.5, 0.5]
                || coordinates.uvToClipScale != [2.0, -2.0]
                || coordinates.uvToClipBias != [-1.0, 1.0]
                || coordinates.projectionJitterFromSample != [-1.0, -1.0]
                || coordinates.visibleMotionIncludesJitter != false) {
            throw new GradleException('Coordinate contract no longer matches the approved NVIDIA-aligned core IR')
        }

        def color = schema.color as Map<?, ?>
        if (color.workingSpace != 'linear-rec2020-d65'
                || color.sourceTransfer != 'iec-61966-2-1-srgb'
                || color.alphaTransfer != 'linear'
                || color.mipFilterSpace != 'linear-rec2020-d65') {
            throw new GradleException('Color contract no longer matches the approved linear Rec.2020 core IR')
        }
        if (!(color.linearSrgbToLinearRec2020 instanceof List)
                || color.linearSrgbToLinearRec2020.size() != 3
                || color.linearSrgbToLinearRec2020.any { !(it instanceof List) || it.size() != 3 }) {
            throw new GradleException('Linear sRGB to Rec.2020 matrix must be 3x3')
        }

        def phases = schema.phases.collect { it.toString() }
        if (phases.empty || phases.size() != phases.toSet().size()) {
            throw new GradleException('Renderer phases must be non-empty and unique')
        }
        def semantics = uniqueById(schema.semantics as List<?>, 'semantic')
        def encodings = uniqueById(schema.encodings as List<?>, 'encoding')
        semantics.each { id, semantic ->
            requireFields(semantic, SEMANTIC_FIELDS, "semantic ${id}")
            if (!(semantic.components instanceof Number) || semantic.components < 1
                    || !encodings.containsKey(semantic.minimumEncoding.toString())) {
                throw new GradleException("semantic ${id} has invalid components or minimumEncoding")
            }
        }
        encodings.each { id, encoding ->
            requireFields(encoding, ENCODING_FIELDS, "encoding ${id}")
            if (!(encoding.bytes instanceof Number) || encoding.bytes < 1) {
                throw new GradleException("encoding ${id} has invalid byte size")
            }
        }

        def bindings = uniqueById(schema.bindings as List<?>, 'binding')
        bindings.each { id, binding ->
            requireFields(binding, BINDING_FIELDS, "binding ${id}")
            if (!semantics.containsKey(binding.semantic.toString())
                    || !encodings.containsKey(binding.encoding.toString())) {
                throw new GradleException("binding ${id} references an unknown semantic or encoding")
            }
            requireFields(binding.lifetime as Map<?, ?>,
                    ['owner', 'generation', 'firstWrite', 'consumers', 'lastRead',
                            'aliasGroup', 'retire'], "binding ${id} lifetime")
            requireFields(binding.conversion as Map<?, ?>,
                    ['producer', 'sourceSemantic', 'owner', 'targetSemantic',
                            'backendBoundary'], "binding ${id} conversion")
            requireFields(binding.verification as Map<?, ?>,
                    ['artifactChecks', 'behaviorOracle', 'numericOrImageThreshold',
                            'benchmark'], "binding ${id} verification")
            int first = phaseIndex(phases, binding.lifetime.firstWrite.toString(), "binding ${id}")
            int last = phaseIndex(phases, binding.lifetime.lastRead.toString(), "binding ${id}")
            if (last < first) {
                throw new GradleException("binding ${id} reads before its first write")
            }
        }
        bindings.values().groupBy { it.lifetime.aliasGroup.toString() }.each { alias, members ->
            if (alias == 'none') return
            for (int first = 0; first < members.size(); first++) {
                def a = members[first]
                int aStart = phaseIndex(phases, a.lifetime.firstWrite.toString(), "binding ${a.id}")
                int aEnd = phaseIndex(phases, a.lifetime.lastRead.toString(), "binding ${a.id}")
                for (int second = first + 1; second < members.size(); second++) {
                    def b = members[second]
                    int bStart = phaseIndex(phases, b.lifetime.firstWrite.toString(), "binding ${b.id}")
                    int bEnd = phaseIndex(phases, b.lifetime.lastRead.toString(), "binding ${b.id}")
                    if (Math.max(aStart, bStart) <= Math.min(aEnd, bEnd)) {
                        throw new GradleException("alias group ${alias} has overlapping lifetimes: ${a.id}, ${b.id}")
                    }
                }
            }
        }

        def planIds = new HashSet<String>()
        def labels = new HashSet<String>()
        (schema.memoryPlans as List<?>).each { plan ->
            requireFields(plan as Map<?, ?>, ['id', 'kind', 'items'], 'memory plan')
            if (!planIds.add(plan.id.toString()) || !(plan.items instanceof List) || plan.items.empty) {
                throw new GradleException("Invalid or duplicate memory plan ${plan.id}")
            }
            def itemIds = new HashSet<String>()
            plan.items.each { item ->
                requireFields(item as Map<?, ?>,
                        ['id', 'semantic', 'debugLabel', 'extentSource', 'bytesPerElement',
                                'elementsPerPixel', 'fixedBytes'], "memory plan ${plan.id} item")
                if (!itemIds.add(item.id.toString())
                        || !labels.add(item.debugLabel.toString())
                        || !(item.extentSource in ['render', 'display', 'fixed'])
                        || item.bytesPerElement < 1 || item.elementsPerPixel < 0
                        || item.fixedBytes < 0) {
                    throw new GradleException("Invalid memory item ${plan.id}.${item.id}")
                }
            }
        }

        uniqueById(schema.benchmarks as List<?>, 'benchmark').each { id, benchmark ->
            requireFields(benchmark,
                    ['id', 'fixture', 'correctnessOracle', 'warmupIterations',
                            'measurementIterations', 'metric'], "benchmark ${id}")
            if (benchmark.warmupIterations < 1 || benchmark.measurementIterations < 1) {
                throw new GradleException("benchmark ${id} requires positive iteration counts")
            }
        }
    }

    private static String javaList(Collection<?> values) {
        return 'List.of(' + values.collect { quote(it.toString()) }.join(', ') + ')'
    }

    private static String generateJava(Object schema) {
        def source = new StringBuilder('''package dev.prime.render.data;

import java.util.List;

/** Generated from shaders/renderer-data.json; contract skeleton only, not a production migration. */
public final class RendererDataContracts {
    public static final int SCHEMA_VERSION = 1;
    public static final String ABI_ID = "prime-renderer-data-v1";

    private RendererDataContracts() {}

    public record Semantic(String id, String valueKind, int components, String domain,
            String space, String unit, String colorimetry, String validity,
            String minimumEncoding) {}
    public record Encoding(String id, String scalarOrFormat, int bytes, String channelMap,
            String encode, String decode, String exactCodes, String errorContract) {}
    public record Lifetime(String owner, String generation, String firstWrite,
            List<String> consumers, String lastRead, String aliasGroup, String retire) {}
    public record Conversion(String producer, String sourceSemantic, String owner,
            String targetSemantic, String backendBoundary) {}
    public record Verification(String artifactChecks, String behaviorOracle,
            String numericOrImageThreshold, String benchmark) {}
    public record Binding(String id, String semantic, String encoding, String resourceKind,
            String extentSource, List<String> usage, String access, String sampling,
            String descriptorOrOffset, Lifetime lifetime, Conversion conversion,
            Verification verification) {}
    public record MemoryItem(String id, String semantic, String debugLabel,
            String extentSource, int bytesPerElement, int elementsPerPixel, long fixedBytes) {}
    public record MemoryPlan(String id, String kind, List<MemoryItem> items) {
        public int renderBytesPerPixel() { return bytesPerPixel("render"); }
        public int displayBytesPerPixel() { return bytesPerPixel("display"); }
        public long fixedBytes() { return items.stream().mapToLong(MemoryItem::fixedBytes).sum(); }
        private int bytesPerPixel(String extent) {
            return items.stream().filter(item -> item.extentSource().equals(extent))
                    .mapToInt(item -> item.bytesPerElement() * item.elementsPerPixel()).sum();
        }
        public long bytes(int renderWidth, int renderHeight, int displayWidth, int displayHeight) {
            if (renderWidth < 1 || renderHeight < 1 || displayWidth < 1 || displayHeight < 1) {
                throw new IllegalArgumentException("Renderer extents must be positive");
            }
            return Math.addExact(fixedBytes(), Math.addExact(
                    Math.multiplyExact((long) renderBytesPerPixel(),
                            Math.multiplyExact((long) renderWidth, renderHeight)),
                    Math.multiplyExact((long) displayBytesPerPixel(),
                            Math.multiplyExact((long) displayWidth, displayHeight))));
        }
    }
    public record Benchmark(String id, String fixture, String correctnessOracle,
            int warmupIterations, int measurementIterations, String metric) {}

''')
        def c = schema.coordinates
        source.append('    public static final double PIXEL_CENTER_X = ')
                .append(decimal(c.pixelCenterOffset[0])).append(";\n")
                .append('    public static final double PIXEL_CENTER_Y = ')
                .append(decimal(c.pixelCenterOffset[1])).append(";\n")
                .append('    public static final String IMAGE_ORIGIN = ')
                .append(quote(c.imageOrigin.toString())).append(";\n")
                .append('    public static final String SAMPLE_JITTER_UNIT = ')
                .append(quote(c.sampleJitterUnit.toString())).append(";\n")
                .append('    public static final String VISIBLE_MOTION_UNIT = ')
                .append(quote(c.visibleMotionUnit.toString())).append(";\n\n")

        def m = schema.color.linearSrgbToLinearRec2020
        source.append('    public static final String WORKING_COLOR_SPACE = ')
                .append(quote(schema.color.workingSpace.toString())).append(";\n")
                .append('    public static final String SOURCE_COLOR_TRANSFER = ')
                .append(quote(schema.color.sourceTransfer.toString())).append(";\n")
                .append('    public static final String ALPHA_TRANSFER = ')
                .append(quote(schema.color.alphaTransfer.toString())).append(";\n")
                .append('    public static final String MIP_FILTER_SPACE = ')
                .append(quote(schema.color.mipFilterSpace.toString())).append(";\n\n")
        source.append('    public static final double[][] LINEAR_SRGB_TO_LINEAR_REC2020 = {\n')
        m.each { row ->
            source.append('        {').append(row.collect { decimal(it) }.join(', ')).append('},\n')
        }
        source.append('    };\n\n')
        source.append('    public static final List<String> PHASES = ')
                .append(javaList(schema.phases)).append(';\n\n')
        source.append('''    public static double[] sampleUv(int x, int y, int width, int height) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            throw new IllegalArgumentException("Pixel is outside the render extent");
        }
        return new double[] {(x + PIXEL_CENTER_X) / width, (y + PIXEL_CENTER_Y) / height};
    }

    public static double[] uvToClip(double u, double v) {
        return new double[] {2.0 * u - 1.0, 1.0 - 2.0 * v};
    }

    public static double[] clipToUv(double x, double y) {
        return new double[] {0.5 * x + 0.5, -0.5 * y + 0.5};
    }

    public static double[] projectionJitterPixels(double sampleX, double sampleY) {
        return new double[] {-sampleX, -sampleY};
    }

    public static double[] visibleMotionUv(
            double previousU, double previousV, double currentU, double currentV) {
        return new double[] {previousU - currentU, previousV - currentV};
    }

    public static double decodeSrgb(double encoded) {
        return encoded <= 0.04045 ? encoded / 12.92
                : Math.pow((encoded + 0.055) / 1.055, 2.4);
    }

    public static double[] linearSrgbToLinearRec2020(double r, double g, double b) {
        return new double[] {
                LINEAR_SRGB_TO_LINEAR_REC2020[0][0] * r
                        + LINEAR_SRGB_TO_LINEAR_REC2020[0][1] * g
                        + LINEAR_SRGB_TO_LINEAR_REC2020[0][2] * b,
                LINEAR_SRGB_TO_LINEAR_REC2020[1][0] * r
                        + LINEAR_SRGB_TO_LINEAR_REC2020[1][1] * g
                        + LINEAR_SRGB_TO_LINEAR_REC2020[1][2] * b,
                LINEAR_SRGB_TO_LINEAR_REC2020[2][0] * r
                        + LINEAR_SRGB_TO_LINEAR_REC2020[2][1] * g
                        + LINEAR_SRGB_TO_LINEAR_REC2020[2][2] * b};
    }

''')

        source.append('    public static final List<Semantic> SEMANTICS = List.of(\n')
        schema.semantics.eachWithIndex { value, index ->
            source.append('        new Semantic(')
                    .append([value.id, value.valueKind].collect { quote(it.toString()) }.join(', '))
                    .append(', ').append(value.components).append(', ')
                    .append([value.domain, value.space, value.unit, value.colorimetry,
                            value.validity, value.minimumEncoding]
                            .collect { quote(it.toString()) }.join(', '))
                    .append(')').append(index + 1 == schema.semantics.size() ? '\n' : ',\n')
        }
        source.append('    );\n\n    public static final List<Encoding> ENCODINGS = List.of(\n')
        schema.encodings.eachWithIndex { value, index ->
            source.append('        new Encoding(').append(quote(value.id.toString())).append(', ')
                    .append(quote(value.scalarOrFormat.toString())).append(', ')
                    .append(value.bytes).append(', ')
                    .append([value.channelMap, value.encode, value.decode, value.exactCodes,
                            value.errorContract].collect { quote(it.toString()) }.join(', '))
                    .append(')').append(index + 1 == schema.encodings.size() ? '\n' : ',\n')
        }
        source.append('    );\n\n    public static final List<Binding> BINDINGS = List.of(\n')
        schema.bindings.eachWithIndex { value, index ->
            def life = value.lifetime
            def conversion = value.conversion
            def verification = value.verification
            source.append('        new Binding(')
                    .append([value.id, value.semantic, value.encoding, value.resourceKind,
                            value.extentSource].collect { quote(it.toString()) }.join(', '))
                    .append(', ').append(javaList(value.usage)).append(', ')
                    .append([value.access, value.sampling, value.descriptorOrOffset]
                            .collect { quote(it.toString()) }.join(', '))
                    .append(', new Lifetime(')
                    .append([life.owner, life.generation, life.firstWrite]
                            .collect { quote(it.toString()) }.join(', '))
                    .append(', ').append(javaList(life.consumers)).append(', ')
                    .append([life.lastRead, life.aliasGroup, life.retire]
                            .collect { quote(it.toString()) }.join(', '))
                    .append('), new Conversion(')
                    .append([conversion.producer, conversion.sourceSemantic, conversion.owner,
                            conversion.targetSemantic, conversion.backendBoundary]
                            .collect { quote(it.toString()) }.join(', '))
                    .append('), new Verification(')
                    .append([verification.artifactChecks, verification.behaviorOracle,
                            verification.numericOrImageThreshold, verification.benchmark]
                            .collect { quote(it.toString()) }.join(', '))
                    .append('))').append(index + 1 == schema.bindings.size() ? '\n' : ',\n')
        }
        source.append('    );\n\n    public static final List<MemoryPlan> MEMORY_PLANS = List.of(\n')
        schema.memoryPlans.eachWithIndex { plan, planIndex ->
            source.append('        new MemoryPlan(').append(quote(plan.id.toString())).append(', ')
                    .append(quote(plan.kind.toString())).append(', List.of(\n')
            plan.items.eachWithIndex { item, itemIndex ->
                source.append('            new MemoryItem(')
                        .append([item.id, item.semantic, item.debugLabel, item.extentSource]
                                .collect { quote(it.toString()) }.join(', '))
                        .append(', ').append(item.bytesPerElement).append(', ')
                        .append(item.elementsPerPixel).append(', ')
                        .append(item.fixedBytes).append('L)')
                        .append(itemIndex + 1 == plan.items.size() ? '\n' : ',\n')
            }
            source.append('        ))').append(planIndex + 1 == schema.memoryPlans.size() ? '\n' : ',\n')
        }
        source.append('    );\n\n    public static final List<Benchmark> BENCHMARKS = List.of(\n')
        schema.benchmarks.eachWithIndex { benchmark, index ->
            source.append('        new Benchmark(')
                    .append([benchmark.id, benchmark.fixture, benchmark.correctnessOracle]
                            .collect { quote(it.toString()) }.join(', '))
                    .append(', ').append(benchmark.warmupIterations).append(', ')
                    .append(benchmark.measurementIterations).append(', ')
                    .append(quote(benchmark.metric.toString())).append(')')
                    .append(index + 1 == schema.benchmarks.size() ? '\n' : ',\n')
        }
        source.append('''    );

    public static MemoryPlan memoryPlan(String id) {
        return MEMORY_PLANS.stream().filter(plan -> plan.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown memory plan: " + id));
    }
}
''')
        return source.toString()
    }

    private static String generateCoordinateSlang(Object schema) {
        return '''#language slang 2026
module "prime_coordinate_contract.slang";

// Generated contract leaf shared by production and its independent behavior oracles.
public static const float2 PRIME_PIXEL_CENTER_OFFSET = float2(0.5, 0.5);

public float2 primeCanonicalSampleUv(uint2 pixel, uint2 extent)
{
    return (float2(pixel) + PRIME_PIXEL_CENTER_OFFSET) / float2(extent);
}

public float2 primeCanonicalUvToClip(float2 uv)
{
    return float2(2.0 * uv.x - 1.0, 1.0 - 2.0 * uv.y);
}

public float2 primeCanonicalClipToUv(float2 clip)
{
    return float2(0.5 * clip.x + 0.5, -0.5 * clip.y + 0.5);
}

public float2 primeCanonicalProjectionJitterPixels(float2 sampleJitterPixels)
{
    return -sampleJitterPixels;
}

public float2 primeCanonicalVisibleMotionUv(float2 previousUv, float2 currentSampleUv)
{
    return previousUv - currentSampleUv;
}
'''
    }

    private static String generateColorSlang(Object schema) {
        def matrix = schema.color.linearSrgbToLinearRec2020
        return """#language slang 2026
module \"prime_color_contract.slang\";

// Generated contract leaf. Alpha is linear data and is never transfer-decoded.
public float primeCanonicalDecodeSrgb(float encoded)
{
    return encoded <= 0.04045 ? encoded / 12.92
            : pow((encoded + 0.055) / 1.055, 2.4);
}

public float3 primeCanonicalLinearSrgbToLinearRec2020(float3 color)
{
    return float3(
            dot(float3(${matrix[0].collect { decimal(it) }.join(', ')}), color),
            dot(float3(${matrix[1].collect { decimal(it) }.join(', ')}), color),
            dot(float3(${matrix[2].collect { decimal(it) }.join(', ')}), color));
}
"""
    }

    private static String generateLedger(Object schema) {
        def output = new StringBuilder('plan,kind,render_bytes_per_pixel,display_bytes_per_pixel,fixed_bytes,item,semantic,debug_label,extent,bytes_per_element,elements_per_pixel\n')
        schema.memoryPlans.each { plan ->
            int render = plan.items.findAll { it.extentSource == 'render' }
                    .sum(0) { it.bytesPerElement * it.elementsPerPixel }
            int display = plan.items.findAll { it.extentSource == 'display' }
                    .sum(0) { it.bytesPerElement * it.elementsPerPixel }
            long fixed = plan.items.sum(0L) { it.fixedBytes as long }
            plan.items.each { item ->
                def fields = [plan.id, plan.kind, render, display, fixed, item.id,
                        item.semantic, item.debugLabel, item.extentSource,
                        item.bytesPerElement, item.elementsPerPixel]
                output.append(fields.collect { value ->
                    def string = value.toString()
                    return string.contains(',') || string.contains('"')
                            ? '"' + string.replace('"', '""') + '"' : string
                }.join(',')).append('\n')
            }
        }
        return output.toString()
    }

    @TaskAction
    void generate() {
        def schema = new JsonSlurper().parse(schemaFile.get().asFile)
        validate(schema)

        def javaFile = new File(javaOutputDirectory.get().asFile,
                'dev/prime/render/data/RendererDataContracts.java')
        javaFile.parentFile.mkdirs()
        javaFile.setText(generateJava(schema), 'UTF-8')

        def slangDirectory = slangOutputDirectory.get().asFile
        slangDirectory.mkdirs()
        new File(slangDirectory, 'prime_coordinate_contract.slang')
                .setText(generateCoordinateSlang(schema), 'UTF-8')
        new File(slangDirectory, 'prime_color_contract.slang')
                .setText(generateColorSlang(schema), 'UTF-8')

        def ledger = memoryLedgerFile.get().asFile
        ledger.parentFile.mkdirs()
        ledger.setText(generateLedger(schema), 'UTF-8')
    }
}
