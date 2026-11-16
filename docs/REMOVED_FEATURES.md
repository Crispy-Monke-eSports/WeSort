# Avgränsningar och borttagningar

Beslut och originalfilvägar finns i [CLEANUP_PLAN.md](CLEANUP_PLAN.md).
Ingen borttagning avser användardata. Git bevarar originalkod och historik.

## W-005 / #6: farming och pearl

Borttaget: AutoTill, AutoDirt, ResetPearl, hela pearl-paketet, Restocker,
StorageList och WorkPattern, deras registreringar och farmingdokumentation.
StashYield har inte längre beroende på farmingmoduler för pauskontrollen;
AutoEat, spelarinput, andra Baritone-mål och främmande inventoryändringar
fortsätter att pausa sorteringen. Ingen exklusiv pearl-/farmingmixin fanns.
Den 1.21.11-mixin som skyddar läsning av player heads behålls.

Restocker/StorageList granskades: endast AutoTill/AutoDirt använde dem.
Inga gemensamma container-, hotbar- eller navigeringsfunktioner behövde flyttas.
BaritoneHelper, AutoHotbar, Containers, ChestStack, ContainerPacing, PlaceSearch,
SurveyRetry, WalkProgress, SnapshotSearch, signkategorier och hela craft/stash
behålls. Seeds, dirt och pearls är fortsatt vanliga items; inga itemfilter eller
recept har tagits bort. Farming som signkategori är därför kvar.

13 pearl-testklasser (129 tester) togs bort tillsammans med hela målpaketet.
Övriga 496 klienttester behålls. 1.21.4: 496 körda, 0 fel/skips. Manager är
oförändrad och dess godkända baslinje gäller. Speltest av chest/barrel, drop-off,
Baritone-resa och shulkerpackning är EJ KÖRT; behöver Minecraft-testvärld.
1.21.11: också 496 körda, 0 fel/skips; båda klientbyggena passerade.
