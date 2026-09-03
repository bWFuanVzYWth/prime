package dev.prime.render.vulkan;

import java.util.List;
import java.util.Objects;

/** Immutable ray-generation module, group, and shader-record contract. */
final class RaygenSchedule {
    private final List<String> modules;
    private final int[] groupModules;
    private final int[] controls;

    private RaygenSchedule(List<String> modules, int[] groupModules, int[] controls) {
        this.modules = List.copyOf(Objects.requireNonNull(modules, "modules"));
        this.groupModules = Objects.requireNonNull(groupModules, "groupModules").clone();
        this.controls = Objects.requireNonNull(controls, "controls").clone();
        if (this.modules.isEmpty() || this.groupModules.length == 0) {
            throw new IllegalArgumentException("Raygen schedule cannot be empty");
        }
        if (this.groupModules.length != this.controls.length) {
            throw new IllegalArgumentException("Raygen group metadata length mismatch");
        }
        for (String module : this.modules) {
            if (module == null || module.isBlank()) {
                throw new IllegalArgumentException("Raygen module resource cannot be blank");
            }
        }
        for (int module : this.groupModules) {
            if (module < 0 || module >= this.modules.size()) {
                throw new IllegalArgumentException("Raygen group references an invalid module");
            }
        }
    }

    static RaygenSchedule of(List<String> modules, int[] groupModules, int[] controls) {
        return new RaygenSchedule(modules, groupModules, controls);
    }

    static RaygenSchedule single(String module, int control) {
        return new RaygenSchedule(List.of(module), new int[] {0}, new int[] {control});
    }

    int moduleCount() {
        return this.modules.size();
    }

    int groupCount() {
        return this.groupModules.length;
    }

    String moduleResource(int module) {
        return this.modules.get(module);
    }

    int module(int group) {
        return this.groupModules[group];
    }

    int control(int group) {
        return this.controls[group];
    }
}
