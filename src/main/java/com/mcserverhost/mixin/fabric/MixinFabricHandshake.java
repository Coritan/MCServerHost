package com.mcserverhost.mixin.fabric;

import com.mcserverhost.FabricHandshakeSupport;
import com.mcserverhost.FabricPlugin;
import net.minecraft.class_2889;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.minecraft.class_3246")
public abstract class MixinFabricHandshake {

    @Inject(method = "method_12576", at = @At("HEAD"), require = 0)
    private void mcsh$onHandshake(class_2889 packet, CallbackInfo ci) {
        FabricPlugin plugin = FabricPlugin.getInstance();
        if (plugin == null) return;
        FabricHandshakeSupport.handleHandshake(packet, this, plugin);
    }
}
