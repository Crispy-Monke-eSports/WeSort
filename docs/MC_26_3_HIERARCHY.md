# W-020: fysisk containerhierarki i Minecraft 26.3

Klienten stöder endast 26.3 med Java 25; Manager använder Java 21.
`physicalVersion=2` skickar en gemensam variantordlista och glesa rader
`[slot, variant, count]` per serverbekräftad källa. En shulker har 27 slots,
även när bara slot 26 är upptagen. Bundles och andra containerkomponenter
har egna barn. Samtidigt icke-tomt container- och bundleinnehåll på samma
item spärras som tvetydigt.

Varje variant bevarar item-ID, stackgräns och hela typbevarande
`DataComponentPatch.CODEC`-patchen, inklusive containerdata, namn, damage och
enchantments. Den äldre aggregerade UI-nyckeln behålls separat. Itemfingerprint
beskriver komponenter och ordnade barn; stackfingerprint inkluderar antal.
Källans snapshot inkluderar inventory-storlek och alla upptagna slotpositioner.

Identitet är `source-id/slot/path`, exempelvis `c:<hash>/20/26`. Källans ID
binder server, dimension och kanoniska blockpositioner, inklusive dubbelkista.
Fingerprint är innehåll, aldrig ett globalt UUID för en flyttbar Minecraft-box.
Identiska boxar kan därför ha samma fingerprint på skilda platser. En placerad
shulker observeras som blockinventory; systemet hittar inte på den metadata
som bara är känd när dess yttre item faktiskt har observerats.

Totalsummor härleds från trädet: varje yttre stack räknas en gång och varje
barn multipliceras med antalet föräldrakopior. Manager avvisar positiva
observationer vars items/shulkergrupper eller fingerprint avviker från trädet.
En ordlista delar komponentdata mellan identiska varianter. Djup begränsas
till 8, expanderade noder till 8192 per källa, komponenttext till 4 miljoner
tecken per snapshot och ordlistan till 16384 varianter; överskridanden spärras.

## Persistens och förändringar

SQLite-schema 3 migrerar tidigare WeSort-schema 1/2 inom en transaktion och
avvisar framtida schema. `physical_snapshots` bevarar historiska innehåll;
`physical_slots` håller aktuell plats, parent, komponenter, antal, fingerprint,
revision och present-status. Källor och stashmedlemskap innehåller små referenser
till snapshots. Detta undviker att hela lagerhierarkin måste laddas för saldo.
Alla uppdateringar sker i samma transaktion som observationsrevisionen.

En ny serveravläsning med identiskt innehåll förnyar källbeviset men behåller
slotrevisionerna. Ändrat innehåll, antal eller plats ändrar berörda revisioner.
Borttagna slots markeras frånvarande; historiken bevaras. Osäker avläsning eller
omstart gör innehållet osäkert och aldrig orderbart enbart genom gammal cache.
Äldre protokollobservationer kan läsas men ger inga påhittade exakta slotbevis.

Nya order mot hierarkikällor binder konservativt stashens aktuella
källfingerprints. En ändring spärrar claim även när tillräckligt aggregerat
material finns kvar. Bindningen överlever Manager-omstart; ingen automatisk
substitution till en annan box sker. Återställning måste kontrollera det fysiska
läget. Detta är ett innehållsvillkor, inte W-024:s kommande atomiska slotallokering.

## API och webb

`GET /api/v1/stock` behåller observed/reserved/available/unknown och ger små
hierarkireferenser. `GET /api/v1/stock/source?id=...` visar senaste verifierade
snapshot, källa, status, verifieringstid och aktuella slotrevisioner. Behörigt
stashmedlemskap kontrolleras före inläsning av komponenterna. Streamer-användare
kan inte hämta detaljer, och både ordlistan och privata referenser redigeras bort
från övriga svar. Webben laddar slotdetaljer vid expansion och använder vanlig
text för komponenter och itemnamn.

## Verifiering

JUnit testar ordlistedelning, glesa slots, komponenter, totalsummor, skilda
identiska boxar, nesting, cykler/djup, atomisk lagring, stabila revisioner,
historik, osäkerhet, äldre protokoll, behörigheter och fingerprintbundna order.
Webbens DOM-test kontrollerar detaljhämting och att komponenttext inte blir HTML.

`scripts/mc26/hierarchy-harness.py` använder en egen ny vanilla 26.3-värld,
Fabric/Meteor/WeSort och Docker Manager med egen volym och uttrycklig EULA.
Den verifierar tre identiska shulkers i två barrels, glesa 27-slot-data,
nested bundle, exakt swordmetadata, totalsummor, oförändrat server-NBT efter
läsning och en verklig innehållsförändring följd av ny avläsning. Äldre fångad
observation ska avvisas med 409. Lokala resultat redovisas separat från
commitbunden CI i workflow `mc26-hierarchy.yml`.

Repack, exakt fysisk kitpackning, återställningsjournal och global atomisk
slotreservation verifieras i W-021–W-024. Inga Qwerte-data används.
