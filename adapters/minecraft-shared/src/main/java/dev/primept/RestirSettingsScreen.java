package dev.primept;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/** ReSTIR controls use the host's ordinary options subpage and Done/Escape navigation. */
public final class RestirSettingsScreen extends OptionsSubScreen {
    private RestirVideoOptions restir;

    public RestirSettingsScreen(Screen parent) {
        super(parent, Minecraft.getInstance().options,
              Component.translatable("primept.settings.restir_pt"));
    }

    @Override
    protected void init() {
        layout.removeChildren();
        super.init();
    }

    @Override
    protected void addOptions() {
        restir = new RestirVideoOptions(list);
        restir.refresh();
    }

    @Override
    public void tick() {
        super.tick();
        restir.refresh();
    }

    @Override
    public void removed() {
        super.removed();
        PrimeClient.saveSettings();
    }
}
