package de.example.autovault;

import com.mojang.logging.LogUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
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
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Auto Vault fuer NeoForge 26.2 (nur Client).
 * Nachbau der Funktionen der Fabric-Mod "Auto Vault" von Fix85 (MIT-Lizenz).
 */
@Mod(value = AutoVaultMod.MOD_ID, dist = Dist.CLIENT)
public class AutoVaultMod {
    public static final String MOD_ID = "autovault";
    private static final Logger LOG = LogUtils.getLogger();

    public static class Config {
        private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

        public static final ModConfigSpec.BooleanValue ENABLED = B
                .comment("Enable or disable the auto-opener mod entirely")
                .translation("autovault.configuration.enabled")
                .define("enabled", true);

        public static final ModConfigSpec.BooleanValue OPEN_NORMAL = B
                .comment("Allow auto-opening normal trial vaults")
                .translation("autovault.configuration.openNormal")
                .define("openNormal", true);

        public static final ModConfigSpec.BooleanValue OPEN_OMINOUS = B
                .comment("Allow auto-opening ominous trial vaults")
                .translation("autovault.configuration.openOminous")
                .define("openOminous", true);

        public static final ModConfigSpec.BooleanValue USE_FILTER = B
                .comment("If ON, vaults only open if they show a selected preset or custom item. If OFF, every vault opens.")
                .translation("autovault.configuration.useFilter")
                .define("useFilter", true);

        public static final ModConfigSpec.BooleanValue WIND_BURST_ONLY = B
                .comment("Enchanted Books only count if they have the Wind Burst enchantment")
                .translation("autovault.configuration.windBurstOnly")
                .define("windBurstOnly", true);

        public static final ModConfigSpec.BooleanValue TRIDENT = B
                .comment("Preset: Trident")
                .translation("autovault.configuration.trident")
                .define("trident", true);

        public static final ModConfigSpec.BooleanValue MACE = B
                .comment("Preset: Mace")
                .translation("autovault.configuration.mace")
                .define("mace", true);

        public static final ModConfigSpec.BooleanValue CORE = B
                .comment("Preset: Heavy Core")
                .translation("autovault.configuration.core")
                .define("heavyCore", true);

        public static final ModConfigSpec.BooleanValue BOOK = B
                .comment("Preset: Enchanted Book")
                .translation("autovault.configuration.book")
                .define("enchantedBook", true);

        public static final ModConfigSpec.ConfigValue<String> CUSTOM_ITEMS = B
                .comment("Custom filter: item IDs separated by commas, e.g. minecraft:diamond,minecraft:emerald")
                .translation("autovault.configuration.customItems")
                .define("customItems", "");

        public static final ModConfigSpec SPEC = B.build();
    }

    private static ModContainer modContainer;
    private static KeyMapping toggleKey;
    private static KeyMapping openGuiKey;
    private static int cooldown = 0;
    private static Field sharedDataField;

    public AutoVaultMod(IEventBus modBus, ModContainer container) {
        modContainer = container;
        container.registerConfig(ModConfig.Type.CLIENT, Config.SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        modBus.addListener(AutoVaultMod::registerKeys);
        NeoForge.EVENT_BUS.addListener(AutoVaultMod::onClientTick);
    }

    private static void registerKeys(RegisterKeyMappingsEvent event) {
        toggleKey = new KeyMapping("key.autovault.toggle", GLFW.GLFW_KEY_G, KeyMapping.Category.MISC);
        openGuiKey = new KeyMapping("key.autovault.open_gui", GLFW.GLFW_KEY_H, KeyMapping.Category.MISC);
        event.register(toggleKey);
        event.register(openGuiKey);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;

        while (toggleKey != null && toggleKey.consumeClick()) {
            boolean now = !Config.ENABLED.get();
            Config.ENABLED.set(now);
            Config.ENABLED.save();
            mc.player.sendOverlayMessage(Component.translatable(
                    "autovault.chat.toggle", Component.literal(now ? "§aON§r" : "§cOFF§r")));
        }

        while (openGuiKey != null && openGuiKey.consumeClick()) {
            openConfigScreen(mc);
        }

        if (!Config.ENABLED.get()) return;
        if (cooldown > 0) { cooldown--; return; }
        if (mc.gui.screen() != null) return;

        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;

        BlockPos pos = hit.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (!(state.getBlock() instanceof VaultBlock)) return;
        if (state.getValue(VaultBlock.STATE) != VaultState.ACTIVE) return;

        boolean ominous = state.getValue(VaultBlock.OMINOUS);
        if (ominous && !Config.OPEN_OMINOUS.get()) return;
        if (!ominous && !Config.OPEN_NORMAL.get()) return;

        BlockEntity be = mc.level.getBlockEntity(pos);
        if (!(be instanceof VaultBlockEntity vault)) return;

        ItemStack display = getDisplayItem(vault);
        if (display == null || display.isEmpty()) return;

        // Anzeige "In Vault: ..." ueber der Hotbar
        boolean windBurstBook = display.is(Items.ENCHANTED_BOOK) && hasWindBurst(display);
        mc.player.sendOverlayMessage(Component.translatable(
                windBurstBook ? "autovault.hud.spinning_item_wind_burst" : "autovault.hud.spinning_item",
                display.getHoverName()));

        InteractionHand hand = findKeyHand(mc, ominous);
        if (hand == null) return;

        if (!passesFilter(display)) return;

        LOG.info("[AutoVault] KLICK bei: {}", display.getHoverName().getString());
        InteractionResult result = mc.gameMode.useItemOn(mc.player, hand, hit);
        if (result.consumesAction()) {
            mc.player.swing(hand);
        }
        cooldown = 5;
    }

    private static InteractionHand findKeyHand(Minecraft mc, boolean ominous) {
        Item key = ominous ? Items.OMINOUS_TRIAL_KEY : Items.TRIAL_KEY;
        if (mc.player.getMainHandItem().is(key)) return InteractionHand.MAIN_HAND;
        if (mc.player.getOffhandItem().is(key)) return InteractionHand.OFF_HAND;
        return null;
    }

    private static boolean passesFilter(ItemStack stack) {
        if (!Config.USE_FILTER.get()) return true;

        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (!buildFilter().contains(id)) return false;

        if (id.equals("minecraft:enchanted_book") && Config.WIND_BURST_ONLY.get()) {
            return hasWindBurst(stack);
        }
        return true;
    }

    private static Set<String> buildFilter() {
        Set<String> f = new LinkedHashSet<>();
        if (Config.TRIDENT.get()) f.add("minecraft:trident");
        if (Config.MACE.get()) f.add("minecraft:mace");
        if (Config.CORE.get()) f.add("minecraft:heavy_core");
        if (Config.BOOK.get()) f.add("minecraft:enchanted_book");
        for (String part : Config.CUSTOM_ITEMS.get().split(",")) {
            String s = part.trim();
            if (s.isEmpty()) continue;
            f.add(s.contains(":") ? s : "minecraft:" + s);
        }
        return f;
    }

    private static boolean hasWindBurst(ItemStack stack) {
        ItemEnchantments stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
        if (stored == null) return false;
        return stored.keySet().stream().anyMatch(h -> h.is(Enchantments.WIND_BURST));
    }

    /** Oeffnet die Einstellungen (setScreen per Reflection, damit es in 26.x sicher kompiliert). */
    private static void openConfigScreen(Minecraft mc) {
        Screen screen = new ConfigurationScreen(modContainer, mc.gui.screen());
        for (Object target : new Object[]{mc, mc.gui}) {
            try {
                target.getClass().getMethod("setScreen", Screen.class).invoke(target, screen);
                return;
            } catch (Exception ignored) {
                // naechsten Versuch probieren
            }
        }
        mc.player.sendOverlayMessage(Component.literal("Auto Vault: Einstellungen unter Mods > Auto Vault > Config"));
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
            if (!data.hasDisplayItem()) return null;
            return data.getDisplayItem();
        } catch (Exception e) {
            return null;
        }
    }
}
