# W-061: Fail-closed världsinteraktionsskydd i den dedikerade botprofilen

WeSort måste kunna köras obevakat på en anarkiserver utan regionskydd eller
annat server-side byggskydd. Därför läggs en **fail-closed client-side-gräns**
mellan agenten och världen: en central beslutspunkt som nekar varje fysisk
handling som inte uttryckligen hör till ett godkänt lagerjobb.

## När skyddet är aktivt

`StashManager.dedicatedProfile()` är `true` när både `server-url` och
`api-key` är ifyllda, det vill säga när Fabric-agenten är kopplad till en
WeSort Manager. I den profilen gäller skyddet **alltid**, även utanför
WeSort-ägda promenader och mål.

## WorldInteractionGuard

`WorldInteractionGuard` (i `src/26.3/java/.../compat`) är den centrala
grinden för blockanvändning och blockbrytning. I dedikerad profil nekas:

- dörrar, trapdoors, staketgrindar, knappar och spakar,
- tryckplattor, tripwire, detector rails, sculk-sensorer och shriekers
  (passiva utlösare),
- redstone, repeaters, comparators, observers, pistons, dispensers, droppers,
  trapped chests, crafter, bell, lectern, daylight detectors, lightning rods,
  powered/activator rails samt nether-/end-portaler och gateways.

Både `BlockInputMixin` (Meteor-input) och `InventorySync` (utgående paket)
leder användning/brytning genom samma guard, så det finns ingen alternativ
väg förbi. Ett förbjudet paket avbryts **före** klientprediktion.

## NavigationGuard

`NavigationGuard` kontrollerar laddade celler vid fötterna, huvudet och
golvet längs rutten. Agenten får gå över tryckplattor, tripwire, detector
rails och sculkblock som reagerar på att agenten passerar. Servern kan
aktivera blocken och eventuell kopplad redstone under passagen. Spärren
för aktiv användning och brytning gäller även dessa block.

`NavigationRuntime` pausar med `PAUSED_NAVIGATION_UNSAFE_BLOCK` om rutten
kräver passage genom en spärrad dörr, grind eller ett annat spärrat block.

## Dedikerad Baritone-profil

`BaritoneHelper.enforceAgentProfile()` håller Baritone permanent
icke-destruktiv i dedikerad profil: `allowBreak=false`, `allowPlace=false`,
`allowBreakAnyway` tömd, inga vätskebucket-/parkour-/sprint-/fallundantag.
Profilen återställs även efter `unconfine()`/`unprotect()`, så osäkra
sparade inställningar återställs inte efter ett jobb. En främmande
Baritone-process (`#goto`, `#mine`, `#build` eller ett externt mål) stoppas
och pausar som `PAUSED_NAVIGATION_OTHER_PROCESS`, även mellan WeSort-jobb.

## Testbevis

`scripts/mc26/navigation-harness.py` utökar navigationsprovet med
`dedicated_guard_cases()`:

| Prov | Kontroll |
|---|---|
| Staketgrind, trapdoor, spak | Klienten vägrar use/break; serverns blockstates matchar referensen |
| Tryckplatta i rutten | Baritone når och passerar plattan. Servern bekräftar att plattan aktiveras och sedan släpper. |
| Osäker Bot-profil + externt mål | `allowBreakAnyway` töms, externt mål avbryts, `PAUSED_NAVIGATION_OTHER_PROCESS` |

`mc26-navigation.yml` kör samma prov med Xvfb/Mesa i CI.

## Begränsningar

Skyddet garanterar endast att **egen klient** inte skickar förbjudna
handlingar enligt verifierad policy. Det hindrar inte andra anarkispelare
från att grieffa, och det kan inte upptäcka ofrivilligt spelarbeteende utan
serverstöd. PvP, död, cargo och anticheat behandlas separat i W-052; anvil-
registrering och ordernamn (W-063/W-064) ska passera samma grind när de
implementeras. Meteor-modul-allowlist och Manager-policy för arbetsytor/
rutter återstår som separata steg i W-061.
