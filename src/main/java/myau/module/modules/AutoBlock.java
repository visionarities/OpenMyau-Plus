package myau.module.modules;

import com.google.common.base.CaseFormat;
import myau.Myau;
import myau.enums.BlinkModules;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.MoveInputEvent;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.mixin.IAccessorPlayerControllerMP;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import myau.util.ItemUtil;
import myau.util.PacketUtil;
import myau.util.PlayerUtil;
import myau.util.RotationUtil;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;

import java.util.Random;

public class AutoBlock extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final ModeProperty mode = new ModeProperty("Mode", 0, new String[]{"Hypixel", "Slinky"});
    public final BooleanProperty requireRightClick = new BooleanProperty("RequireRightClick", true);
    public final BooleanProperty requireLeftClick = new BooleanProperty("RequireLeftClick", false, () -> this.mode.getValue() == 1);
    public final BooleanProperty requireDamaged = new BooleanProperty("RequireDamaged", false, () -> this.mode.getValue() == 1);
    public final FloatProperty range = new FloatProperty("Range", 6.0F, 3.0F, 8.0F);
    public final IntProperty fov = new IntProperty("FOV", 360, 30, 360);
    public final FloatProperty cps = new FloatProperty("CPS", 8.0F, 1.0F, 10.0F, () -> this.mode.getValue() == 0);
    public final IntProperty maxHurtTime = new IntProperty("MaxHurtTime", 200, 0, 500, () -> this.mode.getValue() == 1);
    public final IntProperty maxHoldDuration = new IntProperty("MaxHoldDuration", 150, 25, 500, () -> this.mode.getValue() == 1);
    public final BooleanProperty forceBlockAnimation = new BooleanProperty("ForceAnimation", true, () -> this.mode.getValue() == 1);
    public final PercentProperty lagChance = new PercentProperty("LagChance", 0, () -> this.mode.getValue() == 1);
    public final IntProperty lagMaxDuration = new IntProperty("LagMaxDuration", 175, 25, 500, () -> this.mode.getValue() == 1 && this.lagChance.getValue() > 0);
    public final BooleanProperty preventDelayingAttacks = new BooleanProperty("PreventAttackDelay", true, () -> this.mode.getValue() == 1 && this.lagChance.getValue() > 0);
    public final BooleanProperty blockAgainImmediately = new BooleanProperty("BlockAgain", true, () -> this.mode.getValue() == 1 && this.lagChance.getValue() > 0);
    public final BooleanProperty throughWalls = new BooleanProperty("ThroughWalls", true);
    public final BooleanProperty teams = new BooleanProperty("Teams", true);
    public final BooleanProperty botCheck = new BooleanProperty("BotCheck", true);

    private boolean blockingState = false;
    private boolean isBlocking = false;
    private boolean fakeBlockState = false;
    private boolean slinkyHolding = false;
    private boolean slinkyLagging = false;
    private int blockTick = 0;
    private int lastHurtTime = 0;
    private int lastHurtResistantTime = 0;
    private long delayMS = 0L;
    private long blockStartTime = 0L;
    private long lagStartTime = 0L;
    private long lastDamageTime = 0L;
    private final Random random = new Random();

    public AutoBlock() {
        super("AutoBlock", false, false, "Standalone sword autoblock");
    }

    @Override
    public void onDisabled() {
        this.resetState(true);
    }

    @EventTarget(Priority.LOW)
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (event.getType() == EventType.POST) {
            if (this.isPlayerBlocking() && !mc.thePlayer.isBlocking()) {
                ItemStack heldItem = mc.thePlayer.getHeldItem();
                if (heldItem != null) {
                    mc.thePlayer.setItemInUse(heldItem, heldItem.getMaxItemUseDuration());
                }
            }
            return;
        }
        if (event.getType() != EventType.PRE) {
            return;
        }

        this.updateDamageTimers();
        if (this.mode.getValue() == 0) {
            this.onHypixelUpdate();
        } else {
            this.onSlinkyUpdate();
        }
    }

    private void onHypixelUpdate() {
        if (this.delayMS > 0L) {
            this.delayMS -= 50L;
        }

        boolean block = this.canAutoBlock() && this.hasValidTarget();
        if (!block) {
            this.resetBlockingOnly();
            return;
        }

        if (!Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
            switch (this.blockTick) {
                case 0:
                    if (!this.isPlayerBlocking()) {
                        this.sendUseItem();
                    }
                    this.delayMS = (long) (1000.0F / this.cps.getValue());
                    this.blockTick = 1;
                    Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                    Myau.blinkManager.setBlinkState(true, BlinkModules.AUTO_BLOCK);
                    break;
                case 1:
                    if (this.isPlayerBlocking()) {
                        this.spoofNoSlowSlot();
                        this.stopBlock();
                    }
                    if (this.delayMS <= 50L) {
                        this.blockTick = 0;
                    }
                    break;
                default:
                    this.blockTick = 0;
                    break;
            }
        }

        this.isBlocking = true;
        this.fakeBlockState = true;
    }

    private void onSlinkyUpdate() {
        long now = System.currentTimeMillis();
        boolean valid = this.canAutoBlock() && this.hasValidTarget() && this.matchesSlinkyConditions();
        if (!valid) {
            this.resetState(false);
            return;
        }

        if (this.slinkyLagging) {
            if (now - this.lagStartTime >= this.lagMaxDuration.getValue()) {
                this.stopSlinkyLag();
                if (this.blockAgainImmediately.getValue()) {
                    this.startSlinkyBlock(now);
                }
            } else {
                this.isBlocking = false;
                this.fakeBlockState = this.forceBlockAnimation.getValue();
                return;
            }
        }

        if (this.slinkyHolding) {
            this.isBlocking = true;
            this.fakeBlockState = true;
            if (now - this.blockStartTime >= this.maxHoldDuration.getValue()) {
                this.stopSlinkyBlock(now);
            }
            return;
        }

        if (this.shouldStartSlinkyBlock()) {
            this.startSlinkyBlock(now);
        } else {
            this.isBlocking = false;
            this.fakeBlockState = this.forceBlockAnimation.getValue() && this.hasValidTarget();
        }
    }

    @EventTarget
    public void onMove(MoveInputEvent event) {
        if (this.isEnabled() && this.shouldAutoBlock()) {
            mc.thePlayer.movementInput.jump = false;
        }
    }

    @EventTarget(Priority.LOWEST)
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.isCancelled()) {
            return;
        }
        if (this.mode.getValue() == 1
                && this.slinkyLagging
                && this.preventDelayingAttacks.getValue()
                && (event.getPacket() instanceof C02PacketUseEntity || event.getPacket() instanceof C0APacketAnimation)) {
            this.stopSlinkyLag();
        }
        if (event.getPacket() instanceof C07PacketPlayerDigging) {
            C07PacketPlayerDigging packet = (C07PacketPlayerDigging) event.getPacket();
            if (packet.getStatus() == C07PacketPlayerDigging.Action.RELEASE_USE_ITEM) {
                this.blockingState = false;
            }
        }
        if (event.getPacket() instanceof C09PacketHeldItemChange) {
            this.blockingState = false;
            if (this.isBlocking) {
                mc.thePlayer.stopUsingItem();
            }
        }
    }

    public boolean shouldAutoBlock() {
        return this.isPlayerBlocking()
                && this.isBlocking
                && !mc.thePlayer.isInWater()
                && !mc.thePlayer.isInLava();
    }

    public boolean isBlocking() {
        return this.fakeBlockState && ItemUtil.isHoldingSword();
    }

    private boolean canAutoBlock() {
        if (!ItemUtil.isHoldingSword()) {
            return false;
        }
        if (this.requireRightClick.getValue() && !PlayerUtil.isUsingItem()) {
            return false;
        }
        if (mc.currentScreen != null) {
            return false;
        }
        return !Myau.moduleManager.modules.get(Scaffold.class).isEnabled();
    }

    private boolean matchesSlinkyConditions() {
        if (this.requireLeftClick.getValue() && !PlayerUtil.isAttacking()) {
            return false;
        }
        return !this.requireDamaged.getValue() || this.wasRecentlyDamaged();
    }

    private boolean shouldStartSlinkyBlock() {
        if (Myau.playerStateManager.digging || Myau.playerStateManager.placing) {
            return false;
        }
        if (this.requireDamaged.getValue() && !this.wasRecentlyDamaged()) {
            return false;
        }
        if (this.wasRecentlyDamaged()) {
            int timeUntilVulnerable = Math.max(0, mc.thePlayer.hurtResistantTime * 50);
            return timeUntilVulnerable <= this.maxHurtTime.getValue();
        }
        return true;
    }

    private void startSlinkyBlock(long now) {
        if (!this.isPlayerBlocking()) {
            this.sendUseItem();
        }
        this.blockStartTime = now;
        this.slinkyHolding = true;
        this.slinkyLagging = false;
        this.isBlocking = true;
        this.fakeBlockState = true;
        Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
    }

    private void stopSlinkyBlock(long now) {
        boolean shouldLag = this.lagChance.getValue() > 0 && this.random.nextInt(100) < this.lagChance.getValue();
        if (shouldLag) {
            Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
            Myau.blinkManager.setBlinkState(true, BlinkModules.AUTO_BLOCK);
        }
        if (this.isPlayerBlocking()) {
            this.spoofNoSlowSlot();
            this.stopBlock();
        }
        this.slinkyHolding = false;
        this.isBlocking = false;
        this.fakeBlockState = this.forceBlockAnimation.getValue();
        if (shouldLag) {
            this.slinkyLagging = true;
            this.lagStartTime = now;
        }
    }

    private void stopSlinkyLag() {
        if (this.slinkyLagging) {
            Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
            this.slinkyLagging = false;
            this.lagStartTime = 0L;
        }
    }

    private void updateDamageTimers() {
        if (mc.thePlayer.hurtTime > this.lastHurtTime || mc.thePlayer.hurtResistantTime > this.lastHurtResistantTime) {
            this.lastDamageTime = System.currentTimeMillis();
        }
        this.lastHurtTime = mc.thePlayer.hurtTime;
        this.lastHurtResistantTime = mc.thePlayer.hurtResistantTime;
    }

    private boolean wasRecentlyDamaged() {
        return mc.thePlayer.hurtResistantTime > 0 || System.currentTimeMillis() - this.lastDamageTime <= 1000L;
    }

    private boolean hasValidTarget() {
        return mc.theWorld.loadedEntityList.stream().anyMatch(entity ->
                entity instanceof EntityLivingBase && this.isValidTarget((EntityLivingBase) entity));
    }

    private boolean isValidTarget(EntityLivingBase entity) {
        if (!mc.theWorld.loadedEntityList.contains(entity)) {
            return false;
        }
        if (entity == mc.thePlayer || entity == mc.thePlayer.ridingEntity) {
            return false;
        }
        Entity renderView = mc.getRenderViewEntity();
        if (entity == renderView || renderView != null && entity == renderView.ridingEntity) {
            return false;
        }
        if (entity.deathTime > 0 || RotationUtil.distanceToEntity(entity) > this.range.getValue()) {
            return false;
        }
        if (RotationUtil.angleToEntity(entity) > this.fov.getValue().floatValue()) {
            return false;
        }
        if (!this.throughWalls.getValue() && RotationUtil.rayTrace(entity) != null) {
            return false;
        }
        if (!(entity instanceof EntityOtherPlayerMP)) {
            return false;
        }
        EntityPlayer player = (EntityPlayer) entity;
        if (TeamUtil.isFriend(player)) {
            return false;
        }
        if (this.teams.getValue() && TeamUtil.isSameTeam(player)) {
            return false;
        }
        return !this.botCheck.getValue() || !TeamUtil.isBot(player);
    }

    private boolean isPlayerBlocking() {
        return (mc.thePlayer.isUsingItem() || this.blockingState) && ItemUtil.isHoldingSword();
    }

    private void sendUseItem() {
        ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
        this.startBlock(mc.thePlayer.getHeldItem());
    }

    private void startBlock(ItemStack itemStack) {
        if (itemStack == null) {
            return;
        }
        PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(itemStack));
        mc.thePlayer.setItemInUse(itemStack, itemStack.getMaxItemUseDuration());
        this.blockingState = true;
    }

    private void stopBlock() {
        PacketUtil.sendPacket(new C07PacketPlayerDigging(C07PacketPlayerDigging.Action.RELEASE_USE_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN));
        mc.thePlayer.stopUsingItem();
        this.blockingState = false;
    }

    private void spoofNoSlowSlot() {
        if (Myau.moduleManager.modules.get(NoSlow.class).isEnabled()) {
            int randomSlot = this.random.nextInt(9);
            while (randomSlot == mc.thePlayer.inventory.currentItem) {
                randomSlot = this.random.nextInt(9);
            }
            PacketUtil.sendPacket(new C09PacketHeldItemChange(randomSlot));
            PacketUtil.sendPacket(new C09PacketHeldItemChange(mc.thePlayer.inventory.currentItem));
        }
    }

    private void resetBlockingOnly() {
        Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
        this.isBlocking = false;
        this.fakeBlockState = false;
        this.blockTick = 0;
    }

    private void resetState(boolean releaseBlock) {
        Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
        if (releaseBlock && mc.thePlayer != null && this.isPlayerBlocking()) {
            this.stopBlock();
        }
        this.blockingState = false;
        this.isBlocking = false;
        this.fakeBlockState = false;
        this.slinkyHolding = false;
        this.slinkyLagging = false;
        this.blockTick = 0;
        this.delayMS = 0L;
        this.blockStartTime = 0L;
        this.lagStartTime = 0L;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, this.mode.getModeString())};
    }
}
