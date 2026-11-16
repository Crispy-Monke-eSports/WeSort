# Upstream och licensspår

WeSort utgår från [Qwertze/Qwerte](https://github.com/Qwertze/Qwerte),
commit `be4635bc8b4e71add4fc411e2cdd7258c45eac90` (2026-11-06),
`Dev build 0.4.0-dev.10.11: Stash Window, Reset Pearl, scan and order fixes`.
Detta är även WeSort-repots orörda startrevision. Git-historiken bevaras.
Originalförfattare: Qwertze (`105510092+Qwertze@users.noreply.github.com`).
Committen anger också `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
WeSort-ändringar framgår av efterföljande commits med W-ID och länkad issue.

`LICENSE` är originalets oförändrade GNU GPL version 3:
SHA-256 `3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986`.
Behåll originalets upphovsrätts-/licensnotiser, ange ändringar och distribuera
GPL-texten med WeSort. Vid distribution av binärer måste motsvarande källkod,
inklusive nödvändiga byggskript och ändringar, göras tillgänglig enligt GPLv3.
Se LICENSE för de fullständiga villkoren. Ändra inte attribution till att
påstå att WeSort skrivit den återanvända stash-/shulker-/kitlogiken.

Inbäddade typsnitt har separata OFL-notiser i
`stash-manager-server/src/main/resources/licenses/fonts/`; dessa ska följa med
Manager-artefakten. Minecraft och dess officiella mappings ingår inte i
WeSorts GPL-licens. Loom visar Mojangs mappingvillkor vid bygge; distribuera
inte Minecraft eller fullständiga oförändrade mappings som WeSort-källkod.

Ingen Qwerte-användardata ingår i baslinjen eller migreras. Nya WeSort-data
införs separat i W-011. Historiska produktnamn i Git och denna attribution
är avsiktligt bevarade.
