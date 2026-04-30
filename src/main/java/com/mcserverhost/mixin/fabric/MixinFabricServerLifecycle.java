package com.mcserverhost.mixin.fabric;

import com.mcserverhost.FabricPlugin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.minecraft.server.MinecraftServer")
public abstract class MixinFabricServerLifecycle {

    @Inject(method = "runServer", at = @At("HEAD"), require = 0)
    private void mcsh$onServerStarted(CallbackInfo ci) {
        FabricPlugin plugin = FabricPlugin.getInstance();
        if (plugin != null) plugin.onServerStarted(this);
    }

    @Inject(method = "method_3782", at = @At("HEAD"), require = 0)
    private void mcsh$onServerStartedIntermediary(CallbackInfo ci) {
        FabricPlugin plugin = FabricPlugin.getInstance();
        if (plugin != null) plugin.onServerStarted(this);
    }

    @Inject(method = "stopServer", at = @At("HEAD"), require = 0)
    private void mcsh$onServerStopping(CallbackInfo ci) {
        FabricPlugin plugin = FabricPlugin.getInstance();
        if (plugin != null) plugin.onServerStopping(this);
    }

    @Inject(method = "method_3747", at = @At("HEAD"), require = 0)
    private void mcsh$onServerStoppingIntermediary(CallbackInfo ci) {
        FabricPlugin plugin = FabricPlugin.getInstance();
        if (plugin != null) plugin.onServerStopping(this);
    }
}
