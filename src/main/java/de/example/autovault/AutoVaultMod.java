package de.example.autovault;

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

import java.lang.reflect.Field;

@Mod(value = AutoVaultMod.MOD_ID, dist = Dist.CLIENT)
public class AutoVaultMod {
    public static final String MOD_ID = "autovault";

    /** Einstellungen: true = Vault wird bei diesem Item geoeffnet, false = nicht. */
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

        public static final ModConfigSpec.BooleanValue MACE = B
                .comment("Open the vault when it shows a Mace")
                .translation("autovault.configuration.mace")
                .define("mace", false);

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

        public static final ModConfigSpec SPEC = B.build();
    }

    private static KeyMapping toggleKey;
    private static boolean enabled = true;
    private static int cooldown = 0;
    private static Field sharedDataField;

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

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;

        while (toggleKey != null && toggleKey.consumeClick()) {
            enabled = !enabled;
            mc.player.sendOverlayMessage(
                    Component.literal("Auto Vault: " + (enabled ? "AN" : "AUS")));
        }

        if (!enabled) return;
        if (cooldown > 0) { cooldown--; return; }
        if (mc.gui.screen() != null) return;

        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;

        BlockPos pos = hit.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (!state.is(Blocks.VAULT)) return;
        if (state.getValue(VaultBlock.STATE) != VaultState.ACTIVE) return;

        boolean ominous = state.getValue(VaultBlock.OMINOUS);
        if (ominous && !Config.OMINOUS_VAULTS.get()) return;
        if (!ominous && !Config.NORMAL_VAULTS.get()) return;

        Item neededKey = ominous ? Items.OMINOUS_TRIAL_KEY : Items.TRIAL_KEY;

        InteractionHand hand;
        if (mc.player.getMainHandItem().is(neededKey)) hand = InteractionHand.MAIN_HAND;
        else if (mc.player.getOffhandItem().is(neededKey)) hand = InteractionHand.OFF_HAND;
        else return;

        BlockEntity be = mc.level.getBlockEntity(pos);
        if (!(be instanceof VaultBlockEntity vault)) return;

        ItemStack display = getDisplayItem(vault);
        if (display == null || display.isEmpty()) return;

        if (isWanted(display)) {
            mc.gameMode.useItemOn(mc.player, hand, hit);
            cooldown = 10;
        }
    }

    private static boolean isWanted(ItemStack stack) {
        if (stack.is(Items.HEAVY_CORE)) return Config.HEAVY_CORE.get();
        if (stack.is(Items.MACE)) return Config.MACE.get();
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
