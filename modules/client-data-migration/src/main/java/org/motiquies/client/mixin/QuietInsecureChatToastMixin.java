package org.motiquies.client.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** User-requested suppression of this one login toast, with no authentication changes. */
@Mixin(ClientPacketListener.class)
public abstract class QuietInsecureChatToastMixin {
    private static boolean motiquies$noticeLogged;
    @Redirect(method="handleLogin",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/components/toasts/ToastComponent;addToast(Lnet/minecraft/client/gui/components/toasts/Toast;)V"))
    private void motiquies$quietKnownLoginToast(ToastComponent component,Toast toast){
        if(toast instanceof SystemToast system&&system.getToken()==SystemToast.SystemToastId.UNSECURE_SERVER_WARNING){
            if(!motiquies$noticeLogged){
                motiquies$noticeLogged=true;
                LogManager.getLogger().info("Motiquies hid the requested insecure-chat login toast; server authentication and chat settings are unchanged");
            }
            return;
        }
        component.addToast(toast);
    }
}
