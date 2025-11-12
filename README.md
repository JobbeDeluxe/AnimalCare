# AnimalCare Plugin / Tierpflege Plugin

## English

### Features
- Automatic pen detection that classifies animals as **WILD**, **PASTURE**, or **CAPTIVE** based on solid boundaries up to the configured radius.
- Persistent hunger system stored in each entity's `PersistentDataContainer`, with configurable loss/regeneration per status and starvation effects.
- Feeding gate that blocks manual breeding until animals are fully fed.
- Barrel-based troughs (named `[Trough]` by default) and automatic double-barrel troughs (place two barrels side by side) that consume the food stored directly inside their barrels, keep both lids propped open while active, and feed nearby animals automatically.
- Optional debug stick (configurable material) that reports trough status, detected animals, next feed cycle, and animal hunger when enabled.
- Actionable configuration with entity lists, hunger tuning, pen detection radius/size, and messaging.
- Continuous GitHub Actions build that produces a packaged plugin jar on every push or pull request.

### Configuration
`src/main/resources/config.yml` contains defaults you can copy to your server's `plugins/AnimalCare/config.yml`:

```
pen:
  detection-radius: 15
  min-pen-size-xz: 12
hunger:
  captive-loss: 6
  pasture-change: -1
  effects:
    low-threshold: 30
trough:
  name-tag: "[Trough]"
  max-feed-per-cycle: 4
```

- `debug.enabled`: Toggle the in-game debug stick that reports trough and animal information.
- `debug.tool`: Material name for the debug stick item (defaults to `STICK`).

Rename a barrel to `[Trough]`, or place two barrels directly next to one another. Double-barrel troughs pull feed from the visible inventory of both barrels, keep their lids permanently open while the pair is intact, and accept the same approved items (wheat, wheat seeds, carrots, potatoes, beetroot). Simply place food into either barrel to stock the trough; the automation loop consumes those stacks during each feed cycle. Pens should be at least 12×12 blocks to count as a pasture; smaller enclosures are treated as captive pens. Enable the optional debug stick in `config.yml` to inspect troughs and animals in-game.

### Building

```bash
mvn -B package
```

The shaded plugin jar is produced in `target/`. The included GitHub Action replicates this command on every merge or pull request.

---

## Deutsch

### Funktionen
- Automatische Gehege-Erkennung, die Tiere anhand von festen Grenzen (bis zum konfigurierten Radius) als **WILD**, **WEIDE** oder **GEHEGE** einstuft.
- Hunger-System pro Tier, gespeichert im `PersistentDataContainer`, inklusive einstellbarem Verlust/Regeneration und Verhungern-Schaden.
- Manuelles Füttern blockiert das Züchten, bis ein Tier vollständig satt ist.
- Fass-Tröge (standardmäßig mit dem Namen `[Trough]`) sowie automatische Doppel-Fass-Tröge (zwei Fässer nebeneinander), die das Futter direkt aus dem sichtbaren Inventar beider Fässer verbrauchen, ihre Deckel dauerhaft offen halten und umliegende Tiere automatisch versorgen.
- Optionaler Debug-Stock (Material in der Konfiguration einstellbar), der bei aktivierter Debug-Option Trog-Status, erkannte Tiere, den Zeitpunkt der nächsten Fütterung sowie den Hungerzustand von Tieren anzeigt.
- Umfassende Konfiguration für Tierlisten, Hungerraten, Erkennungsradien und Nachrichten.
- GitHub Actions Workflow, der bei jedem Push oder Pull Request automatisch baut und das fertige Jar als Artefakt bereitstellt.

### Konfiguration
Die Standardwerte liegen in `src/main/resources/config.yml` und werden beim ersten Start in den Plugin-Ordner kopiert.

- `pen.detection-radius`: Block-Radius für die Suche nach soliden Grenzen (Standard 15).
- `pen.min-pen-size-xz`: Mindestgröße (Breite/Länge) für eine Weide.
- `hunger.captive-loss` & `hunger.pasture-change`: Hungerverlust bzw. Regeneration pro Intervall.
- `trough.name-tag`: Name, den ein Fass tragen muss, um als Trog erkannt zu werden.
- `debug.enabled`: Aktiviert den Debug-Stock zur Anzeige von Trog- und Tierinformationen.
- `debug.tool`: Materialname für den Debug-Stock (Standard `STICK`).

### Build

```bash
mvn -B package
```

Das Jar liegt anschließend im Ordner `target/`. Der mitgelieferte GitHub Actions Workflow baut automatisch auf dem Hauptbranch und bei Pull Requests.
