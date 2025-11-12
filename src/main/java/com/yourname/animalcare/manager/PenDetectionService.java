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

import java.util.ArrayDeque;
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
    private final int maxVerticalDelta;
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
        this.maxVerticalDelta = penSection != null ? penSection.getInt("max-vertical-delta", 4) : 4;
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
        int baseY = findStartingY(world, baseX, location.getBlockY(), location.getBlockZ());
        int baseZ = location.getBlockZ();

        if (!isWalkable(world, baseX, baseY, baseZ)) {
            return new PenInfo(PenStatus.WILD, null, 0, 0);
        }

        FloodFillResult fillResult = floodFillArea(world, baseX, baseY, baseZ);
        if (fillResult == null || fillResult.isEscaped()) {
            return new PenInfo(PenStatus.WILD, null, 0, 0);
        }

        int width = fillResult.getMaxX() - fillResult.getMinX() + 1;
        int length = fillResult.getMaxZ() - fillResult.getMinZ() + 1;

        PenStatus status = (width < minPenSize || length < minPenSize) ? PenStatus.CAPTIVE : PenStatus.PASTURE;
        BoundingBox boundingBox = new BoundingBox(
                fillResult.getMinX(),
                fillResult.getMinY() - 1,
                fillResult.getMinZ(),
                fillResult.getMaxX() + 1,
                fillResult.getMaxY() + 2,
                fillResult.getMaxZ() + 1
        );
        return new PenInfo(status, boundingBox, width, length);
    }

    private FloodFillResult floodFillArea(World world, int baseX, int baseY, int baseZ) {
        int verticalLimit = Math.max(1, maxVerticalDelta);
        int horizontalLimit = Math.max(1, detectionRadius);

        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();

        BlockPos start = new BlockPos(baseX, baseY, baseZ);
        queue.add(start);
        visited.add(start);

        int minX = baseX;
        int maxX = baseX;
        int minY = baseY;
        int maxY = baseY;
        int minZ = baseZ;
        int maxZ = baseZ;

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();

            if (Math.abs(current.x - baseX) > horizontalLimit || Math.abs(current.z - baseZ) > horizontalLimit) {
                return FloodFillResult.escaped();
            }

            minX = Math.min(minX, current.x);
            maxX = Math.max(maxX, current.x);
            minY = Math.min(minY, current.y);
            maxY = Math.max(maxY, current.y);
            minZ = Math.min(minZ, current.z);
            maxZ = Math.max(maxZ, current.z);

            for (Direction direction : Direction.values()) {
                int nextX = current.x + direction.xOffset;
                int nextZ = current.z + direction.zOffset;

                for (int deltaY : direction.yCandidates) {
                    int nextY = current.y + deltaY;
                    if (Math.abs(nextY - baseY) > verticalLimit) {
                        continue;
                    }
                    if (!isWalkable(world, nextX, nextY, nextZ)) {
                        continue;
                    }

                    BlockPos next = new BlockPos(nextX, nextY, nextZ);
                    if (visited.contains(next)) {
                        continue;
                    }

                    if (Math.abs(next.x - baseX) > horizontalLimit || Math.abs(next.z - baseZ) > horizontalLimit) {
                        return FloodFillResult.escaped();
                    }

                    visited.add(next);
                    queue.add(next);
                }
            }
        }

        if (visited.isEmpty()) {
            return null;
        }

        return FloodFillResult.enclosed(minX, maxX, minY, maxY, minZ, maxZ);
    }

    private boolean isWalkable(World world, int x, int y, int z) {
        Material feet = world.getBlockAt(x, y, z).getType();
        if (!isPassable(feet)) {
            return false;
        }

        Material head = world.getBlockAt(x, y + 1, z).getType();
        if (!isPassable(head)) {
            return false;
        }

        Material below = world.getBlockAt(x, y - 1, z).getType();
        return canStandOn(below);
    }

    private boolean isPassable(Material type) {
        if (bypassBlocks.contains(type)) {
            return true;
        }
        if (type == Material.WATER || type == Material.LAVA || type == Material.POWDER_SNOW) {
            return false;
        }
        return type.isAir() || !type.isSolid();
    }

    private boolean canStandOn(Material type) {
        if (type == Material.AIR) {
            return false;
        }
        String name = type.name();
        if (name.endsWith("_FENCE") || name.endsWith("_WALL") || name.endsWith("_GATE")) {
            return false;
        }
        if (type == Material.FARMLAND || type == Material.SNOW || name.endsWith("_PATH") || name.endsWith("_CARPET")) {
            return true;
        }
        return type.isSolid();
    }

    private int findStartingY(World world, int x, int initialY, int z) {
        int y = initialY;
        for (int i = 0; i < 3; i++) {
            if (isWalkable(world, x, y, z)) {
                return y;
            }
            y--;
        }
        y = initialY + 1;
        for (int i = 0; i < 2; i++) {
            if (isWalkable(world, x, y, z)) {
                return y;
            }
            y++;
        }
        return initialY;
    }

    private static class FloodFillResult {
        private final boolean escaped;
        private final int minX;
        private final int maxX;
        private final int minY;
        private final int maxY;
        private final int minZ;
        private final int maxZ;

        private FloodFillResult(boolean escaped, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
            this.escaped = escaped;
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
            this.minZ = minZ;
            this.maxZ = maxZ;
        }

        public static FloodFillResult escaped() {
            return new FloodFillResult(true, 0, 0, 0, 0, 0, 0);
        }

        public static FloodFillResult enclosed(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
            return new FloodFillResult(false, minX, maxX, minY, maxY, minZ, maxZ);
        }

        public boolean isEscaped() {
            return escaped;
        }

        public int getMinX() {
            return minX;
        }

        public int getMaxX() {
            return maxX;
        }

        public int getMinY() {
            return minY;
        }

        public int getMaxY() {
            return maxY;
        }

        public int getMinZ() {
            return minZ;
        }

        public int getMaxZ() {
            return maxZ;
        }
    }

    private enum Direction {
        NORTH(0, -1, new int[]{0, 1, -1}),
        SOUTH(0, 1, new int[]{0, 1, -1}),
        EAST(1, 0, new int[]{0, 1, -1}),
        WEST(-1, 0, new int[]{0, 1, -1});

        private final int xOffset;
        private final int zOffset;
        private final int[] yCandidates;

        Direction(int xOffset, int zOffset, int[] yCandidates) {
            this.xOffset = xOffset;
            this.zOffset = zOffset;
            this.yCandidates = yCandidates;
        }
    }

    private static class BlockPos {
        private final int x;
        private final int y;
        private final int z;

        private BlockPos(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof BlockPos)) {
                return false;
            }
            BlockPos other = (BlockPos) obj;
            return x == other.x && y == other.y && z == other.z;
        }

        @Override
        public int hashCode() {
            int result = x;
            result = 31 * result + y;
            result = 31 * result + z;
            return result;
        }
    }

    public boolean isEntityInPen(LivingEntity entity) {
        return getPenStatus(entity) != PenStatus.WILD;
    }

    public PenStatus getPenStatus(LivingEntity entity) {
        return getPenInfo(entity).getStatus();
    }

    public PenInfo getPenInfo(LivingEntity entity) {
        if (!trackedTypes.contains(entity.getType())) {
            return new PenInfo(PenStatus.WILD, null, 0, 0);
        }
        PenInfo cached = cachedPenInfo.get(entity.getUniqueId());
        if (cached != null) {
            return cached;
        }
        PenInfo detected = detectPen(entity);
        cachedPenInfo.put(entity.getUniqueId(), detected);
        return detected;
    }
}
