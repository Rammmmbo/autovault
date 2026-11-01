package de.example.autovault;

import com.mojang.logging.LogUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.block.entity.vault.VaultSharedData;
import net.minecraft.world.level.block.entity.vault.VaultState;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.lang.reflect.Field;

@Mod(value = AutoVaultMod.MOD_ID, dist = Dist.CLIENT)
public class AutoVaultMod {
    public static final String MOD_ID = "autovault";
    private static final Logger LOG = LogUtils.getLogger();

    public static class Config {
        private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

        public static final ModConfigSpec.BooleanValue HEAVY_CORE = B
                .comment("Open the vault when it shows a Heavy Core")
                .translation("autovault.configuration.heavyCore")
                .define("heavyCore", true);

        public static final ModConfigSpec.BooleanValue WIND_BURST = B
                .comment("Open the vault when it shows a Wind Burst book")
                .translation("autovault.configuration.windBurst")
                .define("windBurst", true);

        public static final ModConfigSpec.BooleanValue TRIDENT = B
                .comment("Open the vault when it shows a Trident")
                .translation("autovault.configuration.trident")
                .define("trident", false);

        public static final ModConfigSpec.BooleanValue NORMAL_VAULTS = B
                .comment("Open normal vaults (Trial Key)")
                .translation("autovault.configuration.normalVaults")
                .define("normalVaults", true);

        public static final ModConfigSpec.BooleanValue OMINOUS_VAULTS = B
                .comment("Open ominous vaults (Ominous Trial Key)")
                .translation("autovault.configuration.ominousVaults")
                .define("ominousVaults", true);

        public static final ModConfigSpec.IntValue MIN_DISPLAY_TICKS = B
                .comment("Item must be shown at least this many ticks in a row before opening (20 ticks = 1 second)")
                .translation("autovault.configuration.minDisplayTicks")
                .defineInRange("minDisplayTicks", 10, 0, 40);

        public static final ModConfigSpec.BooleanValue DRY_RUN = B
                .comment("Test mode: do not click, only show what the mod would open")
                .translation("autovault.configuration.dryRun")
                .define("dryRun", false);

        public static final ModConfigSpec SPEC = B.build();
    }

    private static KeyMapping toggleKey;
    private static boolean enabled = true;
    private static int cooldown = 0;
    private static Field sharedDataField;

    // Logging nach dem Klick
    private static BlockPos watchPos = null;
    private static int watchTicks = 0;
    private static String lastWatched = "";

    // Wie lange wird das aktuelle Item schon angezeigt?
    private static BlockPos trackedPos = null;
    private static ItemStack trackedStack = null;
    private static long trackedSince = 0;

    public AutoVaultMod(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, Config.SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        modBus.addListener(AutoVaultMod::registerKeys);
        NeoForge.EVENT_BUS.addListener(AutoVaultMod::onClientTick);
    }

    private static void registerKeys(RegisterKeyMappingsEvent event) {
        toggleKey = new KeyMapping("key.autovault.toggle", GLFW.GLFW_KEY_G, KeyMapping.Category.MISC);
        event.register(toggleKey);
    }

    private static void resetTrack() {
        trackedPos = null;
        trackedStack = null;
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;

        // Log: was zeigt der Vault nach dem Klick?
        if (watchTicks > 0 && watchPos != null) {
            BlockEntity wbe = mc.level.getBlockEntity(watchPos);
            if (wbe instanceof VaultBlockEntity wv) {
                ItemStack w = getDisplayItem(wv);
                String n = (w == null || w.isEmpty()) ? "leer" : w.getHoverName().getString();
                if (!n.equals(lastWatched)) {
                    LOG.info("[AutoVault] gameTime={} Anzeige wechselt zu: {}", mc.level.getGameTime(), n);
                    lastWatched = n;
                }
            }
            watchTicks--;
        }

        while (toggleKey != null && toggleKey.consumeClick()) {
            enabled = !enabled;
            mc.player.sendOverlayMessage(
                    Component.literal("Auto Vault: " + (enabled ? "AN" : "AUS")));
        }

        if (!enabled) { resetTrack(); return; }
        if (mc.gui.screen() != null) { resetTrack(); return; }

        boolean onCooldown = cooldown > 0;
        if (onCooldown) cooldown--;

        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            resetTrack();
            return;
        }

        BlockPos pos = hit.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (!state.is(Blocks.VAULT) || state.getValue(VaultBlock.STATE) != VaultState.ACTIVE) {
            resetTrack();
            return;
        }

        BlockEntity be = mc.level.getBlockEntity(pos);
        if (!(be instanceof VaultBlockEntity vault)) { resetTrack(); return; }

        ItemStack display = getDisplayItem(vault);
        if (display == null || display.isEmpty()) { resetTrack(); return; }

        // Anzeigedauer verfolgen
        long now = mc.level.getGameTime();
        if (!pos.equals(trackedPos) || trackedStack == null
                || !ItemStack.isSameItemSameComponents(trackedStack, display)) {
            trackedPos = pos;
            trackedStack = display.copy();
            trackedSince = now;
        }
        long age = now - trackedSince;

        if (onCooldown) return;

        boolean ominous = state.getValue(VaultBlock.OMINOUS);
        if (ominous && !Config.OMINOUS_VAULTS.get()) return;
        if (!ominous && !Config.NORMAL_VAULTS.get()) return;

        Item neededKey = ominous ? Items.OMINOUS_TRIAL_KEY : Items.TRIAL_KEY;

        InteractionHand hand;
        if (mc.player.getMainHandItem().is(neededKey)) hand = InteractionHand.MAIN_HAND;
        else if (mc.player.getOffhandItem().is(neededKey)) hand = InteractionHand.OFF_HAND;
        else return;

        if (!isWanted(display)) return;
        if (age < Config.MIN_DISPLAY_TICKS.get()) return;

        String name = display.getHoverName().getString();
        if (Config.DRY_RUN.get()) {
            LOG.info("[AutoVault] gameTime={} TESTMODUS wuerde oeffnen bei: {} (seit {} Ticks)", now, name, age);
            mc.player.sendOverlayMessage(Component.literal("TEST: wuerde öffnen bei: " + name + " (" + age + " Ticks)"));
            cooldown = 10;
            return;
        }

        LOG.info("[AutoVault] gameTime={} KLICK bei: {} (seit {} Ticks)", now, name, age);
        mc.gameMode.useItemOn(mc.player, hand, hit);
        cooldown = 10;
        watchPos = pos;
        watchTicks = 200;
        lastWatched = "";
        resetTrack();
        mc.player.sendOverlayMessage(Component.literal("Auto Vault geöffnet bei: " + name + " (" + age + " Ticks)"));
    }

    private static boolean isWanted(ItemStack stack) {
        if (stack.is(Items.HEAVY_CORE)) return Config.HEAVY_CORE.get();
        if (stack.is(Items.TRIDENT)) return Config.TRIDENT.get();

        if (stack.is(Items.ENCHANTED_BOOK)) {
            if (!Config.WIND_BURST.get()) return false;
            ItemEnchantments stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
            if (stored == null) return false;
            return stored.keySet().stream().anyMatch(h -> h.is(Enchantments.WIND_BURST));
        }
        return false;
    }

    private static ItemStack getDisplayItem(VaultBlockEntity vault) {
        try {
            if (sharedDataField == null) {
                for (Field f : VaultBlockEntity.class.getDeclaredFields()) {
                    if (f.getType() == VaultSharedData.class) {
                        f.setAccessible(true);
                        sharedDataField = f;
                        break;
                    }
                }
                if (sharedDataField == null) return null;
            }
            VaultSharedData data = (VaultSharedData) sharedDataField.get(vault);
            return data.getDisplayItem();
        } catch (Exception e) {
            return null;
        }
    }
}
