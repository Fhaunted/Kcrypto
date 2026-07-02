package com.kcrp.kcrypto.listeners;

import com.kcrp.kcrypto.KcryptoPlugin;
import com.kcrp.kcrypto.config.ConfigManager;
import com.kcrp.kcrypto.database.DatabaseManager;
import com.kcrp.kcrypto.economy.EconomyManager;
import com.kcrp.kcrypto.villager.LaundererManager;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Entity;

public final class LaundererListener implements Listener {

    public enum ChatAction { SELL, BUY, SET_TAX }
    public record ChatState(Villager villager, ChatAction action) {}

    private final KcryptoPlugin    plugin;
    private final LaundererManager launderer;
    private final EconomyManager   economy;
    private final DatabaseManager  db;
    private final ConfigManager    cfg;

    private final Map<UUID, ChatState> chatStates = new ConcurrentHashMap<>();
    /** Tracks which Villager (launderer) each player currently has open in their GUI. */
    private final Map<UUID, Villager> openLaundererSessions = new ConcurrentHashMap<>();

    public LaundererListener(KcryptoPlugin plugin, LaundererManager launderer,
                              EconomyManager economy, DatabaseManager db,
                              ConfigManager cfg) {
        this.plugin    = plugin;
        this.launderer = launderer;
        this.economy   = economy;
        this.db        = db;
        this.cfg       = cfg;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Villager villager)) return;
        if (!launderer.isLaunderer(villager)) return;

        Player player = event.getPlayer();
        event.setCancelled(true); // Prevent default trade UI

        if (!player.hasPermission("Kcrypto.blanchisseur")) {
            player.sendMessage(cfg.getMsgLaundererDenied());
            return;
        }

        plugin.getServer().getAsyncScheduler().runNow(plugin, t -> {
            double balance = db.getCryptoBalance(player.getUniqueId());
            plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
                openLaundererSessions.put(player.getUniqueId(), villager);
                player.openInventory(launderer.buildGUI(villager, player, balance));
            });
        });
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals(LaundererManager.GUI_TITLE)) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null || event.getClickedInventory().equals(player.getInventory())) return;

        // Retrieve villager from our session map instead of getHolder() which may be null
        Villager villager = openLaundererSessions.get(player.getUniqueId());
        if (villager == null || !villager.isValid()) return;

        int slot = event.getSlot();
        if (slot == 11) { // Sell
            player.closeInventory();
            chatStates.put(player.getUniqueId(), new ChatState(villager, ChatAction.SELL));
            player.sendMessage(cfg.fmt("§eCombien de K-Crypto voulez-vous vendre ? Écrivez le montant dans le chat."));
        } else if (slot == 15) { // Buy
            player.closeInventory();
            chatStates.put(player.getUniqueId(), new ChatState(villager, ChatAction.BUY));
            player.sendMessage(cfg.fmt("§aCombien de K-Crypto voulez-vous acheter ? Écrivez le montant dans le chat."));
        } else if (slot == 22) { // Set Tax
            player.closeInventory();
            chatStates.put(player.getUniqueId(), new ChatState(villager, ChatAction.SET_TAX));
            player.sendMessage(cfg.fmt("§6Quel pourcentage de taxe voulez-vous appliquer (ex: 5.5) ? Écrivez dans le chat."));
        } else if (slot == 26) { // Claim Tax
            openLaundererSessions.remove(player.getUniqueId());
            player.closeInventory();
            claimTax(player, villager);
        }
    }

    @EventHandler
    public void onInventoryClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (event.getView().getTitle().equals(LaundererManager.GUI_TITLE)) {
            openLaundererSessions.remove(event.getPlayer().getUniqueId());
        }
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        ChatState state = chatStates.remove(player.getUniqueId());
        if (state == null) return;

        event.setCancelled(true);
        String msg = PlainTextComponentSerializer.plainText().serialize(event.message());
        double amount;
        try {
            amount = Double.parseDouble(msg);
            if (amount <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            plugin.getServer().getGlobalRegionScheduler().execute(plugin, () ->
                    player.sendMessage(cfg.fmt("§cMontant invalide. Action annulée.")));
            return;
        }

        Villager villager = state.villager();
        if (villager == null || !villager.isValid()) {
            plugin.getServer().getGlobalRegionScheduler().execute(plugin, () ->
                    player.sendMessage(cfg.fmt("§cLe Blanchisseur est invalide ou trop loin.")));
            return;
        }

        switch (state.action()) {
            case SELL -> handleSell(player, villager, Math.ceil(amount));
            case BUY -> handleBuy(player, villager, Math.ceil(amount));
            case SET_TAX -> handleSetTax(player, villager, amount);
        }
    }

    private void handleSetTax(Player player, Villager villager, double amount) {
        if (amount > 20.0) amount = 20.0; // Limit to 20%
        final double finalAmount = amount;
        plugin.getServer().getRegionScheduler().execute(plugin, villager.getLocation(), () -> {
            launderer.setTaxRate(villager, finalAmount);
            player.sendMessage(cfg.fmt("§aLa taxe de ce Blanchisseur est maintenant de §e" + finalAmount + "%"));
        });
    }

    private void claimTax(Player player, Villager villager) {
        plugin.getServer().getRegionScheduler().execute(plugin, villager.getLocation(), () -> {
            if (!villager.isValid()) return;
            double pool = launderer.getTaxPool(villager);
            if (pool <= 0) {
                player.sendMessage(cfg.fmt("§cLa cagnotte de ce Blanchisseur est vide."));
                return;
            }
            launderer.setTaxPool(villager, 0.0);
            plugin.getServer().getAsyncScheduler().runNow(plugin, t -> {
                db.addCryptoBalance(player.getUniqueId(), pool);
                plugin.getServer().getGlobalRegionScheduler().execute(plugin, () ->
                        player.sendMessage(cfg.fmt("§aVous avez récolté §e" + pool + " K-Crypto §ade taxes !")));
            });
        });
    }

    private void handleSell(Player player, Villager villager, double cryptoAmount) {
        UUID playerUuid = player.getUniqueId();
        double rate = economy.getRate();

        plugin.getServer().getRegionScheduler().execute(plugin, villager.getLocation(), () -> {
            if (!villager.isValid()) return;
            double taxRate = launderer.getTaxRate(villager) / 100.0;
            double currentPool = launderer.getTaxPool(villager);
            
            if (currentPool >= 1000.0) {
                player.sendMessage(cfg.fmt("§cCe Blanchisseur est saturé et ne peut plus percevoir de taxes. Demandez à son propriétaire de le vider."));
                return;
            }

            plugin.getServer().getAsyncScheduler().runNow(plugin, t -> {
                double currentBalance = db.getCryptoBalance(playerUuid);
                if (currentBalance < cryptoAmount) {
                    plugin.getServer().getGlobalRegionScheduler().execute(plugin, () ->
                            player.sendMessage(cfg.fmt("§cSolde insuffisant.")));
                    return;
                }

                double grossPayout = cryptoAmount * rate;
                // Tax is taken in K-Crypto
                double cryptoTax = cryptoAmount * taxRate;
                double netCryptoToConvert = cryptoAmount - cryptoTax;
                double netKCoins = netCryptoToConvert * rate;

                db.addCryptoBalance(playerUuid, -cryptoAmount);
                boolean deposited = db.creditKconomy(playerUuid, netKCoins);

                plugin.getServer().getRegionScheduler().execute(plugin, villager.getLocation(), () -> {
                    if (villager.isValid()) {
                        launderer.addTaxPool(villager, cryptoTax);
                    }
                    plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
                        if (deposited) {
                            player.sendMessage(cfg.fmt("§a✓ Vente réussie: §e" + cryptoAmount + " K-Crypto §a→ §e" + String.format("%.2f", netKCoins) + " KCoins"));
                        } else {
                            player.sendMessage(cfg.fmt("§cErreur de dépôt SQL."));
                        }
                    });
                });
            });
        });
    }

    private void handleBuy(Player player, Villager villager, double cryptoAmount) {
        UUID playerUuid = player.getUniqueId();
        double rate = economy.getRate();

        plugin.getServer().getRegionScheduler().execute(plugin, villager.getLocation(), () -> {
            if (!villager.isValid()) return;
            double taxRate = launderer.getTaxRate(villager) / 100.0;
            double currentPool = launderer.getTaxPool(villager);

            if (currentPool >= 1000.0) {
                player.sendMessage(cfg.fmt("§cCe Blanchisseur est saturé et ne peut plus percevoir de taxes."));
                return;
            }

            double kcoinCost = cryptoAmount * rate;
            double cryptoTax = cryptoAmount * taxRate;
            double totalCryptoCost = cryptoAmount + cryptoTax; // Not used
            // When buying crypto, you pay KCoins and get Crypto.
            
            plugin.getServer().getAsyncScheduler().runNow(plugin, t -> {
                // Here we need to deduct KCoins and add K-Crypto
                double kcoinBalance = db.getKconomyBalance(playerUuid);
                if (kcoinBalance < kcoinCost) {
                    plugin.getServer().getGlobalRegionScheduler().execute(plugin, () ->
                            player.sendMessage(cfg.fmt("§cSolde KCoins insuffisant. Il vous faut §e" + String.format("%.2f", kcoinCost) + " KCoins.")));
                    return;
                }

                boolean deducted = db.creditKconomy(playerUuid, -kcoinCost);
                if (!deducted) {
                    plugin.getServer().getGlobalRegionScheduler().execute(plugin, () ->
                            player.sendMessage(cfg.fmt("§cErreur lors du retrait de vos KCoins.")));
                    return;
                }

                double netCryptoReceived = cryptoAmount - cryptoTax;
                db.addCryptoBalance(playerUuid, netCryptoReceived);

                plugin.getServer().getRegionScheduler().execute(plugin, villager.getLocation(), () -> {
                    if (villager.isValid()) {
                        launderer.addTaxPool(villager, cryptoTax);
                    }
                    plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
                        player.sendMessage(cfg.fmt("§a✓ Achat réussi: §e" + String.format("%.2f", kcoinCost) + " KCoins §a→ §e" + String.format("%.2f", netCryptoReceived) + " K-Crypto"));
                    });
                });
            });
        });
    }
}
