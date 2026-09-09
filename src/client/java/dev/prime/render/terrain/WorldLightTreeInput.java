// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.terrain;

import java.util.List;
import java.util.Objects;

/**
 * Immutable, device-free semantic input for one world-light-tree build.
 *
 * <p>Entries are strictly key-sorted so collection iteration and GPU residency identity cannot
 * affect the packed topology.
 */
public final class WorldLightTreeInput {
    private final int originX;
    private final int originY;
    private final int originZ;
    private final long[] keys;
    private final int[] coordinates;
    private final CompiledClusterLights.Summary[] lights;

    private WorldLightTreeInput(
            int originX,
            int originY,
            int originZ,
            long[] keys,
            int[] coordinates,
            CompiledClusterLights.Summary[] lights) {
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.keys = keys;
        this.coordinates = coordinates;
        this.lights = lights;
        validate();
    }

    public static WorldLightTreeInput capture(
            List<Entry> sortedClusters,
            int originX,
            int originY,
            int originZ) {
        Objects.requireNonNull(sortedClusters, "sortedClusters");
        int count = sortedClusters.size();
        long[] keys = new long[count];
        int[] coordinates = new int[Math.multiplyExact(count, 3)];
        CompiledClusterLights.Summary[] lights =
                new CompiledClusterLights.Summary[count];
        for (int index = 0; index < count; index++) {
            Entry cluster = Objects.requireNonNull(
                    sortedClusters.get(index), "cluster");
            keys[index] = cluster.key();
            int coordinate = index * 3;
            coordinates[coordinate] = cluster.clusterX();
            coordinates[coordinate + 1] = cluster.clusterY();
            coordinates[coordinate + 2] = cluster.clusterZ();
            lights[index] = cluster.lights();
        }
        return new WorldLightTreeInput(
                originX,
                originY,
                originZ,
                keys,
                coordinates,
                lights);
    }

    public record Entry(
            long key,
            int clusterX,
            int clusterY,
            int clusterZ,
            CompiledClusterLights.Summary lights) {
        public Entry {
            lights = Objects.requireNonNull(lights, "lights");
        }
    }

    int clusterCount() {
        return this.keys.length;
    }

    long key(int index) {
        return this.keys[index];
    }

    int clusterX(int index) {
        return this.coordinates[index * 3];
    }

    int clusterY(int index) {
        return this.coordinates[index * 3 + 1];
    }

    int clusterZ(int index) {
        return this.coordinates[index * 3 + 2];
    }

    CompiledClusterLights.Summary lights(int index) {
        return this.lights[index];
    }

    int originX() {
        return this.originX;
    }

    int originY() {
        return this.originY;
    }

    int originZ() {
        return this.originZ;
    }

    private void validate() {
        if (this.coordinates.length != Math.multiplyExact(this.keys.length, 3)
                || this.lights.length != this.keys.length) {
            throw new IllegalArgumentException(
                    "World-light input arrays have different lengths");
        }
        for (int index = 0; index < this.keys.length; index++) {
            Objects.requireNonNull(this.lights[index], "lights");
            if (index != 0
                    && Long.compare(this.keys[index - 1], this.keys[index]) >= 0) {
                throw new IllegalArgumentException(
                        "World-light clusters must be strictly key-sorted");
            }
        }
    }
}
