package com.mcserverhost.mixin.fabric;

import com.mcserverhost.FabricHandshakeSupport;
import com.mcserverhost.FabricPacketContext;
import com.mcserverhost.FabricPlayerContext;
import com.mcserverhost.FabricPlugin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fabric handshake interceptor. Injects at HEAD of handleIntention on
 * ServerHandshakePacketListenerImpl (intermediary class_3248 on 1.21.x).
 *
 * Kept minimal: the mixin only forwards to FabricHandshakeSupport so that
 * Mixin's inner-class generator does not have to resolve target classes
 * that are remapped at runtime.
 */
@Pseudo
@Mixin(targets = "net.minecraft.class_3248", remap = false)
public abstract class MixinFabricHandshake {

    @Inject(
        method = {"method_14414", "handleIntention"},
        at = @At("HEAD"),
        remap = false,
        require = 0
    )
    private void mcsh$onHandleIntention(Object packet, CallbackInfo ci) {
        FabricPlugin plugin = FabricPlugin.getInstance();
        if (plugin == null) return;
        try {
            Object connection = FabricHandshakeSupport.findConnection(this);
            if (connection == null) {
                plugin.getLogger().warning("[MCServerHost] handshake: connection field not found on " + this.getClass().getName());
                return;
            }
            FabricPacketContext packetCtx = new FabricPacketContext(packet);
            FabricPlayerContext player = new FabricPlayerContext(connection);
            plugin.getLogger().info("[MCServerHost] Handshake intercepted. Raw payload: \"" + packetCtx.getPayloadString() + "\", IP: " + player.getIP());
            plugin.getHandshakeHandler().handleHandshake(packetCtx, player);
            plugin.getLogger().info("[MCServerHost] Handshake handled. New IP: " + player.getIP());
        } catch (Throwable e) {
            plugin.getLogger().warning("[MCServerHost] Handshake error: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
}
