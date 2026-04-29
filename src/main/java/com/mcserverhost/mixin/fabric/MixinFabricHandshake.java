package com.mcserverhost.mixin.fabric;

import com.mcserverhost.FabricHandshakeSupport;
import com.mcserverhost.FabricPacketContext;
import com.mcserverhost.FabricPlayerContext;
import com.mcserverhost.FabricPlugin;
import net.minecraft.class_2889;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects at HEAD of handleIntention on class_3248 (ServerHandshakePacketListenerImpl).
 * Intermediary: method_14414. We also try method_10766 (older name) and Mojang name.
 */
@Pseudo
@Mixin(targets = {"net.minecraft.class_3246", "net.minecraft.class_3248"}, remap = false)
public abstract class MixinFabricHandshake {

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void mcsh$ctor(CallbackInfo ci) {
        System.out.println("[MCServerHost] [mixin] handshake listener constructed: " + this.getClass().getName());
    }

    @Inject(
        method = {"method_12576", "method_14414", "onHandshake", "handleIntention"},
        at = @At("HEAD"),
        remap = false,
        require = 0
    )
    private void mcsh$onHandleIntention(class_2889 packet, CallbackInfo ci) {
        System.out.println("[MCServerHost] [mixin] handleIntention fired. packet=" + (packet == null ? "null" : packet.getClass().getName()));
        FabricPlugin plugin = FabricPlugin.getInstance();
        if (plugin == null) {
            System.out.println("[MCServerHost] [mixin] plugin instance is null - bailing");
            return;
        }
        try {
            Object connection = FabricHandshakeSupport.findConnection(this);
            System.out.println("[MCServerHost] [mixin] connection=" + (connection == null ? "null" : connection.getClass().getName()));
            if (connection == null) return;
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
