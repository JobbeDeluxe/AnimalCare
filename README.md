# AnimalCare Plugin / Tierpflege Plugin

## English

### Features
- Automatic pen detection that classifies animals as **WILD**, **PASTURE**, or **CAPTIVE** based on solid boundaries up to the configured radius.
- Persistent hunger system stored in each entity's `PersistentDataContainer`, with configurable loss/regeneration per status and starvation effects.
- Feeding gate that blocks manual breeding until animals are fully fed.
- Barrel-based troughs (named `[Trough]` by default) that consume stored food items to feed nearby animals automatically.
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

Rename a barrel to `[Trough]`, load it with approved items, and right-click it with more feed to activate the automatic feeding loop. Pens should be at least 12×12 blocks to count as a pasture; smaller enclosures are treated as captive pens.

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
- Fass-Tröge (standardmäßig mit dem Namen `[Trough]`), die eingelagerte Futteritems verbrauchen und umliegende Tiere automatisch versorgen.
- Umfassende Konfiguration für Tierlisten, Hungerraten, Erkennungsradien und Nachrichten.
- GitHub Actions Workflow, der bei jedem Push oder Pull Request automatisch baut und das fertige Jar als Artefakt bereitstellt.

### Konfiguration
Die Standardwerte liegen in `src/main/resources/config.yml` und werden beim ersten Start in den Plugin-Ordner kopiert.

- `pen.detection-radius`: Block-Radius für die Suche nach soliden Grenzen (Standard 15).
- `pen.min-pen-size-xz`: Mindestgröße (Breite/Länge) für eine Weide.
- `hunger.captive-loss` & `hunger.pasture-change`: Hungerverlust bzw. Regeneration pro Intervall.
- `trough.name-tag`: Name, den ein Fass tragen muss, um als Trog erkannt zu werden.

### Build

```bash
mvn -B package
```

Das Jar liegt anschließend im Ordner `target/`. Der mitgelieferte GitHub Actions Workflow baut automatisch auf dem Hauptbranch und bei Pull Requests.
