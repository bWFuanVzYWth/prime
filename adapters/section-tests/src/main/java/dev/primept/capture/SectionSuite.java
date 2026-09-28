package dev.primept.capture;

/** Test-only bootstrap. Exits in Fabric preLaunch; never calls Minecraft main. */
final class SectionSuite {
    static void run() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        net.fabricmc.fabric.api.client.renderer.v1.Renderer.register(
                net.fabricmc.fabric.impl.client.indigo.renderer.IndigoRenderer.INSTANCE);
        // Controlled fixtures deliberately have no resource reload or tag-dependent models.
        var bind = net.minecraft.core.Holder.Reference.class.getDeclaredMethod(
                "bindTags", java.util.Collection.class);
        bind.setAccessible(true);
        for (var registry :
             java.util.List.of(net.minecraft.core.registries.BuiltInRegistries.BLOCK,
                               net.minecraft.core.registries.BuiltInRegistries.FLUID))
            for (var holder : registry.listElements().toList())
                bind.invoke(holder, java.util.Set.of());
        SectionCompilerOracle.run();
    }
}
