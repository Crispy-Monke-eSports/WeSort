# WeSorts arkitektur

En installation består av en Java 21 Manager och en guild med ett gemensamt
lager på en Minecraft 26.3-server. Owner väljer server och lagerområden.
En aktiv Fabric/Meteor/Baritone-agent med Java 25 utför lagerarbetet i de
konfigurerade stasharna. 6b6t är målmiljön.

| Komponent           | Ansvar                                                                                                              |
| ------------------- | ------------------------------------------------------------------------------------------------------------------- |
| Manager             | Inbyggd React-webb och HTTP-API, medlemskonton, server/stashes, order, reservationer, audit och agentpolicy         |
| Agent               | Läser fysiska slots, sorterar efter tiers och utför verifierade inventory-, crafting-, packnings- och anvilsteg     |
| Minecraft-server    | Auktoritativt fysiskt inventory och världstillstånd. Serverbekräftad före-/efterbild behövs för ett fysiskt kvitto. |
| Lokal Manager-volym | SQLite-lager, order- och driftdata, skyddade credentials och regenererbar ikoncache                                 |

## Identiteter och tillit

Owner administrerar medlemmar, server, stashes, agent och återställning.
Member använder en individuell webbsession och läser eller beställer inom
sina tilldelade rättigheter. Befintliga behörighetsmallar konfigurerar
medlemmars rättigheter och scopes. Målbilden för medlemsåtkomst och
sekretess följs upp i [#58](https://github.com/Crispy-Monke-eSports/WeSort/issues/58).

Webben använder cookies, samma origin för skrivningar och CSRF-skydd.
Manager validerar varje begäran. Positioner och orderdata filtreras efter
åtkomst. Vid uppdatering bevarar uteblivna scopefält tidigare värden.
Ett scopevärde på `null` innebär alla värden och `[]` innebär inga värden.

Agentens HMAC-nyckel är separat från webbsessioner och Minecraftkontot.
Nyckeln, serverbindningen och kontrollsessionen valideras av Manager.
Fysiska tillstånd avgränsas dessutom till operation, block-ID, position,
jobb och giltighetstid. Agenten spärrar arbete vid okänt eller osäkert
fysiskt tillstånd. 6b6t-lobbyn har en avgränsad
[portalprocess](MC_26_3_LOBBY_PORTAL.md).

## Stock, kit och leverans

En fysisk källa identifieras av server, dimension, stash, container och
slot. En observation innehåller mängd, komponentfingerprint och revision.
Globala saldon härleds från distinkta källor. En hel box och dess innehåll
konkurrerar om samma fysiska resurs. Anspråk och färskhetskontroll måste
gälla när agenten börjar det fysiska arbetet.

Katalogen hämtar dynamiskt renderade ikoner från Managers verifierade och
privata 26.3-assets. En kitorder pinnar mallens 27-slotlayout. En craftorder
planerar ingredienser och använder den konfigurerade arbetsbänken vid
behov. Packade shulkerboxar kan märkas i städ med `WeSort (X/Y)` och
beställarens suffix. Staging etablerar paketets identitet och custody.
Slutleverans kräver ett separat bevis för deposition i rätt CHEST eller
bekräftat mottagande för rätt PLAYER. Se
[domänkontrakten](MANAGER_CONTRACTS.md) och de aktiva
[API-rutterna](../stash-manager-server/PROTOCOL.md).

## Persistens och drift

Manager tar ett exklusivt installationslås före initiering av data.
SQLite är auktoritativt för de fysiska lagervägar som har flyttats dit.
Order, custody och andra befintliga stores har fortfarande separata
persistensgränser. Den gemensamma transaktionen följs upp i
[#140](https://github.com/Crispy-Monke-eSports/WeSort/issues/140)
och [ADR-001](adr/001-transactional-guild-storage.md).

HTTP-readiness avser Manager-processen. Agentnärvaro kräver färsk,
signerad kontakt och lagerberedskap kräver giltiga observationer.
Vid omstart krävs ny fysisk observation för osäkra intents. En utgången
lease ger ingen rätt att spela upp ett klick igen. Se
[Docker och Owner-bootstrap](DOCKER.md) och [privat 26.3-E2E](MC_26_3_E2E.md).
