# W-044: säker backup, omstart, dataåterställning och crash consistency

Managerns hela guildgemensamma data ska kunna säkerhetskopieras och
återställas utan att läsa Qwerte och utan att en crash orsakar dubbel
utleverans.

## Manager-sidan (denna commit)

- **`Backups`** (Manager): atomisk, checksummad och versionsmärkt backup
  av hela datakatalogen (users, roller, kvotpolicy, orderkommentarer,
  audit, stock, paketjournal och credentials — aldrig Qwerte, aldrig de
  regenererbara ikon-/klientcachearna). `create`/`list`/`verify`/`restore`.
  - Varje fil SHA-256-summeras och schema-versionen registreras i
    `manifest.json`; `restore` verifierar checksummor + format och nekar en
    post som försöker skriva utanför datakatalogen.
  - Snapshot tas atomiskt (staging → rename) och gör om försöket om
    filer ändras mitt i; retention beskär till ett begränsat antal.
  - Återställning skriver bara datafiler; den bevisar aldrig att en box
    finns eller är namngiven (agenten måste observera om).
- **CLI** (`backup` / `backup-list` / `backup-restore <id>`): körs mot en
  **stoppad** Manager och vägrar medan kontrollsockeln är aktiv.
- **Omstart-reconciliation** (`JobScheduler`): en held lease som löpt ut
  medan Managern var nere markeras `NEEDS_RECONCILIATION` redan vid laddning,
  före första claim — ingen blind återupptagning eller dubbelutleverans.
- **Städning vid start** (`Backups.sweepStaging`): avbrutna
  `.staging-*`-kataloger tas bort vid uppstart.

## Testbevis

- `BackupsTest` (Manager, 8 tester): roundtrip av alla guildfiler +
  credentials (rollpolicy, kvot, ordertyp/kommentar, audit), restore till
  ny värdkatalog, retention, korrupt backup nekas, transienta/regenererbara
  poster utesluts, schema-versioner i manifest, path-traversal nekas,
  avbruten staging sopas.
- Hela managersviten grön (326), common-sviten grön (525), klienten
  kompilerar.

## Gränser och återstår

Återstår (kräver live 26.3/Docker och kan inte visas i en ren manager-svit):
- Simulerat containerbyte med samma volym och restore på ny Linux
  Docker-host (docker-smoke körs separat; `backup`/`restore` är redo att
  driva det).
- Test mot strömavbrott mitt i agentens anvil-omdöpning/returintag samt
  verifiering av att DB-återställning aldrig bevisar box-existens (kräver
  agent + server).
- `docker compose stop` kontra konsistent live-snapshot dokumenteras i
  installations-/driftdokumentationen (W-050/W-055).
