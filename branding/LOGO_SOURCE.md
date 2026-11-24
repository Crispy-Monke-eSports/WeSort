# WeSort logo – original och verifiering

## Original

WeSort-logotypen utgår från **WeAB.nu** i samma GitHub-organisation:
[WeAB.nu/public/logos/wesort.svg](https://github.com/Crispy-Monke-eSports/WeAB.nu/blob/main/public/logos/wesort.svg).
Git blob SHA-1 för den officiella källan: `0288c2962a8eb9572f70d45f15da78bd2cc798ec`.

`branding/wesort.svg` och `stash-manager-server/src/main/resources/web/wesort.svg` är **exakta kopior av originalfilen**, inte omritningar. Alla WE-bokstäver, stenbeslag, lager, färger, skuggor och gradients får inte ändras. Brand-color är `#E66A00`.

## Texten SORT under skylten

På WeAB.nu skapas texten under logotypen via [BrandMark.astro](https://github.com/Crispy-Monke-eSports/WeAB.nu/blob/main/src/components/BrandMark.astro). Den tar bort prefixet **We** från **WeSort** och visar suffixet **SORT** med **Press Start 2P** i färgen `#f5f7fa`, text-shadow `0 2px 5px #0009`, normal font-weight, line-height `1.65` och margin-top `.32em`.

`branding/wesort-lockup.svg` kombinerar det **oförändrade** officiella SVG-konstverket med konverterade vektorformer för `SORT` från samma Press Start 2P-typsnitt. Layouten använder webbplatsens `card`-proportion (`8.5rem` skyltbredd, `.85rem` textstorlek) i en upplösningsoberoende SVG. Webben använder samma identiska lockupfil under `/wesort-lockup.svg`. Glypherna är konturer (inga fontfiler eller fjärranrop krävs).

Fontfamiljen Press Start 2P distribueras av Google Fonts under **SIL Open Font License 1.1**. Typsnittets licens finns i `branding/PRESS_START_2P_LICENSE.txt`. Ingen binär fontfil ingår i projektet.

## Sidhuvud och inloggning

`branding/wesort-horizontal.svg` och Manager-resursen `/wesort-horizontal.svg` är identiska. De använder samma oförändrade WE-skylt och samma Press Start 2P-konturer för SORT som den stående lockupfilen, med enbart en ny viewBox och en enhetlig transform av suffixgruppen för horisontell placering. Appens sidhuvud använder den horisontella lockupen. Inloggning och första konfiguration använder den ursprungliga stående lockupen centrerad ovanför formuläret.

## Minecraft-ikon

`src/main/resources/assets/wesort/icon.png` är en 128×128 PNG-rendering av samma granskade SVG-lockup, med bibehållna proportioner. PNG-format behövs för moddens icon i Fabric och är därmed en rasteriserad användning av samma grafik.

## Regressionstester

`web-tests/smoke.cjs` säkerställer att SVG-källans Git-blobhash är exakt, att originalet är identiskt i Manager/webb och branding, att vektorkonturerna i lockup är oförändrade, att båda UI-ytorna använder rätt lockup och att den horisontella varianten behåller samma originalgrafik och glyphkonturer och att modikonens PNG har rätt SHA-256.

För att visa logotypen behöver ingen Google Fonts-anslutning användas. Symbolen är identisk med WeAB.nu-källan; den fristående lockupfilen behåller webbplatsens typsnitt och proportioner (renderingens kantutjämning kan variera mellan webbläsare).
