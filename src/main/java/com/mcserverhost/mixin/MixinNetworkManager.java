package com.mcserverhost.mixin;

import com.mcserverhost.SpoofableAddress;
import net.minecraft.network.NetworkManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

@Mixin(value = NetworkManager.class, remap = false)
public abstract class MixinNetworkManager implements SpoofableAddress {

    @Unique
    private InetSocketAddress mcsh$spoofedAddress = null;

    @Override
    public void mcsh$setSpoofedAddress(InetSocketAddress address) {
        this.mcsh$spoofedAddress = address;
    }

    @Override
    public InetSocketAddress mcsh$getSpoofedAddress() {
        return this.mcsh$spoofedAddress;
    }

    @Inject(method = {"func_74430_c", "getRemoteAddress"}, at = @At("HEAD"), cancellable = true, remap = false)
    private void mcsh$interceptGetRemoteAddress(CallbackInfoReturnable<SocketAddress> cir) {
        if (mcsh$spoofedAddress != null) {
            cir.setReturnValue(mcsh$spoofedAddress);
        }
    }

}
