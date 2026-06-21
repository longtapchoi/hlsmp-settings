package de.elivb.donutSettings;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public class Settings extends JavaPlugin implements Listener, TabCompleter {
   private static final String NIGHTVISION_KEY = "nightvision";
   private FileConfiguration config;
   private FileConfiguration langConfig;
   private final Map<UUID, Long> cooldowns = new HashMap<>();
   private final Map<UUID, Map<String, Boolean>> playerSettings = new HashMap<>();
   private final Map<String, Sound> soundCache = new HashMap<>();
   private final Map<String, String> commandToSettingMap = new HashMap<>();
   private boolean isFolia = false;

   @Override
   public void onEnable() {
      try {
         Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
         this.isFolia = true;
      } catch (ClassNotFoundException e) {
         this.isFolia = false;
      }

      this.saveDefaultConfig();
      this.config = this.getConfig();
      this.loadLang();
      this.initializeSoundCache();
      this.initializeCommandMonitoring();
      Bukkit.getPluginManager().registerEvents(this, this);
      this.getCommand("settings").setTabCompleter(this);

      for (Player player : Bukkit.getOnlinePlayers()) {
         this.loadPlayerSettingsAsync(player.getUniqueId());
      }
   }

   private void loadPlayerSettingsAsync(UUID uuid) {
      if (this.isFolia) {
         this.getServer().getAsyncScheduler().runNow(this, (scheduledTask) -> {
            Map<String, Boolean> settings = this.getPlayerSettingsFromFile(uuid);
            ConfigurationSection section = this.config.getConfigurationSection("settings-gui.items");
            if (section != null) {
               for (String key : section.getKeys(false)) {
                  boolean defaultValue = this.config.getBoolean("settings-gui.items." + key + ".default", true);
                  boolean value = settings.getOrDefault(key, defaultValue);
                  settings.put(key, value);
               }
            }
            this.getServer().getGlobalRegionScheduler().run(this, (scheduledTask2) -> {
               this.playerSettings.put(uuid, settings);
               Player player = Bukkit.getPlayer(uuid);
               if (player != null && Boolean.TRUE.equals(settings.get(NIGHTVISION_KEY))) {
                  this.applyNightVision(player);
               }
            });
         });
      } else {
         Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            Map<String, Boolean> settings = this.getPlayerSettingsFromFile(uuid);
            ConfigurationSection section = this.config.getConfigurationSection("settings-gui.items");
            if (section != null) {
               for (String key : section.getKeys(false)) {
                  boolean defaultValue = this.config.getBoolean("settings-gui.items." + key + ".default", true);
                  boolean value = settings.getOrDefault(key, defaultValue);
                  settings.put(key, value);
               }
            }
            Bukkit.getScheduler().runTask(this, () -> {
               this.playerSettings.put(uuid, settings);
               Player player = Bukkit.getPlayer(uuid);
               if (player != null && Boolean.TRUE.equals(settings.get(NIGHTVISION_KEY))) {
                  this.applyNightVision(player);
               }
            });
         });
      }
   }

   private void initializeCommandMonitoring() {
      this.commandToSettingMap.clear();
      ConfigurationSection section = this.config.getConfigurationSection("settings-gui.items");
      if (section != null) {
         for (String key : section.getKeys(false)) {
            String path = "settings-gui.items." + key;
            String command = this.config.getString(path + ".command");
            if (command != null && !command.isEmpty()) {
               String baseCommand = command.replace("%status%", "").trim();
               this.commandToSettingMap.put(baseCommand.toLowerCase(), key);
            }
         }
      }
   }

   public void reloadCommandMonitoring() {
      this.initializeCommandMonitoring();
   }

   @EventHandler
   public void onPlayerCommand(PlayerCommandPreprocessEvent e) {
      if (this.config.getBoolean("settings-gui.command-monitoring.enabled", true)) {
         String message = e.getMessage().toLowerCase();
         Player player = e.getPlayer();
         UUID uuid = player.getUniqueId();
         if (message.startsWith("/")) {
            String commandWithoutSlash = message.substring(1);
            String[] parts = commandWithoutSlash.split(" ");
            String baseCommand = parts[0];
            if (this.commandToSettingMap.containsKey(baseCommand)) {
               String settingKey = this.commandToSettingMap.get(baseCommand);
               if (this.isFolia) {
                  player.getScheduler().runDelayed(this, (scheduledTask) -> {
                     try {
                        boolean newStatus = this.determineNewStatus(commandWithoutSlash, settingKey, player);
                        this.savePlayerSetting(uuid, settingKey, newStatus);
                        if (NIGHTVISION_KEY.equals(settingKey)) {
                           if (newStatus) {
                              this.applyNightVision(player);
                           } else {
                              this.removeNightVision(player);
                           }
                        }
                     } catch (Exception ex) { }
                  }, null, 2L);
               } else {
                  Bukkit.getScheduler().runTaskLater(this, () -> {
                     try {
                        boolean newStatus = this.determineNewStatus(commandWithoutSlash, settingKey, player);
                        this.savePlayerSetting(uuid, settingKey, newStatus);
                        if (NIGHTVISION_KEY.equals(settingKey)) {
                           if (newStatus) {
                              this.applyNightVision(player);
                           } else {
                              this.removeNightVision(player);
                           }
                        }
                     } catch (Exception ex) { }
                  }, 2L);
               }
            }
         }
      }
   }

   private boolean determineNewStatus(String fullCommand, String settingKey, Player player) {
      if (fullCommand.contains(" on") || fullCommand.contains(" enable") || fullCommand.contains(" ON")) {
         return true;
      } else if (fullCommand.contains(" off") || fullCommand.contains(" disable") || fullCommand.contains(" OFF")) {
         return false;
      } else {
         return !this.getSettingValue(player.getUniqueId(), settingKey);
      }
   }

   @Override
   public void onDisable() {
      if (this.isFolia) {
         this.getServer().getGlobalRegionScheduler().cancelTasks(this);
         this.getServer().getAsyncScheduler().cancelTasks(this);
      } else {
         Bukkit.getScheduler().cancelTasks(this);
      }
   }

   @Override
   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      List<String> completions = new ArrayList<>();
      if (args.length == 1 && sender.hasPermission("settings.reload")) {
         completions.add("reload");
      }
      return completions;
   }

   private void loadLang() {
      File langFile = new File(this.getDataFolder(), "lang.yml");
      if (!langFile.exists()) {
         this.saveResource("lang.yml", false);
      }
      this.langConfig = YamlConfiguration.loadConfiguration(langFile);
   }

   private String getLang(String path, String defaultValue) {
      return this.langConfig.contains(path) ? Hex.color(this.langConfig.getString(path)) : Hex.color(defaultValue);
   }

   private void reloadLang() {
      File langFile = new File(this.getDataFolder(), "lang.yml");
      this.langConfig = YamlConfiguration.loadConfiguration(langFile);
   }

   private void initializeSoundCache() {
      this.soundCache.clear();
      this.addSoundToCache("ui_button_click", Sound.UI_BUTTON_CLICK);
      this.addSoundToCache("block_note_block_chime", Sound.BLOCK_NOTE_BLOCK_CHIME);
      this.addSoundToCache("entity_experience_orb_pickup", Sound.ENTITY_EXPERIENCE_ORB_PICKUP);
      this.addSoundToCache("block_note_block_pling", Sound.BLOCK_NOTE_BLOCK_PLING);
      this.addSoundToCache("block_chest_open", Sound.BLOCK_CHEST_OPEN);
      this.addSoundToCache("block_chest_close", Sound.BLOCK_CHEST_CLOSE);
      this.addSoundToCache("entity_player_levelup", Sound.ENTITY_PLAYER_LEVELUP);
      this.addSoundToCache("item_armor_equip_leather", Sound.ITEM_ARMOR_EQUIP_LEATHER);
      this.addSoundToCache("entity_villager_no", Sound.ENTITY_VILLAGER_NO);
      this.addSoundToCache("entity_villager_yes", Sound.ENTITY_VILLAGER_YES);
      this.addSoundToCache("block_note_block_bell", Sound.BLOCK_NOTE_BLOCK_BELL);
      this.addSoundToCache("block_note_block_xylophone", Sound.BLOCK_NOTE_BLOCK_XYLOPHONE);
      this.addSoundToCache("entity_item_pickup", Sound.ENTITY_ITEM_PICKUP);
      this.addSoundToCache("ui.button.click", Sound.UI_BUTTON_CLICK);
      this.addSoundToCache("entity.experience_orb.pickup", Sound.ENTITY_EXPERIENCE_ORB_PICKUP);
      this.addSoundToCache("entity.villager.no", Sound.ENTITY_VILLAGER_NO);
      this.addSoundToCache("entity.villager.yes", Sound.ENTITY_VILLAGER_YES);
   }

   private void addSoundToCache(String key, Sound sound) {
      this.soundCache.put(key.toLowerCase(), sound);
      this.soundCache.put(key.toLowerCase().replace("_", "."), sound);
      this.soundCache.put(key.toLowerCase().replace("_", " "), sound);
   }

   private void loadPlayerSettings(UUID uuid) {
      if (this.isFolia) {
         this.loadPlayerSettingsAsync(uuid);
      } else {
         Map<String, Boolean> settings = this.getPlayerSettingsFromFile(uuid);
         ConfigurationSection section = this.config.getConfigurationSection("settings-gui.items");
         if (section != null) {
            for (String key : section.getKeys(false)) {
               boolean defaultValue = this.config.getBoolean("settings-gui.items." + key + ".default", true);
               boolean value = settings.getOrDefault(key, defaultValue);
               settings.put(key, value);
            }
         }
         this.playerSettings.put(uuid, settings);
      }
   }

   private void savePlayerSetting(UUID uuid, String key, boolean value) {
      if (!this.playerSettings.containsKey(uuid)) {
         this.playerSettings.put(uuid, new HashMap<>());
      }
      this.playerSettings.get(uuid).put(key, value);
      if (this.isFolia) {
         this.getServer().getAsyncScheduler().runNow(this, (scheduledTask) -> this.savePlayerSettingToFile(uuid, key, value));
      } else {
         Bukkit.getScheduler().runTaskAsynchronously(this, () -> this.savePlayerSettingToFile(uuid, key, value));
      }
   }

   private void applyNightVision(Player player) {
      player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, 0, true, false, false));
   }

   private void removeNightVision(Player player) {
      player.removePotionEffect(PotionEffectType.NIGHT_VISION);
   }

   private boolean getSettingValue(UUID uuid, String key) {
      Map<String, Boolean> settings = this.playerSettings.getOrDefault(uuid, new HashMap<>());
      return settings.getOrDefault(key, this.config.getBoolean("settings-gui.items." + key + ".default", true));
   }

   @EventHandler
   public void onPlayerJoin(PlayerJoinEvent e) {
      Player player = e.getPlayer();
      UUID uuid = player.getUniqueId();
      this.loadPlayerSettings(uuid);
      if (this.isFolia) {
         player.getScheduler().runDelayed(this, (scheduledTask) -> {
            if (this.getSettingValue(uuid, NIGHTVISION_KEY)) {
               this.applyNightVision(player);
            }
         }, null, 5L);
      } else {
         Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline() && this.getSettingValue(uuid, NIGHTVISION_KEY)) {
               this.applyNightVision(player);
            }
         }, 5L);
      }
   }

   @EventHandler
   public void onPlayerRespawn(PlayerRespawnEvent e) {
      Player player = e.getPlayer();
      UUID uuid = player.getUniqueId();
      if (this.getSettingValue(uuid, NIGHTVISION_KEY)) {
         if (this.isFolia) {
            player.getScheduler().runDelayed(this, (scheduledTask) -> this.applyNightVision(player), null, 1L);
         } else {
            Bukkit.getScheduler().runTaskLater(this, () -> this.applyNightVision(player), 1L);
         }
      }
   }

   @Override
   public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
      if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
         if (!sender.hasPermission("settings.reload")) {
            if (sender instanceof Player player) {
               sender.sendMessage(this.getLang("no-permission", "&#FF0000You don't have permission for this!"));
               Sound sound = this.getSoundFromCache(this.config.getString("sound.no-permission", "ENTITY_VILLAGER_NO"));
               if (sound != null) player.playSound(player.getLocation(), sound, 1.0F, 1.0F);
            }
            return true;
         }

         this.reloadConfig();
         this.config = this.getConfig();
         this.reloadLang();
         this.reloadCommandMonitoring();
         for (Player player : Bukkit.getOnlinePlayers()) {
            this.loadPlayerSettings(player.getUniqueId());
         }
         if (sender instanceof Player player) {
            Sound sound = this.getSoundFromCache(this.config.getString("sound.settings-reload", "ENTITY_PLAYER_LEVELUP"));
            if (sound != null) player.playSound(player.getLocation(), sound, 1.0F, 1.0F);
         }
         this.getLogger().info("Plugin reloaded successfully!");
         sender.sendMessage(this.getLang("plugin-reload", "&#00FF00Plugin reloaded successfully!"));
         return true;

      } else if (!(sender instanceof Player)) {
         sender.sendMessage(this.getLang("only-players", "&#FF0000Only players can use this!"));
         return true;
      } else {
         this.openSettings((Player) sender);
         return true;
      }
   }

   private void openSettings(Player player) {
      UUID uuid = player.getUniqueId();
      Map<String, Boolean> settings = this.playerSettings.getOrDefault(uuid, new HashMap<>());
      Inventory inv = Bukkit.createInventory(
         (InventoryHolder) null,
         this.config.getInt("settings-gui.gui-slots", 54),
         Hex.color(this.config.getString("settings-gui.title", "&8ѕᴇᴛᴛɪɴɢѕ"))
      );

      if (this.config.getBoolean("settings-gui.fill.enabled", true)) {
         this.fillInventory(inv);
      }

      ConfigurationSection section = this.config.getConfigurationSection("settings-gui.items");
      if (section != null) {
         for (String key : section.getKeys(false)) {
            boolean status = settings.getOrDefault(key, true);
            String path = "settings-gui.items." + key;
            Material mat = Material.matchMaterial(this.config.getString(path + ".material", "STONE"));
            if (mat == null) mat = Material.STONE;

            ItemStack item = new ItemStack(mat);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
               meta.setDisplayName(Hex.color(this.config.getString(path + ".display-name", "ѕᴇᴛᴛɪɴɢѕ")));
               String loreText = this.config.getString(path + ".lore", "Status: %status%");
               String statusText = status
                  ? Hex.color(this.config.getString("settings-gui.status.on", "&#00FF00&lON"))
                  : Hex.color(this.config.getString("settings-gui.status.off", "&x&F&9&1&8&1&8&lOFF"));
               loreText = Hex.color(loreText.replace("%status%", statusText));
               meta.setLore(Arrays.asList(loreText.split("\n")));
               item.setItemMeta(meta);
            }

            int slot = this.config.getInt(path + ".slot", -1);
            if (slot >= 0 && slot < inv.getSize()) {
               inv.setItem(slot, item);
            }
         }
      }

      player.openInventory(inv);
   }

   private void fillInventory(Inventory inv) {
      String fillMaterialName = this.config.getString("settings-gui.fill.material", "BLACK_STAINED_GLASS_PANE");
      Material fillMaterial = Material.matchMaterial(fillMaterialName);
      if (fillMaterial == null) fillMaterial = Material.BLACK_STAINED_GLASS_PANE;

      ItemStack fillItem = new ItemStack(fillMaterial);
      ItemMeta meta = fillItem.getItemMeta();
      if (meta != null) {
         meta.setDisplayName(Hex.color(this.config.getString("settings-gui.fill.display-name", " ")));
         fillItem.setItemMeta(meta);
      }

      String slotsConfig = this.config.getString("settings-gui.fill.slots", "0-8,45-53");
      for (int slot : this.parseSlots(slotsConfig, inv.getSize())) {
         if (slot >= 0 && slot < inv.getSize()) {
            inv.setItem(slot, fillItem);
         }
      }
   }

   private Set<Integer> parseSlots(String slotsConfig, int inventorySize) {
      Set<Integer> slots = new HashSet<>();
      if (slotsConfig == null || slotsConfig.isEmpty()) return slots;
      for (String part : slotsConfig.split(",")) {
         part = part.trim();
         if (part.contains("-")) {
            String[] range = part.split("-");
            if (range.length == 2) {
               try {
                  int start = Integer.parseInt(range[0].trim());
                  int end = Integer.parseInt(range[1].trim());
                  for (int i = Math.max(0, start); i <= Math.min(end, inventorySize - 1); i++) {
                     slots.add(i);
                  }
               } catch (NumberFormatException ignored) { }
            }
         } else {
            try {
               int slot = Integer.parseInt(part.trim());
               if (slot >= 0 && slot < inventorySize) slots.add(slot);
            } catch (NumberFormatException ignored) { }
         }
      }
      return slots;
   }

   @EventHandler
   public void onClick(InventoryClickEvent e) {
      if (!(e.getWhoClicked() instanceof Player player)) return;
      UUID uuid = player.getUniqueId();
      String guiTitle = Hex.color(this.config.getString("settings-gui.title", "&8ѕᴇᴛᴛɪɴɢѕ"));
      if (!guiTitle.equals(e.getView().getTitle())) return;

      e.setCancelled(true);
      if (e.getCurrentItem() == null || e.getCurrentItem().getType() == Material.AIR) return;

      if (this.config.getBoolean("settings-gui.cooldown.enabled", true)) {
         long now = System.currentTimeMillis();
         if (this.cooldowns.containsKey(uuid)) {
            long cooldownTime = (long) this.config.getInt("settings-gui.cooldown.duration", 3) * 1000L;
            long elapsed = now - this.cooldowns.get(uuid);
            if (elapsed < cooldownTime) {
               long timeLeft = (cooldownTime - elapsed) / 1000L;
               player.sendMessage(this.getLang("cooldown-message", "&#FF0000Please wait %time% seconds before changing settings again!").replace("%time%", String.valueOf(timeLeft)));
               Sound sound = this.getSoundFromCache(this.config.getString("sound.you-must-wait", "ENTITY_VILLAGER_NO"));
               if (sound != null) player.playSound(player.getLocation(), sound, 1.0F, 1.0F);
               return;
            }
         }
         this.cooldowns.put(uuid, now);
      }

      ConfigurationSection section = this.config.getConfigurationSection("settings-gui.items");
      if (section != null) {
         for (String key : section.getKeys(false)) {
            String path = "settings-gui.items." + key;
            int slot = this.config.getInt(path + ".slot", -1);
            if (e.getSlot() == slot) {
               boolean current = this.playerSettings.getOrDefault(uuid, new HashMap<>()).getOrDefault(key, true);
               boolean newValue = !current;
               this.savePlayerSetting(uuid, key, newValue);
               if (NIGHTVISION_KEY.equals(key)) {
                  if (newValue) {
                     this.applyNightVision(player);
                  } else {
                     this.removeNightVision(player);
                  }
               } else {
                  String command = this.config.getString(path + ".command");
                  if (command != null && !command.isEmpty()) {
                     Bukkit.dispatchCommand(player, command.replace("%status%", newValue ? "on" : "off"));
                  }
               }
               Sound sound = this.getSoundFromCache(this.config.getString("sound.menu-click", "UI_BUTTON_CLICK"));
               if (sound != null) player.playSound(player.getLocation(), sound, 1.0F, 1.0F);
               if (this.isFolia) {
                  player.getScheduler().runDelayed(this, (scheduledTask) -> this.openSettings(player), null, 1L);
               } else {
                  Bukkit.getScheduler().runTaskLater(this, () -> this.openSettings(player), 1L);
               }
               break;
            }
         }
      }

      if (this.config.getBoolean("settings-gui.fill.enabled", true)) {
         Set<Integer> fillSlots = this.parseSlots(this.config.getString("settings-gui.fill.slots", "0-8,45-53"), e.getInventory().getSize());
         if (fillSlots.contains(e.getSlot())) {
            Sound fillSound = this.getSoundFromCache(this.config.getString("settings-gui.fill.sound", "UI_BUTTON_CLICK"));
            if (fillSound != null) player.playSound(player.getLocation(), fillSound, 1.0F, 1.0F);
         }
      }
   }

   private Sound getSoundFromCache(String name) {
      if (name == null || name.isEmpty()) return null;
      String normalized = name.toLowerCase().replace(".", "_").replace(" ", "_").trim();
      if (this.soundCache.containsKey(normalized)) return this.soundCache.get(normalized);
      String alt1 = normalized.replace("_", ".");
      if (this.soundCache.containsKey(alt1)) return this.soundCache.get(alt1);
      String alt2 = normalized.replace("_", " ");
      if (this.soundCache.containsKey(alt2)) return this.soundCache.get(alt2);

      Map<String, Sound> common = new HashMap<>();
      common.put("ui_button_click", Sound.UI_BUTTON_CLICK);
      common.put("entity_villager_no", Sound.ENTITY_VILLAGER_NO);
      common.put("entity_player_levelup", Sound.ENTITY_PLAYER_LEVELUP);
      common.put("entity_experience_orb_pickup", Sound.ENTITY_EXPERIENCE_ORB_PICKUP);
      common.put("block_note_block_pling", Sound.BLOCK_NOTE_BLOCK_PLING);
      for (Map.Entry<String, Sound> entry : common.entrySet()) {
         if (normalized.contains(entry.getKey()) || entry.getKey().contains(normalized)) {
            this.soundCache.put(normalized, entry.getValue());
            return entry.getValue();
         }
      }
      return null;
   }

   private File getPlayerDataFolder() {
      File folder = new File(this.getDataFolder(), "Playerdata");
      if (!folder.exists()) folder.mkdirs();
      return folder;
   }

   private File getPlayerFile(UUID uuid) {
      // Always use UUID as filename to prevent data loss on name change
      return new File(this.getPlayerDataFolder(), uuid.toString() + ".yml");
   }

   private Map<String, Boolean> getPlayerSettingsFromFile(UUID uuid) {
      Map<String, Boolean> settings = new HashMap<>();
      File playerFile = this.getPlayerFile(uuid);
      if (playerFile.exists()) {
         try {
            YamlConfiguration playerConfig = YamlConfiguration.loadConfiguration(playerFile);
            ConfigurationSection settingsSection = playerConfig.getConfigurationSection("settings");
            if (settingsSection != null) {
               for (String key : settingsSection.getKeys(false)) {
                  settings.put(key, settingsSection.getBoolean(key));
               }
            }
         } catch (Exception ignored) { }
      }
      return settings;
   }

   private void savePlayerSettingToFile(UUID uuid, String key, boolean value) {
      File playerFile = this.getPlayerFile(uuid);
      YamlConfiguration playerConfig = playerFile.exists()
         ? YamlConfiguration.loadConfiguration(playerFile)
         : new YamlConfiguration();
      try {
         Player player = Bukkit.getPlayer(uuid);
         if (player != null) {
            playerConfig.set("name", player.getName());
            playerConfig.set("uuid", uuid.toString());
         }
         Map<String, Boolean> existingSettings = this.getPlayerSettingsFromFile(uuid);
         existingSettings.put(key, value);
         for (Map.Entry<String, Boolean> entry : existingSettings.entrySet()) {
            playerConfig.set("settings." + entry.getKey(), entry.getValue());
         }
         playerConfig.save(playerFile);
      } catch (Exception ignored) { }
   }
}
