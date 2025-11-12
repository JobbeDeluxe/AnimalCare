package com.yourname.animalcare.listener;

import com.yourname.animalcare.manager.HungerManager;
import com.yourname.animalcare.manager.PenDetectionService;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Animals;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class FeedListener implements Listener {

    private final FileConfiguration config;
    private final HungerManager hungerManager;
    private final PenDetectionService penDetectionService;
    private final Set<String> allowedItems;
    private final Map<Material, Integer> feedEnergy;

    public FeedListener(FileConfiguration config, HungerManager hungerManager, PenDetectionService penDetectionService,
                       Map<Material, Integer> feedEnergy) {
        this.config = config;
        this.hungerManager = hungerManager;
        this.penDetectionService = penDetectionService;
        this.feedEnergy = Collections.unmodifiableMap(new HashMap<>(feedEnergy));
        this.allowedItems = new HashSet<>(config.getStringList("feeding.hand-feed-items").stream()
            .map(String::toUpperCase)
            .collect(Collectors.toSet()));
        if (this.allowedItems.isEmpty()) {
            this.allowedItems.addAll(config.getStringList("trough.feed-items").stream().map(String::toUpperCase).collect(Collectors.toSet()));
        }
    }

    @EventHandler
    public void onEntityFeed(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (!(event.getRightClicked() instanceof LivingEntity living)) {
            return;
        }
        if (!hungerManager.isManagedEntity(living)) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        EquipmentSlot hand = event.getHand();
        ItemStack item = player.getInventory().getItem(hand);
        if (item == null) {
            sendMessage(player, "messages.wrong-item", "%entity%", readableName(living));
            return;
        }
        Material material = item.getType();
        if (!allowedItems.contains(material.name())) {
            sendMessage(player, "messages.wrong-item", "%entity%", readableName(living));
            return;
        }
        Integer energy = feedEnergy.get(material);
        if (energy == null || energy <= 0) {
            sendMessage(player, "messages.wrong-item", "%entity%", readableName(living));
            return;
        }
        PenDetectionService.PenStatus status = penDetectionService.getPenStatus(living);
        if (status == PenDetectionService.PenStatus.WILD) {
            sendMessage(player, "messages.not-in-pen", "%entity%", readableName(living));
            return;
        }
        if (hungerManager.getHunger(living) >= hungerManager.getMaxHunger()) {
            sendMessage(player, "messages.not-hungry", "%entity%", readableName(living));
            return;
        }
        int hungerBefore = hungerManager.getHunger(living);
        int hunger = hungerManager.addHunger(living, energy);
        decrementItem(player, hand, item);
        sendMessage(player, "messages.feed-success", "%entity%", readableName(living));
        if (hungerBefore < hungerManager.getMaxHunger() && hunger >= hungerManager.getMaxHunger() && living instanceof Animals animals) {
            animals.setLoveModeTicks(600);
            animals.setBreedCause(player.getUniqueId());
        }
    }

    private void decrementItem(Player player, EquipmentSlot hand, ItemStack stack) {
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        int newAmount = stack.getAmount() - 1;
        if (newAmount <= 0) {
            player.getInventory().setItem(hand, null);
        } else {
            stack.setAmount(newAmount);
        }
    }

    private void sendMessage(Player player, String path, String placeholder, String replacement) {
        String message = config.getString(path);
        if (message != null && !message.isEmpty()) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', message.replace(placeholder, replacement)));
        }
    }

    private String readableName(LivingEntity entity) {
        return ChatColor.stripColor(entity.getType().name().toLowerCase().replace('_', ' '));
    }
}
