# W-056: Manager i GHCR

Registry: `ghcr.io/crispy-monke-esports/wesort-manager`. Paketet ska vara
Public och kopplat till `Crispy-Monke-eSports/WeSort` via OCI source-label och
publicering med repositoryts `GITHUB_TOKEN`. Ingen personlig PAT behövs för
workflow eller anonym installation. Plattformen är Linux amd64; arm64 är inte
verifierad och publiceras inte.

## Publicering och spårbarhet

`.github/workflows/manager-image.yml` bygger/testar PR utan package-push.
Publicering startas manuellt på main med `publish=true`, eller av en `v*`-tagg
vars commit finns på main. Taggen måste motsvara versionen i Managers Gradlefil.
Den aktuella 26.3-utvecklingsversionen är `v0.1.0-dev.1` (första imagen var
`v0.1.0-dev.0`). Detta är en migrationsversion,
inte den färdiga guildplattformen.

```sh
gh workflow run manager-image.yml --ref main -f publish=true -f version=v0.1.0-dev.1
```

Workflow kör hela baslinjen: båda klienttargets, Manager, webb, isolering och
Docker/TLS/Owner-bootstrap/volymrestart. Därefter byggs en kandidat och testas
igen som faktisk container. Först ett separat publiceringsjobb får
`contents:read` och `packages:write`. Det importerar exakt det testade arkivet,
kontrollerar checksummor och OCI-identitet och bygger inte om imagen.

Imagen får `v<appversion>` och `sha-<fullständig commit-SHA>`. Befintliga taggar
med annat innehåll får inte ersättas; ändrad kod kräver höjd appversion.
Förhandsversioner får aldrig `latest`; workflow tilldelar inte heller latest
automatiskt för stabila versioner. Sådan markering kräver ett senare uttryckligt
releasebeslut. Digest är den immutabla installationsreferensen.

Actions-artifacten `manager-publication-<SHA>` innehåller `publication.json`
med verklig registry-digest, version, commit, workflow-URL, paketsynlighet och
verifierad repositoryassociation. Den innehåller också SPDX-JSON från Syft
v1.57.1 samt kandidatens checksummor och smoketest. Actions skyddar överföringen
av kandidatarkivet mellan jobb. Provenance är osignerad CI-metadata; detta är
ingen kryptografisk SLSA-attestering eller signering av registry-manifestet.
Kandidatarkivet sparas sju dagar; spara releaseunderlaget separat vid behov.

Ett nytt jobb på en ny Linux-runner, utan registryinloggning och med tom
`DOCKER_CONFIG`, drar publicerad digest och kör produktions-Compose med en ny
datavolym. Artifact `manager-published-smoke-<SHA>` är resultatet. Grön
publiceringskörning kräver även detta jobb. Testagenten är en separat container
som använder riktigt signerat HTTP-protokoll utan Discord. En verklig Fabric-
session och fysisk Minecraft-leverans ingår inte i detta test.

## GitHub Packages-inställningar

GitHub skapar normalt nya paket som privata även från publika repositories.
En paketadministratör behöver då välja Public i paketets Settings. Synligheten
kan inte ändras via det publika Packages REST-API som workflow använder.
Se [GitHubs dokumentation om synlighet](https://docs.github.com/en/packages/learn-github-packages/configuring-a-packages-access-control-and-visibility).
Behåll repositoryassociationen och dess Actions-access. Publiceringsjobbet
kontrollerar association via API och bevisar push med repositoryts token.

Om paketet är privat misslyckas det anonyma testet. Ändra synligheten och kör
om endast det misslyckade pull-jobbet; publicera inte om imagen. Ingen
orgpolicy som kräver privat paket är etablerad här. Ett privat paket uppfyller
inte denna versions krav på anonym installation.

## Installation, uppgradering och rollback

Hämta `compose.yaml` från samma commit som önskad release. Lokal källkod,
Java, Gradle, Discord eller en separat databas behövs inte på Docker-värden.
Konfigurera TLS, Host och exakt betrodd proxy enligt [DOCKER.md](DOCKER.md).

```sh
# compose.yaml har en versionspinnad standardimage.
docker compose pull
docker compose up -d
docker compose exec -T manager /opt/wesort/entrypoint bootstrap
docker compose ps
```

För starkare pinning, kopiera hela `registry_reference` från den gröna
publiceringskörningens `publication.json` till `WESORT_IMAGE` i deploymentens
`.env`. Referensen har formen `ghcr.io/crispy-monke-esports/wesort-manager@sha256:<digest>`.
Använd inte en påhittad eller förkortad digest. Bootstrap behövs bara innan
första Owner skapats. Verifiera readiness och webb/API genom din TLS-ingress.

Vid uppgradering: notera nuvarande digest, stoppa Manager och säkerhetskopiera
hela dess egna datavolym offline med samma filrättigheter. Byt till den nya
verifierade digesten, kör `pull` och `up -d` och kontrollera health/webb/API.
Behåll samma volym och endast en aktiv Manager. `down -v` raderar datan.

Vid rollback: stoppa den nya imagen och välj föregående verifierade digest.
Om dataskemat har ändrats måste även motsvarande offlinebackup återställas;
starta inte äldre kod mot ett nyare schema. Kör därefter `up -d` och verifiera
health. Automatisk schema-downgrade eller backup av en aktiv framtida SQLite-
databas är inte implementerad i steg 1; se ADR-001 och kommande W-044.
