# W-013: verifierad Minecraft 26.3-toolchain

Kontrollerad 2026-11-16 mot faktiska upstreamartefakter. Det separata projektet
`tools/mc26-toolchain` bygger en minimal Meteor-addon utan att ändra WeSorts
1.21.4/1.21.11-baslinje. Lokal riktig klientstart passerar; detta är inte ett
resultat för den ännu oportade WeSort-klienten eller fysisk lagerautomation.

| Komponent | Låst provversion | Verifiering |
|---|---|---|
| Minecraft | 26.3, release 2026-09-15 | Officiell manifest, verklig klientstart |
| Java | Temurin 25.0.5+8 | SHA256-verifierad JDK, faktisk runtime |
| Gradle | 9.6.0 | Separat wrapper med distributionSha256Sum |
| Fabric Loom | 1.17.21, `net.fabricmc.fabric-loom` | Bygg och runClient |
| Fabric Loader | 0.19.5 | Faktiskt laddad i klient |
| Mappings | Inga externa mappings/remap | Minecraft är unobfuscated; Mojangnamn direkt |
| Meteor | 26.3-20261115.173242-22, runtime 26.3-22 | Officiell Maven-jar, laddad addon |
| Baritone | dekrom 1.4.0-26.3, **api-fabric** | Kompilering och faktisk primary-provider |
| Fabric API | Meteor-bundlad base 2.0.6+fcdff87f5d och resource-loader 3.0.4+ba0d6c0d5d | Laddade i klient |
| Full Fabric API | 0.162.0+26.3 finns publicerad | Inte separat nödvändig eller testad i provet |

Meteors jar anger source commit `474da7c33d391109a4a1feea288ea93ea696cfa9`.
Dekroms release anger source commit `843427f6ae042b4c13530a66c2c50f8c26975260`.
Ingen flytande Meteor-SNAPSHOT används som dependency-version.

## Artefakter och reproduktion

Officiella källor:

- [Mojangs versionsmanifest](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json)
  och [26.3 metadata](https://piston-meta.mojang.com/v1/packages/9f67e10ed013deeaad20ac3c800652765d493408/26.3.json).
- [Fabric 26.3-portningsguide](https://fabricmc.net/2026/09/15/263.html),
  [Loader API](https://meta.fabricmc.net/v2/versions/loader/26.3/0.19.5)
  och [Loom Maven](https://maven.fabricmc.net/net/fabricmc/fabric-loom/).
- [Meteors exakta snapshotmetadata](https://maven.meteordev.org/snapshots/meteordevelopment/meteor-client/26.3-SNAPSHOT/maven-metadata.xml)
  och [Meteors källversionsmatris](https://github.com/MeteorDevelopment/meteor-client/blob/474da7c33d391109a4a1feea288ea93ea696cfa9/gradle/libs.versions.toml).
- [Dekroms Baritone-release](https://github.com/dekrom/baritone/releases/tag/v1.4.0-26.3).

| Hämtad artefakt | SHA256 |
|---|---|
| Meteor jar, build 22 | `a61ec522cb02a97a7dc9108c107dedeee75c4286c4d96f3efd7c253b03b7940a` |
| Meteor sources, build 22 | `089418e278be1c362500e1634e34acd6398934bf26fad489c7597da4965f71e3` |
| Baritone api-fabric 1.4.0-26.3 | `5cc0aa320970b6f8aa5f618ff233842b50680e1eb90917087f1883461181cb08` |
| Gradle 9.6.0 bin | `bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01` |
| Temurin Linux x64 25.0.5+8 tar.gz | `68982529869257d0ee6b824ec5aa270ff8b118bb438670da0b97ebdab178d7fb` |

Baritone `standalone-fabric` har omdöpta publika API-metoder (`a()` i stället
för `getProvider()`) och är inte en binär ersättare för api-fabric. Använd
endast den API-bevarande jar som provas här. API-jaren innehåller även
implementationen; installera inte båda eftersom de deklarerar samma mod-ID.
Meteor identifierade denna implementation som Path Manager: Baritone.

Installera exakt Temurin 25.0.5+8, exportera JAVA_HOME och kör från reporoten:

```sh
python3 tools/mc26-toolchain/verify.py
# Linux utan desktop, med Xvfb och Mesa/OpenGL:
LIBGL_ALWAYS_SOFTWARE=1 SDL_VIDEODRIVER=x11 ALSOFT_DRIVERS=null \
  xvfb-run -a -s '-screen 0 1280x720x24' python3 tools/mc26-toolchain/verify.py
```

Första körningen hämtar spel, bibliotek och assets. Körningen använder en egen
tom gameDir under projektets ignorerade build-katalog. Inga konton eller
Qwerte-/befintliga speldata används. Den startar faktisk render-/clientloop,
kontrollerar runtimeversioner och Baritone-provider och stoppar klienten.
Timeout, icke-noll exit, saknad/felaktig rapport eller gammal rapport ger fel.
Logg och maskinrapport ligger i `build/evidence` under provprojektet och
publiceras av workflow `Minecraft 26.3 toolchain`.

## Portningsrisker och nästa gräns

- Java 25 krävs av Mojangs manifest; Manager ligger kvar på Java 21.
- Gamla remap-pluginet, `modImplementation` och officialMojangMappings ska
  inte användas för 26.3. Baslinjetargets behöver fortsatt sin gamla byggväg.
- `ResourceLocation` har ersatts av `Identifier`; klientens GUI/rendering,
  font-/itemrendering, screens och input har ändrade API:er. SDL ersätter GLFW.
- Mixins och packet targets måste verifieras mot 26.3 och auditas i runtime.
- Registry-/itemkomponenter och dynamiska reloadable registries har ändrats.
  Riktig snapshot-/shulker-/slotserialisering måste testas i W-015/W-017.
- Meteor-events och Baritone-koppling är laddningsbara i minimiaddonen;
  WeSorts samtliga moduler är ännu inte porterade (W-014).
- Ingen värld eller konto ansluts i W-013-provet. W-045 behövs före bevis om
  riktiga serverkvitterade inventory clicks (W-015) och navigation (W-016).

Lokalt: bygg och riktig klientstart PASS, inklusive Meteor mixin-audit,
Baritone-instans, render-/assetinit och ordnat stopp. Ursprungligt prototypfel
med saknad addon-author rättades innan slutkörningen. Se separat
[runtimeunderlag](verification/W-013-toolchain-runtime.json).
