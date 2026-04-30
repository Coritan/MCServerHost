package com.mcserverhost.mixin.fabric;

import com.mcserverhost.FabricPlugin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

@Pseudo
@Mixin(targets = "net.minecraft.class_2170")
public abstract class MixinFabricCommandManager {

    @Inject(method = "<init>*", at = @At("RETURN"), require = 0)
    private void mcsh$onInit(CallbackInfo ci) {
        FabricPlugin plugin = FabricPlugin.getInstance();
        if (plugin == null) return;
        try {
            Object dispatcher = null;
            for (String name : new String[]{"getDispatcher", "method_9235"}) {
                try {
                    Method m = this.getClass().getMethod(name);
                    dispatcher = m.invoke(this);
                    if (dispatcher != null) break;
                } catch (Throwable ignored) {
                }
            }
            if (dispatcher == null) {
                for (java.lang.reflect.Field f : this.getClass().getDeclaredFields()) {
                    if (f.getType().getName().endsWith("CommandDispatcher")) {
                        f.setAccessible(true);
                        dispatcher = f.get(this);
                        if (dispatcher != null) break;
                    }
                }
            }
            if (dispatcher != null) plugin.onRegisterCommands(dispatcher);
        } catch (Throwable e) {
            plugin.getLogger().warning("[MCServerHost] Failed to hook CommandManager init: " + e.getMessage());
        }
    }
}
