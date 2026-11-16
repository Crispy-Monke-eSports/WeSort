# Steg 0 – leverans och verifieringsgräns

Utgångspunkt: `be4635bc8b4e71add4fc411e2cdd7258c45eac90`.
Arbetet görs direkt på main enligt användarens instruktion. Varje GitHub-issue
har egen commit med W-ID och push; inga GitHub-issues stängs eller bockas av.
Godkännande av issues lämnas till den efterföljande granskningen.

| Issue | Leverans |
|---|---|
| W-002 / #3 | Låst upstream, verktyg/dependencies, GPL/OFL och faktiska ursprungliga testresultat |
| W-003 / #4 | Klientmatris, separat Manager, JUnit/skips, artefakter/versioner och read-only PR-policy |
| W-004 / #5 | Fil-/endpoint-/permissionmatris, beroenden, dataformat och risker |
| W-005 / #6 | Farming/ResetPearl och exklusiva hjälpare/tester borttagna |
| W-007 / #8 | Planner/schematics borta, kits/crafting/order kvar; negativa route-tester |
| W-008 / #9 | Proxy/console/world-viewer borta; settings endast eget scope, webb/botstatus/auth/audit kvar |
| W-010 / #11 | Dödkod/resurser/docs rensade; kvarvarande funktioner motiverade |
| W-011 / #12 | Egna namn/artefakter/logo, tomma schema-/symlinkskyddade data, inga legacyfallbacks; About/topics uppdaterade |

## Slutlig lokal verifiering

Temurin 21.0.9+10, wrapper Gradle 9.2.0, låsta Loom/Meteor/Baritone-versioner.

| Körning | Resultat |
|---|---|
| `clean build -Pmc=1.21.4` | PASS, 430 tester, inga fel/skips |
| `clean build -Pmc=1.21.11` | PASS, 430 tester, inga fel/skips |
| `-p stash-manager-server clean build` | PASS, 100 tester, inga fel, 1 skip (99 godkända) |
| `scripts/smoke-isolation.py` mot byggd jar | PASS: riktig start, signerat index/heartbeat/order/claim, omstart och isolerad backup; legacy SHA-256/mtime/mode oförändrade |
| Webb DOM/no-undef + legacy-storage-sentinel | PASS: login, orders/bots/activity/settings/stash, eget scope, inga gamla data lästa/ändrade |
| actionlint, diff-check, fake-addon --help | PASS |
| GPL-textens SHA-256 | Oförändrad från upstream |

Den kända Manager-skipen är SpecialModelsTest.realJars: ingen lokal klientjar
hittas av testets launcher-sökning. Det syntetiska assettestet körs. De
befintliga deprecation-/Baritone-remapvarningarna beskrivs i BASELINE.md.
Borttagna tester följer endast borttagna featurepaket; se REMOVED_FEATURES.md.
Två ursprungliga tester för återställning av gammal server-URL togs bort i
W-011 tillsammans med själva fallbacken; sex isoleringstester tillkom i båda
byggnaderna. De kvarvarande order-/kit-/craft-/inventorymodelltesterna körs.

GitHub Actions har en manuell trigger och samma bygge/tester, plus Manager-
process-test och webbregression. Hosted run-resultat måste läsas från Actions;
lokal verifiering är inte ett påstående om hosted CI.

## Återstående verifiering och nästa steg

Inga fysiska Minecraft-interaktioner eller riktig Meteor-klientstart har körts.
Chest/barrel/drop-off, shulker/repack/kit, Baritone-resa och lokal order i spelet
kräver testvärld och ska granskas som EJ KÖRDA, även för baslinjetargets.
Minecraft 26.3 är inte portad/verifierad. Docker/Compose/Owner-bootstrap/GHCR,
guildidentitet/reservationer/SELF-GUILD/kvoter/fleet och verifierad slutleverans
ligger i senare roadmapsteg. En färdigpackad/claimad testorder bevisar inte
fysisk leverans. Steg 1 påbörjas inte i denna leverans.
