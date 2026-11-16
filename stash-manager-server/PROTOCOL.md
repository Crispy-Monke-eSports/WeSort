# Manager API efter steg 0

Alla aktiva routes registreras av WeSortManager.routes; Route-regressionen
klassificerar varje inloggad route för permission och sekretess.
Detta protokoll är den bevarade baslinjen, inte framtida guildordermotor.

| Område | Aktiva routes |
|---|---|
| Browser-auth | GET auth/state; POST auth/setup, auth/login, auth/logout, auth/logout-all, auth/password, auth/confirm, auth/lock |
| Agent | POST index, heartbeat, orders/claim, orders/:id/status, delivery |
| Lager/webb | GET stashes, index, orders, delivery, link; POST stashes/release, rescan, link/regenerate |
| Order/webb | POST orders, orders/clear, orders/:id/cancel, orders/:id/force-cancel, orders/:id/retry |
| Drift/agent | POST bot/hello, bot/state, bot/results, bot/activity; GET bot/next |
| Drift/webb | GET bots, bots/:id/settings, bots/:id/jobs, bots/:id/activity; POST bots/:id/settings |
| Administration | GET/POST users; POST users/owner, users/:id, users/:id/actions enligt UserRoutes; GET audit |
| Assets | GET /icon/:id och statiska resurser |

API-routes börjar med `/api/v1/`. Se exakta regex/actions i respektive
*Routes.java. Egen agent-settings använder endast `scope=plugin`; andra scopes
avvisas. Jobbkön kan bara bära settingsändringar. Gamla planner-, console- och
viewer-routes saknas och ger 404. Inga kommandon kan köas via gammalt API.

Browserläsning kräver session; writes kräver dessutom POST, JSON,
`X-Stash-Manager: 1`, samma origin och tillämplig permission. CORS-preflight
nekas. Owner-only administration använder lösenordsbekräftelse.
Agentanrop signeras genom Signer; replay/skew/key-generation kontrolleras.
SignerTest och HTTP-regressionerna är protokollets körbara exempel.

Aktiva permissions: view, order, ordersAll, activity, settings, rescan, link,
streamer samt stash-/bot-scope. Planner/proxy/console/viewer ger ingen åtkomst.
Servern maskerar känsliga positions-/identitetsfält för forced streamer;
webbens blur är ett extra skydd. Nycklar lämnas inte ut till sub-users.

IndexStore är observerat lager med heartbeat, OrderStore är befintliga orders.
DeliveryContents är observation av leveranskistans innehåll; dess data läggs
inte i lagersaldot. Fysisk leverans och framtida globala reservationer kräver
senare roadmapsteg. Det finns ingen Qwerte-datamigrering.
