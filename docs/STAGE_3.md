# Steg 3 – lagergrunden

W-018 (#19), W-019 (#20), W-020 (#21) och W-021 (#22) är implementerade.
W-022–W-024 återstår.
Aktuell klientpolicy är endast Minecraft 26.3 med Java 25; Manager kör Java 21.
Äldre klienttargets som nämns i tidigare verifieringar är historik från före versionsavvecklingen. Arbetet
ligger direkt på main och varje issue får en egen commit som pushas innan
nästa påbörjas. GitHubs checklistor och issuestatusar ändras inte.

## W-018 – observationsgrunden

Fysiska container-ID:n, serverbekräftade observationsbevis, SQLite-revisioner,
färskhetspolicy, automatisk rescan, guildsaldo, saldo per stash och fysiska
källor med dimension och senaste verifiering finns nu i koden. För gamla,
oåtkomliga och omstartade observationer ger noll orderbart lager men behåller
historiken. Läs [observationskontrakt och drift](MC_26_3_STOCK.md).

Rescan söker även efter nya och återställda containrar när scannern redan
kör. En verklig borttagen/återställd kista avslöjade den tidigare luckan i
den aktiva scannerns medlemslista; återupptäckten sker vid nästa säkra
jobbgräns och kräver sedan ny serveravläsning.

## Verifiering av W-018

Lokal JUnit: samtliga klienttargets kör 455 tester vardera utan fel. Manager
kör 160 tester, 159 passerar och ett befintligt prov mot gamla 1.21.x-speljar
hoppar över när de inte finns. Webbens DOM-prov visar guild-/stashsaldo och
osäkra fysiska källor, och behandlar itemnamn med HTML som vanlig text.
Det befintliga riktiga 26.3-provet för ikoner, webb/API och Stash Window
passerar också mot den nya Manager-imagen.
Inventory-regressionen jämför efter avbrott/omstart både variantmängder och
fulla fysiska innehållsfingerprints. Observationshashen kan ändras när en
källa blir stale och används därför inte som bevis på oförändrat innehåll.
Dockerproven verifierar non-root, noexec-/tmp, signerade anrop, Owner-bootstrap,
persistens, processlås och TLS; isoleringsprovet använder en egen ny datakatalog.

Det separata Minecraft-provet använder riktig vanilla 26.3, Fabric/Meteor/
WeSort, faktisk Baritone för scan-only-förflyttningar och Docker Manager.
Det verifierar ordnade fulla inventory-paket, tvetydiga dubbelöppningar,
vanlig/trapped/dubbel kista och barrel, faktisk QUICK_MOVE med retur, TTL,
förändrad servermängd, äldre signerad snapshot, blockerad/borttagen/återställd
källa samt Manager-omstart. Hela fixturens server-NBT är identisk före/efter.

Lokal spelrapport och skärmdump ligger i [verification](verification/).
De anger bascommit plus dirty-worktree eftersom provet kördes före denna
issue-commit. CI är separat: [physical stock](https://github.com/Crispy-Monke-eSports/WeSort/actions/workflows/mc26-stock.yml)
bygger commitens egen image och laddar upp commitbundna spelartefakter.
Befintliga baseline-/26.3-workflows körs också efter push; lokal spelkörning
är inte i sig bevis för en grön hosted CI-körning.

Manager-versionen höjs till `0.1.0-dev.2` för den nya image-publiceringen;
redan publicerade versioner ersätts inte. Releaseflödet ska även verifiera
SBOM och anonym pull/start av den publicerade digesten.

## W-019 – sortering och skydd

Denna commit bevarar och verifierar Signs/Tiers i riktig 26.3 samt rättar
partial-stack-planering, återläsning av markeringar och Baritone-överlämning.
Skydd för kit/staging, per-item-diagnostik och ett konservativt exklusivt
sorteringstillstånd spärrar samtidiga reservationer. Se [W-019](MC_26_3_SORTING.md).
Manager version är nu `0.1.0-dev.3` med SQLite-schema 2. Klienttargets har
455 godkända tester vardera, Manager har 166 tester (ett befintligt skip).
Verklig Sign/Tier-sortering, exakt metadata, overflow, full kapacitet,
reserverad stock och låg nivå-skydd ligger i `verification/W-019-*`.

## W-020 – fysisk hierarki

Shulkers, sparse slots, fullständiga komponenter och nested container-items har
nu en egen verifierad modell. SQLite-schema 3 lagrar plats- och innehållshistorik
atomiskt, separerar identiska boxars slotadresser och binder nya order till
källfingerprints. Detalj-API/webb följer stashbehörigheter och streamer-skydd.
Se [kontrakt och verifiering](MC_26_3_HIERARCHY.md).
Manager-versionen är `0.1.0-dev.4`. Klienten har 461 godkända JUnit-tester
och Manager 182, utan skip eller fel. Webbens DOM-prov och installations-
isolering passerar. Lokala spelresultat finns i
`verification/W-020-*`; hosted CI verifieras separat efter push.

## W-021 – repack, consolidation och bulk

Den riktiga pipelinen har en separat VERIFY-fas, exakta metadata-/antalvillkor,
full kvittens även för passiv pickup och verifierad lagring efter pickup.
Verktyg, utrymme, försök och absolut tid begränsas; osäkra utfall spärras.
Se [fasmodell, begränsningar och verkliga prov](MC_26_3_REPACK.md).
Lokalt passerar 467 klienttester och 188 Manager-tester, webbens DOM-prov,
15 verkliga repackscenarier samt inventory-/kraschregressionen.
Denna ändring kräver ingen ny Manager-version; publicerad W-020-backend
`v0.1.0-dev.4` behålls. Fysisk fasjournal/startup-recovery återstår i W-023.

## W-022 – Managerägda exakta kits

Kitmallar skapas, versionssätts, redigeras och raderas av Owner i Manager-
webben. Endast ett signerat tilldelat jobb skickar full exekveringsdata till
Fabric. Lokal inventarie→mall, filkatalog och Share/Delete är borttagna.
En packning låser 27 slots, exakta komponenter, version/hash, destination och
godkänd arbetsposition. Malländring, avbrott och omstart ger reconciliation
utan tyst substitution. Manager kräver en ny exakt fysisk lagringskvittens;
kit-estimat är tidsstämplad diagnostik och skapar inte saldo eller leverans.
Se [kontrakt, webbflöde och avgränsning](MC_26_3_KITS.md).

Manager är `0.1.0-dev.5`, SQLite-schema 4. Lokalt passerar 475 klienttester,
204 Manager-tester utan skip/fel, webb-DOM och actionlint. Tio riktiga
Minecraft 26.3-kitfall, Docker Manager-packning, repack-regression och
inventory/kraschregression finns i `verification/W-022-*`. Hosted CI måste
verifieras separat på den pushade committen. Central anarkisäkerhetsguard
W-061 samt samlad Agent-/Manager-operatörsyta W-010/steg 4 återstår.

## W-023 – fysisk journal och återhämtning

Klienten skriver intention och verifierat utfall till en privat,
atomisk journal. Manager speglar faser, revisioner och tillstånd via det
signerade API:t. Owner kan se möjliga orphaned shulkers och begära en
ny läsning från samma agent. Exakt återhämtning kvitterar den tidigare
flytten utan omtag. Delvis avbruten packning förblir spärrad när
hela operationens slutläge inte kan bevisas.
Se [journal, operatörsflöde och 26.3-proven](MC_26_3_JOURNAL.md).

## Återstående i steget

W-019:s [sortering och reservationsskydd](MC_26_3_SORTING.md) finns i koden.
Återstående är W-024 och den globala atomiska reservationshierarkin.
Orderhållningar är fortfarande aggregerade per stash (`reservationPrecision`
är `aggregate`); exakt fysisk reservation/allokering visas därför inte som
om den redan fanns. Delade fysiska källor räknas en gång men spärras för nya
order tills hierarkin finns. Full cross-stash-orderallokering hör till W-040.
Ingen Qwerte-data eller befintlig användarvärld läses/importeras.
