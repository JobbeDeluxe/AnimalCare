package com.yourname.animalcare.listener;

import com.yourname.animalcare.manager.TroughManager;
import org.bukkit.ChatColor;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;

public class TroughListener implements Listener {

    private final FileConfiguration config;
    private final TroughManager troughManager;

    public TroughListener(FileConfiguration config, TroughManager troughManager) {
        this.config = config;
        this.troughManager = troughManager;
    }

    @EventHandler
    public void onTroughInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        Block block = event.getClickedBlock();
        if (!troughManager.isTroughBlock(block.getType())) {
            return;
        }
        Player player = event.getPlayer();
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
}
