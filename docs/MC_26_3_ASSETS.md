# W-017: 26.3-index, resurser och visning

`python3 scripts/mc26/metadata-harness.py` startar en ny verklig vanilla-
server och Fabric-klient och jämför tabellerna i repot med spelets bundna
register och komponenter. `--write-resources` ersätter uttryckligen de två
genererade WeSort-filerna. Gamla Git Bash-generatorer för 1.21.x används
inte som källa för 26.3.

26.3-exporten innehåller 1 623 kreativa itemkategorier och 1 182 vanliga
craftingrecept. Recept hämtas från den checksumlåsta officiella klientjarens
vanilla-data och avkodas med Recipe.DIRECT_CODEC i den anslutna klienten.
Resultat med extra komponenter eller ingredienser med crafting remainder
utesluts, som i den äldre generatorn. Övriga stationer anges separat.
Fysisk crafting/kitpackning godkänns inte av en genererad tabell.
Block-itemlistan exporteras som diagnostiskt underlag i evidence, inte som
en oanvänd produktionstabell.

26.3-itemnycklar använder kanoniskt ordnad typbevarande NBT-komponentkodning före det befintliga
API v1-hashformatet. Custom data, bundleinnehåll, namn, enchantments och
damage bevaras som varianter. NBT-taltypen bevaras också: byte och int med
samma tal får olika nycklar, medan compound-nycklarnas ordning inte spelar roll. Shulkerinnehåll har fortsatt en separat gruppnyckel
som omfattar slottilldelning, innehåll, antal och boxvariant. Misslyckad
26.3-komponentkodning kastar fel; den får inte tyst göra varianten till ett
naket item-id. Äldre klientversioner behåller sina befintliga nycklar.
Detta ersätter inte den kommande fulla fysiska fingerprint-/custodymodellen.

## Ikoner i en tom Linux Docker-installation

Manager `0.1.0-dev.1` har 26.3-tabellen och använder korrekt version av
Minecraft-assets. En angiven lokal speljar måste ha rätt id i version.json;
en annan releases jar får inte fylla en 26.3-cache. Om ingen lämplig jar finns
hämtar Manager den officiella immutable 26.3-klientjaren från
[Mojangs objektserver](https://piston-data.mojang.com/v1/objects/e877b6a07acd633fb3bb475002175cec036e7b87/client.jar).
Den kontrollerar exakt 41 483 720 bytes och SHA1
`e877b6a07acd633fb3bb475002175cec036e7b87` före atomisk installation.
HTTP använder fast HTTPS-adress, inga redirects och begränsad timeout.

Binären distribueras inte i Manager-imagen. Den läses som ett asset-arkiv,
inte som körbar Minecraft-kod i containern. Cachen finns endast under den
egna WeSort-volymens `icons/sources/26.3/client-<sha1>.jar`; genererade ikoner
ligger i `icons/game-26.3-v2/<namespace>/<item>.png`. Ingen gammal Qwerte-cache
eller dess index används. V2-katalogen skiljer dessa verifierade renders från
tidigare försök att rendera 26.3 med en godtycklig override.

Standardläget auto och läget game kan rendera från spelet. För 26.3 ersätts
en saknad modell inte av en overifierad versionslös wiki-bild. Okänd/saknad
resurs ger den särskilda 16px magenta/svarta placeholdern; riktiga ikoner är
64px. Om officiella assets inte kan hämtas blir resultatet säker fallback;
en Manager-omstart försöker igen. Andra Minecraft-versioners befintliga
ikonkälla behålls. Alla caches ligger i samma skyddade WeSort-installation.

## Kör det verkliga visningsprovet

Bygg först en lokal Manager-kandidat enligt Dockerfile och kör med samma
JDK/grafik-/Dockerförutsättningar som [grundvärlden](MC_26_3_E2E.md):

```sh
docker build -t wesort-manager:mc26-tests .
npm ci --prefix web-tests
NODE_PATH="$PWD/web-tests/node_modules" \
  python3 scripts/mc26/assets-harness.py --manager-image wesort-manager:mc26-tests
```

Harnessen kräver en uttrycklig image. Den testar ny egen volym, riktig signerad
Fabric-scan och API v1, ETag/304, kreativa kategorier och separata varianter.
Fixtures innehåller tre swords, tre bundles med olika innehåll/custom data,
tre fyllda shulkergrupper med olika innehåll/färg samt vanliga material.
Alla tio item-id:n måste ge riktiga 64px PNG:er; shulkerfärger måste skilja
sig. Okänt id måste ge en annan 16px-placeholder. Serverns Items-SNBT ska
vara identisk före/efter.

Webbprovet kör den faktiskt Docker-serverade JavaScript-koden mot dess riktiga
API i JSDOM. Det kontrollerar synliga slots, separata custom-data-varianter,
damage-bars, enchantment-tooltips och shulkerinnehållsöverlägg. Det är DOM-
verifiering, inte ett påstående om browserpixel-rendering. Owner-sessionen
finns endast i processminne och publiceras inte i artifacts.

Stash Window aktiverar sin riktiga passiva capture före containerläsningarna.
Fönstret öppnas och renderas i den riktiga 26.3-klienten; dess lokala index
måste ha samma totals och tre shulkergrupper som Manager. Ingen syntetisk
UI-snapshot injiceras. [Riktig skärmdump](verification/W-017-stash-window.png).

Lokalt underlag: [resurser](verification/W-017-metadata.json),
[scan/ikoner/fönster](verification/W-017-assets.json),
[webb-DOM](verification/W-017-web.json) och
[inventory/omstartsregression efter typbevarande kodning](verification/W-017-inventory-regression.json). Lokala rapporter föregår issue-
committen; source_commit/dirty_worktree beskriver det. `mc26-assets.yml`
bygger kandidaten och kör både jämförelse och visningsprov på exakt pushad
commit. Artifacts omfattar endast evidence, inte spelprofil, API-nyckel,
cookies, speljar eller Manager-volym.
