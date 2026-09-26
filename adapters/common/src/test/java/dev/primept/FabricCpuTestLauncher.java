package dev.primept;

/** Initializes Fabric/Mixin only. Even a missing test entrypoint cannot launch Minecraft main. */
public final class FabricCpuTestLauncher {
    public static void main(String[] args) throws Exception {
        // Fabric is supplied by the version adapter at runtime, not by the common module.
        Class<?> side = Class.forName("net.fabricmc.api.EnvType");
        Class<?> knot = Class.forName("net.fabricmc.loader.impl.launch.knot.Knot");
        Object loader = knot.getConstructor(side).newInstance(side.getField("CLIENT").get(null));
        knot.getMethod("init", String[].class).invoke(loader, (Object)args);
        throw new AssertionError(
                "CPU test preLaunch entrypoint did not exit; game launch is forbidden");
    }
}
