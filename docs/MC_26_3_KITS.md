# W-022: Managerägda kit och exakt packning i Minecraft 26.3

Manager äger kitdefinitionerna i sin egna WeSort-volym, SQLite-schema 4.
Klientens fysiska KitBuilder finns kvar men skapar, redigerar, delar eller
raderar inga mallar. Ingen lokal kitkatalog upptäcks eller importeras. Gamla
Qwerte-data öppnas inte. Agenten håller endast tilldelad exekveringsdata i
minnet; klientomstart återställer aldrig ett packningstillstånd automatiskt.

## Operatörsflöde

Owner öppnar **Settings → Guild kits** i Manager-webben och anger serverns
identitet. De 27 slotarna väljs ur serverobserverade exakta itemvarianter.
Varje variant visar item-ID, fingerprint och fullständiga typbevarande
komponenter. Slotnummer, antal och även tomma slots ingår i mallen.
Bundle-innehåll bevaras. Shulkers inne i shulkers nekas.

Skapa/redigera/radera kräver Owner, vanlig webbinloggning och browser-write-
skydd. En ändring kräver aktuell revisionsnummer; samtidiga redigerare får
409 i stället för att skriva över varandra. Alla versioner bevaras i
`kit_template_versions`, även när den aktiva mallen tas bort. Actor, revision
och fingerprint auditloggas. Mallar är definitioner och skapar aldrig lager.

Owner väljer en sparad version, en färsk fysisk destination i rätt stash och
en exakt godkänd arbetsposition för placering/brytning. **Assign packing job**
skapar ett jobb för en box. Jobblistan visar status, pausorsak och agentens tidsstämplade observation av
färdiga/byggbara kopior och explicit materialbrist. Denna uppskattning är
inte en reservation eller en slutkvittens. Denna
operatörsåtgärd är separat från medlemsbeställning och slutlig utleverans.

## Tilldelning och fysisk exekvering

`/api/v1/kits/agent` kräver befintlig signerad agentautentisering och signerade
svar. Agenten får den fullständiga definitionen endast genom ett tilldelat
jobb. Publicering och nedladdning av en godtycklig full katalog nekas.
Tilldelningen låser server, stash, job-ID, mallrevision, fingerprint,
destination och arbetsposition. Minecrafts riktiga 26.3-codecs måste läsa
komponenterna och återskapa exakt samma fingerprint innan fysisk körning.

Fem samtidiga anspråk kan bara ge en vinnare. Ett privat exekveringstoken
krävs för förnyelse och kvittens; det visas inte i webblistan eller audit.
Manager har 15 sekunders lease, agenten högst 5 sekunder räknat från
förfrågans start. Fördröjda svar förlänger inte det fönstret. Jobbet har även
en absolut femminutersgräns. Ändrad/raderad mall eller förlorad lease ger
`NEEDS_RECONCILIATION`; en ny version ersätter aldrig en pågående packning.
Manageromstart spärrar tidigare aktiva jobb; agentomstart ger inget nytt
anspråk på den redan tilldelade fysiska operationen.

Den befintliga Fabric-scannern och KitBuilder kör utan att Sorter-modulen
behöver aktiveras manuellt. W-019:s exklusiva serverlease krävs dessutom,
så en öppen orderreservation eller annan agent spärrar materialflyttar.
Det är ett konservativt skydd; slot-/hierarkiallokering återstår i W-024.

KitBuilder räknar material efter fullständiga komponenter, inklusive damage,
repair cost, namn, enchantments, potiondata, bundles och numeriska NBT-typer.
Byggbara kopior använder tillgängliga lösa material, tomma boxar och
underskott i delvis byggda boxar utan dubbelräkning. Små kombinationer söks
exakt; större uppsättningar rapporterar en säker undre gräns. Felplacerade
men innehållsmässigt korrekta partials får sin layout rättad. Skyddade,
excluded och staging-källor får inte konsumeras. Observerade färdiga kit och
material som faktiskt får användas redovisas separat. En saknad variant ger
explicit brist, aldrig substitution.

Placering sker endast på den godkända arbetspositionen. Full serverreadback,
separat komponent-/antalsverifiering före brytning och exakt pickup-kvittens
använder samma skydd som W-021. Den färdiga boxen lagras i den tilldelade
källan; överblivet material återlämnas. `VERIFYING` kvarstår medan Manager
väntar på en ny lagerobservation. `COMPLETED` kräver exakt en extra box med
rätt innehåll i destinationen jämfört med jobbets baseline och en ny fysisk
revision. En befintlig identisk kitbox räcker inte. Kvittensen sparar
revision och snapshotfingerprint. Detta betyder inte `DELIVERED`.

## Verifiering och avgränsning

`KitTemplateTest`, `KitAvailabilityTest`, `KitCatalogTest`, `KitJobsTest` och
HTTP-behörighetstester verifierar format, exakta resurser, samtidighet,
versioner, beständighet, omstart och uteblivna/felaktiga kvittenser. Webbens
DOM-prov kör den riktiga editorn med 27 slots och provar revisionsbunden
POST, godkänd arbetsposition samt textvisning utan HTML-exekvering.

`scripts/mc26/kit-harness.py` kör en riktig vanilla 26.3-server, den externa
Fabric/Meteor-agenten och Docker Manager med egna nya världar och volymer.
Mallarna skapas via samma Manager-API som webben utifrån riktiga observationer.
Oberoende RCON-SNBT jämför rekursiva antal och komponenter före/efter.
Scenarier: pack, partial, repeat (två separata jobb med samma mallversion), repeat-version (äldre
färdigt kit bevaras när en ny version packas),
variant, reserved, abort, offline, version, restart.
CI finns i `.github/workflows/mc26-kits.yml`; lokala bevis och hosted CI-status
redovisas separat i `docs/verification/W-022-*`.

Efter fel får enbart testharnessen uttryckligen stänga menyn för att inventera
serverns cursor och räkna kvarstående boxar. Detta är manuell teståterställning,
inte automatisk produkt-recovery. Den kompletta fysiska fasjournalen och
operatörsreconciliation hör till W-023. Central skyddsprofil för samtliga
Meteor-/Baritone-processer och känsliga världsblock hör till W-061. W-022 är
inte i sig ett godkännande för obevakad drift på anarkiserver. Den återstående
sammanslagningen till en synlig WeSort Agent-modul och borttagning av hela
legacy-GUI:t följer W-010 och Manager-operatörsytorna i steg 4.

W-022:s första klient-CI stannade före kompilering när upstream tog bort den
låsta Meteor-snapshoten. Samma originalbinär, metadata, GPL-text och motsvarande
upstream-källkod finns därför i [den frysta Maven-källan](../vendor/README.md).
Ett separat CI-prov resolverar den med tom beroendecache och `--offline`, och
nekar en modifierad binär. Meteor-versionen och dess verifierade hash ändras inte.
