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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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

        // Abschnitt "Allgemein"
        public static final ModConfigSpec.BooleanValue ENABLED;
        public static final ModConfigSpec.BooleanValue OPEN_NORMAL;
        public static final ModConfigSpec.BooleanValue OPEN_OMINOUS;

        // Abschnitt "Filter"
        public static final ModConfigSpec.BooleanValue USE_FILTER;
        public static final ModConfigSpec.BooleanValue TRIDENT;
        public static final ModConfigSpec.BooleanValue MACE;
        public static final ModConfigSpec.BooleanValue CORE;
        public static final ModConfigSpec.BooleanValue BOOK;
        public static final ModConfigSpec.BooleanValue WIND_BURST_ONLY;
        public static final ModConfigSpec.ConfigValue<String> CUSTOM_ITEMS;

        // Abschnitt "Statistik"
        public static final ModConfigSpec.IntValue TOTAL_OPENS;
        public static final ModConfigSpec.IntValue HITS;

        public static final ModConfigSpec SPEC;

        static {
            B.comment("General settings").translation("autovault.configuration.general").push("general");
            ENABLED = B
                    .comment("Enable or disable the auto-opener mod entirely")
                    .translation("autovault.configuration.enabled")
                    .define("enabled", true);
            OPEN_NORMAL = B
                    .comment("Allow auto-opening normal trial vaults")
                    .translation("autovault.configuration.openNormal")
                    .define("openNormal", true);
            OPEN_OMINOUS = B
                    .comment("Allow auto-opening ominous trial vaults")
                    .translation("autovault.configuration.openOminous")
                    .define("openOminous", true);
            B.pop();

            B.comment("Which items should open a vault").translation("autovault.configuration.filter").push("filter");
            USE_FILTER = B
                    .comment("If ON, vaults only open if they show a selected item. If OFF, every vault opens.")
                    .translation("autovault.configuration.useFilter")
                    .define("useFilter", true);
            CORE = B
                    .comment("Open for Heavy Core")
                    .translation("autovault.configuration.core")
                    .define("heavyCore", true);
            BOOK = B
                    .comment("Open for Enchanted Books")
                    .translation("autovault.configuration.book")
                    .define("enchantedBook", true);
            WIND_BURST_ONLY = B
                    .comment("Enchanted Books only count if they have Wind Burst")
                    .translation("autovault.configuration.windBurstOnly")
                    .define("windBurstOnly", true);
            TRIDENT = B
                    .comment("Open for Trident")
                    .translation("autovault.configuration.trident")
                    .define("trident", true);
            MACE = B
                    .comment("Open for Mace")
                    .translation("autovault.configuration.mace")
                    .define("mace", true);
            CUSTOM_ITEMS = B
                    .comment("Custom items: IDs separated by commas, e.g. minecraft:diamond,minecraft:emerald")
                    .translation("autovault.configuration.customItems")
                    .define("customItems", "");
            B.pop();

            B.comment("Counts how often the opened vault really gave the item. Set to 0 to reset.")
                    .translation("autovault.configuration.statistics").push("statistics");
            TOTAL_OPENS = B
                    .comment("Number of vaults opened by the mod")
                    .translation("autovault.configuration.totalOpens")
                    .defineInRange("totalOpens", 0, 0, Integer.MAX_VALUE);
            HITS = B
                    .comment("How often the vault really gave the item it was opened for")
                    .translation("autovault.configuration.hits")
                    .defineInRange("hits", 0, 0, Integer.MAX_VALUE);
            B.pop();

            SPEC = B.build();
        }
    }

    private static ModContainer modContainer;
    private static KeyMapping toggleKey;
    private static KeyMapping openGuiKey;
    private static int cooldown = 0;
    private static Field sharedDataField;

    // Auswertung nach dem Klick: was wirft der Vault wirklich aus?
    private static boolean watching = false;
    private static BlockPos watchPos = null;
    private static ItemStack triggerStack = ItemStack.EMPTY;
    private static int watchElapsed = 0;
    private static boolean matched = false;
    private static String lastSeen = "";
    private static final List<String> seen = new ArrayList<>();

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

        if (watching) trackEjection(mc);

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

        if (watching) return; // erst das letzte Ergebnis auswerten

        InteractionHand hand = findKeyHand(mc, ominous);
        if (hand == null) return;

        if (!passesFilter(display)) return;

        LOG.info("[AutoVault] KLICK bei: {}", display.getHoverName().getString());
        InteractionResult result = mc.gameMode.useItemOn(mc.player, hand, hit);
        if (result.consumesAction()) {
            mc.player.swing(hand);
        }
        cooldown = 5;

        // Auswertung starten
        watching = true;
        watchPos = pos;
        triggerStack = display.copy();
        watchElapsed = 0;
        matched = false;
        lastSeen = "";
        seen.clear();
        mc.player.sendOverlayMessage(Component.translatable("autovault.hud.opened", display.getHoverName()));
    }

    /** Beobachtet, was der Vault nach dem Klick wirklich auswirft, und zaehlt die Treffer. */
    private static void trackEjection(Minecraft mc) {
        watchElapsed++;
        if (watchElapsed < 2) return; // die ersten Ticks zeigen noch die alte Anzeige

        BlockEntity be = mc.level.getBlockEntity(watchPos);
        ItemStack cur = (be instanceof VaultBlockEntity v) ? getDisplayItem(v) : null;
        boolean empty = cur == null || cur.isEmpty();

        if (!empty) {
            String name = cur.getHoverName().getString();
            if (seen.isEmpty() || !name.equals(lastSeen)) {
                seen.add(name);
                lastSeen = name;
                if (matchesTrigger(cur)) matched = true;
            }
        }

        if ((empty && !seen.isEmpty()) || watchElapsed > 200) {
            finishTracking(mc);
        }
    }

    private static boolean matchesTrigger(ItemStack cur) {
        if (!cur.is(triggerStack.getItem())) return false;
        if (cur.is(Items.ENCHANTED_BOOK) && hasWindBurst(triggerStack)) return hasWindBurst(cur);
        return true;
    }

    private static void finishTracking(Minecraft mc) {
        watching = false;
        if (seen.isEmpty()) return;

        int total = Config.TOTAL_OPENS.get() + 1;
        int hits = Config.HITS.get() + (matched ? 1 : 0);
        Config.TOTAL_OPENS.set(total);
        Config.HITS.set(hits);
        Config.TOTAL_OPENS.save();

        String wanted = triggerStack.getHoverName().getString();
        LOG.info("[AutoVault] ERGEBNIS gewollt={} erhalten={} treffer={} ({}/{})",
                wanted, seen, matched, hits, total);
        mc.player.sendOverlayMessage(Component.translatable(
                matched ? "autovault.stats.hit" : "autovault.stats.miss", wanted, hits, total));
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
