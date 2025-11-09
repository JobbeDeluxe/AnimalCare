package com.yourname.animalcare;

import com.yourname.animalcare.listener.FeedListener;
import com.yourname.animalcare.listener.TroughListener;
import com.yourname.animalcare.manager.HungerManager;
import com.yourname.animalcare.manager.PenDetectionService;
import com.yourname.animalcare.manager.TroughManager;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public class AnimalCarePlugin extends JavaPlugin {

    private HungerManager hungerManager;
    private PenDetectionService penDetectionService;
    private TroughManager troughManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        FileConfiguration config = getConfig();
        NamespacedKey hungerKey = new NamespacedKey(this, "hunger");

        this.penDetectionService = new PenDetectionService(this, config);
        this.hungerManager = new HungerManager(this, hungerKey, config, penDetectionService);
        this.troughManager = new TroughManager(this, hungerManager, penDetectionService, config);

        getServer().getPluginManager().registerEvents(new FeedListener(config, hungerManager, penDetectionService), this);
        getServer().getPluginManager().registerEvents(new TroughListener(config, troughManager), this);

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
}
