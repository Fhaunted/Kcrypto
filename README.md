# Kcrypto (KKopia)

> **Plugin Folia** pour serveurs Minecraft 1.21+ — Économie crypto-virtuelle complète intégrée dans le jeu.

---

## 📋 Vue d'ensemble

**Kcrypto** est un plugin d'économie crypto-virtuelle développé pour les serveurs **Folia** (Paper 1.21+). Il introduit une crypto-monnaie in-game appelée **K-Crypto** dont le taux de conversion vers les KCoins (Vault) fluctue dynamiquement en fonction de la quantité totale en circulation. Les joueurs peuvent miner, échanger, et blanchir leur K-Crypto via des mécaniques immersives.

---

## ✨ Fonctionnalités

### ⛏️ K-Miner — Machine de Minage
- **Item personnalisé** : Le K-Miner est un bloc Observer avec un tag PDC (`kminer`) et un modèle custom (`CustomModelData: 1001`).
- **Recette de craft** :
  ```
  I O I
  R D R
  I O I

  I = Iron Ingot   O = Observer
  R = Redstone Block   D = Diamond
  ```
- **Fonctionnement** : Placez des minerais dans le GUI de la machine. Toutes les `tick-interval-seconds` (défaut : 30s), la machine consomme un minerai et génère de la K-Crypto.
- **Surchauffe** : Une fois la barre de chaleur à 100%, la machine entre en **overload** et se bloque pendant `overload-cooldown-seconds` (défaut : 30 min).
- **Propriétaire** : Seul le joueur qui a posé la machine peut ouvrir son GUI.
- **Persistance** : Toutes les machines et leur état (chaleur, stock, propriétaire) sont sauvegardés en base de données MySQL.

### 💱 Économie Dynamique
- **Taux flottant** : Le taux de conversion `1 K-Crypto → X KCoins` est recalculé automatiquement **toutes les 24h** en fonction de la quantité totale de K-Crypto en circulation.
  - Plus il y a de K-Crypto → taux bas (inflation).
  - Moins il y en a → taux élevé.
  - `rate-floor` empêche le taux de tomber à zéro.
- **Intégration Vault** : Les KCoins sont crédités directement sur les wallets de l'économie principale du serveur (table SQL `kconomy_wallets`).

### 🧹 Blanchisseur (Launderer NPC)
- **PNJ Villager** personnalisé invocable par les admins.
- Les joueurs avec la permission `Kcrypto.blanchisseur` peuvent interagir avec lui pour convertir leur K-Crypto en KCoins.
- Une **taxe configurable** (`launderer-tax-rate`, défaut : 5%) est prélevée sur chaque conversion et versée dans un pool de taxes propre au blanchisseur.
- Le blanchisseur peut retirer son pool de taxes accumulé via `/crypto withdraw`.

---

## 🎮 Commandes

### `/crypto` — Commandes joueur (`kcrypto.member`)

| Commande | Description |
|---|---|
| `/crypto wallet` | Affiche votre solde K-Crypto et sa valeur en KCoins au taux actuel. |
| `/crypto pay <joueur> <montant>` | Transfère de la K-Crypto à un autre joueur sans taxe. |
| `/crypto withdraw` | *(Blanchisseur uniquement)* Retire le pool de taxes accumulé vers votre compte KCoins principal. |

### `/kcrypto` — Commandes admin (`kcrypto.admin`)

| Commande | Description |
|---|---|
| `/kcrypto add <joueur> <montant>` | Ajoute de la K-Crypto au portefeuille d'un joueur. |
| `/kcrypto remove <joueur> <montant>` | Retire de la K-Crypto du portefeuille d'un joueur. |
| `/kcrypto admin rate` | Force immédiatement le recalcul du taux de conversion. |
| `/kcrypto admin resetheat` | Réinitialise la chaleur de la machine K-Miner sous vos pieds. |
| `/kcrypto admin spawnnpc` | Invoque un PNJ Blanchisseur à votre position. |
| `/kcrypto admin removenpc` | Supprime le PNJ Blanchisseur que vous visez. |
| `/kcrypto admin giveminer <joueur>` | Donne un K-Miner à un joueur. |
| `/kcrypto admin whitelist <joueur>` | Ajoute un joueur à la liste blanche d'une machine. |
| `/kcrypto admin blacklist <joueur>` | Retire un joueur de la liste blanche. |

---

## 🔑 Permissions

| Permission | Description | Défaut |
|---|---|---|
| `kcrypto.admin` | Accès complet à toutes les commandes admin. | OP |
| `kcrypto.member` | Accès aux commandes joueur (wallet, pay). | Tous |
| `Kcrypto.blanchisseur` | Accès au Blanchisseur et à `/crypto withdraw`. | False |

---

## ⚙️ Configuration (`config.yml`)

```yaml
database:
  host: "localhost"
  port: 3306
  name: "kcrp_main"
  user: "root"
  password: "password"
  economy-table: "kconomy_wallets"   # Table Vault/kconomy
  economy-column: "balance"
  pool-size: 10

economy:
  launderer-tax-rate: 5.0            # Taxe du Blanchisseur en %
  rate-floor: 0.01                   # Taux minimum (anti-division par zéro)
  base-rate: 100.0                   # Taux de départ (circulation = 0)

machine:
  tick-interval-seconds: 30          # Intervalle de tick des machines
  overload-cooldown-seconds: 1800    # Durée du cooldown de surchauffe (30 min)

messages:
  prefix: "§8[§6KKopia§8] §r"
  no-permission: "§cVous n'avez pas la permission d'effectuer cette action."
  launderer-denied: "§7Cet individu ne souhaite pas vous parler."
```

---

## 🗄️ Base de Données

Le plugin utilise **MySQL** avec **HikariCP** (shaded dans le JAR). Les tables suivantes sont créées automatiquement :

| Table | Description |
|---|---|
| `kcrypto_wallets` | Portefeuilles K-Crypto de chaque joueur (UUID + balance). |
| `kcrypto_machines` | Données persistantes de chaque machine (position, propriétaire, chaleur, stock). |
| `kcrypto_launderers` | Données des PNJ Blanchisseurs (UUID entité, propriétaire, pool de taxes). |

---

## 🔧 Installation

1. **Prérequis** :
   - Serveur Folia ou Paper **1.21+**
   - Java **21**
   - MySQL **5.7+** ou MariaDB
   - Plugin d'économie compatible Vault (`kconomy_wallets`)

2. **Installation** :
   - Téléchargez le JAR depuis la [page des releases](../../releases).
   - Placez-le dans le dossier `plugins/` de votre serveur.
   - Démarrez le serveur — le fichier `config.yml` est généré automatiquement.
   - Configurez vos identifiants MySQL dans `plugins/Kcrypto/config.yml`.
   - Redémarrez le serveur.

3. **Resource Pack (optionnel)** :
   - Le K-Miner utilise `CustomModelData: 1001` sur un bloc Observer.
   - Ajoutez votre modèle et texture custom dans votre resource pack pour avoir un rendu 3D personnalisé.

---

## 🏗️ Build depuis les sources

```bash
git clone https://github.com/Fhaunted/Kcrypto.git
cd Kcrypto
./gradlew build
```

Le JAR final se trouve dans `build/libs/KKopia-<version>.jar`.

---

## 📦 Release automatique (CI/CD)

Ce projet utilise **GitHub Actions** pour automatiser les releases. Chaque fois qu'un tag `v*` est poussé, le workflow :
1. Compile le plugin avec Gradle + Java 21.
2. Crée une release GitHub avec le JAR attaché et des notes auto-générées.

```bash
# Créer et pousser un tag de release
git tag v1.0.0
git push origin v1.0.0
```

---

## 📜 Licence

Projet privé — KCRP © 2026. Tous droits réservés.
