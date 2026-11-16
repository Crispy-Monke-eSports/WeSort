# W-003: baslinje-CI

`.github/workflows/build.yml` bygger/testar båda klienttargets och det separata
Manager-projektet på Linux med `.java-version` och den checksummalåsta wrappern.
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
har körts. GitHub Actions-run återstår tills branchen har pushats; lokal Gradle
är inte bevis för en grön hosted workflow-run. Minecraft/Docker är ej körda.
