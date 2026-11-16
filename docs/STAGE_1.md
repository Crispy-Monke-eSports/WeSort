# Steg 1: arkitektur och Docker-distribution

Steget omfattar tre separata issues och commits:

- W-025 (#26): en Manager för ett guild, fysisk källidentitet och globalt
  aggregerat stock, transaktionsgränser, custody/reconciliation samt 1–5
  agenter. Se [arkitektur](ARCHITECTURE.md), [ADR-001](adr/001-transactional-guild-storage.md)
  och [Managerkontrakt](MANAGER_CONTRACTS.md).
- W-055 (#56): faktisk Linux Docker-runtime, skyddad persistent volym,
  processlås, health/shutdown och lokal säker Owner-bootstrap. Se
  [Docker](DOCKER.md) och [lokal testrapport](verification/W-055-docker-smoke.json).
- W-056 (#57): releasegates, faktisk GHCR-publicering, source association,
  version/SHA/digest, SPDX och anonym pull/start i produktionens Compose.
  Se [GHCR](GHCR.md). Den faktiska publiceringskörningens artifacts är
  underlaget för digest, synlighet och det publicerade smoketestet.

Arkitekturkontrakten är inte en implementerad guildmotor. Reservationer,
kvoter, transaktionell SQLite-lagring, hållbara leases, multiagent-failover
och fysisk transport/mottagning införs i senare issues. Docker-smoketestets
externa agent verifierar det befintliga signerade protokollet; det ersätter
inte Minecraft-E2E med riktiga inventory clicks eller Baritone. Minecraft
26.3 ingår inte i denna baslinje.

Ingen GitHub-checklista eller issuestatus ändras av implementationen.
