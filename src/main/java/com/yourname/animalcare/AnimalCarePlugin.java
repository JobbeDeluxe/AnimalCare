package com.yourname.animalcare;

import com.yourname.animalcare.listener.FeedListener;
import com.yourname.animalcare.listener.TroughListener;
import com.yourname.animalcare.manager.HungerManager;
import com.yourname.animalcare.manager.PenDetectionService;
import com.yourname.animalcare.manager.TroughManager;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

public class AnimalCarePlugin extends JavaPlugin {

    public static final int FEED_ENERGY_SCALE = 5;

    private HungerManager hungerManager;
    private PenDetectionService penDetectionService;
    private TroughManager troughManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        FileConfiguration config = getConfig();
        if (ensureConfigCompleteness(config)) {
            saveConfig();
            reloadConfig();
            config = getConfig();
        }
        NamespacedKey hungerKey = new NamespacedKey(this, "hunger");

        this.penDetectionService = new PenDetectionService(this, config);
        this.hungerManager = new HungerManager(this, hungerKey, config, penDetectionService);

        Map<Material, Integer> feedEnergy = loadFeedEnergy(config);
        this.troughManager = new TroughManager(this, hungerManager, penDetectionService, config, feedEnergy);

        getServer().getPluginManager().registerEvents(new FeedListener(config, hungerManager, penDetectionService, feedEnergy), this);

        ConfigurationSection debugSection = config.getConfigurationSection("debug");
        boolean debugEnabled = debugSection != null && debugSection.getBoolean("enabled", false);
        String debugToolName = debugSection != null ? debugSection.getString("tool", "WOODEN_SWORD") : "WOODEN_SWORD";
        Material debugTool = Material.matchMaterial(debugToolName == null ? "WOODEN_SWORD" : debugToolName.toUpperCase());
        if (debugTool == null) {
            getLogger().warning("Unknown debug tool material: " + debugToolName + ". Falling back to WOODEN_SWORD.");
            debugTool = Material.WOODEN_SWORD;
        }

        getServer().getPluginManager().registerEvents(
            new TroughListener(config, troughManager, hungerManager, penDetectionService, debugEnabled, debugTool), this);

        hungerManager.start();
        penDetectionService.start();
        troughManager.start();
    }

    @Override
    public void onDisable() {
        if (hungerManager != null) {
            hungerManager.stop();
        }
        if (penDetectionService != null) {
            penDetectionService.stop();
        }
        if (troughManager != null) {
            troughManager.stop();
        }
    }

    private Map<Material, Integer> loadFeedEnergy(FileConfiguration config) {
        ConfigurationSection feedingSection = config.getConfigurationSection("feeding");
        if (feedingSection == null) {
            return Collections.emptyMap();
        }
        ConfigurationSection energySection = feedingSection.getConfigurationSection("item-energy");
        if (energySection == null) {
            return Collections.emptyMap();
        }
        Map<Material, Integer> energies = new HashMap<>();
        for (String key : energySection.getKeys(false)) {
            Material material = Material.matchMaterial(key.toUpperCase());
            if (material == null) {
                getLogger().warning("Unknown feed material in feeding.item-energy: " + key);
                continue;
            }
            int value = energySection.getInt(key, 0);
            if (value <= 0) {
                getLogger().warning("Feed energy for " + material + " must be positive. Skipping entry.");
                continue;
            }
            int scaled = value;
            if (value % FEED_ENERGY_SCALE == 0) {
                scaled = value / FEED_ENERGY_SCALE;
            } else {
                getLogger().warning("Feed energy for " + material + " should be a multiple of " + FEED_ENERGY_SCALE
                    + ". Using legacy value " + value + ".");
            }
            if (scaled <= 0) {
                getLogger().warning("Feed energy for " + material + " resolves to zero after scaling. Skipping entry.");
                continue;
            }
            energies.put(material, scaled);
        }
        return Collections.unmodifiableMap(energies);
    }

    private boolean ensureConfigCompleteness(FileConfiguration config) {
        try (InputStream stream = getResource("config.yml")) {
            if (stream == null) {
                getLogger().warning("Default config.yml not found in plugin resources; skipping completeness check.");
                return false;
            }
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(reader);
                boolean updated = false;
                for (String key : defaults.getKeys(true)) {
                    if (key == null || key.isEmpty()) {
                        continue;
                    }
                    if (!config.contains(key)) {
                        config.set(key, defaults.get(key));
                        updated = true;
                    }
                }
                return updated;
            }
        } catch (IOException ex) {
            getLogger().log(Level.WARNING, "Failed to verify config completeness.", ex);
            return false;
        }
    }
}
