package com.mcserverhost.mixin.fabric;

import com.mcserverhost.FabricNetworkIoHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures ServerNetworkIo (intermediary class_3242) at construction time.
 * Fabric 1.14+ all use this intermediary class name. We avoid field walking
 * on the server and instead rely on the fact that this class exposes its
 * channels list directly as a field whose type is List<ChannelFuture>.
 */
@Mixin(targets = {
    "net.minecraft.class_3242",
    "net.minecraft.class_3414",
    "net.minecraft.server.network.ServerNetworkIo",
    "net.minecraft.server.network.ServerConnectionListener",
    "net.minecraft.server.ServerConnectionListener"
}, remap = false)
public abstract class MixinServerNetworkIo {

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void mcsh$captureIo(CallbackInfo ci) {
        FabricNetworkIoHolder.set(this);
        System.out.println("[MCServerHost] [mixin] ServerNetworkIo captured: " + this.getClass().getName());
    }
}
