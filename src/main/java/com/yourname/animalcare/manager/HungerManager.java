package com.yourname.animalcare.manager;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class HungerManager {

    private final JavaPlugin plugin;
    private final NamespacedKey hungerKey;
    private final Set<EntityType> managedTypes;
    private final int maxHunger;
    private final int captiveLoss;
    private final int pastureChange;
    private final long hungerIntervalTicks;
    private final int feedAmount;
    private final int lowThreshold;
    private final double starvationDamage;

    private BukkitTask task;
    private final PenDetectionService penDetectionService;

    public HungerManager(JavaPlugin plugin, NamespacedKey hungerKey, FileConfiguration config, PenDetectionService penDetectionService) {
        this.plugin = plugin;
        this.hungerKey = hungerKey;
        this.managedTypes = loadManagedTypes(config.getStringList("entities"));
        this.penDetectionService = penDetectionService;
        ConfigurationSection hungerSection = config.getConfigurationSection("hunger");
        this.maxHunger = hungerSection != null ? hungerSection.getInt("max", 100) : 100;
        this.captiveLoss = hungerSection != null ? hungerSection.getInt("captive-loss", 5) : 5;
        this.pastureChange = hungerSection != null ? hungerSection.getInt("pasture-change", -1) : -1;
        this.feedAmount = hungerSection != null ? hungerSection.getInt("feed-amount", 25) : 25;
        this.hungerIntervalTicks = hungerSection != null ? hungerSection.getLong("interval-ticks", 20L * 60L) : 20L * 60L;
        ConfigurationSection effectsSection = hungerSection != null ? hungerSection.getConfigurationSection("effects") : null;
        this.lowThreshold = effectsSection != null ? effectsSection.getInt("low-threshold", 30) : 30;
        this.starvationDamage = effectsSection != null ? effectsSection.getDouble("starvation-damage", 1.0D) : 1.0D;
    }

    private Set<EntityType> loadManagedTypes(Iterable<String> values) {
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

    public void start() {
        if (task != null) {
            task.cancel();
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, hungerIntervalTicks, hungerIntervalTicks);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        Bukkit.getWorlds().forEach(world -> world.getLivingEntities().stream()
            .filter(this::isManagedEntity)
            .forEach(this::applyHungerTick));
    }

    private void applyHungerTick(LivingEntity entity) {
        PenDetectionService.PenStatus status = penDetectionService.getPenStatus(entity);
        if (status == PenDetectionService.PenStatus.WILD) {
            setHunger(entity, maxHunger);
            return;
        }

        int delta = 0;
        if (status == PenDetectionService.PenStatus.CAPTIVE) {
            delta = -Math.abs(captiveLoss);
        } else if (status == PenDetectionService.PenStatus.PASTURE) {
            delta = pastureChange;
        }

        int hunger = addHunger(entity, delta);
        applyStatusEffects(entity, hunger);
    }

    public boolean isManagedEntity(LivingEntity entity) {
        return managedTypes.contains(entity.getType());
    }

    public int getHunger(LivingEntity entity) {
        PersistentDataContainer container = entity.getPersistentDataContainer();
        Integer value = container.get(hungerKey, PersistentDataType.INTEGER);
        if (value == null) {
            setHunger(entity, maxHunger);
            return maxHunger;
        }
        return Math.max(0, Math.min(maxHunger, value));
    }

    public void setHunger(LivingEntity entity, int hunger) {
        PersistentDataContainer container = entity.getPersistentDataContainer();
        container.set(hungerKey, PersistentDataType.INTEGER, Math.max(0, Math.min(maxHunger, hunger)));
    }

    public int addHunger(LivingEntity entity, int amount) {
        if (amount == 0) {
            return getHunger(entity);
        }
        int newHunger = Math.max(0, Math.min(maxHunger, getHunger(entity) + amount));
        setHunger(entity, newHunger);
        return newHunger;
    }

    public int feedEntity(LivingEntity entity) {
        return addHunger(entity, feedAmount);
    }

    public int getMaxHunger() {
        return maxHunger;
    }

    public int getFeedAmount() {
        return feedAmount;
    }

    private void applyStatusEffects(LivingEntity entity, int hunger) {
        if (hunger > lowThreshold) {
            entity.removePotionEffect(PotionEffectType.SLOW);
            entity.removePotionEffect(PotionEffectType.WEAKNESS);
        } else {
            int duration = (int) Math.max(100, hungerIntervalTicks + 40);
            PotionEffect slowness = new PotionEffect(PotionEffectType.SLOW, duration, 0, true, false, true);
            PotionEffect weakness = new PotionEffect(PotionEffectType.WEAKNESS, duration, 0, true, false, true);
            entity.addPotionEffect(slowness);
            entity.addPotionEffect(weakness);
        }
        if (hunger <= 0 && starvationDamage > 0 && !entity.isDead()) {
            entity.damage(starvationDamage);
        }
    }
}
