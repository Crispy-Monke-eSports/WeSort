# 6b6t-läge: lobbyportal före arbete

När den anslutna servern är 6b6t (`6b6t.org`, `join.6b6t.org`, `play.6b6t.org`,
med eller utan port) hamnar spelaren i en lobbyvärld och måste gå igenom
netherportalen på torget för att komma till survival-världen. Agenten gör den
promenaden själv innan något arbete börjar.

## Beteende

- **Aktivering:** inställningen `6b6t-mode` i wesort-stash-sorter (Auto/På/Av,
  default Auto). Auto = endast på 6b6t-servrar (`Stash.is6b6tServer`).
- **Väntan:** Managerns `driveSorter` fortsätter driva medan `portalAwaiting()`
  gäller; sorterns tick parkerar alla jobb tills transitens är klar.
  `driveBlocked` rapporterar `"in lobby, entering survival world"`, vilket syns
  som order-blocker i heartbeat — Managern ser alltså att boten är på väg in.
- **Sökning:** throttlad (10 s) `scanArea` efter `minecraft:nether_portal`
  inom 128 block. Inga hårdkodade koordinater — lobbyns layout ändras.
- **Promenad:** `walkNear(portal, 0)` med vanlig safe-walk-confine; Baritone
  krävs. Fastnar promenaden (ingen rörelse 60 s) släpps målet och sökningen
  görs om.
- **Ankomst:** nytt `ClientLevel`-objekt (proxy-transfer) eller ändrad dimension
  (portal-teleport) jämfört med snapshot vid start → `releaseGoal()`,
  `"Through the portal (...). Starting normal work."`, och ordinarie jobb tar vid.
  Portalen söks aldrig igen under sessionen (dimensionsvillkor); nytt GameLeft
  nollställer.
- **Ping-pong-skydd:** transitlogik körs bara före ankomst; i survival-världen
  gäller vanliga grindar. En borttagen portalblockposition (söndergrävd)
  invaliderar målet direkt.

## Gränser

- Fungerar bara med Baritone installerat (varning utan, väntar kvar).
- Manager-sidans serversträng (`ServerRoutes`, exakt match) måste stämma mot
  den värld boten står i, annars svarar heartbeat 409 wrong server oavsett läge.
- Mojangs server-blocklista och 6b6t-verifiering (captcha/e-post) ligger utanför:
  kontot måste redan kunna ansluta.
