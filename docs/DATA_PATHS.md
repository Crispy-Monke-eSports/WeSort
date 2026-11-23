# W-011: nya, isolerade WeSort-installationer

WeSort har mod-id `wesort`, Java-paket `com.porter.wesort` och
`com.porter.wesortmanager`, kategori WeSort, artifact `wesort-addon` och
`wesort-manager.jar`, Manager-version `0.1.0-dev.1`. Ursprunglig attribution till
Qwertze och GPL/OFL-text är kvar; se UPSTREAM.md. Logotypens SVG-källa finns
i branding/wesort.svg, med PNG-export för Fabric och SVG-favicon för webben.

| Tillstånd                                           | Ny plats/identitet                                                                           |
| --------------------------------------------------- | -------------------------------------------------------------------------------------------- |
| Meteor module settings                              | `wesort-stash-sorter`, `wesort-stash-overlay`, `wesort-stash-manager`, `wesort-stash-window` |
| Klientstash, aktiv order, shulker/recovery, backups | `meteor-client/wesort/stashes/*.nbt` och backups bredvid                                     |
| Kits                                                | `meteor-client/wesort/kits/*.nbt`                                                            |
| Lokala containerobservationer                       | `meteor-client/wesort/local-index/*.nbt`                                                     |
| Lokala order                                        | `meteor-client/wesort/local-orders/*.json`                                                   |
| Window-session och geometri                         | `wesortSession`/`wesortWindow` under den egna Meteor-modulen                                 |
| Klientens schema                                    | `meteor-client/wesort/.wesort-installation`: `wesort-client:1`                               |
| Manager utveckling                                  | `wesort-manager-data/`, eller explicit ny `--data`                                           |
| Manager produktion, senare W-055                    | dedikerad Linux Docker-volume via `--data /var/lib/wesort`                                   |
| Manager schema                                      | `.wesort-installation`: `wesort-manager:1`                                                   |
| Manager config/API-nyckel                           | `config.properties` under egen rot; nyckel genereras ny                                      |
| Users/sessions/ägarskap/audit                       | `users.json`, `sessions.json`, `owners.json`, `audit.json` under egen rot                    |
| Index och orderhistorik                             | `indexes/`, `orders/` under egen rot                                                         |
| Server-id-secret och ikoncache                      | `streamer-ids.key`, `icons/` under egen rot                                                  |
| Webb-sessioncookie                                  | `wesort_session`                                                                             |
| Webbläsarens val, cart, theme, streamer             | endast `wesort.*` i localStorage                                                             |
| Dev-harness nyckel                                  | `WESORT_MANAGER_KEY`, explicit `--key` eller ny egen --data                                  |

Ingen gammal Qwerte-konfiguration, stash, kit, recovery, order, user, session,
nyckel eller ikoncache läses/importeras/kopieras/raderas. Ursprungliga
`meteor-client/qwerte` och `stash-manager-data` ignoreras helt. Den gamla
server-URL-migreringen är borttagen och gamla kommandot `stash` registreras
inte; använd `.wesort` eller `.wesort-stash-window` i Meteor.

`common/.../DataDirectory` är gemensam för klient och Manager. Den avvisar
legacy-sökvägskomponenter, symlänkar i rotens föräldrar och inom roten,
okända icke-tomma kataloger, fel installationsroll och okänd/korrupt schema-
markör. Den markerar endast tomma kataloger som en ny WeSort-installation.
Manager kör denna kontroll före temp-sweep, config, nycklar, stores eller
ikoncache. Klientens fyra datarötter går genom samma kontroll. Framtida
WeSort-schemaändringar kräver en uttrycklig, versionsstyrd implementation;
ingen implicit fallback till gamla Qwerte-format/kataloger finns.

Befintliga signerade wire-headernamn/HMAC-algoritmer behålls som protokoll-
implementation. Nya installationer använder egna genererade nycklar och
sessions; inga autentiseringsuppgifter hämtas från upstream-data.

## Körda kontroller

Gemensamma JUnit-testfall körs i både klient och Manager: rena installationer,
korrupta/read-only Qwerte-filer, explicit gamla sökvägar, okända/schemamärkta
kataloger, olika roller, symlinkrot/förälder/markör/cache och path-escape.
Webbtestet förladdar gamla keys med korrupta värden och förbjuder all läsning,
skrivning och radering av dem; de är byte-oförändrade efter navigation.

```sh
python3 scripts/smoke-isolation.py --java "$JAVA_HOME/bin/java"
```

Detta process-test startar riktig Manager från jar i en ny temporär rot bredvid
korrupta/read-only legacyfixtures, skapar Owner och ny nyckel, skickar signerat
index/heartbeat, lägger order via webben och claimar den via agent-API, stänger,
startar om och skapar backup av endast egen rot. SHA-256, mtime och filrättigheter
hos legacyfixtures är oförändrade hela vägen. Direkt start mot gammal datarot
avvisas. Testet loggar inte nyckel eller setupkod. Detta är inte fysisk
Minecraft-orderpackning eller en färdig produktionsbackupfunktion (W-044).

Klientens initiering i en verklig Meteor-session och fysiska Minecraft-flöden
är EJ KÖRDA. Docker och 26.3 ingår i senare roadmapsteg.

W-055: Manager håller även `.manager.lock` och en owner-only `.control.sock`.
Bootstrapkapabiliteten är en kortlivad minnesdigest och hämtas endast via lokal
CLI; den loggas och persisteras aldrig. Se DOCKER.md.
