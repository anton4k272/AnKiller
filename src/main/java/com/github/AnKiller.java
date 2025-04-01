package com.github;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AnKiller extends JavaPlugin implements Listener, TabExecutor {

    private final Map<String, Integer> bounties = new HashMap<>();
    private static Economy econ = null;
    private FileConfiguration config;
    private FileConfiguration messages;

    @Override
    public void onEnable() {
        if (!setupEconomy()) {
            getLogger().severe("Vault не найден! Плагин отключен.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        config = getConfig();
        loadMessages();

        if (messages == null) {
            getLogger().severe("Не удалось загрузить messages.yml. Плагин отключен.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("killer").setExecutor(this);
        getCommand("ankiller").setExecutor(this);
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        econ = rsp.getProvider();
        return econ != null;
    }

    private void loadMessages() {
        File messagesFile = new File(getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            saveResource("messages.yml", false);
        }
        messages = YamlConfiguration.loadConfiguration(messagesFile);
    }

    private String getMessage(String key) {
        if (messages == null) {
            return "Message configuration is not loaded.";
        }
        return ChatColor.translateAlternateColorCodes('&', messages.getString("messages." + key));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("killer")) {
            if (args.length == 0 && sender instanceof Player) {
                openMenu((Player) sender);
                return true;
            } else if (args.length == 2 && sender instanceof Player) {
                Player player = (Player) sender;
                String targetName = args[0];
                int amount;
                try {
                    amount = Integer.parseInt(args[1]);
                } catch (NumberFormatException e) {
                    player.sendMessage(getMessage("invalid_amount"));
                    return false;
                }

                int minimumBountyAmount = config.getInt("minimum-bounty-amount", 10000);
                int maximumBountyAmount = config.getInt("maximum-bounty-amount", 10000000);

                if (amount < minimumBountyAmount) {
                    player.sendMessage(getMessage("minimum_bounty_amount").replace("{amount}", String.valueOf(minimumBountyAmount)));
                    return false;
                }

                if (amount > maximumBountyAmount) {
                    player.sendMessage(getMessage("maximum_bounty_amount").replace("{amount}", String.valueOf(maximumBountyAmount)));
                    return false;
                }

                Player target = Bukkit.getPlayer(targetName);
                if (target == null) {
                    player.sendMessage(getMessage("player_not_found"));
                    return false;
                }

                if (bounties.containsKey(targetName)) {
                    player.sendMessage(getMessage("bounty_exists").replace("{target}", targetName));
                    return false;
                }

                if (econ.getBalance(player) < amount) {
                    player.sendMessage(getMessage("insufficient_funds"));
                    return false;
                }

                econ.withdrawPlayer(player, amount);
                bounties.put(targetName, amount);
                Bukkit.broadcastMessage(getMessage("bounty_set").replace("{player}", player.getName()).replace("{amount}", String.valueOf(amount)).replace("{target}", targetName));

                return true;
            }
        } else if (command.getName().equalsIgnoreCase("ankiller")) {
            if (args.length == 1 && args[0].equalsIgnoreCase("reload") && sender.hasPermission("ankiller.admin")) {
                reloadConfig();
                config = getConfig();
                loadMessages();
                sender.sendMessage(getMessage("config_reloaded"));
                return true;
            }
        }
        return false;
    }

    private void openMenu(Player player) {
        String title = ChatColor.translateAlternateColorCodes('&', config.getString("menu.title"));
        int size = config.getInt("menu.size");
        Inventory menu = Bukkit.createInventory(null, size, title);

        // Добавить предмет на указанный слот (нельзя брать)
        Map<String, Object> barrierConfig = config.getConfigurationSection("menu.items.barrier").getValues(false);
        ItemStack barrierItem = createItem(barrierConfig);
        int barrierSlot = (int) barrierConfig.get("slot");
        menu.setItem(barrierSlot, barrierItem);

        // Добавить розыски на указанные слоты или пустые слоты с AIR
        Map<String, Object> bountyConfig = config.getConfigurationSection("menu.items.bounty").getValues(false);
        List<Integer> bountySlots = (List<Integer>) bountyConfig.get("slots");

        int index = 0;
        for (int slot : bountySlots) {
            if (index < bounties.size()) {
                Map.Entry<String, Integer> entry = (Map.Entry<String, Integer>) bounties.entrySet().toArray()[index];
                ItemStack bountyItem = createBountyItem(entry.getKey(), entry.getValue(), player.getName());
                menu.setItem(slot, bountyItem);
                index++;
            } else {
                menu.setItem(slot, new ItemStack(Material.AIR));
            }
        }

        player.openInventory(menu);
    }

    private ItemStack createItem(Map<String, Object> itemConfig) {
        Material material = Material.valueOf((String) itemConfig.get("material"));
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', (String) itemConfig.get("name")));
        List<String> lore = (List<String>) itemConfig.get("lore");
        if (lore != null) {
            for (int i = 0; i < lore.size(); i++) {
                lore.set(i, ChatColor.translateAlternateColorCodes('&', lore.get(i)));
            }
            meta.setLore(lore);
        }

        if (itemConfig.containsKey("customModelData")) {
            meta.setCustomModelData((int) itemConfig.get("customModelData"));
        }

        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createBountyItem(String playerName, int amount, String orderer) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(playerName));
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', "&eРозыск: " + playerName));
        List<String> lore = config.getStringList("menu.items.bounty.lore");
        if (lore != null) {
            for (int i = 0; i < lore.size(); i++) {
                lore.set(i, lore.get(i).replace("{player}", playerName).replace("{reward}", String.valueOf(amount)).replace("{orderer}", orderer));
                lore.set(i, ChatColor.translateAlternateColorCodes('&', lore.get(i)));
            }
            meta.setLore(lore);
        }

        if (config.getConfigurationSection("menu.items.bounty").contains("customModelData")) {
            meta.setCustomModelData(config.getInt("menu.items.bounty.customModelData"));
        }

        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player deceased = event.getEntity();
        Player killer = deceased.getKiller();

        if (killer != null && bounties.containsKey(deceased.getName())) {
            int reward = bounties.get(deceased.getName());
            bounties.remove(deceased.getName());

            econ.depositPlayer(killer, reward);
            killer.sendMessage(getMessage("bounty_received").replace("{deceased}", deceased.getName()).replace("{reward}", String.valueOf(reward)));
            Bukkit.broadcastMessage(getMessage("bounty_broadcast").replace("{killer}", killer.getName()).replace("{deceased}", deceased.getName()).replace("{reward}", String.valueOf(reward)));
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getInventory().getType() == InventoryType.CHEST
                && event.getView().getTitle().equals(ChatColor.translateAlternateColorCodes('&', config.getString("menu.title")))) {
            event.setCancelled(true);
        }
    }
}