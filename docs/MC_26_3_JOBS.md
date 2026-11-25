# W-034: jobbscheduler med claim, leases, prioritet och återkallning

Manager allokerar atomiskt jobb och lease till den enda aktiva agenten utan
dubbla actions (single-agent lease; ingen fleet/failover). Agenten kedjar alla steg utan överlämning. Fleet/failover
för 1–5 agenter är borttaget per bindande scope #139.

## Manager-sidan (denna commit)

- **`Job`** (common): kategori `SCAN → SORT → PACK → PICKUP → DELIVER`
  med explicita prereqs (varje steg kräver föregående COMPLETED för samma
  `chain`) samt `RECONCILE` som override. Tillstånd `QUEUED → CLAIMED →
(PAUSED) → COMPLETED`, `NEEDS_RECONCILIATION` vid lease-timeout och
  terminal `REVOKED`. `advance` upprätthåller grafen: claim sätter ägare,
  reassign (reconcile→QUEUED) rensar den.
- **`JobScheduler`** (Manager): atomisk `claim` (högsta prioritet, exakt en
  ägare, max en fysisk transaktion per agent), lease med token + TTL,
  `renew`/`complete`/`pause`/`resume`, `revoke`/`reconcile` (ägare), och
  `expire` som efter timeout sätter `NEEDS_RECONCILIATION` — aldrig blind
  reassignment. Prereqs och prioritet gaterar kön; persistens över omstart.
- **`JobRoutes`**: Owner `GET/POST /api/v1/jobs` (submit/revoke/reconcile)
  och signerad agent `POST /api/v1/jobs/agent` (claim/renew/complete/
  pause/resume).

## Testbevis

- `JobTest` (common): prereqs-kedjan, claim sätter/reassign rensar ägare,
  ogiltiga övergångar nekas, pause/resume/revoke, validering, roundtrip.
- `JobSchedulerTest` (Manager): atomisk claim (två agenter får aldrig samma
  jobb, en agent håller max ett), prioritet, prereq-gating, lease-timeout →
  `NEEDS_RECONCILIATION` utan blind reassignment förrän reconcile, renew
  förlänger lease, pause/resume/revoke, enagentskedja över alla steg, och
  persistens.
- Manager-, common- och klientkompilering samt hela managersviten gröna.

## Gränser och återstår

Återstår: koppling till faktisk agentförmåga/position/last heartbeat och
`AutomationGate`/`BotHub`/`KitJobs`/`BoxJobs` (envy över befintliga
leases för den enda agenten). Generell teleportstrategi finns inte (se
MC_26_3_TRANSPORT.md). Återstår verklig 26.3-E2E med en agent (claim,
stop/cancel, lease-timeout efter fysisk pickup).
