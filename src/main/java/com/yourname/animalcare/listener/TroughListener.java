package com.yourname.animalcare.listener;

import com.yourname.animalcare.manager.HungerManager;
import com.yourname.animalcare.manager.PenDetectionService;
import com.yourname.animalcare.manager.TroughManager;
import org.bukkit.ChatColor;
import org.bukkit.block.Block;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;

public class TroughListener implements Listener {

    private final FileConfiguration config;
    private final TroughManager troughManager;
    private final HungerManager hungerManager;
    private final PenDetectionService penDetectionService;
    private final boolean debugEnabled;
    private final Material debugTool;

    public TroughListener(FileConfiguration config, TroughManager troughManager, HungerManager hungerManager,
                          PenDetectionService penDetectionService, boolean debugEnabled, Material debugTool) {
        this.config = config;
        this.troughManager = troughManager;
        this.hungerManager = hungerManager;
        this.penDetectionService = penDetectionService;
        this.debugEnabled = debugEnabled;
        this.debugTool = debugTool;
    }

    @EventHandler
    public void onTroughInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        if (!troughManager.isTroughBlock(block.getType())) {
            return;
        }
        Player player = event.getPlayer();
        if (debugEnabled && event.getHand() == EquipmentSlot.HAND && isDebugTool(event.getItem())) {
            handleTroughDebug(player, block);
            event.setCancelled(true);
            return;
        }
        TroughManager.FillResult result = troughManager.handleTroughInteract(player, block, event.getHand());
        switch (result) {
            case ADDED -> {
                event.setCancelled(true);
                sendMessage(player, "messages.trough-filled");
            }
            case CONTAINER_FULL -> {
                event.setCancelled(true);
                sendMessage(player, "messages.trough-full");
            }
            default -> {
                // allow normal interaction/opening when not feeding
            }
        }
    }

    @EventHandler
    public void onEntityDebug(PlayerInteractEntityEvent event) {
        if (!debugEnabled || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        ItemStack held = event.getPlayer().getInventory().getItem(event.getHand());
        if (!isDebugTool(held)) {
            return;
        }
        if (!(event.getRightClicked() instanceof org.bukkit.entity.LivingEntity living)) {
            return;
        }
        if (!hungerManager.isManagedEntity(living)) {
            return;
        }
        PenDetectionService.PenStatus status = penDetectionService.getPenStatus(living);
        int hunger = hungerManager.getHunger(living);
        int max = hungerManager.getMaxHunger();
        event.getPlayer().sendMessage(ChatColor.GOLD + "Tier-Debug:" + ChatColor.GRAY + " Status=" + formatStatus(status)
            + ChatColor.GRAY + " Hunger=" + hunger + "/" + max);
        event.setCancelled(true);
    }

    @EventHandler
    public void onTroughBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!troughManager.isTroughBlock(block.getType())) {
            return;
        }
        troughManager.deactivate(block.getLocation());
    }

    private void sendMessage(Player player, String path) {
        String message = config.getString(path);
        if (message != null && !message.isEmpty()) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
        }
    }

    private void handleTroughDebug(Player player, Block block) {
        TroughManager.TroughDebugInfo info = troughManager.inspectTrough(block);
        if (info == null) {
            player.sendMessage(ChatColor.RED + "Kein aktiver Trog an diesem Block.");
            return;
        }
        double seconds = info.millisUntilNextFeed() / 1000.0D;
        String formatted = String.format(Locale.US, "%.1f", seconds);
        player.sendMessage(ChatColor.GOLD + "Trog-Debug:" + ChatColor.GRAY + " Typ="
            + (info.doubleBarrel() ? "Doppelfass" : "Behälter")
            + ChatColor.GRAY + " Futter=" + info.feedItems()
            + ChatColor.GRAY + " Tiere=" + info.detectedAnimals()
            + ChatColor.GRAY + " Aktiv=" + (info.active() ? "ja" : "nein")
            + ChatColor.GRAY + " nächste Fütterung in=" + formatted + "s");
    }

    private boolean isDebugTool(ItemStack stack) {
        return stack != null && stack.getType() == debugTool;
    }

    private String formatStatus(PenDetectionService.PenStatus status) {
        return switch (status) {
            case CAPTIVE -> "Gefangen";
            case PASTURE -> "Weide";
            default -> "Wildnis";
        };
    }
}
