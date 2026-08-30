package dev.prime.render.terrain;

/**
 * Conservative directional emission bound shared by local and world light-tree builders.
 * Coherent arbitrary normals use a cone; mixed normals use the six-axis inequality
 * {@code max(0, n·d) <= sum(abs(n[i]) * max(0, sign(n[i]) * d[i]))}.
 */
final class LightDirection {
    static final int MODE_ONE_SIDED_CONE = 0;
    static final int MODE_TWO_SIDED_CONE = 1;
    static final int MODE_LOBES = 2;
    static final int MODE_FULL = 3;
    static final int FULL = MODE_FULL << 30;

    private static final int MODE_SHIFT = 30;
    private static final int OCT_MASK = 0x3ff;
    private static final int CONE_SINE_MASK = 0x3ff;
    private static final int LOBE_MASK = 0x1f;
    private static final float MAX_CONE_HALF_ANGLE = (float) (Math.PI * 0.5);
    private static final float CONE_THRESHOLD = (float) (Math.PI * 0.125);
    private static final float CONE_ANGLE_MARGIN = MAX_CONE_HALF_ANGLE / CONE_SINE_MASK;

    private LightDirection() {}

    static Bounds fromNormal(float x, float y, float z, boolean twoSided) {
        float inverseLength = inverseLength(x, y, z);
        x *= inverseLength;
        y *= inverseLength;
        z *= inverseLength;
        float positiveX = twoSided ? 0.5F * Math.abs(x) : Math.max(x, 0.0F);
        float negativeX = twoSided ? 0.5F * Math.abs(x) : Math.max(-x, 0.0F);
        float positiveY = twoSided ? 0.5F * Math.abs(y) : Math.max(y, 0.0F);
        float negativeY = twoSided ? 0.5F * Math.abs(y) : Math.max(-y, 0.0F);
        float positiveZ = twoSided ? 0.5F * Math.abs(z) : Math.max(z, 0.0F);
        float negativeZ = twoSided ? 0.5F * Math.abs(z) : Math.max(-z, 0.0F);
        return new Bounds(
                x,
                y,
                z,
                0.0F,
                twoSided ? MODE_TWO_SIDED_CONE : MODE_ONE_SIDED_CONE,
                positiveX,
                negativeX,
                positiveY,
                negativeY,
                positiveZ,
                negativeZ);
    }

    static Bounds full() {
        return new Bounds(0.0F, 0.0F, 1.0F, MAX_CONE_HALF_ANGLE, MODE_FULL,
                1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F);
    }

    static Bounds combine(Bounds first, float firstPower, Bounds second, float secondPower) {
        if (!(firstPower > 0.0F)) {
            return second;
        }
        if (!(secondPower > 0.0F)) {
            return first;
        }
        float power = firstPower + secondPower;
        float firstWeight = firstPower / power;
        float secondWeight = secondPower / power;
        int coneMode = first.mode == second.mode
                        && (first.mode == MODE_ONE_SIDED_CONE
                                || first.mode == MODE_TWO_SIDED_CONE)
                ? first.mode
                : MODE_LOBES;
        Cone cone = coneMode == MODE_LOBES ? null : union(first, second, coneMode);
        if (cone == null || cone.halfAngle > CONE_THRESHOLD) {
            coneMode = MODE_LOBES;
            cone = new Cone(0.0F, 0.0F, 1.0F, MAX_CONE_HALF_ANGLE);
        }
        return new Bounds(
                cone.x,
                cone.y,
                cone.z,
                cone.halfAngle,
                coneMode,
                firstWeight * first.positiveX + secondWeight * second.positiveX,
                firstWeight * first.negativeX + secondWeight * second.negativeX,
                firstWeight * first.positiveY + secondWeight * second.positiveY,
                firstWeight * first.negativeY + secondWeight * second.negativeY,
                firstWeight * first.positiveZ + secondWeight * second.positiveZ,
                firstWeight * first.negativeZ + secondWeight * second.negativeZ);
    }

    static int pack(Bounds bounds) {
        if (bounds.mode == MODE_FULL) {
            return FULL;
        }
        if (bounds.mode == MODE_ONE_SIDED_CONE || bounds.mode == MODE_TWO_SIDED_CONE) {
            int[] encoded = packUnitVector(bounds.x, bounds.y, bounds.z);
            float[] decoded = unpackUnitVector(encoded[0], encoded[1]);
            float dot = clamp(bounds.x * decoded[0] + bounds.y * decoded[1] + bounds.z * decoded[2], -1.0F, 1.0F);
            // Fold axis quantization into the cone and add one angular step for CPU/GPU rounding.
            float expandedAngle = bounds.halfAngle
                    + (float) Math.acos(dot)
                    + CONE_ANGLE_MARGIN;
            if (expandedAngle <= MAX_CONE_HALF_ANGLE) {
                int coneSine = Math.min(
                        CONE_SINE_MASK,
                        (int) Math.ceil(Math.sin(expandedAngle) * CONE_SINE_MASK));
                return encoded[0]
                        | encoded[1] << 10
                        | coneSine << 20
                        | bounds.mode << MODE_SHIFT;
            }
        }
        // Every lobe rounds upward so decoded world-tree summaries remain conservative.
        int packed = MODE_LOBES << MODE_SHIFT;
        packed |= packLobe(bounds.positiveX);
        packed |= packLobe(bounds.negativeX) << 5;
        packed |= packLobe(bounds.positiveY) << 10;
        packed |= packLobe(bounds.negativeY) << 15;
        packed |= packLobe(bounds.positiveZ) << 20;
        packed |= packLobe(bounds.negativeZ) << 25;
        return packed;
    }

    static Bounds unpack(int packed) {
        int mode = packed >>> MODE_SHIFT;
        if (mode == MODE_FULL) {
            return full();
        }
        if (mode == MODE_LOBES) {
            return new Bounds(
                    0.0F,
                    0.0F,
                    1.0F,
                    MAX_CONE_HALF_ANGLE,
                    MODE_LOBES,
                    unpackLobe(packed),
                    unpackLobe(packed >>> 5),
                    unpackLobe(packed >>> 10),
                    unpackLobe(packed >>> 15),
                    unpackLobe(packed >>> 20),
                    unpackLobe(packed >>> 25));
        }
        float[] axis = unpackUnitVector(packed & OCT_MASK, packed >>> 10 & OCT_MASK);
        float halfAngle = (float) Math.asin(
                (float) (packed >>> 20 & CONE_SINE_MASK) / CONE_SINE_MASK);
        float positiveX;
        float negativeX;
        float positiveY;
        float negativeY;
        float positiveZ;
        float negativeZ;
        if (mode == MODE_TWO_SIDED_CONE) {
            positiveX = negativeX = 0.5F * axialComponentBound(axis[0], halfAngle);
            positiveY = negativeY = 0.5F * axialComponentBound(axis[1], halfAngle);
            positiveZ = negativeZ = 0.5F * axialComponentBound(axis[2], halfAngle);
        } else {
            positiveX = coneCosineBound(axis[0], halfAngle);
            negativeX = coneCosineBound(-axis[0], halfAngle);
            positiveY = coneCosineBound(axis[1], halfAngle);
            negativeY = coneCosineBound(-axis[1], halfAngle);
            positiveZ = coneCosineBound(axis[2], halfAngle);
            negativeZ = coneCosineBound(-axis[2], halfAngle);
        }
        return new Bounds(
                axis[0], axis[1], axis[2], halfAngle, mode,
                positiveX, negativeX, positiveY, negativeY, positiveZ, negativeZ);
    }

    static float emissionCosineBound(
            int packed,
            CpuLightTree.Bounds bounds,
            float pointX,
            float pointY,
            float pointZ) {
        int mode = packed >>> MODE_SHIFT;
        if (mode == MODE_FULL) {
            return 1.0F;
        }
        float closestX = clamp(pointX, bounds.minX(), bounds.maxX());
        float closestY = clamp(pointY, bounds.minY(), bounds.maxY());
        float closestZ = clamp(pointZ, bounds.minZ(), bounds.maxZ());
        float deltaX = pointX - closestX;
        float deltaY = pointY - closestY;
        float deltaZ = pointZ - closestZ;
        float distanceSquared = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
        if (!(distanceSquared > 0.0F)) {
            return 1.0F;
        }
        float inverseDistance = 1.0F / (float) Math.sqrt(distanceSquared);
        if (mode == MODE_LOBES) {
            float result = 0.0F;
            result += unpackLobe(packed)
                    * axisCosineBound(bounds, pointX, pointY, pointZ, 1.0F, 0.0F, 0.0F, inverseDistance);
            result += unpackLobe(packed >>> 5)
                    * axisCosineBound(bounds, pointX, pointY, pointZ, -1.0F, 0.0F, 0.0F, inverseDistance);
            result += unpackLobe(packed >>> 10)
                    * axisCosineBound(bounds, pointX, pointY, pointZ, 0.0F, 1.0F, 0.0F, inverseDistance);
            result += unpackLobe(packed >>> 15)
                    * axisCosineBound(bounds, pointX, pointY, pointZ, 0.0F, -1.0F, 0.0F, inverseDistance);
            result += unpackLobe(packed >>> 20)
                    * axisCosineBound(bounds, pointX, pointY, pointZ, 0.0F, 0.0F, 1.0F, inverseDistance);
            result += unpackLobe(packed >>> 25)
                    * axisCosineBound(bounds, pointX, pointY, pointZ, 0.0F, 0.0F, -1.0F, inverseDistance);
            return Math.min(result, 1.0F);
        }
        float[] axis = unpackUnitVector(packed & OCT_MASK, packed >>> 10 & OCT_MASK);
        float halfAngle = (float) Math.asin(
                (float) (packed >>> 20 & CONE_SINE_MASK) / CONE_SINE_MASK);
        float forward = expandedConeBound(
                axisCosineBound(bounds, pointX, pointY, pointZ,
                        axis[0], axis[1], axis[2], inverseDistance),
                halfAngle);
        if (mode == MODE_ONE_SIDED_CONE) {
            return forward;
        }
        float backward = expandedConeBound(
                axisCosineBound(bounds, pointX, pointY, pointZ,
                        -axis[0], -axis[1], -axis[2], inverseDistance),
                halfAngle);
        return 0.5F * Math.max(forward, backward);
    }

    static int mode(int packed) {
        return packed >>> MODE_SHIFT;
    }

    /** Excess spherical integral of the packed cosine envelope over an exact diffuse lobe. */
    static float spread(Bounds bounds) {
        if (bounds == null) {
            return 0.0F;
        }
        if (bounds.mode == MODE_FULL) {
            return 3.0F;
        }
        if (bounds.mode == MODE_LOBES) {
            return Math.max(
                    bounds.positiveX
                            + bounds.negativeX
                            + bounds.positiveY
                            + bounds.negativeY
                            + bounds.positiveZ
                            + bounds.negativeZ
                            - 1.0F,
                    0.0F);
        }
        return 1.0F
                - (float) Math.cos(bounds.halfAngle)
                + 0.5F * (float) Math.PI * (float) Math.sin(bounds.halfAngle);
    }

    private static Cone union(Bounds first, Bounds second, int mode) {
        float secondX = second.x;
        float secondY = second.y;
        float secondZ = second.z;
        float dot = clamp(first.x * secondX + first.y * secondY + first.z * secondZ, -1.0F, 1.0F);
        if (mode == MODE_TWO_SIDED_CONE && dot < 0.0F) {
            secondX = -secondX;
            secondY = -secondY;
            secondZ = -secondZ;
            dot = -dot;
        }
        float separation = (float) Math.acos(dot);
        if (first.halfAngle >= separation + second.halfAngle) {
            return new Cone(first.x, first.y, first.z, first.halfAngle);
        }
        if (second.halfAngle >= separation + first.halfAngle) {
            return new Cone(secondX, secondY, secondZ, second.halfAngle);
        }
        float halfAngle = 0.5F * (separation + first.halfAngle + second.halfAngle);
        if (halfAngle > CONE_THRESHOLD) {
            return new Cone(first.x, first.y, first.z, halfAngle);
        }
        float amount = separation > 1.0E-6F
                ? (halfAngle - first.halfAngle) / separation
                : 0.5F;
        float x;
        float y;
        float z;
        float sine = (float) Math.sin(separation);
        if (Math.abs(sine) > 1.0E-6F) {
            float firstScale = (float) Math.sin((1.0F - amount) * separation) / sine;
            float secondScale = (float) Math.sin(amount * separation) / sine;
            x = firstScale * first.x + secondScale * secondX;
            y = firstScale * first.y + secondScale * secondY;
            z = firstScale * first.z + secondScale * secondZ;
        } else {
            x = (1.0F - amount) * first.x + amount * secondX;
            y = (1.0F - amount) * first.y + amount * secondY;
            z = (1.0F - amount) * first.z + amount * secondZ;
        }
        float inverseLength = inverseLength(x, y, z);
        return new Cone(x * inverseLength, y * inverseLength, z * inverseLength, halfAngle);
    }

    private static int[] packUnitVector(float x, float y, float z) {
        float inverseL1 = 1.0F / Math.max(Math.abs(x) + Math.abs(y) + Math.abs(z), 1.0E-20F);
        x *= inverseL1;
        y *= inverseL1;
        z *= inverseL1;
        if (z < 0.0F) {
            float oldX = x;
            x = (1.0F - Math.abs(y)) * signNotZero(oldX);
            y = (1.0F - Math.abs(oldX)) * signNotZero(y);
        }
        return new int[] {
            Math.round(clamp(x * 0.5F + 0.5F, 0.0F, 1.0F) * OCT_MASK),
            Math.round(clamp(y * 0.5F + 0.5F, 0.0F, 1.0F) * OCT_MASK)
        };
    }

    private static float[] unpackUnitVector(int xBits, int yBits) {
        float x = (float) xBits / OCT_MASK * 2.0F - 1.0F;
        float y = (float) yBits / OCT_MASK * 2.0F - 1.0F;
        float z = 1.0F - Math.abs(x) - Math.abs(y);
        if (z < 0.0F) {
            float oldX = x;
            x = (1.0F - Math.abs(y)) * signNotZero(oldX);
            y = (1.0F - Math.abs(oldX)) * signNotZero(y);
        }
        float inverseLength = inverseLength(x, y, z);
        return new float[] {x * inverseLength, y * inverseLength, z * inverseLength};
    }

    private static int packLobe(float value) {
        return Math.min(LOBE_MASK, (int) Math.ceil(clamp(value, 0.0F, 1.0F) * LOBE_MASK));
    }

    private static float unpackLobe(int value) {
        return (float) (value & LOBE_MASK) / LOBE_MASK;
    }

    private static float axialComponentBound(float axisComponent, float halfAngle) {
        return Math.max(
                coneCosineBound(axisComponent, halfAngle),
                coneCosineBound(-axisComponent, halfAngle));
    }

    private static float coneCosineBound(float cosine, float halfAngle) {
        cosine = clamp(cosine, -1.0F, 1.0F);
        float cosineHalfAngle = (float) Math.cos(halfAngle);
        if (cosine >= cosineHalfAngle) {
            return 1.0F;
        }
        float result = cosine * cosineHalfAngle
                + (float) Math.sqrt(Math.max(1.0F - cosine * cosine, 0.0F))
                        * (float) Math.sin(halfAngle);
        return clamp(result, 0.0F, 1.0F);
    }

    private static float expandedConeBound(float axisCosineBound, float halfAngle) {
        return coneCosineBound(clamp(axisCosineBound, 0.0F, 1.0F), halfAngle);
    }

    private static float axisCosineBound(
            CpuLightTree.Bounds bounds,
            float pointX,
            float pointY,
            float pointZ,
            float axisX,
            float axisY,
            float axisZ,
            float inverseDistance) {
        float supportX = axisX >= 0.0F ? bounds.minX() : bounds.maxX();
        float supportY = axisY >= 0.0F ? bounds.minY() : bounds.maxY();
        float supportZ = axisZ >= 0.0F ? bounds.minZ() : bounds.maxZ();
        float projection = axisX * (pointX - supportX)
                + axisY * (pointY - supportY)
                + axisZ * (pointZ - supportZ);
        return projection > 0.0F ? Math.min(projection * inverseDistance, 1.0F) : 0.0F;
    }

    private static float inverseLength(float x, float y, float z) {
        float squared = x * x + y * y + z * z;
        if (!(squared > 0.0F) || !Float.isFinite(squared)) {
            throw new IllegalArgumentException("Light direction must be finite and nonzero");
        }
        return 1.0F / (float) Math.sqrt(squared);
    }

    private static float signNotZero(float value) {
        return value >= 0.0F ? 1.0F : -1.0F;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    static final class Bounds {
        final float x;
        final float y;
        final float z;
        final float halfAngle;
        final int mode;
        final float positiveX;
        final float negativeX;
        final float positiveY;
        final float negativeY;
        final float positiveZ;
        final float negativeZ;

        private Bounds(
                float x,
                float y,
                float z,
                float halfAngle,
                int mode,
                float positiveX,
                float negativeX,
                float positiveY,
                float negativeY,
                float positiveZ,
                float negativeZ) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.halfAngle = halfAngle;
            this.mode = mode;
            this.positiveX = positiveX;
            this.negativeX = negativeX;
            this.positiveY = positiveY;
            this.negativeY = negativeY;
            this.positiveZ = positiveZ;
            this.negativeZ = negativeZ;
        }
    }

    private record Cone(float x, float y, float z, float halfAngle) {}
}
