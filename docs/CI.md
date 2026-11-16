# W-003: baslinje-CI

`.github/workflows/build.yml` bygger/testar båda klienttargets och det separata
Manager-projektet på Linux med `.java-version` och den checksummalåsta wrappern.
Wrappern är nu Gradle 9.6.0. 26.3-klienten har ett separat workflow med
Temurin 25.0.5+8 och verklig klient-/Stash Window-start under Xvfb/Mesa;
Java 21-baslinjen används fortsatt för båda 1.21-targets och Manager.
JUnit XML/HTML, explicit test-/skiplista, jar, licens och versionsmetadata laddas
upp även när ett test misslyckas. Artefaktnamnet innehåller target och commit-SHA.
Saknade JUnit-rapporter ger fel, inte ett missvisande resultat med noll tester.

PR-jobb har endast `contents: read`; checkout sparar inga credentials. Ingen
package-token, produktionskonfiguration eller datafil ingår. Lägg aldrig
API-nycklar, sessions, användare eller annan privat runtime-data i källkod,
byggevidence eller artefakter.

W-055 lägger till Docker build/smoke efter Manager-tester. W-056 får ett separat
releasejobb med `packages: write` endast för betrodda releasehändelser, efter
alla tester. Dockerfile/GHCR ingår inte i W-003. Utöka klientmatrisen först efter
W-013/W-014; de ursprungliga targets finns kvar under portningen.

Lokal verifiering: samma tre byggkommandon och rapportverktyg som i BASELINE.md
har körts. Hosted workflow körs för main och kan även startas manuellt. Lokal Gradle
är inte bevis för en grön hosted workflow-run. W-011 lägger till ett riktigt
Manager-process-test för ny installation, order/claim, omstart och isolerad
backup. Webbens DOM-/referensregression körs i ett separat Node 22-jobb. Minecraft/Docker är ej körda.
