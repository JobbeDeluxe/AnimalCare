package com.yourname.animalcare.manager;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

public class TroughManager {

    private final JavaPlugin plugin;
    private final HungerManager hungerManager;
    private final PenDetectionService penDetectionService;
    private final Set<Material> troughBlocks;
    private final Set<Material> feedItems;
    private final double feedRadius;
    private final long feedInterval;
    private final int maxFeedsPerCycle;
    private final String troughNameTag;

    private final Set<Location> activeTroughs = new HashSet<>();
    private BukkitTask task;

    public TroughManager(JavaPlugin plugin, HungerManager hungerManager, PenDetectionService penDetectionService, FileConfiguration config) {
        this.plugin = plugin;
        this.hungerManager = hungerManager;
        this.penDetectionService = penDetectionService;
        ConfigurationSection troughSection = config.getConfigurationSection("trough");
        this.troughBlocks = loadMaterials(troughSection != null ? troughSection.getStringList("blocks") : Collections.singletonList("COMPOSTER"));
        this.feedItems = loadMaterials(troughSection != null ? troughSection.getStringList("feed-items") : Collections.singletonList("WHEAT"));
        this.feedRadius = troughSection != null ? troughSection.getDouble("radius", 6.0D) : 6.0D;
        this.feedInterval = troughSection != null ? troughSection.getLong("feed-interval-ticks", 20L * 10L) : 20L * 10L;
        this.maxFeedsPerCycle = troughSection != null ? troughSection.getInt("max-feed-per-cycle", 3) : 3;
        this.troughNameTag = troughSection != null ? troughSection.getString("name-tag", "[Trough]") : "[Trough]";
    }

    private Set<Material> loadMaterials(Iterable<String> values) {
        if (values == null) {
            return Collections.emptySet();
        }
        Set<Material> set = new HashSet<>();
        for (String value : values) {
            try {
                set.add(Material.valueOf(value.toUpperCase()));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Unknown material in trough configuration: " + value);
            }
        }
        return Collections.unmodifiableSet(set);
    }

    public void start() {
        if (task != null) {
            task.cancel();
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::processTroughs, feedInterval, feedInterval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        activeTroughs.clear();
    }

    public boolean isTroughBlock(Material material) {
        return troughBlocks.contains(material);
    }

    public boolean isFeedItem(Material material) {
        return feedItems.contains(material);
    }

    public FillResult handleTroughInteract(Player player, Block block, EquipmentSlot hand) {
        if (!isTroughBlock(block.getType())) {
            return FillResult.NOT_TROUGH;
        }
        Container container = getContainer(block);
        if (container == null || !isNamedTrough(container)) {
            return FillResult.NOT_TROUGH;
        }
        ItemStack held = player.getInventory().getItem(hand);
        if (held == null || !isFeedItem(held.getType())) {
            return FillResult.NOT_FEED_ITEM;
        }

        ItemStack single = held.clone();
        single.setAmount(1);
        if (!container.getInventory().addItem(single).isEmpty()) {
            return FillResult.CONTAINER_FULL;
        }

        if (player.getGameMode() != GameMode.CREATIVE) {
            int amount = held.getAmount() - 1;
            if (amount <= 0) {
                player.getInventory().setItem(hand, null);
            } else {
                held.setAmount(amount);
            }
        }
        container.update(true, false);
        Location loc = block.getLocation();
        activeTroughs.add(loc);
        feedNearby(loc, container);
        return FillResult.ADDED;
    }

    public void deactivate(Location location) {
        activeTroughs.remove(location.getBlock().getLocation());
    }

    private void processTroughs() {
        Iterator<Location> iterator = activeTroughs.iterator();
        while (iterator.hasNext()) {
            Location location = iterator.next();
            Container container = getContainer(location.getBlock());
            if (container == null || !hasFeed(container)) {
                iterator.remove();
                continue;
            }
            feedNearby(location, container);
            if (!hasFeed(container)) {
                iterator.remove();
            }
        }
    }

    private void feedNearby(Location center, Container container) {
        Location middle = center.clone().add(0.5, 0.5, 0.5);
        if (middle.getWorld() == null) {
            return;
        }
        int fed = 0;
        for (Entity entity : middle.getWorld().getNearbyEntities(middle, feedRadius, feedRadius, feedRadius)) {
            if (fed >= maxFeedsPerCycle) {
                break;
            }
            if (!(entity instanceof LivingEntity living)) {
                continue;
            }
            if (!hungerManager.isManagedEntity(living)) {
                continue;
            }
            if (penDetectionService.getPenStatus(living) == PenDetectionService.PenStatus.WILD) {
                continue;
            }
            if (!consumeFeed(container)) {
                break;
            }
            hungerManager.feedEntity(living);
            fed++;
        }
    }

    private boolean hasFeed(Container container) {
        Inventory inventory = container.getInventory();
        for (ItemStack stack : inventory.getContents()) {
            if (stack != null && feedItems.contains(stack.getType()) && stack.getAmount() > 0) {
                return true;
            }
        }
        return false;
    }

    private boolean consumeFeed(Container container) {
        Inventory inventory = container.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || !feedItems.contains(stack.getType())) {
                continue;
            }
            int newAmount = stack.getAmount() - 1;
            if (newAmount <= 0) {
                inventory.setItem(slot, null);
            } else {
                stack.setAmount(newAmount);
                inventory.setItem(slot, stack);
            }
            container.update(true, false);
            return true;
        }
        return false;
    }

    private boolean isNamedTrough(Container container) {
        if (troughNameTag == null || troughNameTag.isEmpty()) {
            return true;
        }
        if (container.getCustomName() == null) {
            return false;
        }
        return troughNameTag.equalsIgnoreCase(ChatColor.stripColor(container.getCustomName()));
    }

    private Container getContainer(Block block) {
        if (!(block.getState() instanceof Container container)) {
            return null;
        }
        return container;
    }

    public enum FillResult {
        NOT_TROUGH,
        NOT_FEED_ITEM,
        CONTAINER_FULL,
        ADDED
    }
}
