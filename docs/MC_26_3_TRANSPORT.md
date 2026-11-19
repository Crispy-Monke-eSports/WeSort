# W-062: valfria /tpa och /home per serverprofil, styrda från Manager

WeSort ska kunna köras på olika anarkiservrar med olika kommandon/
pluginfunktioner (2b2t, 6b6t är exempel, inte bevis). `/tpa` och `/home`
är server-/pluginfunktioner, inte generiska Minecraft 26.3- eller
Baritone-funktioner, och är därför ett **opt-in transportalternativ** som
aldrig krävs för normal lagerdrift. Ingen server-till-server-teleport och
ingen genväg runt order-, positions- eller inventorysäkerhet.

## Manager-sidan (denna commit)

- **`TransportProfiles`** håller en persistent, per-serverprofil med två
  oberoende, **default OFF**-flaggor (`allowTpa`, `allowHome`) och två
  separata bekräftelseflaggor (`tpaVerified`, `homeVerified`). Ny profil och
  helt ren installation har båda av. Varje uppdatering ökar `revision` och
  kräver `expectedRevision` (409 vid stale).
- **`TransportCommands`** (`common/.../data`) är den slutna allowlistan av
  typade operationer: `TPA_REQUEST(target)`, `HOME_GO(home)` och
  `TPA_ACCEPT(requestId)`. Målspelare, hemnamn och request-id valideras
  strikt (inga mellanslag, `§`, kontroll-/bidi-/formattecken, längdgränser);
  inga godtyckliga `/command`, vidarebefordrade meddelanden eller osanerade
  fält kan skickas.
- **`TransportRoutes`**: Owner `GET /api/v1/transport` (listar profiler)
  och Owner `POST /api/v1/transport` (uppdaterar en servers flaggor med
  revision). Maskeras för forced streamer. Konfigurerat stöd skiljs från
  verifierat stöd; inget annonseras som fungerande utan faktiskt testad
  server.

## Testbevis

- `TransportCommandsTest`: typade operationer normaliseras, godtyckliga
  kommandon/osanerade fält/okända typer nekas, längdgränser gäller.
- `TransportProfilesTest`: default OFF, revision/409-konflikt, oberoende
  profiler per server, persistens över omstart och okänd server börjar OFF.
- Manager- och common-byggena samt hela managersviten är gröna.

## Gränser och återstår

Återstår: agentens faktiska sändning/kvittens av typade kommandon (via
W-027:s signerade bot-protokoll), ankomstvalidering från verklig
serverlevererad position/dimension (aldrig enbart chatrespons),
`TPA_ACCEPT` endast för en uttryckligen förväntad request, hemregistrering
som separat behörig operatörsprocess, fallback till gång/portal (W-036),
koppling till W-061/W-052 vid osäkert inventory/PvP, samt riktig 26.3-E2E
på server med och utan stöd. Funktionen får aldrig bli ett beroende som
kräver serverplugin.
