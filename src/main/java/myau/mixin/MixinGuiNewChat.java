package myau.mixin;

import myau.module.modules.RenderFixes;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@SideOnly(Side.CLIENT)
@Mixin(value = {GuiNewChat.class}, priority = 9999)
public abstract class MixinGuiNewChat {
    @Unique
    private boolean myau$translatedChat;

    @Inject(method = {"drawChat"}, at = @At("HEAD"))
    private void myau$beginModernChat(int updateCounter, CallbackInfo callbackInfo) {
        this.myau$translatedChat = false;
        if (RenderFixes.isChatActive()) {
            RenderFixes.renderChatHistoryBackground((GuiNewChat) (Object) this);
            GlStateManager.pushMatrix();
            RenderFixes.translateChat();
            this.myau$translatedChat = true;
        }
    }

    @Inject(method = {"drawChat"}, at = @At("RETURN"))
    private void myau$endModernChat(int updateCounter, CallbackInfo callbackInfo) {
        if (this.myau$translatedChat) {
            GlStateManager.popMatrix();
            this.myau$translatedChat = false;
        }
    }

    @Redirect(
            method = {"drawChat"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiNewChat;drawRect(IIIII)V"
            )
    )
    private void myau$drawChatRect(int left, int top, int right, int bottom, int color) {
        if (!RenderFixes.isChatActive()) {
            Gui.drawRect(left, top, right, bottom, color);
        }
    }

    @ModifyVariable(method = {"getChatComponent"}, at = @At("HEAD"), ordinal = 0, argsOnly = true)
    private int myau$adjustChatComponentMouseX(int mouseX) {
        return RenderFixes.adjustChatMouseX(mouseX);
    }

    @ModifyVariable(method = {"getChatComponent"}, at = @At("HEAD"), ordinal = 1, argsOnly = true)
    private int myau$adjustChatComponentMouseY(int mouseY) {
        return RenderFixes.adjustChatMouseY(mouseY);
    }
}
