# Steg 2 – Minecraft 26.3 och testplattform

Steget omfattar W-013, W-014, W-045, W-015, W-016 och W-017 från issue #1.
Arbetet ligger direkt på main, i separata W-ID-commits som pushas före nästa
issue. Inga GitHub-checklistor eller issuestatusar ändras av implementationen.

| Issue | Kod och verkligt underlag                                                                                                                                |
| ----- | -------------------------------------------------------------------------------------------------------------------------------------------------------- |
| W-013 | `d5c44d0`: låst Java 25.0.5+8, Gradle 9.6, Loom 1.17.21, Loader 0.19.5, Meteor 26.3 build 22 och riktig Baritone 1.4.0-26.3; minimal verklig klientstart |
| W-014 | `2e95e52`: hela addonens 26.3-build, versionsadaptrar och riktig GUI-/klientstart; även start i användarens anvisade Prism-instans                       |
| W-045 | `76ea83c`: reproducerbar privat vanilla-server, extern Fabric-agent, fixturelager och egen Docker Manager-volym                                          |
| W-015 | `3516dad`: verkliga inventory-/blockoperationer, full serverläsning, timeout, beständig pending och riktig JVM-omstart utan återspelning                 |
| W-016 | `4bfe2c5`: riktig Baritone-rundtur, höjd/chunkgräns, otillgängligt mål, cancel, faktisk 60-sekunderstimeout och dimensionsbyte                           |
| W-017 | Denna commit: genererade 26.3-resurser, kanoniska komponentvarianter, isolerad officiell assetcache, verklig Docker-/webb-/Stash Window-verifiering      |

Detaljer: [toolchain/port](MC_26_3_PORT.md), [testvärld](MC_26_3_E2E.md),
[inventory](MC_26_3_INVENTORY.md), [navigation](MC_26_3_NAVIGATION.md) och
[index/ikoner/visning](MC_26_3_ASSETS.md). Rapporterna och den riktiga
Stash Window-skärmdumpen ligger i docs/verification. Opt-in-testdrivern
kräver uttryckligen sin egen markerade, nyskapade testprofil.

## Verifiering

Lokalt bygger samtliga tre klienttargets och kör vardera 452 enhetstester
utan fel. [Historiskt W-055-bevis:] Java 21 Manager körde då 131 tester: 130 passerade, ett befintligt test
för gamla 1.21.x-speljar hoppar över när dessa inte finns. De riktiga 26.3-
ikonfallen verifieras separat med faktisk officiell jar i Docker. Webbens
regressioner och Docker-smoke för Owner-bootstrap, signerade agentanrop,
volymrestart, processlås och verklig TLS-proxy passerar.

Minecraftprov startar riktiga separata processer och jämför serverns
oberoende NBT med klient- och Manager-data. Fördröjning sker på verklig
loopbacktrafik. Navigatorns timeout mäts med produktionsgränsen 60 sekunder.
Servern jämför hela lagerområdet inklusive block entities med en klon från
före navigering. Resurser genereras från verkligt bundna spelregister:
1 623 creative items och 1 182 säkra vanliga craftingrecept.

CI rapporteras separat från lokal spelkörning. Varje workflow laddar upp
commitbundna artifacts:

- [Baseline build](https://github.com/Crispy-Monke-eSports/WeSort/actions/workflows/build.yml): äldre klienter, Manager, webb och Docker.
- [Minimal toolchain](https://github.com/Crispy-Monke-eSports/WeSort/actions/workflows/mc26-toolchain.yml).
- [Hela klientens build/start/GUI](https://github.com/Crispy-Monke-eSports/WeSort/actions/workflows/mc26-client.yml).
- [Verklig grundvärld och signerad agent](https://github.com/Crispy-Monke-eSports/WeSort/actions/workflows/mc26-world.yml).
- [Inventory och avbrott/omstart](https://github.com/Crispy-Monke-eSports/WeSort/actions/workflows/mc26-inventory.yml).
- [Baritone-navigation](https://github.com/Crispy-Monke-eSports/WeSort/actions/workflows/mc26-navigation.yml).
- [Resurser, Docker-ikoner och UI](https://github.com/Crispy-Monke-eSports/WeSort/actions/workflows/mc26-assets.yml).

Manager-versionen höjs till `0.1.0-dev.1` för den nya GHCR-imagen;
`v0.1.0-dev.0` ersätts inte. [Releaseflödet](GHCR.md) verifierar kandidaten,
publicerar SBOM/version/SHA/digest och kontrollerar anonym pull/start.
Ingen kontodata eller befintlig värld används. Prism-provet verifierar
addonstart; världsscenarierna körs i separata nya spelprofiler.

## Avgränsning

Detta steg godkänner den portade testade 26.3-grunden. Full sortering,
kit/repack/crafting, global guildstock, reservationer, transaktionell
custodyjournal, en-agent-scheduler och verifierad slutleverans återstår i
senare steg. [Kvoter är borttagna.] Recepttabellen eller en lyckad QUICK_MOVE är inte bevis på en
full guildorder. Anticheat-plugin, långdistans och portalrouting ingår inte
i dessa privata vanilla-prov. Alla data börjar i egna tomma WeSort-kataloger;
inga Qwerte-data läses/importeras eller påverkas.
