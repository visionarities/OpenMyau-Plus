package myau.mixin;

import myau.module.modules.RenderFixes;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@SideOnly(Side.CLIENT)
@Mixin(value = {GuiChat.class}, priority = 9999)
public abstract class MixinGuiChat extends GuiScreen {
    @Unique
    private boolean myau$translatedInput;

    @Inject(method = {"drawScreen"}, at = @At("HEAD"))
    private void myau$beginModernInput(int mouseX, int mouseY, float partialTicks, CallbackInfo callbackInfo) {
        this.myau$translatedInput = false;
        if (RenderFixes.isChatActive()) {
            RenderFixes.renderChatInputBackground(this.width, this.height);
            GlStateManager.pushMatrix();
            RenderFixes.translateChat();
            this.myau$translatedInput = true;
        }
    }

    @Inject(method = {"drawScreen"}, at = @At("RETURN"))
    private void myau$endModernInput(int mouseX, int mouseY, float partialTicks, CallbackInfo callbackInfo) {
        if (this.myau$translatedInput) {
            GlStateManager.popMatrix();
            this.myau$translatedInput = false;
        }
    }

    @Redirect(
            method = {"drawScreen"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiChat;drawRect(IIIII)V"
            )
    )
    private void myau$drawInputRect(int left, int top, int right, int bottom, int color) {
        if (!RenderFixes.isChatActive()) {
            Gui.drawRect(left, top, right, bottom, color);
        }
    }
}
