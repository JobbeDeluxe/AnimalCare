package com.yourname.animalcare.manager;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class PenDetectionService {

    public enum PenStatus {
        WILD,
        CAPTIVE,
        PASTURE
    }

    public static class PenInfo {
        private final PenStatus status;
        private final BoundingBox boundingBox;
        private final int width;
        private final int length;

        public PenInfo(PenStatus status, BoundingBox boundingBox, int width, int length) {
            this.status = status;
            this.boundingBox = boundingBox;
            this.width = width;
            this.length = length;
        }

        public PenStatus getStatus() {
            return status;
        }

        public BoundingBox getBoundingBox() {
            return boundingBox;
        }

        public int getWidth() {
            return width;
        }

        public int getLength() {
            return length;
        }
    }

    private final JavaPlugin plugin;
    private final Set<EntityType> trackedTypes;
    private final int detectionRadius;
    private final int minPenSize;
    private final Set<Material> bypassBlocks;
    private final long scanInterval;

    private BukkitTask task;
    private final Map<UUID, PenInfo> cachedPenInfo = new HashMap<>();

    public PenDetectionService(JavaPlugin plugin, FileConfiguration config) {
        this.plugin = plugin;
        this.trackedTypes = loadEntityTypes(config.getStringList("entities"));
        ConfigurationSection penSection = config.getConfigurationSection("pen");
        this.detectionRadius = penSection != null ? penSection.getInt("detection-radius", 15) : 15;
        this.minPenSize = penSection != null ? penSection.getInt("min-pen-size-xz", 10) : 10;
        this.bypassBlocks = loadMaterials(penSection != null ? penSection.getStringList("ignore-blocks") : Collections.emptyList());
        this.scanInterval = penSection != null ? penSection.getLong("scan-interval-ticks", 20L * 60L) : 20L * 60L;
    }

    private Set<EntityType> loadEntityTypes(Iterable<String> values) {
        if (values == null) {
            return Collections.emptySet();
        }
        Set<EntityType> set = new HashSet<>();
        for (String value : values) {
            try {
                set.add(EntityType.valueOf(value.toUpperCase()));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Unknown entity type in config: " + value);
            }
        }
        return Collections.unmodifiableSet(set);
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
                plugin.getLogger().warning("Unknown material in configuration list: " + value);
            }
        }
        return Collections.unmodifiableSet(set);
    }

    public void start() {
        if (task != null) {
            task.cancel();
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::scanPens, scanInterval, scanInterval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        cachedPenInfo.clear();
    }

    private void scanPens() {
        for (World world : Bukkit.getWorlds()) {
            for (LivingEntity entity : world.getLivingEntities()) {
                if (!trackedTypes.contains(entity.getType())) {
                    continue;
                }
                cachedPenInfo.put(entity.getUniqueId(), detectPen(entity));
            }
        }
    }

    private PenInfo detectPen(LivingEntity entity) {
        if (!entity.isValid() || detectionRadius <= 0) {
            return new PenInfo(PenStatus.WILD, null, 0, 0);
        }

        Location location = entity.getLocation();
        World world = location.getWorld();
        if (world == null) {
            return new PenInfo(PenStatus.WILD, null, 0, 0);
        }

        int baseX = location.getBlockX();
        int baseY = location.getBlockY();
        int baseZ = location.getBlockZ();

        Integer west = findBarrier(world, baseX, baseY, baseZ, -1, 0);
        Integer east = findBarrier(world, baseX, baseY, baseZ, 1, 0);
        Integer north = findBarrier(world, baseX, baseY, baseZ, 0, -1);
        Integer south = findBarrier(world, baseX, baseY, baseZ, 0, 1);

        if (west == null || east == null || north == null || south == null) {
            return new PenInfo(PenStatus.WILD, null, 0, 0);
        }

        int minX = Math.min(west, east);
        int maxX = Math.max(west, east);
        int minZ = Math.min(north, south);
        int maxZ = Math.max(north, south);

        int width = maxX - minX + 1;
        int length = maxZ - minZ + 1;

        PenStatus status = (width < minPenSize || length < minPenSize) ? PenStatus.CAPTIVE : PenStatus.PASTURE;
        BoundingBox boundingBox = new BoundingBox(minX, baseY - 1, minZ, maxX + 1, baseY + 2, maxZ + 1);
        return new PenInfo(status, boundingBox, width, length);
    }

    private Integer findBarrier(World world, int baseX, int baseY, int baseZ, int dirX, int dirZ) {
        for (int distance = 1; distance <= detectionRadius; distance++) {
            int checkX = baseX + dirX * distance;
            int checkZ = baseZ + dirZ * distance;
            for (int y = baseY - 1; y <= baseY + 2; y++) {
                Material type = world.getBlockAt(checkX, y, checkZ).getType();
                if (type.isAir() || bypassBlocks.contains(type)) {
                    continue;
                }
                if (type.isSolid()) {
                    return dirX != 0 ? checkX : checkZ;
                }
            }
        }
        return null;
    }

    public boolean isEntityInPen(LivingEntity entity) {
        return getPenStatus(entity) != PenStatus.WILD;
    }

    public PenStatus getPenStatus(LivingEntity entity) {
        return getPenInfo(entity).getStatus();
    }

    public PenInfo getPenInfo(LivingEntity entity) {
        PenInfo cached = cachedPenInfo.get(entity.getUniqueId());
        if (cached != null) {
            return cached;
        }
        PenInfo detected = detectPen(entity);
        cachedPenInfo.put(entity.getUniqueId(), detected);
        return detected;
    }
}
