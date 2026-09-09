// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.runtime.terrain;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

final class ClusterGenerationTracker {
    private final Long2LongOpenHashMap generations = new Long2LongOpenHashMap();
    private long worldEpoch;

    long worldEpoch() {
        return this.worldEpoch;
    }

    long current(long clusterKey) {
        return this.generations.get(clusterKey);
    }

    long advance(long clusterKey) {
        long next = Math.addExact(this.current(clusterKey), 1L);
        this.generations.put(clusterKey, next);
        return next;
    }

    boolean isCurrent(long clusterKey, long token) {
        return this.current(clusterKey) == token;
    }

    boolean isCurrent(long epoch, long clusterKey, long token) {
        return this.worldEpoch == epoch && this.isCurrent(clusterKey, token);
    }

    void resetWorld() {
        this.worldEpoch = Math.addExact(this.worldEpoch, 1L);
        this.generations.clear();
    }
}
