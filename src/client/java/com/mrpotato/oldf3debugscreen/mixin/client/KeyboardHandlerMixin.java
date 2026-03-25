package com.mrpotato.oldf3debugscreen.mixin.client;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import com.mrpotato.oldf3debugscreen.client.debug.DebugFeedbackOverlay;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {

    @Shadow @Final private Minecraft minecraft;

    @Inject(method = "debugFeedbackComponent", at = @At("HEAD"), cancellable = true)
    private void oldf3screen$redirectDebugFeedback(Component component, CallbackInfo ci) {
        if (minecraft.debugEntries.isOverlayVisible()) {
            DebugFeedbackOverlay.push(component.getString());
            ci.cancel();
        }
    }

    @Inject(method = "debugWarningComponent", at = @At("HEAD"), cancellable = true)
    private void oldf3screen$redirectDebugWarning(Component component, CallbackInfo ci) {
        if (minecraft.debugEntries.isOverlayVisible()) {
            DebugFeedbackOverlay.push(component.getString());
            ci.cancel();
        }
    }
}
