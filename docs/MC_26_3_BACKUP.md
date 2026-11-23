# Säker omstart och manuell volymkopiering

Stoppa Manager med `docker compose stop manager` och invänta att processen
har avslutats. För en manuell, privat driftkopia, kopiera hela den stoppade
volymen med filägare och rättigheter bevarade. Håll kopian skyddad eftersom
den innehåller konton, sessioner och agentnycklar. Följ
[resurs- och licensvillkoren](DOCKER.md) för Minecraft-cache vid distribution.
SQLite:s databas och eventuella WAL-/SHM-filer hör till samma volym.

Starta med `docker compose up -d manager`. Kontrollera processens readiness
och därefter agentens signerade närvaro och att lagret observerats på nytt.
Fysiskt osäkra operationer kräver ny verifierad observation före fortsatt
arbete. Granska blockerade orders och journalen i Operations.

Se [livscykel och installationslås](DOCKER.md) och
[crash-verifiering](https://github.com/Crispy-Monke-eSports/WeSort/issues/45).
