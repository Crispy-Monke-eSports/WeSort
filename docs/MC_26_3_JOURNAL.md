# Fysisk journal i Minecraft 26.3

En WeSort-installation skriver en privat journal i `meteor-client/wesort/physical-journal`.
Den aktuella posten ligger i `active.json` och avslutade poster arkiveras per
operations-ID. Intentionen synkas till disk före sändning. Den innehåller källa,
förväntade slot- och cursorvärden, ändrade slotar och förväntade block. Den
färska fullständiga serverramen och eventuell blockkvittens verifierar utfallet.
Atomiskt filbyte och en hashkedja binder varje kvittens till sin intention.
Journalen korrelerar steg med order-ID och tilldelat kitjobb när de finns.

Repack journalför SELECT, PLACE, OPEN, TRANSFER, VERIFY, BREAK, PICKUP och
STORE. Journalen blir COMPLETED först när den fysiska lagringen har
verifierats. Planering, pågående arbete, verifiering, säker avbruten
planering och avstämning har tillstånden PLANNED, IN_PROGRESS, VERIFYING,
FAILED respektive NEEDS_RECONCILIATION. Lagerflyttar, kit och callbacks
som Meteor kör fördröjt efter en rotation går genom samma skrivspärr.

## Avstämning efter avbrott

Vid omstart spärrar agenten oavslutat arbete och den signerade Manager-
länken speglar tillståndet. Manager spärrar öppna journaler när länken
upphör att förnya dem och vid Manager-omstart. SQLite-schema 5 bevarar
journalrevisioner och inspektionsbeslut. Manager får fortsatt sin
lagerobservation genom det separata, serverbekräftade lagerflödet.

1. Öppna **Settings → Physical operations** som Owner. Där visas
   operations-ID, order-/kitkoppling, avbruten fas och möjliga orphaned
   shulkers. Koordinaterna i vyn omfattas av streamerläget. Auditloggen
   sparar inspektionsåtgärden med operations-ID.
2. Låt samma agent ansluta på ursprunglig server och dimension. Ladda
   berörda block och ställ agenten inom räckhåll för den journalförda
   källan. Begär **Request inspection** för aktuell revision.
3. Agenten öppnar den registrerade källan i rent läsläge och verifierar
   block, serverns slotram och agentens fulla inventarium. En enskild flytt
   med exakt matchande eftertillstånd kan kvitteras. En avbruten plan utan
   utsänd flytt kan avslutas som FAILED. En tidigare spärrad
   sorteringslease frigörs endast med bevis för den matchande leasen.
4. Avvikande resultat och senare avbrutna packfaser förblir spärrade.
   Stående eller tappade boxar och delvis färdiga operationer kräver
   manuell bedömning. Begäran utför läsningar och återutsänder aldrig
   en tidigare fysisk flytt. Fullt obevakad körning kräver W-061.

## Verifiering

CI-matrisen kör riktiga Minecraft 26.3-klienter mot en separat vanilla-
server och Manager som Docker-container. Den avslutar klientprocessen
med SIGKILL efter journalförd fas, startar om Manager och klienten och
jämför auktoritativa inventarier och komponenter via RCON. DIRECT-fallet
låter servern ta emot en flytt men håller tillbaka kvittensen. Efter
Owner-inspektion ska samma agent kunna göra en ny flytt med oförändrat
totalantal och utan att den första flytten tas om.

```sh
docker build -t wesort-manager:journal-tests .
xvfb-run -a python3 scripts/mc26/journal-harness.py --phase DIRECT
xvfb-run -a python3 scripts/mc26/journal-harness.py --phase TRANSFER
```

[Workflow för fas- och omstartsproven](https://github.com/Crispy-Monke-eSports/WeSort/actions/workflows/mc26-journal.yml)
