package com.kcrp.kcrypto.commands;

import com.kcrp.kcrypto.KcryptoPlugin;
import com.kcrp.kcrypto.config.ConfigManager;
import com.kcrp.kcrypto.database.DatabaseManager;
import com.kcrp.kcrypto.economy.EconomyManager;
import com.kcrp.kcrypto.economy.RateTask;
import com.kcrp.kcrypto.items.MinerItem;
import com.kcrp.kcrypto.machines.MachineData;
import com.kcrp.kcrypto.machines.MachineManager;
import com.kcrp.kcrypto.villager.LaundererManager;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * KcryptoCommand – handles all {@code /kcrypto} sub-commands.
 *
 * <pre>
 * /kcrypto wallet             → shows own K-Crypto balance            (kcrypto.member)
 * /kcrypto pay <joueur> <m>   → transfers K-Crypto without tax        (kcrypto.member)
 * /kcrypto withdraw           → drains launderer tax pool → Vault     (kcrypto.blanchisseur)
 * /kcrypto admin rate         → force 24h rate recalculation          (kcrypto.admin)
 * /kcrypto admin resetheat    → resets targeted machine heat          (kcrypto.admin)
 * /kcrypto admin spawnnpc     → spawns a launderer NPC                (kcrypto.admin)
 * /kcrypto add <joueur> <m>   → ajoute de la K-Crypto à un joueur     (kcrypto.admin)
 * /kcrypto remove <joueur> <m>→ retire de la K-Crypto d'un joueur     (kcrypto.admin)
 * </pre>
 *
 * <p>DB operations are always dispatched via {@code AsyncScheduler}.</p>
 */
public final class KcryptoCommand implements CommandExecutor, TabCompleter {

    private final KcryptoPlugin    plugin;
    private final MachineManager  machineManager;
    private final EconomyManager  economyManager;
    private final DatabaseManager db;
    private final LaundererManager launderer;
    private final ConfigManager   cfg;

    public KcryptoCommand(KcryptoPlugin plugin, MachineManager machineManager,
                         EconomyManager economyManager, DatabaseManager db,
                         LaundererManager launderer, ConfigManager cfg) {
        this.plugin         = plugin;
        this.machineManager = machineManager;
        this.economyManager = economyManager;
        this.db             = db;
        this.launderer      = launderer;
        this.cfg            = cfg;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Execution
    // ────────────────────────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        String cmdName = command.getName().toLowerCase();
        if (args.length == 0) {
            sendHelp(sender, cmdName);
            return true;
        }

        if (cmdName.equals("crypto")) {
            return switch (args[0].toLowerCase()) {
                case "wallet"   -> handleWallet(sender);
                case "pay"      -> handlePay(sender, args);
                case "withdraw" -> handleWithdraw(sender);
                default         -> { sendHelp(sender, cmdName); yield true; }
            };
        } else if (cmdName.equals("kcrypto")) {
            return switch (args[0].toLowerCase()) {
                case "admin"    -> handleAdmin(sender, args);
                case "add"      -> handleAddRemove(sender, args, true);
                case "remove"   -> handleAddRemove(sender, args, false);
                default         -> { sendHelp(sender, cmdName); yield true; }
            };
        }
        return false;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  /kkopia wallet
    // ────────────────────────────────────────────────────────────────────────

    private boolean handleWallet(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(cfg.fmt("§cCette commande est réservée aux joueurs."));
            return true;
        }
        if (!player.hasPermission("kcrypto.member")) {
            player.sendMessage(cfg.getMsgNoPermission());
            return true;
        }

        plugin.getServer().getAsyncScheduler().runNow(plugin, t -> {
            double balance = db.getCryptoBalance(player.getUniqueId());
            double inKCoins = economyManager.cryptoToKCoins(balance);

            plugin.getServer().getGlobalRegionScheduler().execute(plugin, () ->
                    player.sendMessage(cfg.fmt(
                            "§7Votre portefeuille K-Crypto: §e" + String.format("%.6f", balance)
                                    + " §7KCrypto §8(≈ §a" + String.format("%.2f", inKCoins)
                                    + " §7KCoins §8@ taux §6" + String.format("%.4f", economyManager.getRate()) + "§8)"
                    ))
            );
        });
        return true;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  /kcrypto pay
    // ────────────────────────────────────────────────────────────────────────

    private boolean handlePay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(cfg.fmt("§cCette commande est réservée aux joueurs."));
            return true;
        }
        if (!player.hasPermission("kcrypto.member")) {
            player.sendMessage(cfg.getMsgNoPermission());
            return true;
        }
        if (args.length < 3) {
            player.sendMessage(cfg.fmt("§cUsage: /crypto pay <joueur> <montant>"));
            return true;
        }

        String targetName = args[1];
        if (targetName.equalsIgnoreCase(player.getName())) {
            player.sendMessage(cfg.fmt("§cVous ne pouvez pas vous payer vous-même."));
            return true;
        }

        double amount;
        try {
            amount = Math.ceil(Double.parseDouble(args[2]));
            if (amount <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            player.sendMessage(cfg.fmt("§cMontant invalide."));
            return true;
        }

        plugin.getServer().getAsyncScheduler().runNow(plugin, t -> {
            @SuppressWarnings("deprecation")
            org.bukkit.OfflinePlayer target = plugin.getServer().getOfflinePlayer(targetName);
            if (!target.hasPlayedBefore() && !target.isOnline()) {
                plugin.getServer().getGlobalRegionScheduler().execute(plugin, () ->
                        player.sendMessage(cfg.fmt("§cJoueur introuvable.")));
                return;
            }

            double balance = db.getCryptoBalance(player.getUniqueId());
            if (balance < amount) {
                plugin.getServer().getGlobalRegionScheduler().execute(plugin, () ->
                        player.sendMessage(cfg.fmt("§cSolde K-Crypto insuffisant.")));
                return;
            }

            db.addCryptoBalance(player.getUniqueId(), -amount);
            db.addCryptoBalance(target.getUniqueId(), amount);

            plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
                player.sendMessage(cfg.fmt("§aVous avez envoyé §e" + amount + " K-Crypto §aà §e" + target.getName()));
                if (target.isOnline() && target.getPlayer() != null) {
                    target.getPlayer().sendMessage(cfg.fmt("§aVous avez reçu §e" + amount + " K-Crypto §ade la part de §e" + player.getName()));
                }
            });
        });
        return true;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  /kcrypto withdraw
    // ────────────────────────────────────────────────────────────────────────

    private boolean handleWithdraw(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(cfg.fmt("§cCette commande est réservée aux joueurs."));
            return true;
        }
        if (!player.hasPermission("Kcrypto.blanchisseur")) {
            player.sendMessage(cfg.getMsgNoPermission());
            return true;
        }

        plugin.getServer().getAsyncScheduler().runNow(plugin, t -> {
            double drained = db.drainTaxPool(player.getUniqueId());

            if (drained <= 0) {
                plugin.getServer().getGlobalRegionScheduler().execute(plugin, () ->
                        player.sendMessage(cfg.fmt("§7Votre pool de taxes est actuellement vide."))
                );
                return;
            }

            // Deposit to kconomy
            boolean ok = db.creditKconomy(player.getUniqueId(), drained);

            final double finalDrained = drained;
            plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
                if (ok) {
                    player.sendMessage(cfg.fmt("§a✓ §e" + String.format("%.2f", finalDrained)
                            + " KCoins §ade taxes ont été déposés sur votre compte principal."));
                } else {
                    player.sendMessage(cfg.fmt("§cÉchec du dépôt SQL (§e"
                            + String.format("%.2f", finalDrained) + " §cKCoins). Contactez un admin."));
                }
            });
        });
        return true;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  /kcrypto add & remove
    // ────────────────────────────────────────────────────────────────────────

    private boolean handleAddRemove(CommandSender sender, String[] args, boolean add) {
        if (!sender.hasPermission("kcrypto.admin")) {
            sender.sendMessage(cfg.getMsgNoPermission());
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(cfg.fmt("§cUsage: /kcrypto " + (add ? "add" : "remove") + " <joueur> <montant>"));
            return true;
        }
        String targetName = args[1];
        double amount;
        try {
            amount = Double.parseDouble(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(cfg.fmt("§cMontant invalide."));
            return true;
        }

        plugin.getServer().getAsyncScheduler().runNow(plugin, t -> {
            @SuppressWarnings("deprecation")
            org.bukkit.OfflinePlayer target = plugin.getServer().getOfflinePlayer(targetName);
            if (!target.hasPlayedBefore() && !target.isOnline()) {
                plugin.getServer().getGlobalRegionScheduler().execute(plugin, () ->
                        sender.sendMessage(cfg.fmt("§cJoueur introuvable."))
                );
                return;
            }

            double actualAmount = add ? amount : -amount;
            db.addCryptoBalance(target.getUniqueId(), actualAmount);

            plugin.getServer().getGlobalRegionScheduler().execute(plugin, () ->
                    sender.sendMessage(cfg.fmt("§aVous avez " + (add ? "ajouté" : "retiré") + " §e" + amount + " §aK-Crypto à §e" + target.getName()))
            );
        });
        return true;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  /kcrypto admin <sub>
    // ────────────────────────────────────────────────────────────────────────

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("kcrypto.admin")) {
            sender.sendMessage(cfg.getMsgNoPermission());
            return true;
        }
        if (args.length < 2) {
            sendAdminHelp(sender);
            return true;
        }

        return switch (args[1].toLowerCase()) {
            case "rate", "refresh" -> handleAdminRate(sender);
            case "resetheat"  -> handleAdminResetHeat(sender);
            case "spawnnpc"   -> handleAdminSpawnNpc(sender);
            case "removenpc"  -> handleAdminRemoveNpc(sender);
            case "giveminer"  -> handleAdminGiveMiner(sender, args);
            case "whitelist"  -> handleAdminWhitelist(sender, args, true);
            case "blacklist"  -> handleAdminWhitelist(sender, args, false);
            default           -> { sendAdminHelp(sender); yield true; }
        };
    }

    private boolean handleAdminRate(CommandSender sender) {
        sender.sendMessage(cfg.fmt("§eForçage de la mise à jour du taux..."));
        plugin.getServer().getAsyncScheduler().runNow(plugin,
                t -> new RateTask(plugin, db, economyManager, launderer, cfg).run());
        sender.sendMessage(cfg.fmt("§aTaux mis à jour! Consultez la console pour le résultat."));
        return true;
    }

    private boolean handleAdminResetHeat(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(cfg.fmt("§cUtilisez cette commande en jeu, debout sur une machine."));
            return true;
        }
        Location loc = player.getLocation().getBlock().getRelative(
                org.bukkit.block.BlockFace.DOWN).getLocation();

        // Try the block under the player and the block at the player's position
        boolean reset = machineManager.forceResetHeat(loc);
        if (!reset) {
            reset = machineManager.forceResetHeat(player.getLocation());
        }
        if (!reset) {
            player.sendMessage(cfg.fmt("§cAucune machine K-Miner trouvée à votre position."));
        } else {
            player.sendMessage(cfg.fmt("§aChaleur de la machine réinitialisée à 0."));
        }
        return true;
    }

    private boolean handleAdminSpawnNpc(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(cfg.fmt("§cCette commande est réservée aux joueurs."));
            return true;
        }
        Location loc = player.getLocation();
        // Must execute on region thread — we're already there (command fires on region thread in Folia)
        launderer.spawnLaunderer(player.getUniqueId(), loc);
        player.sendMessage(cfg.fmt("§a✓ Le Blanchisseur a été invoqué à votre position."));
        return true;
    }

    private boolean handleAdminRemoveNpc(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(cfg.fmt("§cCette commande est réservée aux joueurs."));
            return true;
        }
        org.bukkit.entity.Entity target = player.getTargetEntity(5);
        if (target == null || !launderer.isLaunderer(target)) {
            player.sendMessage(cfg.fmt("§cVous devez viser un PNJ Blanchisseur."));
            return true;
        }
        target.remove();
        player.sendMessage(cfg.fmt("§a✓ Blanchisseur supprimé."));
        return true;
    }

    private boolean handleAdminGiveMiner(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(cfg.fmt("§cUsage: /kcrypto admin giveminer <joueur>"));
            return true;
        }
        Player target = plugin.getServer().getPlayer(args[2]);
        if (target == null) {
            sender.sendMessage(cfg.fmt("§cJoueur hors-ligne ou introuvable."));
            return true;
        }
        target.getInventory().addItem(MinerItem.build(plugin));
        sender.sendMessage(cfg.fmt("§aVous avez donné 1 K-Miner à §e" + target.getName()));
        return true;
    }

    private boolean handleAdminWhitelist(CommandSender sender, String[] args, boolean add) {
        // Placeholder: a full whitelist system would require a DB table.
        // Implementation left intentionally minimal to avoid scope creep; extend as needed.
        if (args.length < 3) {
            sender.sendMessage(cfg.fmt("§cUsage: /kcrypto admin " + (add ? "whitelist" : "blacklist") + " <joueur>"));
            return true;
        }
        String target = args[2];
        sender.sendMessage(cfg.fmt((add ? "§a" : "§c") + target
                + (add ? " §aajouté à la liste blanche." : " §cretrait de la liste blanche.")));
        // TODO: persist in DB table `kkopia_whitelist` when extended
        return true;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Tab completion
    // ────────────────────────────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        String cmdName = command.getName().toLowerCase();
        
        if (args.length == 1) {
            if (cmdName.equals("crypto")) {
                if (sender.hasPermission("kcrypto.member")) {
                    completions.add("wallet");
                    completions.add("pay");
                }
                if (sender.hasPermission("Kcrypto.blanchisseur")) {
                    completions.add("withdraw");
                }
            } else if (cmdName.equals("kcrypto")) {
                if (sender.hasPermission("kcrypto.admin")) {
                    completions.add("add");
                    completions.add("remove");
                    completions.add("admin");
                }
            }
        } else if (args.length == 2 && cmdName.equals("kcrypto") && args[0].equalsIgnoreCase("admin")
                && sender.hasPermission("kcrypto.admin")) {
            completions.addAll(List.of("rate", "refresh", "resetheat", "spawnnpc", "removenpc", "giveminer", "whitelist", "blacklist"));
        }
        // Filter by what the player has typed so far
        String partial = args[args.length - 1].toLowerCase();
        completions.removeIf(s -> !s.toLowerCase().startsWith(partial));
        return completions;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Help messages
    // ────────────────────────────────────────────────────────────────────────

    private void sendHelp(CommandSender sender, String cmdName) {
        sender.sendMessage(cfg.fmt("§eCommandes disponibles:"));
        if (cmdName.equals("crypto")) {
            if (sender.hasPermission("kcrypto.member")) {
                sender.sendMessage("  §6/crypto wallet §7- Voir votre solde K-Crypto");
                sender.sendMessage("  §6/crypto pay <joueur> <m> §7- Payer un joueur sans taxe");
            }
            if (sender.hasPermission("Kcrypto.blanchisseur"))
                sender.sendMessage("  §6/crypto withdraw §7- Retirer votre pool de taxes");
        } else if (cmdName.equals("kcrypto")) {
            if (sender.hasPermission("kcrypto.admin")) {
                sender.sendMessage("  §6/kcrypto add <joueur> <m> §7- Ajouter de la K-Crypto");
                sender.sendMessage("  §6/kcrypto remove <joueur> <m> §7- Retirer de la K-Crypto");
                sender.sendMessage("  §6/kcrypto admin §7- Commandes administratives");
            }
        }
    }

    private void sendAdminHelp(CommandSender sender) {
        sender.sendMessage("§e/kcrypto admin <sous-commande>:");
        sender.sendMessage("  §6rate/refresh §7- Force une mise à jour du taux");
        sender.sendMessage("  §6resetheat §7- Reset la chaleur de la machine sous vous");
        sender.sendMessage("  §6spawnnpc §7- Invoque un Blanchisseur à votre position");
        sender.sendMessage("  §6removenpc §7- Supprime le Blanchisseur que vous visez");
        sender.sendMessage("  §6giveminer <joueur> §7- Donne un K-Miner au joueur");
        sender.sendMessage("  §6whitelist <joueur> §7- Ajoute à la whitelist");
        sender.sendMessage("  §6blacklist <joueur> §7- Retire de la whitelist");
    }
}
