package com.yourname.animalcare.manager;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
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
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.type.Barrel;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class TroughManager {

    private final JavaPlugin plugin;
    private final HungerManager hungerManager;
    private final PenDetectionService penDetectionService;
    private final Set<Material> troughBlocks;
    private final Set<Material> feedItems;
    private final Map<Material, Integer> feedEnergy;
    private final double feedRadius;
    private final long feedIntervalTicks;
    private final int maxFeedsPerCycle;
    private final String troughNameTag;

    private static final BlockFace[] HORIZONTAL_FACES = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    private final Set<Location> activeTroughs = new HashSet<>();
    private final Map<Location, DoubleBarrelTrough> doubleBarrelTroughs = new HashMap<>();
    private BukkitTask task;
    private long nextFeedRunMillis;

    public TroughManager(JavaPlugin plugin, HungerManager hungerManager, PenDetectionService penDetectionService,
                         FileConfiguration config, Map<Material, Integer> feedEnergy) {
        this.plugin = plugin;
        this.hungerManager = hungerManager;
        this.penDetectionService = penDetectionService;
        ConfigurationSection troughSection = config.getConfigurationSection("trough");
        this.troughBlocks = loadMaterials(troughSection != null ? troughSection.getStringList("blocks") : Arrays.asList("BARREL"));
        this.feedEnergy = Collections.unmodifiableMap(new HashMap<>(feedEnergy));
        Set<Material> configuredFeedItems = loadMaterials(troughSection != null ? troughSection.getStringList("feed-items") : Collections.emptyList());
        if (configuredFeedItems.isEmpty() && !this.feedEnergy.isEmpty()) {
            configuredFeedItems = new HashSet<>(this.feedEnergy.keySet());
        }
        Set<Material> validFeedItems = new HashSet<>();
        for (Material material : configuredFeedItems) {
            if (!this.feedEnergy.containsKey(material)) {
                plugin.getLogger().warning("Feed item " + material + " has no configured energy and will be ignored.");
                continue;
            }
            validFeedItems.add(material);
        }
        this.feedItems = Collections.unmodifiableSet(validFeedItems);
        this.feedRadius = troughSection != null ? troughSection.getDouble("radius", 6.0D) : 6.0D;
        this.feedIntervalTicks = troughSection != null ? troughSection.getLong("feed-interval-ticks", 20L * 10L) : 20L * 10L;
        this.maxFeedsPerCycle = troughSection != null ? troughSection.getInt("max-feed-per-cycle", 3) : 3;
        this.troughNameTag = troughSection != null ? troughSection.getString("name-tag", "[Trough]") : "[Trough]";
        this.nextFeedRunMillis = System.currentTimeMillis() + ticksToMillis(feedIntervalTicks);
    }

    private int consumeFromInventory(Inventory inventory, int requiredEnergy) {
        if (inventory == null || requiredEnergy <= 0) {
            return 0;
        }
        int remaining = requiredEnergy;
        int consumedEnergy = 0;
        for (int slot = 0; slot < inventory.getSize() && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null) {
                continue;
            }
            Material type = stack.getType();
            if (!isFeedItem(type)) {
                continue;
            }
            Integer energy = feedEnergy.get(type);
            if (energy == null || energy <= 0) {
                continue;
            }
            int available = stack.getAmount();
            if (available <= 0) {
                continue;
            }
            int perItem = energy;
            int requiredItems = Math.max(1, (remaining + perItem - 1) / perItem);
            int toConsume = Math.min(available, requiredItems);
            if (toConsume <= 0) {
                continue;
            }
            int newAmount = available - toConsume;
            if (newAmount <= 0) {
                inventory.clear(slot);
            } else {
                ItemStack replacement = stack.clone();
                replacement.setAmount(newAmount);
                inventory.setItem(slot, replacement);
            }
            int energyProvided = toConsume * perItem;
            consumedEnergy += energyProvided;
            remaining = Math.max(0, remaining - energyProvided);
            if (remaining <= 0) {
                break;
            }
        }
        return consumedEnergy;
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
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::processTroughs, feedIntervalTicks, feedIntervalTicks);
        nextFeedRunMillis = System.currentTimeMillis() + ticksToMillis(feedIntervalTicks);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        activeTroughs.clear();
    }

    private long ticksToMillis(long ticks) {
        return Math.max(0L, ticks) * 50L;
    }

    public boolean isTroughBlock(Material material) {
        return troughBlocks.contains(material);
    }

    public boolean isFeedItem(Material material) {
        return feedItems.contains(material) && feedEnergy.containsKey(material);
    }

    public FillResult handleTroughInteract(Player player, Block block, EquipmentSlot hand) {
        TroughStorage storage = resolveTrough(block);
        if (storage == null) {
            return FillResult.NOT_TROUGH;
        }
        if (storage instanceof DoubleBarrelTrough doubleBarrel) {
            doubleBarrel.ensureOpen();
            activeTroughs.add(storage.getKeyLocation());
            return FillResult.NOT_FEED_ITEM;
        }
        ItemStack held = player.getInventory().getItem(hand);
        if (held == null || !isFeedItem(held.getType())) {
            return FillResult.NOT_FEED_ITEM;
        }

        if (!storage.addFeed(held)) {
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
        activeTroughs.add(storage.getKeyLocation());
        feedNearby(storage);
        return FillResult.ADDED;
    }

    public void deactivate(Location location) {
        Location key = location.getBlock().getLocation();
        DoubleBarrelTrough trough = doubleBarrelTroughs.remove(key);
        if (trough != null) {
            removeDoubleBarrelTrough(trough);
            activeTroughs.remove(trough.getKeyLocation());
            return;
        }
        activeTroughs.remove(key);
    }

    private void processTroughs() {
        Set<Location> toProcess = new HashSet<>(activeTroughs);
        for (DoubleBarrelTrough trough : new HashSet<>(doubleBarrelTroughs.values())) {
            if (trough.hasFeed()) {
                toProcess.add(trough.getKeyLocation());
            }
        }

        for (Location location : toProcess) {
            Block block = location.getBlock();
            if (block == null) {
                activeTroughs.remove(location);
                continue;
            }
            TroughStorage storage = resolveTrough(block);
            if (storage == null) {
                activeTroughs.remove(location);
                continue;
            }
            if (!storage.hasFeed()) {
                activeTroughs.remove(storage.getKeyLocation());
                continue;
            }
            feedNearby(storage);
            if (storage.hasFeed()) {
                activeTroughs.add(storage.getKeyLocation());
            } else {
                activeTroughs.remove(storage.getKeyLocation());
            }
        }
        nextFeedRunMillis = System.currentTimeMillis() + ticksToMillis(feedIntervalTicks);
    }

    private void feedNearby(TroughStorage storage) {
        Location middle = storage.getCenterLocation();
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
            int hungerDeficit = hungerManager.getMaxHunger() - hungerManager.getHunger(living);
            if (hungerDeficit <= 0) {
                continue;
            }
            int provided = storage.consumeFeed(hungerDeficit);
            if (provided <= 0) {
                break;
            }
            hungerManager.addHunger(living, provided);
            fed++;
        }
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

    private TroughStorage resolveTrough(Block block) {
        if (block == null) {
            return null;
        }
        if (block.getType() == Material.BARREL) {
            TroughStorage trough = getDoubleBarrelTrough(block);
            if (trough != null) {
                return trough;
            }
        }
        if (block.getState() instanceof Container container) {
            if (!isNamedTrough(container)) {
                return null;
            }
            return new ContainerTrough(block.getLocation(), container);
        }
        return null;
    }

    private DoubleBarrelTrough getDoubleBarrelTrough(Block block) {
        Location location = block.getLocation();
        DoubleBarrelTrough existing = doubleBarrelTroughs.get(location);
        if (existing != null) {
            if (existing.isIntact()) {
                existing.ensureOpen();
                return existing;
            }
            removeDoubleBarrelTrough(existing);
        }
        Block adjacent = findAdjacentBarrel(block);
        if (adjacent == null) {
            return null;
        }
        DoubleBarrelTrough trough = createDoubleBarrelTrough(location, adjacent.getLocation());
        registerDoubleBarrelTrough(trough);
        return trough;
    }

    private Block findAdjacentBarrel(Block origin) {
        Block candidate = null;
        for (BlockFace face : HORIZONTAL_FACES) {
            Block relative = origin.getRelative(face);
            if (relative.getType() != Material.BARREL) {
                continue;
            }
            if (candidate != null) {
                return null;
            }
            candidate = relative;
        }
        return candidate;
    }

    private DoubleBarrelTrough createDoubleBarrelTrough(Location first, Location second) {
        Location a = first.clone();
        Location b = second.clone();
        if (compareLocations(a, b) > 0) {
            Location temp = a;
            a = b;
            b = temp;
        }
        return new DoubleBarrelTrough(a, b);
    }

    private int compareLocations(Location a, Location b) {
        String worldA = Objects.requireNonNull(a.getWorld()).getName();
        String worldB = Objects.requireNonNull(b.getWorld()).getName();
        int result = worldA.compareTo(worldB);
        if (result != 0) {
            return result;
        }
        result = Integer.compare(a.getBlockX(), b.getBlockX());
        if (result != 0) {
            return result;
        }
        result = Integer.compare(a.getBlockY(), b.getBlockY());
        if (result != 0) {
            return result;
        }
        return Integer.compare(a.getBlockZ(), b.getBlockZ());
    }

    private void registerDoubleBarrelTrough(DoubleBarrelTrough trough) {
        doubleBarrelTroughs.put(trough.getPrimary(), trough);
        doubleBarrelTroughs.put(trough.getSecondary(), trough);
    }

    private void removeDoubleBarrelTrough(DoubleBarrelTrough trough) {
        doubleBarrelTroughs.remove(trough.getPrimary());
        doubleBarrelTroughs.remove(trough.getSecondary());
        trough.close();
    }

    private interface TroughStorage {
        Location getKeyLocation();

        Location getCenterLocation();

        boolean addFeed(ItemStack stack);

        boolean hasFeed();

        int consumeFeed(int requiredEnergy);

        int getFeedCount();
    }

    private class ContainerTrough implements TroughStorage {

        private final Location location;
        private final Container container;

        private ContainerTrough(Location location, Container container) {
            this.location = location.getBlock().getLocation();
            this.container = container;
        }

        @Override
        public Location getKeyLocation() {
            return location;
        }

        @Override
        public Location getCenterLocation() {
            return location.clone().add(0.5, 0.5, 0.5);
        }

        @Override
        public boolean addFeed(ItemStack stack) {
            ItemStack single = stack.clone();
            single.setAmount(1);
            if (!container.getInventory().addItem(single).isEmpty()) {
                return false;
            }
            container.update(true, false);
            return true;
        }

        @Override
        public boolean hasFeed() {
            Inventory inventory = container.getInventory();
            for (ItemStack stack : inventory.getContents()) {
                if (stack != null && isFeedItem(stack.getType()) && stack.getAmount() > 0) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public int consumeFeed(int requiredEnergy) {
            if (requiredEnergy <= 0) {
                return 0;
            }
            Inventory inventory = container.getInventory();
            int consumed = consumeFromInventory(inventory, requiredEnergy);
            if (consumed > 0) {
                container.update(true, false);
            }
            return consumed;
        }

        @Override
        public int getFeedCount() {
            int total = 0;
            Inventory inventory = container.getInventory();
            for (ItemStack stack : inventory.getContents()) {
                if (stack != null && isFeedItem(stack.getType())) {
                    total += stack.getAmount();
                }
            }
            return total;
        }
    }

    private class DoubleBarrelTrough implements TroughStorage {

        private final Location primary;
        private final Location secondary;
        private final Location center;

        private DoubleBarrelTrough(Location primary, Location secondary) {
            this.primary = primary.getBlock().getLocation();
            this.secondary = secondary.getBlock().getLocation();
            this.center = computeCenter(primary, secondary);
            ensureOpen();
        }

        private Location computeCenter(Location a, Location b) {
            double x = (a.getBlockX() + b.getBlockX()) / 2.0 + 0.5;
            double y = (a.getBlockY() + b.getBlockY()) / 2.0 + 0.5;
            double z = (a.getBlockZ() + b.getBlockZ()) / 2.0 + 0.5;
            return new Location(Objects.requireNonNull(a.getWorld()), x, y, z);
        }

        boolean isIntact() {
            return isBarrel(primary) && isBarrel(secondary) && areAdjacent(primary, secondary);
        }

        private boolean isBarrel(Location location) {
            return location.getWorld() != null && location.getBlock().getType() == Material.BARREL;
        }

        private boolean areAdjacent(Location first, Location second) {
            if (!Objects.equals(first.getWorld(), second.getWorld())) {
                return false;
            }
            int dx = Math.abs(first.getBlockX() - second.getBlockX());
            int dy = Math.abs(first.getBlockY() - second.getBlockY());
            int dz = Math.abs(first.getBlockZ() - second.getBlockZ());
            return dy == 0 && ((dx == 1 && dz == 0) || (dz == 1 && dx == 0));
        }

        Location getPrimary() {
            return primary;
        }

        Location getSecondary() {
            return secondary;
        }

        @Override
        public Location getKeyLocation() {
            return primary;
        }

        @Override
        public Location getCenterLocation() {
            return center.clone();
        }

        @Override
        public boolean addFeed(ItemStack stack) {
            ItemStack single = stack.clone();
            single.setAmount(1);
            if (tryAddToContainer(primary, single)) {
                return true;
            }
            return tryAddToContainer(secondary, single.clone());
        }

        @Override
        public boolean hasFeed() {
            return getFeedCount() > 0;
        }

        @Override
        public int consumeFeed(int requiredEnergy) {
            if (requiredEnergy <= 0) {
                return 0;
            }
            int consumed = consumeFromContainer(primary, requiredEnergy);
            int remaining = requiredEnergy - consumed;
            if (remaining > 0) {
                consumed += consumeFromContainer(secondary, remaining);
            }
            if (consumed > 0) {
                ensureOpen();
            }
            return consumed;
        }

        void close() {
            setBarrelOpen(primary, false);
            setBarrelOpen(secondary, false);
        }

        private boolean tryAddToContainer(Location location, ItemStack item) {
            Container container = getContainer(location);
            if (container == null) {
                return false;
            }
            Map<Integer, ItemStack> overflow = container.getInventory().addItem(item);
            if (!overflow.isEmpty()) {
                return false;
            }
            container.update(true, false);
            ensureOpen();
            return true;
        }

        private int consumeFromContainer(Location location, int requiredEnergy) {
            Container container = getContainer(location);
            if (container == null) {
                return 0;
            }
            Inventory inventory = container.getInventory();
            int consumed = consumeFromInventory(inventory, requiredEnergy);
            if (consumed > 0) {
                container.update(true, false);
            }
            return consumed;
        }

        private Container getContainer(Location location) {
            if (location.getWorld() == null) {
                return null;
            }
            BlockState state = location.getBlock().getState();
            if (state instanceof Container container) {
                return container;
            }
            return null;
        }

        @Override
        public int getFeedCount() {
            return countInventory(primary) + countInventory(secondary);
        }

        private int countInventory(Location location) {
            Container container = getContainer(location);
            if (container == null) {
                return 0;
            }
            int total = 0;
            for (ItemStack stack : container.getInventory().getContents()) {
                if (stack != null && isFeedItem(stack.getType())) {
                    total += stack.getAmount();
                }
            }
            return total;
        }

        void ensureOpen() {
            setBarrelOpen(primary, true);
            setBarrelOpen(secondary, true);
        }

        private void setBarrelOpen(Location location, boolean open) {
            if (location.getWorld() == null) {
                return;
            }
            Block block = location.getBlock();
            if (block.getType() != Material.BARREL) {
                return;
            }
            BlockData data = block.getBlockData();
            if (data instanceof Barrel barrelData) {
                if (barrelData.isOpen() != open) {
                    barrelData.setOpen(open);
                    block.setBlockData(barrelData, false);
                }
                return;
            }
            if (data instanceof Openable openable) {
                if (openable.isOpen() != open) {
                    openable.setOpen(open);
                    block.setBlockData(data, false);
                }
            }
        }
    }

    public enum FillResult {
        NOT_TROUGH,
        NOT_FEED_ITEM,
        CONTAINER_FULL,
        ADDED
    }

    public TroughDebugInfo inspectTrough(Block block) {
        TroughStorage storage = resolveTrough(block);
        if (storage == null) {
            return null;
        }
        int feedCount = storage.getFeedCount();
        int detected = 0;
        Location middle = storage.getCenterLocation();
        if (middle.getWorld() != null) {
            for (Entity entity : middle.getWorld().getNearbyEntities(middle, feedRadius, feedRadius, feedRadius)) {
                if (!(entity instanceof LivingEntity living)) {
                    continue;
                }
                if (!hungerManager.isManagedEntity(living)) {
                    continue;
                }
                if (penDetectionService.getPenStatus(living) == PenDetectionService.PenStatus.WILD) {
                    continue;
                }
                detected++;
            }
        }
        long millisUntilNext = Math.max(0L, nextFeedRunMillis - System.currentTimeMillis());
        boolean doubleBarrel = storage instanceof DoubleBarrelTrough;
        boolean active = activeTroughs.contains(storage.getKeyLocation());
        return new TroughDebugInfo(storage.getKeyLocation(), doubleBarrel, feedCount, detected, millisUntilNext, active);
    }

    public long getNextFeedRunMillis() {
        return nextFeedRunMillis;
    }

    public record TroughDebugInfo(Location keyLocation, boolean doubleBarrel, int feedItems,
                                   int detectedAnimals, long millisUntilNextFeed, boolean active) {
    }
}
