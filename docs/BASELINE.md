> **HISTORISKT UPSTREAMUNDERLAG:** Det här dokumentet beskriver den ursprungliga Qwerte-/WeSort-baslinjen före portning. **Det är inte installations-, bygg- eller supportanvisningar.** WeSort stöder nu **endast Minecraft 26.3 (Java 25 för klienten)**. Gamla 1.21.4/1.21.11-byggkommandon här är enbart reproducerbar proveniens.

# W-002: baslinje

Källdatum: 2026-11-16. Orörd revision:
`be4635bc8b4e71add4fc411e2cdd7258c45eac90`. Se [UPSTREAM.md](UPSTREAM.md).

## Låsta verktyg och beroenden

| Del | Version |
|---|---|
| Bygg-JDK | Eclipse Temurin 21.0.9+10, Linux x86_64 (`.java-version`) |
| Gradle | Wrapper 9.2.0, SHA-256 verifieras av wrappern |
| Fabric Loader | 0.18.2 |
| Fabric Loom | 1.14.10 (basens `1.14-SNAPSHOT` löste till denna) |
| Klient 1.21.4 | Meteor 1.21.4-20250408.145004-42, Baritone API 1.3.0-1.21.4 |
| Klient 1.21.11 | Meteor 1.21.11-20260810.140237-86, Baritone API 1.3.0-1.21.11 |
| Manager | Java 21, Gson 2.13.2 |
| Tester | JUnit BOM 5.11.4 |

Meteor-versionerna är tidsstämplade snapshots, så en ny snapshot ersätter inte
baslinjen. Minecraft-targets är endast **1.21.4 och 1.21.11**. 26.3 är ännu
inte portad eller verifierad. Använd en ren Gradle-cache vid reproduktion;
artefakter kräver tillgång till upstreams Maven-/release-servrar.

## Körning

Sätt `JAVA_HOME` till Temurin 21.0.9+10 och använd samma JDK i PATH.
Kör klienterna sekventiellt eftersom de delar build-katalog:

```sh
./gradlew --no-daemon clean build -Pmc=1.21.4
python3 scripts/test-summary.py build
# Spara build/test-results och build/reports före nästa clean.
./gradlew --no-daemon clean build -Pmc=1.21.11
python3 scripts/test-summary.py build
./gradlew --no-daemon -p stash-manager-server clean build
python3 scripts/test-summary.py stash-manager-server/build
```

## Faktiska resultat från orörd bas

| Bygge | Tester | Fel | Skips | Resultat |
|---|---:|---:|---:|---|
| Klient 1.21.4 | 625 | 0 | 0 | PASS |
| Klient 1.21.11 | 625 | 0 | 0 | PASS |
| Manager | 189 | 0 | 1 | PASS, 188 godkända |

Manager-skip: `SpecialModelsTest.realJars`, antagandet säger att ingen lokal
1.21.4/1.21.11-clientjar hittades. Syntetiska asset-/ikonregressioner kördes.
`remapSourcesJar SKIPPED` är en Gradle-task, inte ett överhoppat JUnit-test.
1.21.4 har en befintlig deprecation-varning: `CustomData.getUnsafe()` i
`compat/LegacyItems.java`. Loom visar Mojangs mappinglicens. 1.21.11 visar
befintliga Baritone-remapvarningar för `onEntityCollision`/`method_9548`
(class_2560/class_3830). Dessa ska inte döljas eller beskrivas som nya fel.

## Funktionell gräns

Enhetstesterna täcker bland annat index/scan-data, kit-/craftplanering,
shulkerpackning och lokal ordermodell. Managers tester kör riktig HTTP,
signerade agentanrop, order, leveransinnehåll, auth, sessions och behörigheter.
Detta verifierar inte fysisk interaktion i Minecraft.

| Manuellt scenario | Status och fortsatt verifiering |
|---|---|
| Scan av chest/barrel och signkategorier | EJ KÖRT: behöver inloggad Minecraft-klient/testvärld |
| Repack, kit och Baritone-stashresa | EJ KÖRT: samma beroende, verifiera boxinnehåll och slots |
| Lokal order i Stash Window | EJ KÖRT: lägg seeds/dirt/pearls, packa och kontrollera leveranskistan |
| Minecraft 26.3 / Docker / GHCR | EJ KÖRT: senare roadmapsteg |

Inga speltester påstås godkända. Baslinjen identifierar befintliga warnings och
saknade integrationstester; ändringar efter basen får egna resultat per W-ID.
Inga Qwerte-data har lästs eller migrerats.

Efter versionslåsningen: båda klienttargets byggde igen (1.21.4: 15 s,
1.21.11: 16 s). Testresultaten återanvändes oförändrade via Gradles cache.
Jar-packningen använder stabil filordning och bevarar inte filernas tidsstämplar.
Manager-jar innehåller nu även originalets LICENSE, utöver fontlicenserna.
