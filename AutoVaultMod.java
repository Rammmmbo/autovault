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
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;

@Mod(value = AutoVaultMod.MOD_ID, dist = Dist.CLIENT)
public class AutoVaultMod {
    public static final String MOD_ID = "autovault";

    private static KeyMapping toggleKey;
    private static boolean enabled = true;
    private static int cooldown = 0;
    private static Field sharedDataField;

    public AutoVaultMod(IEventBus modBus) {
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
            mc.player.displayClientMessage(
                    Component.literal("Auto Vault: " + (enabled ? "AN" : "AUS")), true);
        }

        if (!enabled) return;
        if (cooldown > 0) { cooldown--; return; }
        if (mc.screen != null) return;

        // Only the vault under the crosshair
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;

        BlockPos pos = hit.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (!state.is(Blocks.VAULT)) return;

        // Items only rotate in state ACTIVE
        if (state.getValue(VaultBlock.STATE) != VaultState.ACTIVE) return;

        boolean ominous = state.getValue(VaultBlock.OMINOUS);
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
        if (stack.is(Items.HEAVY_CORE)) return true;

        if (stack.is(Items.ENCHANTED_BOOK)) {
            ItemEnchantments stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
            if (stored == null) return false;
            return stored.keySet().stream().anyMatch(h -> h.is(Enchantments.WIND_BURST));
        }
        return false;
    }

    /** Reads the currently displayed item via reflection (field is found by its type). */
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
