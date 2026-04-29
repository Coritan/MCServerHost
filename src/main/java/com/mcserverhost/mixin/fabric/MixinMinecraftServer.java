package com.mcserverhost.mixin.fabric;

import com.mcserverhost.FabricServerHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the MinecraftServer instance on Fabric at construction time.
 * Target FQCN is declared as a string with remap = false because
 * "net.minecraft.server.MinecraftServer" is a stable name under Fabric's
 * Intermediary mappings and does not need remapping from the dev mappings
 * used to compile this project (Forge 1.16.5 SRG).
 */
@Mixin(targets = "net.minecraft.server.MinecraftServer", remap = false)
public abstract class MixinMinecraftServer {

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void mcsh$captureServer(CallbackInfo ci) {
        FabricServerHolder.setServer(this);
    }
}
