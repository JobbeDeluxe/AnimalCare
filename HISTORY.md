# AnimalCare Change History

## 1.0.0
- Initial public release with hunger tracking, pen classification (WILD/PASTURE/CAPTIVE), and trough automation.
- Includes bilingual configuration messages and GitHub Actions build workflow.

## 1.1.0
- Switched the automatic trough pairing to double barrels with lid animations while stocked, preventing accidental bone meal generation.
- Allowed wheat seeds as feed for both trough automation and manual feeding.
- Double-barrel troughs now store and consume visible inventory stacks from both barrels, keep lids open while paired, and support an optional debug stick for live trough/animal diagnostics.
- Hardened hunger processing so only configured animal types are affected, keeping villagers and other NPCs safe even when enclosed.
- Improved automatic feeding by proactively scanning stocked double-barrel troughs every cycle, so hoppers and manual inventory transfers trigger feeding reliably.
- Defaulted the debug tool to a wooden sword and aligned documentation/configuration to match.
