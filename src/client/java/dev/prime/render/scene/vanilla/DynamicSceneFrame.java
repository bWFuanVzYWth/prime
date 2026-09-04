package dev.prime.render.scene.vanilla;

import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.prime.render.terrain.CpuClusterMesh;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable dynamic geometry captured from one vanilla world-render submission. */
public record DynamicSceneFrame(
        int clusterX,
        int clusterY,
        int clusterZ,
        CpuClusterMesh mesh,
        List<SceneTexture> textures,
        List<MotionSegment> motionSegments,
        List<GeometrySpan> geometrySpans,
        Set<CompatibilityIssue> compatibilityIssues) {

    public DynamicSceneFrame {
        mesh = Objects.requireNonNull(mesh, "mesh");
        textures = List.copyOf(textures);
        motionSegments = List.copyOf(motionSegments);
        geometrySpans = List.copyOf(geometrySpans);
        EnumSet<CompatibilityIssue> issues = compatibilityIssues.isEmpty()
                ? EnumSet.noneOf(CompatibilityIssue.class)
                : EnumSet.copyOf(compatibilityIssues);
        if (!mesh.lights().isEmpty()) {
            throw new IllegalArgumentException(
                    "Dynamic geometry must not contain light-tree emitters");
        }
        int previousEnd = 0;
        for (MotionSegment segment : motionSegments) {
            Objects.requireNonNull(segment, "motion segment");
            if (segment.firstTriangle() < previousEnd
                    || (long) segment.firstTriangle() + segment.triangleCount()
                            > mesh.triangleLayout().triangleCount()) {
                throw new IllegalArgumentException(
                        "Dynamic motion segments must be ordered, disjoint, and inside the mesh");
            }
            previousEnd = Math.addExact(
                    segment.firstTriangle(), segment.triangleCount());
        }
        motionSegments = unambiguousMotionSegments(motionSegments, issues);
        int geometryEnd = 0;
        for (GeometrySpan span : geometrySpans) {
            Objects.requireNonNull(span, "geometry span");
            if (span.firstTriangle() != geometryEnd
                    || (long) span.firstTriangle() + span.triangleCount()
                            > mesh.triangleLayout().triangleCount()) {
                throw new IllegalArgumentException(
                        "Dynamic geometry spans must exactly partition the captured mesh");
            }
            geometryEnd = Math.addExact(span.firstTriangle(), span.triangleCount());
        }
        if (geometryEnd != mesh.triangleLayout().triangleCount()) {
            throw new IllegalArgumentException(
                    "Dynamic geometry spans do not cover the captured mesh");
        }
        compatibilityIssues = Set.copyOf(issues);
    }

    public boolean isEmpty() {
        return this.mesh.isEmpty();
    }

    public enum CompatibilityIssue {
        BLENDED_MATERIAL_APPROXIMATED(
                "blended dynamic materials are approximated as alpha-tested surfaces"),
        TEXTURELESS_MATERIAL_APPROXIMATED(
                "a textureless render type is approximated with its submitted vertex color"),
        MISSING_ALBEDO_TEXTURE(
                "a render type has no usable Sampler0 albedo and uses submitted vertex color"),
        UNSUPPORTED_ALBEDO_FORMAT(
                "a dynamic albedo format is unsupported and uses submitted vertex color"),
        UNKNOWN_ALBEDO_ENCODING(
                "a dynamic render attachment has unknown color encoding and uses submitted vertex color"),
        SCENE_TEXTURE_LIMIT(
                "the scene texture descriptor ABI capacity was reached; excess geometry uses submitted vertex color"),
        UNSUPPORTED_TOPOLOGY(
                "a non-triangle render topology was omitted"),
        CUSTOM_SUBMIT_NODE(
                "a Fabric custom submit node has no general mesh replay contract and was omitted"),
        MISSING_MOTION_IDENTITY(
                "an entity submission bypassed stable identity extraction and uses zero object motion"),
        DUPLICATE_MOTION_IDENTITY(
                "an entity or block entity was submitted more than once with the same motion identity and uses zero object motion");

        private final String description;

        CompatibilityIssue(String description) {
            this.description = description;
        }

        public String description() {
            return this.description;
        }
    }

    /** Texture zero is the block atlas; captured textures start at index one. */
    public enum Sampling {
        SRGB_COLOR,
        LINEAR_RED_DATA
    }

    public record SceneTexture(
            GpuTextureView view,
            GpuSampler sampler,
            Sampling sampling) {
        public SceneTexture {
            Objects.requireNonNull(view, "view");
            Objects.requireNonNull(sampler, "sampler");
            Objects.requireNonNull(sampling, "sampling");
        }
    }

    /** Stable captured-object span whose animated vertices keep one semantic triangle order. */
    public record MotionSegment(
            VanillaSceneBoundary.Element element,
            long key,
            int firstTriangle,
            int triangleCount) {
        public MotionSegment {
            Objects.requireNonNull(element, "element");
            if (element != VanillaSceneBoundary.Element.ENTITY
                    && element != VanillaSceneBoundary.Element.BLOCK_ENTITY) {
                throw new IllegalArgumentException(
                        "Only entities and block entities have stable motion identities");
            }
            if (firstTriangle < 0 || triangleCount < 0) {
                throw new IllegalArgumentException(
                        "Motion segment triangle range must not be negative");
            }
        }
    }

    /** One independently placed dynamic object or one unshareable compatibility submission. */
    public record GeometrySpan(
            GeometryKind kind,
            VanillaSceneBoundary.Element element,
            long key,
            boolean stableIdentity,
            int firstTriangle,
            int triangleCount) {
        public GeometrySpan {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(element, "element");
            if (firstTriangle < 0 || triangleCount <= 0) {
                throw new IllegalArgumentException(
                        "Dynamic geometry span must contain a non-negative triangle range");
            }
            if (stableIdentity
                    && element != VanillaSceneBoundary.Element.ENTITY
                    && element != VanillaSceneBoundary.Element.BLOCK_ENTITY) {
                throw new IllegalArgumentException(
                        "Only entities and block entities have stable dynamic identities");
            }
        }
    }

    public enum GeometryKind {
        INSTANCED,
        UNIQUE
    }

    private static List<MotionSegment> unambiguousMotionSegments(
            List<MotionSegment> segments,
            EnumSet<CompatibilityIssue> issues) {
        Map<MotionIdentity, Integer> counts = new HashMap<>(segments.size());
        for (MotionSegment segment : segments) {
            if (segment.triangleCount() > 0) {
                counts.merge(
                        new MotionIdentity(segment.element(), segment.key()),
                        1,
                        Math::addExact);
            }
        }

        ArrayList<MotionSegment> result = new ArrayList<>(segments.size());
        boolean duplicate = false;
        for (MotionSegment segment : segments) {
            if (segment.triangleCount() == 0) {
                continue;
            }
            int count = counts.get(
                    new MotionIdentity(segment.element(), segment.key()));
            if (count == 1) {
                result.add(segment);
            } else {
                duplicate = true;
            }
        }
        if (duplicate) {
            issues.add(CompatibilityIssue.DUPLICATE_MOTION_IDENTITY);
        }
        return List.copyOf(result);
    }

    private record MotionIdentity(
            VanillaSceneBoundary.Element element, long key) {}
}
