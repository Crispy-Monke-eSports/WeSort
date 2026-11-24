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

## Ikoner och Mojang-resurser i Docker

Innan Manager kan starta krävs administratörens explicita EULA-acceptans
via `eula=true` i `config.properties` eller `WESORT_EULA=true` i
Docker Compose. I annat fall avslutas startsekvensen utan HTTP-tjänst
och utan några Minecraft-nedladdningar. EULA finns på
[www.minecraft.net/en-us/eula](https://www.minecraft.net/en-us/eula).
Äldre `acceptMinecraftDownload` och `minecraftJar` ignoreras;
Manager söker **aldrig** i lokala spelinstallationer eller launcher-filer.

När `eula=true` är aktiverat laddar Manager alltid ned (eller validerar
sin befintliga privata cache av) den officiella immutable Minecraft
26.3-klient-JAR:en från
[Mojangs objektserver](https://piston-data.mojang.com/v1/objects/e877b6a07acd633fb3bb475002175cec036e7b87/client.jar).
Endast exakt 41 483 720 byte och SHA-1
`e877b6a07acd633fb3bb475002175cec036e7b87` accepteras. HTTP
använder fast HTTPS-adress utan redirects, med begränsad timeout.
Misslyckas hämtning eller verifiering öppnas **inte** HTTP-porten;
administratören får korrigera nätverksproblemet och starta om.

Klienten används som ett passivt asset-arkiv för ikonrendering, inte
som ett körbart Minecraft-program. Den inkluderas inte i Managers Docker-image,
Git-repo eller releaser. Den verifierade källfilen finns under den privata
volymens `icons/sources/26.3/client-<sha1>.jar`, medan renderade
ikoner sparas under `icons/game-26.3-v2/<namespace>/<item>.png`.
Dessa filer ska inte distribueras vidare. Okända item-ID:n får
fortsatt en säker 16px-platshållare i stället för felaktiga texturer;
riktiga spelrenderade ikoner har storleken 64px.

Recept och Creative Tabs kan inte genereras av enbart arkivläsning
i Manager (Java 25): det befintliga 26.3-exportflödet använder
en aktiv Minecraft-klient på Java 25, bundna registries och receptcodec.
Genererade 26.3-JSON förblir därför separata resurser tills ett
verifierat klient-till-Manager-metadataflöde utvecklats.

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
committen; source_commit/dirty_worktree beskriver det. Den fristående Managern kan inte återskapa den kreativa katalogen eller
recepttabellen enbart från klient-JAR-arkivet. De genereras med en verklig
Minecraft 26.3-klient (Java 25), inlästa registries och receptcodec i
`src/26.3/java/com/porter/wesort/compat/MetadataExport.java` och
`MetadataRecipes.java`. `scripts/mc26/metadata-harness.py` kan jämföra och
exportera dem i ett kontrollerat separat prov. En säker flytt från incheckade
JSON-filer till opt-in-skapade runtime-data kräver ny klient-till-Manager-
metadataöverföring, versions-/integritetsverifiering och testtäckning; det
ingår därför inte i detta opt-in-PR. De befintliga 26.3-recept-/creative-tabs-
JSON-filerna lämnas orörda och deras redistribution behöver en separat bedömning.

`mc26-assets.yml`
bygger kandidaten och kör både jämförelse och visningsprov på exakt pushad
commit. Artifacts omfattar endast evidence, inte spelprofil, API-nyckel,
cookies, speljar eller Manager-volym.
