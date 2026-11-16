> **Historisk kartläggning:** Den ursprungliga fler-versionsbaslinjen är avvecklad. WeSort stöder endast Minecraft 26.3; 1.21.x-omnämnanden nedan dokumenterar tidigare migreringsbeslut och inte aktiva bygg-/supporttargets.

# W-004: beslutad keep/remove-matris

Analys av den orörda basen `be4635bc8b4e71add4fc411e2cdd7258c45eac90`,
före kodrensning. Tabellen anger originalvägar; W-011 får byta namespace.
Varje borttagning ska hänvisa till denna matris och REMOVED_FEATURES.md.

## Gemensamma beroenden och risker

- StashSorter → Containers/AutoHotbar/BaritoneHelper/ChestStack/ContainerPacing,
  PlaceSearch/SurveyRetry/WalkProgress; behåll. AutoTill/AutoDirt → Restocker och
  StorageList, vars samtliga externa anropare är farming. Ingen extraktion behövs.
- KitBuilder/OrderRunner/Crafter/Compactor → craft/*, Kit/Kits/Order/StackInfo,
  signkategorier, anvil och inventory clicks; behåll. Crafting är inte Build Planner.
- StashWindow → LocalHub/LocalCapture/LocalStore/LocalOrderStore → stash/craft;
  behåll även utan Manager. ServerSource/SmClient/auth/SSE-parser används av webbanslutning.
- StashManager → signerad index/heartbeat/order/botkommunikation; behåll. BotHub,
  BotRoutes, DashboardRoutes delas med proxy/konsol: extrahera säkert och neka
  gamla kommandon/settings-scopes även om en äldre klient skickar dem.
- MapTab/ViewerHub/ViewerRoutes levererar generella karttiles, HUD, inventory,
  entiteter och chat. Inga befintliga stashkartor använder dem. Ta bort hela
  denna viewer. Behåll containerpositioner, stash-/botstatus och skyddad activity
  för framtida lager-/logistikkarta; radera inte orderns DeliveryBook.onViewers
  (antal spelare som tittar i leveranskistan), som är ett annat begrepp.
- Auth, sessions, signerare, CSRF/origin, owner/stash-scope, streamer-sekretess,
  audit och orderstatus behålls. Behörigheter för planner/proxy/console/viewer
  avlägsnas från serializer, parser, presets och båda UI:erna.

## Dataformat och regression

| Data/feature | Format och ägare | Regression som bevaras |
|---|---|---|
| Stashes/kits/recovery | Klient NBT; Stash, Kits, StashSorter | StashSafety, IndexJson, DeliveryBook/Contents, craft och kit/order-modeller |
| Lokal observation/order | LocalFile NBT, LocalOrderStore JSON | manager/local/*, Cart/CartPlan/OrderModel |
| Index/heartbeat | Manager IndexStore JSON per server/stash | index/naming/tabs, signer och HTTP delivery/order-tester |
| Order | OrderStore JSON + LocalOrders/OrderRunner | DeliveryTest, DashboardTest och order-/packplanering |
| Auth | config, users, sessions, owners, audit, server-id-secret | AuthTest, LoginSecurity, BrowserWriteOrigin, Signer, Users/permissions |
| Planner | PlanStore i minnet, schematics/NBT/materiallistor | Ta bort exklusiva planner-tester; behåll order/craft-regression |
| Proxy/viewer | BotHub i minnet, streaming frames/job queues | Ersätt exklusiva tester med negativa endpoint-/scope-tester |

Alla Qwerte-vägar ska ignoreras, inte migreras eller tas bort. W-011 skapar
nya WeSort-namespace för klient och Manager; även cache/browserstorage/cookies
måste separeras. Inga gamla order, users, sessions, keys eller ikoner återanvänds.
Speltester av scan/dropoff/shulker/kit/Baritone kräver separat Minecraft-körning.

## Permissions

| Beslut | Fält |
|---|---|
| KEEP | view, order, ordersAll, activity, settings (endast egen lageragent), rescan, link, streamer, stashes, bots, owner |
| REMOVE W-007 | planner, plannerOrder, PLANNER, PLANNER_ORDER |
| REMOVE W-008 | proxy, console, consoleAllow, viewer, VIEWER |
| EXTRACT | ICONS ska endast kräva lageråtkomst; ORDER_OWN_OR_ALL ska inte godta plannerOrder |

## Filmatris

| Originalfil | Beslut | Beroende/påverkan |
|---|---|---|
| `CHANGELOG.md` | EXTRACT W-010/011 | Ersätt aktiv produktdokumentation, bevara ursprung i Git/UPSTREAM och relevanta protokollkrav. |
| `LICENSE` | KEEP | Ursprunglig GPL/OFL-attribution måste bevaras. |
| `README.md` | EXTRACT W-010/011 | Ersätt aktiv produktdokumentation, bevara ursprung i Git/UPSTREAM och relevanta protokollkrav. |
| `src/1.21.11/java/com/porter/qwerte/compat/BaritoneFluids.java` | KEEP | Versionsadapter, inventory/hotbar, cheststack, pacing, Baritone, retry och placeringssökning; döda rester granskas W-010. |
| `src/1.21.11/java/com/porter/qwerte/compat/Drops.java` | KEEP | Versionsadapter, inventory/hotbar, cheststack, pacing, Baritone, retry och placeringssökning; döda rester granskas W-010. |
| `src/1.21.11/java/com/porter/qwerte/compat/GameVersion.java` | KEEP | Versionsadapter, inventory/hotbar, cheststack, pacing, Baritone, retry och placeringssökning; döda rester granskas W-010. |
| `src/1.21.11/java/com/porter/qwerte/compat/ItemIcons.java` | KEEP | Versionsadapter, inventory/hotbar, cheststack, pacing, Baritone, retry och placeringssökning; döda rester granskas W-010. |
| `src/1.21.11/java/com/porter/qwerte/compat/LegacyItems.java` | KEEP | Versionsadapter, inventory/hotbar, cheststack, pacing, Baritone, retry och placeringssökning; döda rester granskas W-010. |
| `src/1.21.11/java/com/porter/qwerte/compat/MapTexture.java` | REMOVE W-008 | Generell live world/HUD/chat-viewer eller fjärrkonsol; ingen stashkarta bygger på dessa klasser. |
| `src/1.21.11/java/com/porter/qwerte/compat/Players.java` | KEEP | Versionsadapter, inventory/hotbar, cheststack, pacing, Baritone, retry och placeringssökning; döda rester granskas W-010. |
| `src/1.21.11/java/com/porter/qwerte/compat/RightClick.java` | KEEP | Versionsadapter, inventory/hotbar, cheststack, pacing, Baritone, retry och placeringssökning; döda rester granskas W-010. |
| `src/1.21.11/java/com/porter/qwerte/compat/ScaledText.java` | KEEP | Versionsadapter, inventory/hotbar, cheststack, pacing, Baritone, retry och placeringssökning; döda rester granskas W-010. |
| `src/1.21.11/java/com/porter/qwerte/compat/SignEditors.java` | KEEP | Versionsadapter, inventory/hotbar, cheststack, pacing, Baritone, retry och placeringssökning; döda rester granskas W-010. |
| `src/1.21.11/java/com/porter/qwerte/mixin/ClientPacketListenerMixin.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/1.21.11/resources/qwerte.mixins.json` | KEEP | Itemikoner, recipes, creative tabs, fonter, teman eller stash-/assetstöd; rebrand W-011. |
| `src/1.21.4/java/com/porter/qwerte/compat/BaritoneFluids.java` | KEEP | Versionsadapter, inventory/hotbar, cheststack, pacing, Baritone, retry och placeringssökning; döda rester granskas W-010. |
| `src/1.21.4/java/com/porter/qwerte/compat/Drops.java` | KEEP | Versionsadapter, inventory/hotbar, cheststack, pacing, Baritone, retry och placeringssökning; döda rester granskas W-010. |
| `src/1.21.4/java/com/porter/qwerte/compat/GameVersion.java` | KEEP | Versionsadapter, inventory/hotbar, cheststack, pacing, Baritone, retry och placeringssökning; döda rester granskas W-010. |
| `src/1.21.4/java/com/porter/qwerte/compat/ItemIcons.java` | KEEP | Versionsadapter, inventory/hotbar, cheststack, pacing, Baritone, retry och placeringssökning; döda rester granskas W-010. |
| `src/1.21.4/java/com/porter/qwerte/compat/LegacyItems.java` | KEEP | Versionsadapter, inventory/hotbar, cheststack, pacing, Baritone, retry och placeringssökning; döda rester granskas W-010. |
| `src/1.21.4/java/com/porter/qwerte/compat/MapTexture.java` | REMOVE W-008 | Generell live world/HUD/chat-viewer eller fjärrkonsol; ingen stashkarta bygger på dessa klasser. |
| `src/1.21.4/java/com/porter/qwerte/compat/Players.java` | KEEP | Versionsadapter, inventory/hotbar, cheststack, pacing, Baritone, retry och placeringssökning; döda rester granskas W-010. |
| `src/1.21.4/java/com/porter/qwerte/compat/RightClick.java` | KEEP | Versionsadapter, inventory/hotbar, cheststack, pacing, Baritone, retry och placeringssökning; döda rester granskas W-010. |
| `src/1.21.4/java/com/porter/qwerte/compat/ScaledText.java` | KEEP | Versionsadapter, inventory/hotbar, cheststack, pacing, Baritone, retry och placeringssökning; döda rester granskas W-010. |
| `src/1.21.4/java/com/porter/qwerte/compat/SignEditors.java` | KEEP | Versionsadapter, inventory/hotbar, cheststack, pacing, Baritone, retry och placeringssökning; döda rester granskas W-010. |
| `src/1.21.4/resources/qwerte.mixins.json` | KEEP | Itemikoner, recipes, creative tabs, fonter, teman eller stash-/assetstöd; rebrand W-011. |
| `src/main/java/com/porter/qwerte/QwerteAddon.java` | EXTRACT W-005/007/008 | Behåll lagerdelar; avlägsna enbart farming, planner, proxy, console och live viewer. |
| `src/main/java/com/porter/qwerte/commands/StashWindowCommand.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/craft/AnvilFinder.java` | KEEP | Recipes/CraftPlanner/CraftHealth/TableFinder/AnvilFinder används för kit/order. |
| `src/main/java/com/porter/qwerte/craft/CraftHealth.java` | KEEP | Recipes/CraftPlanner/CraftHealth/TableFinder/AnvilFinder används för kit/order. |
| `src/main/java/com/porter/qwerte/craft/CraftPlanner.java` | KEEP | Recipes/CraftPlanner/CraftHealth/TableFinder/AnvilFinder används för kit/order. |
| `src/main/java/com/porter/qwerte/craft/Recipes.java` | KEEP | Recipes/CraftPlanner/CraftHealth/TableFinder/AnvilFinder används för kit/order. |
| `src/main/java/com/porter/qwerte/craft/TableFinder.java` | KEEP | Recipes/CraftPlanner/CraftHealth/TableFinder/AnvilFinder används för kit/order. |
| `src/main/java/com/porter/qwerte/manager/gui/BotDirectory.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/DataSource.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/ItemStacks.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/LocalOrderBackend.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/LocalSource.java` | EXTRACT W-005/007/008 | Behåll lagerdelar; avlägsna enbart farming, planner, proxy, console och live viewer. |
| `src/main/java/com/porter/qwerte/manager/gui/OrderBackend.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/ServerOrderBackend.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/ServerSource.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/StashManagerScreen.java` | EXTRACT W-005/007/008 | Behåll lagerdelar; avlägsna enbart farming, planner, proxy, console och live viewer. |
| `src/main/java/com/porter/qwerte/manager/gui/chrome/WBotStatus.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/chrome/WChip.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/chrome/WCreativeTabs.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/chrome/WFlow.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/chrome/WHeaderBar.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/chrome/WMacDots.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/chrome/WNavPill.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/frame/Buttons.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/frame/CursorShape.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/frame/FrameMath.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/frame/OverlayMask.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/frame/PopupLayout.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/frame/WDragHandle.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/frame/WFrame.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/frame/WOverlayLayer.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/frame/WResizeGrip.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/frame/WindowCursor.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/frame/WindowGeometry.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/layout/CountLayout.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/layout/FlowLayout.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/layout/GridLayout.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/layout/RoundRect.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/layout/StashLayout.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/panels/ChangePasswordPanel.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/panels/ConfirmPanel.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/panels/LoginPanel.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/panels/OwnPasswordPanel.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/panels/PasswordPanel.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/style/Paint.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/style/Palette.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/style/SmButton.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/style/SmCheckbox.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/style/SmGuiTheme.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/style/SmLabel.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/style/SmSelect.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/style/SmTextBox.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/style/SmTooltip.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/style/TextScale.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/style/WindowTheme.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/tabs/ActivityTab.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/tabs/BotsTab.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/tabs/BrowseTab.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/tabs/ConsoleTab.java` | REMOVE W-008 | Generell live world/HUD/chat-viewer eller fjärrkonsol; ingen stashkarta bygger på dessa klasser. |
| `src/main/java/com/porter/qwerte/manager/gui/tabs/MapTab.java` | REMOVE W-008 | Generell live world/HUD/chat-viewer eller fjärrkonsol; ingen stashkarta bygger på dessa klasser. |
| `src/main/java/com/porter/qwerte/manager/gui/tabs/OrdersTab.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/tabs/PlannerTab.java` | REMOVE W-007 | Schematics/materialplanering, inte kitpackning eller crafting. |
| `src/main/java/com/porter/qwerte/manager/gui/tabs/ServerTabHost.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/tabs/SettingsTab.java` | EXTRACT W-005/007/008 | Behåll lagerdelar; avlägsna enbart farming, planner, proxy, console och live viewer. |
| `src/main/java/com/porter/qwerte/manager/gui/tabs/TabFocus.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/tabs/UsersTab.java` | EXTRACT W-005/007/008 | Behåll lagerdelar; avlägsna enbart farming, planner, proxy, console och live viewer. |
| `src/main/java/com/porter/qwerte/manager/gui/tabs/WDrawer.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/tabs/WStashPage.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/widgets/ItemLayer.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/widgets/MaskedRenderer.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/widgets/SlotArt.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/widgets/TextWrap.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/widgets/WBar.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/widgets/WItemGrid.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/widgets/WItemIcon.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/widgets/WMapView.java` | REMOVE W-008 | Generell live world/HUD/chat-viewer eller fjärrkonsol; ingen stashkarta bygger på dessa klasser. |
| `src/main/java/com/porter/qwerte/manager/gui/widgets/WSlotGrid.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/gui/widgets/WTextLog.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/local/CaptureRules.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/local/ClickLog.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/local/ContainerKey.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/local/Evict.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/local/LocalCapture.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/local/LocalFile.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/local/LocalHub.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/local/LocalOrderStore.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/local/LocalSafety.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/local/LocalStore.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/local/SaveBackoff.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/local/ServerId.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/local/Where.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/map/MapAtlas.java` | REMOVE W-008 | Generell live world/HUD/chat-viewer eller fjärrkonsol; ingen stashkarta bygger på dessa klasser. |
| `src/main/java/com/porter/qwerte/manager/map/MapPalette.java` | REMOVE W-008 | Generell live world/HUD/chat-viewer eller fjärrkonsol; ingen stashkarta bygger på dessa klasser. |
| `src/main/java/com/porter/qwerte/manager/map/MapView.java` | REMOVE W-008 | Generell live world/HUD/chat-viewer eller fjärrkonsol; ingen stashkarta bygger på dessa klasser. |
| `src/main/java/com/porter/qwerte/manager/map/ViewerEvent.java` | REMOVE W-008 | Generell live world/HUD/chat-viewer eller fjärrkonsol; ingen stashkarta bygger på dessa klasser. |
| `src/main/java/com/porter/qwerte/manager/map/ViewerEvents.java` | REMOVE W-008 | Generell live world/HUD/chat-viewer eller fjärrkonsol; ingen stashkarta bygger på dessa klasser. |
| `src/main/java/com/porter/qwerte/manager/map/ViewerState.java` | REMOVE W-008 | Generell live world/HUD/chat-viewer eller fjärrkonsol; ingen stashkarta bygger på dessa klasser. |
| `src/main/java/com/porter/qwerte/manager/model/ActivityModel.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/model/AuthState.java` | EXTRACT W-005/007/008 | Behåll lagerdelar; avlägsna enbart farming, planner, proxy, console och live viewer. |
| `src/main/java/com/porter/qwerte/manager/model/BotInfo.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/model/BotList.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/model/BotsModel.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/model/Cart.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/model/CartPlan.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/model/ConsoleLog.java` | REMOVE W-008 | Generell live world/HUD/chat-viewer eller fjärrkonsol; ingen stashkarta bygger på dessa klasser. |
| `src/main/java/com/porter/qwerte/manager/model/DeliveryModel.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/model/DisplayPolicy.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/model/HeaderInfo.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/model/Ids.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/model/IndexModel.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/model/Json.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/model/NamesDraft.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/model/NavPage.java` | EXTRACT W-005/007/008 | Behåll lagerdelar; avlägsna enbart farming, planner, proxy, console och live viewer. |
| `src/main/java/com/porter/qwerte/manager/model/OrderMode.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/model/OrderModel.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/model/PlanModel.java` | REMOVE W-007 | Schematics/materialplanering, inte kitpackning eller crafting. |
| `src/main/java/com/porter/qwerte/manager/model/SchematicFiles.java` | REMOVE W-007 | Schematics/materialplanering, inte kitpackning eller crafting. |
| `src/main/java/com/porter/qwerte/manager/model/SettingsDraft.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/model/SettingsSchema.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/model/StashList.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/model/StashRef.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/model/Text.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/model/UsersModel.java` | EXTRACT W-005/007/008 | Behåll lagerdelar; avlägsna enbart farming, planner, proxy, console och live viewer. |
| `src/main/java/com/porter/qwerte/manager/model/WindowMode.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/net/ApiError.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/net/ApiResult.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/net/Cookies.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/net/ReconnectPolicy.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/net/ServerUrl.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/net/Session.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/net/SmClient.java` | EXTRACT W-005/007/008 | Behåll lagerdelar; avlägsna enbart farming, planner, proxy, console och live viewer. |
| `src/main/java/com/porter/qwerte/manager/net/SseParser.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `src/main/java/com/porter/qwerte/manager/net/StreamRestart.java` | REMOVE W-008 | Generell live world/HUD/chat-viewer eller fjärrkonsol; ingen stashkarta bygger på dessa klasser. |
| `src/main/java/com/porter/qwerte/manager/net/ViewerStream.java` | REMOVE W-008 | Generell live world/HUD/chat-viewer eller fjärrkonsol; ingen stashkarta bygger på dessa klasser. |
| `src/main/java/com/porter/qwerte/modules/AutoDirt.java` | REMOVE W-005 | Exklusiv farming-/pearl-automation; Restocker/StorageList har endast farming-anropare. |
| `src/main/java/com/porter/qwerte/modules/AutoTill.java` | REMOVE W-005 | Exklusiv farming-/pearl-automation; Restocker/StorageList har endast farming-anropare. |
| `src/main/java/com/porter/qwerte/modules/ChestPlacer.java` | KEEP | Lagerautomation, inventory clicks, shulkers, kits och lokala/fjärrorder. |
| `src/main/java/com/porter/qwerte/modules/Compactor.java` | KEEP | Lagerautomation, inventory clicks, shulkers, kits och lokala/fjärrorder. |
| `src/main/java/com/porter/qwerte/modules/Crafter.java` | KEEP | Lagerautomation, inventory clicks, shulkers, kits och lokala/fjärrorder. |
| `src/main/java/com/porter/qwerte/modules/GroundCollector.java` | KEEP | Lagerautomation, inventory clicks, shulkers, kits och lokala/fjärrorder. |
| `src/main/java/com/porter/qwerte/modules/KitBuilder.java` | KEEP | Lagerautomation, inventory clicks, shulkers, kits och lokala/fjärrorder. |
| `src/main/java/com/porter/qwerte/modules/LocalOrders.java` | KEEP | Lagerautomation, inventory clicks, shulkers, kits och lokala/fjärrorder. |
| `src/main/java/com/porter/qwerte/modules/OrderRunner.java` | KEEP | Lagerautomation, inventory clicks, shulkers, kits och lokala/fjärrorder. |
| `src/main/java/com/porter/qwerte/modules/ResetPearl.java` | REMOVE W-005 | Exklusiv farming-/pearl-automation; Restocker/StorageList har endast farming-anropare. |
| `src/main/java/com/porter/qwerte/modules/Restocker.java` | REMOVE W-005 | Exklusiv farming-/pearl-automation; Restocker/StorageList har endast farming-anropare. |
| `src/main/java/com/porter/qwerte/modules/StashLabeller.java` | KEEP | Lagerautomation, inventory clicks, shulkers, kits och lokala/fjärrorder. |
| `src/main/java/com/porter/qwerte/modules/StashManager.java` | KEEP | Lagerautomation, inventory clicks, shulkers, kits och lokala/fjärrorder. |
| `src/main/java/com/porter/qwerte/modules/StashOverlay.java` | KEEP | Lagerautomation, inventory clicks, shulkers, kits och lokala/fjärrorder. |
| `src/main/java/com/porter/qwerte/modules/StashSorter.java` | KEEP | Lagerautomation, inventory clicks, shulkers, kits och lokala/fjärrorder. |
| `src/main/java/com/porter/qwerte/modules/StashWindow.java` | KEEP | Lagerautomation, inventory clicks, shulkers, kits och lokala/fjärrorder. |
| `src/main/java/com/porter/qwerte/modules/StashYield.java` | KEEP | Lagerautomation, inventory clicks, shulkers, kits och lokala/fjärrorder. |
| `src/main/java/com/porter/qwerte/modules/StorageList.java` | REMOVE W-005 | Exklusiv farming-/pearl-automation; Restocker/StorageList har endast farming-anropare. |
| `src/main/java/com/porter/qwerte/pearl/Chamber.java` | REMOVE W-005 | Exklusiv farming-/pearl-automation; Restocker/StorageList har endast farming-anropare. |
| `src/main/java/com/porter/qwerte/pearl/ClimbDiagnosis.java` | REMOVE W-005 | Exklusiv farming-/pearl-automation; Restocker/StorageList har endast farming-anropare. |
| `src/main/java/com/porter/qwerte/pearl/DebugThrottle.java` | REMOVE W-005 | Exklusiv farming-/pearl-automation; Restocker/StorageList har endast farming-anropare. |
| `src/main/java/com/porter/qwerte/pearl/EdgeApproach.java` | REMOVE W-005 | Exklusiv farming-/pearl-automation; Restocker/StorageList har endast farming-anropare. |
| `src/main/java/com/porter/qwerte/pearl/ElytraGuard.java` | REMOVE W-005 | Exklusiv farming-/pearl-automation; Restocker/StorageList har endast farming-anropare. |
| `src/main/java/com/porter/qwerte/pearl/ElytraMode.java` | REMOVE W-005 | Exklusiv farming-/pearl-automation; Restocker/StorageList har endast farming-anropare. |
| `src/main/java/com/porter/qwerte/pearl/ElytraSwap.java` | REMOVE W-005 | Exklusiv farming-/pearl-automation; Restocker/StorageList har endast farming-anropare. |
| `src/main/java/com/porter/qwerte/pearl/Hang.java` | REMOVE W-005 | Exklusiv farming-/pearl-automation; Restocker/StorageList har endast farming-anropare. |
| `src/main/java/com/porter/qwerte/pearl/InvQueue.java` | REMOVE W-005 | Exklusiv farming-/pearl-automation; Restocker/StorageList har endast farming-anropare. |
| `src/main/java/com/porter/qwerte/pearl/OwnThrow.java` | REMOVE W-005 | Exklusiv farming-/pearl-automation; Restocker/StorageList har endast farming-anropare. |
| `src/main/java/com/porter/qwerte/pearl/Pending.java` | REMOVE W-005 | Exklusiv farming-/pearl-automation; Restocker/StorageList har endast farming-anropare. |
| `src/main/java/com/porter/qwerte/pearl/PhaseTimer.java` | REMOVE W-005 | Exklusiv farming-/pearl-automation; Restocker/StorageList har endast farming-anropare. |
| `src/main/java/com/porter/qwerte/pearl/PullDetector.java` | REMOVE W-005 | Exklusiv farming-/pearl-automation; Restocker/StorageList har endast farming-anropare. |
| `src/main/java/com/porter/qwerte/pearl/Sequence.java` | REMOVE W-005 | Exklusiv farming-/pearl-automation; Restocker/StorageList har endast farming-anropare. |
| `src/main/java/com/porter/qwerte/pearl/ShaftScan.java` | REMOVE W-005 | Exklusiv farming-/pearl-automation; Restocker/StorageList har endast farming-anropare. |
| `src/main/java/com/porter/qwerte/pearl/SlotWatch.java` | REMOVE W-005 | Exklusiv farming-/pearl-automation; Restocker/StorageList har endast farming-anropare. |
| `src/main/java/com/porter/qwerte/pearl/SpeedMode.java` | REMOVE W-005 | Exklusiv farming-/pearl-automation; Restocker/StorageList har endast farming-anropare. |
| `src/main/java/com/porter/qwerte/pearl/TrapClicks.java` | REMOVE W-005 | Exklusiv farming-/pearl-automation; Restocker/StorageList har endast farming-anropare. |
| `src/main/java/com/porter/qwerte/pearl/TrapRoles.java` | REMOVE W-005 | Exklusiv farming-/pearl-automation; Restocker/StorageList har endast farming-anropare. |
| `src/main/java/com/porter/qwerte/stash/Categories.java` | KEEP | Lagerautomation, inventory clicks, shulkers, kits och lokala/fjärrorder. |
| `src/main/java/com/porter/qwerte/stash/Containers.java` | KEEP | Lagerautomation, inventory clicks, shulkers, kits och lokala/fjärrorder. |
| `src/main/java/com/porter/qwerte/stash/DeliveryBook.java` | KEEP | Lagerautomation, inventory clicks, shulkers, kits och lokala/fjärrorder. |
| `src/main/java/com/porter/qwerte/stash/DeliveryContents.java` | KEEP | Lagerautomation, inventory clicks, shulkers, kits och lokala/fjärrorder. |
| `src/main/java/com/porter/qwerte/stash/DeliveryRoom.java` | KEEP | Lagerautomation, inventory clicks, shulkers, kits och lokala/fjärrorder. |
| `src/main/java/com/porter/qwerte/stash/IndexJson.java` | KEEP | Lagerautomation, inventory clicks, shulkers, kits och lokala/fjärrorder. |
| `src/main/java/com/porter/qwerte/stash/ItemPreviewScreen.java` | KEEP | Lagerautomation, inventory clicks, shulkers, kits och lokala/fjärrorder. |
| `src/main/java/com/porter/qwerte/stash/Kit.java` | KEEP | Lagerautomation, inventory clicks, shulkers, kits och lokala/fjärrorder. |
| `src/main/java/com/porter/qwerte/stash/KitPreviewScreen.java` | KEEP | Lagerautomation, inventory clicks, shulkers, kits och lokala/fjärrorder. |
| `src/main/java/com/porter/qwerte/stash/Kits.java` | KEEP | Lagerautomation, inventory clicks, shulkers, kits och lokala/fjärrorder. |
| `src/main/java/com/porter/qwerte/stash/Order.java` | KEEP | Lagerautomation, inventory clicks, shulkers, kits och lokala/fjärrorder. |
| `src/main/java/com/porter/qwerte/stash/SpaceWait.java` | KEEP | Lagerautomation, inventory clicks, shulkers, kits och lokala/fjärrorder. |
| `src/main/java/com/porter/qwerte/stash/StackInfo.java` | KEEP | Lagerautomation, inventory clicks, shulkers, kits och lokala/fjärrorder. |
| `src/main/java/com/porter/qwerte/stash/Stash.java` | KEEP | Lagerautomation, inventory clicks, shulkers, kits och lokala/fjärrorder. |
| `src/main/java/com/porter/qwerte/stash/StashIndex.java` | KEEP | Lagerautomation, inventory clicks, shulkers, kits och lokala/fjärrorder. |
| `src/main/java/com/porter/qwerte/stash/StashSafety.java` | KEEP | Lagerautomation, inventory clicks, shulkers, kits och lokala/fjärrorder. |
| `src/main/java/com/porter/qwerte/stash/StashWatch.java` | KEEP | Lagerautomation, inventory clicks, shulkers, kits och lokala/fjärrorder. |
| `src/main/java/com/porter/qwerte/utils/AutoHotbar.java` | KEEP | Versionsadapter, inventory/hotbar, cheststack, pacing, Baritone, retry och placeringssökning; döda rester granskas W-010. |
| `src/main/java/com/porter/qwerte/utils/BaritoneHelper.java` | KEEP | Versionsadapter, inventory/hotbar, cheststack, pacing, Baritone, retry och placeringssökning; döda rester granskas W-010. |
| `src/main/java/com/porter/qwerte/utils/ChestStack.java` | KEEP | Versionsadapter, inventory/hotbar, cheststack, pacing, Baritone, retry och placeringssökning; döda rester granskas W-010. |
| `src/main/java/com/porter/qwerte/utils/ContainerPacing.java` | KEEP | Versionsadapter, inventory/hotbar, cheststack, pacing, Baritone, retry och placeringssökning; döda rester granskas W-010. |
| `src/main/java/com/porter/qwerte/utils/PlaceSearch.java` | KEEP | Versionsadapter, inventory/hotbar, cheststack, pacing, Baritone, retry och placeringssökning; döda rester granskas W-010. |
| `src/main/java/com/porter/qwerte/utils/SnapshotSearch.java` | KEEP | Versionsadapter, inventory/hotbar, cheststack, pacing, Baritone, retry och placeringssökning; döda rester granskas W-010. |
| `src/main/java/com/porter/qwerte/utils/SurveyRetry.java` | KEEP | Versionsadapter, inventory/hotbar, cheststack, pacing, Baritone, retry och placeringssökning; döda rester granskas W-010. |
| `src/main/java/com/porter/qwerte/utils/WalkProgress.java` | KEEP | Versionsadapter, inventory/hotbar, cheststack, pacing, Baritone, retry och placeringssökning; döda rester granskas W-010. |
| `src/main/java/com/porter/qwerte/utils/WorkPattern.java` | REMOVE W-005 | Exklusiv farming-/pearl-automation; Restocker/StorageList har endast farming-anropare. |
| `src/main/resources/assets/qwerte/icon.png` | KEEP | Itemikoner, recipes, creative tabs, fonter, teman eller stash-/assetstöd; rebrand W-011. |
| `src/main/resources/assets/qwerte/recipes/1.21.11.json` | KEEP | Itemikoner, recipes, creative tabs, fonter, teman eller stash-/assetstöd; rebrand W-011. |
| `src/main/resources/assets/qwerte/recipes/1.21.4.json` | KEEP | Itemikoner, recipes, creative tabs, fonter, teman eller stash-/assetstöd; rebrand W-011. |
| `src/main/resources/fabric.mod.json` | KEEP | Itemikoner, recipes, creative tabs, fonter, teman eller stash-/assetstöd; rebrand W-011. |
| `src/test/java/com/porter/qwerte/craft/AnvilFinderTest.java` | KEEP | Recipes/CraftPlanner/CraftHealth/TableFinder/AnvilFinder används för kit/order. |
| `src/test/java/com/porter/qwerte/craft/CraftHealthTest.java` | KEEP | Recipes/CraftPlanner/CraftHealth/TableFinder/AnvilFinder används för kit/order. |
| `src/test/java/com/porter/qwerte/craft/CraftPlannerTest.java` | KEEP | Recipes/CraftPlanner/CraftHealth/TableFinder/AnvilFinder används för kit/order. |
| `src/test/java/com/porter/qwerte/craft/TableFinderTest.java` | KEEP | Recipes/CraftPlanner/CraftHealth/TableFinder/AnvilFinder används för kit/order. |
| `src/test/java/com/porter/qwerte/manager/gui/StashPageWiringTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/gui/TextBoxFilterTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/gui/frame/DragInputTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/gui/frame/FrameMathTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/gui/frame/PopupAndMaskTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/gui/frame/WindowGeometryTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/gui/layout/CountLayoutTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/gui/layout/GridLayoutTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/gui/layout/LayoutsTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/gui/style/PaletteTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/gui/style/TextScaleTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/gui/widgets/TextWrapTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/local/LocalHardeningTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/local/LocalOrderStoreTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/local/LocalRulesTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/local/LocalStoreTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/map/MapAtlasTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/map/MapPaletteTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/map/MapViewTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/map/ViewerEventsTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/map/ViewerStateTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/model/ActivityModelTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/model/AuthStateTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/model/BotListTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/model/BotsModelTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/model/CartPlanTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/model/CartTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/model/ConsoleLogTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/model/DeliveryModelTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/model/HeaderInfoNavTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/model/IdsTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/model/IndexModelTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/model/NamesAndModeTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/model/OrderModelTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/model/PlanModelTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/model/SchematicFilesTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/model/SettingsSchemaTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/model/StashListTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/model/TextTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/model/UsersModelTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/model/WindowModeTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/net/ApiErrorTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/net/ApiResultTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/net/CookiesTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/net/ServerUrlTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/net/SmClientTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/net/SseParserTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/manager/net/StreamPolicyTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `src/test/java/com/porter/qwerte/modules/StashManagerUrlDefaultTest.java` | KEEP | Lagerautomation, inventory clicks, shulkers, kits och lokala/fjärrorder. |
| `src/test/java/com/porter/qwerte/pearl/ChamberTest.java` | REMOVE W-005 | Exklusiv farming-/pearl-automation; Restocker/StorageList har endast farming-anropare. |
| `src/test/java/com/porter/qwerte/pearl/ColumnAndThrowTest.java` | REMOVE W-005 | Exklusiv farming-/pearl-automation; Restocker/StorageList har endast farming-anropare. |
| `src/test/java/com/porter/qwerte/pearl/EdgeAndSlotTest.java` | REMOVE W-005 | Exklusiv farming-/pearl-automation; Restocker/StorageList har endast farming-anropare. |
| `src/test/java/com/porter/qwerte/pearl/ElytraTest.java` | REMOVE W-005 | Exklusiv farming-/pearl-automation; Restocker/StorageList har endast farming-anropare. |
| `src/test/java/com/porter/qwerte/pearl/HangAndSequenceTest.java` | REMOVE W-005 | Exklusiv farming-/pearl-automation; Restocker/StorageList har endast farming-anropare. |
| `src/test/java/com/porter/qwerte/pearl/InvQueueTest.java` | REMOVE W-005 | Exklusiv farming-/pearl-automation; Restocker/StorageList har endast farming-anropare. |
| `src/test/java/com/porter/qwerte/pearl/PearlWaitTest.java` | REMOVE W-005 | Exklusiv farming-/pearl-automation; Restocker/StorageList har endast farming-anropare. |
| `src/test/java/com/porter/qwerte/pearl/PullDetectorTest.java` | REMOVE W-005 | Exklusiv farming-/pearl-automation; Restocker/StorageList har endast farming-anropare. |
| `src/test/java/com/porter/qwerte/pearl/SaveScanTest.java` | REMOVE W-005 | Exklusiv farming-/pearl-automation; Restocker/StorageList har endast farming-anropare. |
| `src/test/java/com/porter/qwerte/pearl/ShaftScanTest.java` | REMOVE W-005 | Exklusiv farming-/pearl-automation; Restocker/StorageList har endast farming-anropare. |
| `src/test/java/com/porter/qwerte/pearl/SpeedTest.java` | REMOVE W-005 | Exklusiv farming-/pearl-automation; Restocker/StorageList har endast farming-anropare. |
| `src/test/java/com/porter/qwerte/pearl/TrapClicksTest.java` | REMOVE W-005 | Exklusiv farming-/pearl-automation; Restocker/StorageList har endast farming-anropare. |
| `src/test/java/com/porter/qwerte/pearl/TrapRolesTest.java` | REMOVE W-005 | Exklusiv farming-/pearl-automation; Restocker/StorageList har endast farming-anropare. |
| `src/test/java/com/porter/qwerte/stash/DeliveryBookTest.java` | KEEP | Lagerautomation, inventory clicks, shulkers, kits och lokala/fjärrorder. |
| `src/test/java/com/porter/qwerte/stash/DeliveryContentsTest.java` | KEEP | Lagerautomation, inventory clicks, shulkers, kits och lokala/fjärrorder. |
| `src/test/java/com/porter/qwerte/stash/DurabilityTest.java` | KEEP | Lagerautomation, inventory clicks, shulkers, kits och lokala/fjärrorder. |
| `src/test/java/com/porter/qwerte/stash/IndexJsonTest.java` | KEEP | Lagerautomation, inventory clicks, shulkers, kits och lokala/fjärrorder. |
| `src/test/java/com/porter/qwerte/stash/SpaceWaitTest.java` | KEEP | Lagerautomation, inventory clicks, shulkers, kits och lokala/fjärrorder. |
| `src/test/java/com/porter/qwerte/stash/StashSafetyTest.java` | KEEP | Lagerautomation, inventory clicks, shulkers, kits och lokala/fjärrorder. |
| `src/test/java/com/porter/qwerte/utils/ChestStackTest.java` | KEEP | Versionsadapter, inventory/hotbar, cheststack, pacing, Baritone, retry och placeringssökning; döda rester granskas W-010. |
| `src/test/java/com/porter/qwerte/utils/ContainerPacingTest.java` | KEEP | Versionsadapter, inventory/hotbar, cheststack, pacing, Baritone, retry och placeringssökning; döda rester granskas W-010. |
| `src/test/java/com/porter/qwerte/utils/PlaceSearchTest.java` | KEEP | Versionsadapter, inventory/hotbar, cheststack, pacing, Baritone, retry och placeringssökning; döda rester granskas W-010. |
| `src/test/java/com/porter/qwerte/utils/SnapshotSearchTest.java` | KEEP | Versionsadapter, inventory/hotbar, cheststack, pacing, Baritone, retry och placeringssökning; döda rester granskas W-010. |
| `src/test/java/com/porter/qwerte/utils/SurveyRetryTest.java` | KEEP | Versionsadapter, inventory/hotbar, cheststack, pacing, Baritone, retry och placeringssökning; döda rester granskas W-010. |
| `stash-manager-server/DASHBOARD-DESIGN.md` | EXTRACT W-010/011 | Ersätt aktiv produktdokumentation, bevara ursprung i Git/UPSTREAM och relevanta protokollkrav. |
| `stash-manager-server/PROTOCOL.md` | EXTRACT W-010/011 | Ersätt aktiv produktdokumentation, bevara ursprung i Git/UPSTREAM och relevanta protokollkrav. |
| `stash-manager-server/README.md` | EXTRACT W-010/011 | Ersätt aktiv produktdokumentation, bevara ursprung i Git/UPSTREAM och relevanta protokollkrav. |
| `stash-manager-server/build.gradle.kts` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `stash-manager-server/dev/block-items/GenBlockItems.java` | REMOVE W-007 | Schematics/materialplanering, inte kitpackning eller crafting. |
| `stash-manager-server/dev/block-items/README.md` | REMOVE W-007 | Schematics/materialplanering, inte kitpackning eller crafting. |
| `stash-manager-server/dev/block-items/rules.json` | REMOVE W-007 | Schematics/materialplanering, inte kitpackning eller crafting. |
| `stash-manager-server/dev/block-items/run.sh` | REMOVE W-007 | Schematics/materialplanering, inte kitpackning eller crafting. |
| `stash-manager-server/dev/creative-tabs/GenCreativeTabs.java` | KEEP | Itemikoner, recipes, creative tabs, fonter, teman eller stash-/assetstöd; rebrand W-011. |
| `stash-manager-server/dev/creative-tabs/README.md` | EXTRACT W-010/011 | Ersätt aktiv produktdokumentation, bevara ursprung i Git/UPSTREAM och relevanta protokollkrav. |
| `stash-manager-server/dev/creative-tabs/run.sh` | KEEP | Itemikoner, recipes, creative tabs, fonter, teman eller stash-/assetstöd; rebrand W-011. |
| `stash-manager-server/dev/fake-addon.py` | KEEP | Itemikoner, recipes, creative tabs, fonter, teman eller stash-/assetstöd; rebrand W-011. |
| `stash-manager-server/dev/map-colors/GenMapColors.java` | KEEP | Itemikoner, recipes, creative tabs, fonter, teman eller stash-/assetstöd; rebrand W-011. |
| `stash-manager-server/dev/map-colors/README.md` | EXTRACT W-010/011 | Ersätt aktiv produktdokumentation, bevara ursprung i Git/UPSTREAM och relevanta protokollkrav. |
| `stash-manager-server/dev/map-colors/run.sh` | KEEP | Itemikoner, recipes, creative tabs, fonter, teman eller stash-/assetstöd; rebrand W-011. |
| `stash-manager-server/dev/recipes/GenRecipes.java` | KEEP | Itemikoner, recipes, creative tabs, fonter, teman eller stash-/assetstöd; rebrand W-011. |
| `stash-manager-server/dev/recipes/README.md` | EXTRACT W-010/011 | Ersätt aktiv produktdokumentation, bevara ursprung i Git/UPSTREAM och relevanta protokollkrav. |
| `stash-manager-server/dev/recipes/run.sh` | KEEP | Itemikoner, recipes, creative tabs, fonter, teman eller stash-/assetstöd; rebrand W-011. |
| `stash-manager-server/dev/themes-palette.json` | KEEP | Itemikoner, recipes, creative tabs, fonter, teman eller stash-/assetstöd; rebrand W-011. |
| `stash-manager-server/run-server.bat` | REMOVE W-010 | Äldre Windows-serverdrift; Linux Docker införs i W-055. |
| `stash-manager-server/settings.gradle.kts` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/AccessControl.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/Activity.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/AddonRoutes.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/AtomicFiles.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/AuditLog.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/Auth.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/AuthRoutes.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/BotHub.java` | EXTRACT W-005/007/008 | Behåll lagerdelar; avlägsna enbart farming, planner, proxy, console och live viewer. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/BotRoutes.java` | EXTRACT W-005/007/008 | Behåll lagerdelar; avlägsna enbart farming, planner, proxy, console och live viewer. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/BrowserRoutes.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/Config.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/CreativeTabs.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/DashboardRoutes.java` | EXTRACT W-005/007/008 | Behåll lagerdelar; avlägsna enbart farming, planner, proxy, console och live viewer. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/DeliveryContents.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/DeliveryRoutes.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/GameIcons.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/Http.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/IconRoutes.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/IconService.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/IndexStore.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/Json.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/LinkState.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/LoginLimiter.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/OrderRoutes.java` | EXTRACT W-005/007/008 | Behåll lagerdelar; avlägsna enbart farming, planner, proxy, console och live viewer. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/OrderStore.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/Owners.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/PlanStore.java` | REMOVE W-007 | Schematics/materialplanering, inte kitpackning eller crafting. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/PlannerRoutes.java` | REMOVE W-007 | Schematics/materialplanering, inte kitpackning eller crafting. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/Redact.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/Router.java` | EXTRACT W-005/007/008 | Behåll lagerdelar; avlägsna enbart farming, planner, proxy, console och live viewer. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/ServerIds.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/SessionStore.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/Signer.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/StashManagerServer.java` | EXTRACT W-005/007/008 | Behåll lagerdelar; avlägsna enbart farming, planner, proxy, console och live viewer. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/StaticRoutes.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/UiIcons.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/UserRoutes.java` | EXTRACT W-005/007/008 | Behåll lagerdelar; avlägsna enbart farming, planner, proxy, console och live viewer. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/Users.java` | EXTRACT W-005/007/008 | Behåll lagerdelar; avlägsna enbart farming, planner, proxy, console och live viewer. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/ViewerHub.java` | REMOVE W-008 | Generell live world/HUD/chat-viewer eller fjärrkonsol; ingen stashkarta bygger på dessa klasser. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/ViewerRoutes.java` | REMOVE W-008 | Generell live world/HUD/chat-viewer eller fjärrkonsol; ingen stashkarta bygger på dessa klasser. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/assets/GameAssets.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/assets/IconRenderer.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/assets/ModelRasterizer.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/assets/SpecialModels.java` | KEEP | Gemensam auth, lager/backend/webb, byggverktyg eller projektmetadata; rebrand där det behövs W-011. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/planner/BlockItems.java` | REMOVE W-007 | Schematics/materialplanering, inte kitpackning eller crafting. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/planner/BlockState.java` | REMOVE W-007 | Schematics/materialplanering, inte kitpackning eller crafting. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/planner/MaterialList.java` | REMOVE W-007 | Schematics/materialplanering, inte kitpackning eller crafting. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/planner/Nbt.java` | REMOVE W-007 | Schematics/materialplanering, inte kitpackning eller crafting. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/planner/Schematic.java` | REMOVE W-007 | Schematics/materialplanering, inte kitpackning eller crafting. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/planner/SchematicException.java` | REMOVE W-007 | Schematics/materialplanering, inte kitpackning eller crafting. |
| `stash-manager-server/src/main/java/com/porter/stashmanager/planner/SchematicReader.java` | REMOVE W-007 | Schematics/materialplanering, inte kitpackning eller crafting. |
| `stash-manager-server/src/main/resources/block-items/1.21.11.json` | REMOVE W-007 | Schematics/materialplanering, inte kitpackning eller crafting. |
| `stash-manager-server/src/main/resources/block-items/1.21.4.json` | REMOVE W-007 | Schematics/materialplanering, inte kitpackning eller crafting. |
| `stash-manager-server/src/main/resources/creative-tabs/1.21.11.json` | KEEP | Itemikoner, recipes, creative tabs, fonter, teman eller stash-/assetstöd; rebrand W-011. |
| `stash-manager-server/src/main/resources/creative-tabs/1.21.4.json` | KEEP | Itemikoner, recipes, creative tabs, fonter, teman eller stash-/assetstöd; rebrand W-011. |
| `stash-manager-server/src/main/resources/licenses/fonts/OFL-atkinsonhyperlegible.txt` | KEEP | Ursprunglig GPL/OFL-attribution måste bevaras. |
| `stash-manager-server/src/main/resources/licenses/fonts/OFL-ibmplexsans.txt` | KEEP | Ursprunglig GPL/OFL-attribution måste bevaras. |
| `stash-manager-server/src/main/resources/licenses/fonts/OFL-inter.txt` | KEEP | Ursprunglig GPL/OFL-attribution måste bevaras. |
| `stash-manager-server/src/main/resources/licenses/fonts/OFL-jetbrainsmono.txt` | KEEP | Ursprunglig GPL/OFL-attribution måste bevaras. |
| `stash-manager-server/src/main/resources/licenses/fonts/OFL-spacemono.txt` | KEEP | Ursprunglig GPL/OFL-attribution måste bevaras. |
| `stash-manager-server/src/main/resources/licenses/fonts/README.txt` | KEEP | Ursprunglig GPL/OFL-attribution måste bevaras. |
| `stash-manager-server/src/main/resources/web/app.js` | EXTRACT W-005/007/008 | Behåll lagerdelar; avlägsna enbart farming, planner, proxy, console och live viewer. |
| `stash-manager-server/src/main/resources/web/atkinson-hyperlegible-400.woff2` | KEEP | Itemikoner, recipes, creative tabs, fonter, teman eller stash-/assetstöd; rebrand W-011. |
| `stash-manager-server/src/main/resources/web/atkinson-hyperlegible-700.woff2` | KEEP | Itemikoner, recipes, creative tabs, fonter, teman eller stash-/assetstöd; rebrand W-011. |
| `stash-manager-server/src/main/resources/web/dirt.svg` | KEEP | Itemikoner, recipes, creative tabs, fonter, teman eller stash-/assetstöd; rebrand W-011. |
| `stash-manager-server/src/main/resources/web/fonts.css` | KEEP | Itemikoner, recipes, creative tabs, fonter, teman eller stash-/assetstöd; rebrand W-011. |
| `stash-manager-server/src/main/resources/web/ibm-plex-sans-var.woff2` | KEEP | Itemikoner, recipes, creative tabs, fonter, teman eller stash-/assetstöd; rebrand W-011. |
| `stash-manager-server/src/main/resources/web/index.html` | EXTRACT W-005/007/008 | Behåll lagerdelar; avlägsna enbart farming, planner, proxy, console och live viewer. |
| `stash-manager-server/src/main/resources/web/inter-var.woff2` | KEEP | Itemikoner, recipes, creative tabs, fonter, teman eller stash-/assetstöd; rebrand W-011. |
| `stash-manager-server/src/main/resources/web/jetbrains-mono-var.woff2` | KEEP | Itemikoner, recipes, creative tabs, fonter, teman eller stash-/assetstöd; rebrand W-011. |
| `stash-manager-server/src/main/resources/web/map-palette.json` | REMOVE W-008 | Generell live world/HUD/chat-viewer eller fjärrkonsol; ingen stashkarta bygger på dessa klasser. |
| `stash-manager-server/src/main/resources/web/placeholder.svg` | KEEP | Itemikoner, recipes, creative tabs, fonter, teman eller stash-/assetstöd; rebrand W-011. |
| `stash-manager-server/src/main/resources/web/space-mono-400.woff2` | KEEP | Itemikoner, recipes, creative tabs, fonter, teman eller stash-/assetstöd; rebrand W-011. |
| `stash-manager-server/src/main/resources/web/space-mono-700.woff2` | KEEP | Itemikoner, recipes, creative tabs, fonter, teman eller stash-/assetstöd; rebrand W-011. |
| `stash-manager-server/src/main/resources/web/streamer-init.js` | KEEP | Itemikoner, recipes, creative tabs, fonter, teman eller stash-/assetstöd; rebrand W-011. |
| `stash-manager-server/src/main/resources/web/style.css` | EXTRACT W-005/007/008 | Behåll lagerdelar; avlägsna enbart farming, planner, proxy, console och live viewer. |
| `stash-manager-server/src/main/resources/web/theme-init.js` | KEEP | Itemikoner, recipes, creative tabs, fonter, teman eller stash-/assetstöd; rebrand W-011. |
| `stash-manager-server/src/main/resources/web/themes.css` | KEEP | Itemikoner, recipes, creative tabs, fonter, teman eller stash-/assetstöd; rebrand W-011. |
| `stash-manager-server/src/test/java/com/porter/stashmanager/AuthTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `stash-manager-server/src/test/java/com/porter/stashmanager/BrowserWriteOriginTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `stash-manager-server/src/test/java/com/porter/stashmanager/DashboardTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `stash-manager-server/src/test/java/com/porter/stashmanager/DeliveryTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `stash-manager-server/src/test/java/com/porter/stashmanager/IconCacheVersionTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `stash-manager-server/src/test/java/com/porter/stashmanager/LoginSecurityTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `stash-manager-server/src/test/java/com/porter/stashmanager/NamingAndTabsTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `stash-manager-server/src/test/java/com/porter/stashmanager/OrderNamesTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `stash-manager-server/src/test/java/com/porter/stashmanager/PlannerTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `stash-manager-server/src/test/java/com/porter/stashmanager/SignerTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `stash-manager-server/src/test/java/com/porter/stashmanager/StreamerTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `stash-manager-server/src/test/java/com/porter/stashmanager/UsersTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `stash-manager-server/src/test/java/com/porter/stashmanager/ViewerTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `stash-manager-server/src/test/java/com/porter/stashmanager/assets/SpecialModelsTest.java` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `stash-manager-server/src/test/java/com/porter/stashmanager/planner/BlockItemsTest.java` | REMOVE W-007 | Schematics/materialplanering, inte kitpackning eller crafting. |
| `stash-manager-server/src/test/java/com/porter/stashmanager/planner/NbtTest.java` | REMOVE W-007 | Schematics/materialplanering, inte kitpackning eller crafting. |
| `stash-manager-server/src/test/java/com/porter/stashmanager/planner/NbtWriter.java` | REMOVE W-007 | Schematics/materialplanering, inte kitpackning eller crafting. |
| `stash-manager-server/src/test/java/com/porter/stashmanager/planner/PlannerFixtures.java` | REMOVE W-007 | Schematics/materialplanering, inte kitpackning eller crafting. |
| `stash-manager-server/src/test/java/com/porter/stashmanager/planner/SchematicReaderTest.java` | REMOVE W-007 | Schematics/materialplanering, inte kitpackning eller crafting. |
| `stash-manager-server/src/test/resources/creative-tabs-fixture/1.20.6.json` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `stash-manager-server/src/test/resources/creative-tabs-fixture/1.21.11.json` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |
| `stash-manager-server/src/test/resources/creative-tabs-fixture/1.21.4.json` | KEEP/EXTRACT | Behåll relevant regression; endast tester uteslutande för borttagen feature får tas bort med redovisning. |

## Endpointmatris från registreringarna

Symboliska regexnamn nedan avser respektive originalfils Pattern-deklaration.

| Fil/registrering | Beslut |
|---|---|
| `AddonRoutes.java: router.exact(Router.POST, "/api/v1/index", Router.Guard.ADDON_KEY, this::postIndex)` | KEEP |
| `AddonRoutes.java: .exact(Router.POST, "/api/v1/heartbeat", Router.Guard.ADDON_KEY, this::heartbeat);` | KEEP |
| `AuthRoutes.java: router.exact(Router.GET, "/api/v1/auth/state", Router.Guard.OPEN, this::state)` | KEEP |
| `AuthRoutes.java: .exact(Router.POST, "/api/v1/auth/setup", Router.Guard.CSRF, this::setup)` | KEEP |
| `AuthRoutes.java: .exact(Router.POST, "/api/v1/auth/login", Router.Guard.CSRF, this::login)` | KEEP |
| `AuthRoutes.java: .exact(Router.POST, "/api/v1/auth/logout", Router.Guard.BROWSER_WRITE, Router.Perm.ANY, this::logout)` | KEEP |
| `AuthRoutes.java: .exact(Router.POST, "/api/v1/auth/logout-all", Router.Guard.BROWSER_WRITE, Router.Perm.ANY, this::logoutAll)` | KEEP |
| `AuthRoutes.java: .exact(Router.POST, "/api/v1/auth/password", Router.Guard.BROWSER_WRITE, Router.Perm.ANY, this::password)` | KEEP |
| `AuthRoutes.java: .exact(Router.POST, "/api/v1/auth/confirm", Router.Guard.BROWSER_WRITE, Router.Perm.ANY, this::confirm)` | KEEP |
| `AuthRoutes.java: .exact(Router.POST, "/api/v1/auth/lock", Router.Guard.BROWSER_WRITE, Router.Perm.ANY, this::lock);` | KEEP |
| `BotRoutes.java: router.exact(Router.POST, "/api/v1/bot/hello", Router.Guard.ADDON_KEY, this::hello)` | EXTRACT W-005/007/008 |
| `BotRoutes.java: .exact(Router.POST, "/api/v1/bot/state", Router.Guard.ADDON_KEY, this::state)` | EXTRACT W-005/007/008 |
| `BotRoutes.java: .exact(Router.GET, "/api/v1/bot/next", Router.Guard.ADDON_KEY, this::next)` | EXTRACT W-005/007/008 |
| `BotRoutes.java: .exact(Router.POST, "/api/v1/bot/results", Router.Guard.ADDON_KEY, this::results)` | EXTRACT W-005/007/008 |
| `BotRoutes.java: .exact(Router.POST, "/api/v1/bot/activity", Router.Guard.ADDON_KEY, this::activity);` | EXTRACT W-005/007/008 |
| `BrowserRoutes.java: router.exact(Router.GET, "/api/v1/stashes", Router.Guard.SESSION, Router.Perm.VIEW, ex -> Http.json(ex, 200, stashes(Router.user(ex))))` | KEEP |
| `BrowserRoutes.java: .exact(Router.POST, "/api/v1/stashes/release", Router.Guard.BROWSER_WRITE, Router.Perm.LINK, this::release)` | KEEP |
| `BrowserRoutes.java: .exact(Router.GET, "/api/v1/index", Router.Guard.SESSION, Router.Perm.VIEW, this::getIndex)` | KEEP |
| `BrowserRoutes.java: .exact(Router.GET, "/api/v1/link", Router.Guard.SESSION, Router.Perm.LINK, this::getLink)` | KEEP |
| `BrowserRoutes.java: .exact(Router.POST, "/api/v1/link/regenerate", Router.Guard.BROWSER_WRITE, Router.Perm.OWNER, this::regenerate)` | KEEP |
| `BrowserRoutes.java: .exact(Router.POST, "/api/v1/rescan", Router.Guard.BROWSER_WRITE, Router.Perm.RESCAN, this::rescan);` | KEEP |
| `DashboardRoutes.java: router.exact(Router.GET, "/api/v1/bots", Router.Guard.SESSION, Router.Perm.ACTIVITY, this::list)` | EXTRACT W-005/007/008 |
| `DashboardRoutes.java: .pattern(Router.GET, BOT, Router.Guard.SESSION, Router.Perm.ACTIVITY, this::get)` | EXTRACT W-005/007/008 |
| `DashboardRoutes.java: .pattern(Router.POST, BOT, Router.Guard.BROWSER_WRITE, Router.Perm.ACTIVITY, this::post);` | EXTRACT W-005/007/008 |
| `DeliveryRoutes.java: router.exact(Router.POST, "/api/v1/delivery", Router.Guard.ADDON_KEY, this::post)` | KEEP |
| `DeliveryRoutes.java: .exact(Router.GET, "/api/v1/delivery", Router.Guard.SESSION, Router.Perm.VIEW, this::get);` | KEEP |
| `IconRoutes.java: router.prefix(Router.GET, "/icon/", Router.Guard.SESSION, Router.Perm.ICONS, this::icon);` | KEEP |
| `OrderRoutes.java: router.exact(Router.POST, "/api/v1/orders/claim", Router.Guard.ADDON_KEY, this::claim)` | EXTRACT W-005/007/008 |
| `OrderRoutes.java: .exact(Router.POST, "/api/v1/orders", Router.Guard.BROWSER_WRITE, Router.Perm.ORDER, this::place)` | EXTRACT W-005/007/008 |
| `OrderRoutes.java: .exact(Router.POST, "/api/v1/orders/clear", Router.Guard.BROWSER_WRITE, Router.Perm.ORDERS_ALL, this::clear)` | EXTRACT W-005/007/008 |
| `OrderRoutes.java: .exact(Router.GET, "/api/v1/orders", Router.Guard.SESSION, Router.Perm.VIEW, this::list)` | EXTRACT W-005/007/008 |
| `OrderRoutes.java: .pattern(Router.POST, CANCEL, Router.Guard.BROWSER_WRITE, Router.Perm.ORDER_OWN_OR_ALL, this::cancel)` | EXTRACT W-005/007/008 |
| `OrderRoutes.java: .pattern(Router.POST, FORCE_CANCEL, Router.Guard.BROWSER_WRITE, Router.Perm.ORDER_OWN_OR_ALL, this::forceCancel)` | EXTRACT W-005/007/008 |
| `OrderRoutes.java: .pattern(Router.POST, RETRY, Router.Guard.BROWSER_WRITE, Router.Perm.ORDER_OWN_OR_ALL, this::retry)` | EXTRACT W-005/007/008 |
| `OrderRoutes.java: .pattern(Router.POST, STATUS, Router.Guard.ADDON_KEY, this::status);` | EXTRACT W-005/007/008 |
| `PlannerRoutes.java: router.exact(Router.POST, "/api/v1/planner/upload", Router.Guard.BROWSER_UPLOAD, Router.Perm.PLANNER, this::upload)` | REMOVE W-007 |
| `PlannerRoutes.java: .exact(Router.GET, "/api/v1/planner", Router.Guard.SESSION, Router.Perm.PLANNER, this::list)` | REMOVE W-007 |
| `PlannerRoutes.java: .pattern(Router.GET, PLAN, Router.Guard.SESSION, Router.Perm.PLANNER, this::view)` | REMOVE W-007 |
| `PlannerRoutes.java: .pattern(Router.POST, VIEW, Router.Guard.BROWSER_WRITE, Router.Perm.PLANNER, this::viewPost)` | REMOVE W-007 |
| `PlannerRoutes.java: .pattern(Router.POST, DELETE, Router.Guard.BROWSER_WRITE, Router.Perm.PLANNER, this::delete)` | REMOVE W-007 |
| `PlannerRoutes.java: .pattern(Router.POST, ORDER, Router.Guard.BROWSER_WRITE, Router.Perm.PLANNER_ORDER, this::order);` | REMOVE W-007 |
| `StaticRoutes.java: router.fallback(Router.GET, "/", this::staticFile);` | KEEP |
| `UserRoutes.java: router.exact(Router.GET, "/api/v1/users", Router.Guard.SESSION, Router.Perm.OWNER, this::list)` | EXTRACT W-005/007/008 |
| `UserRoutes.java: .exact(Router.POST, "/api/v1/users", Router.Guard.BROWSER_WRITE, Router.Perm.OWNER, this::create)` | EXTRACT W-005/007/008 |
| `UserRoutes.java: .exact(Router.POST, "/api/v1/users/owner", Router.Guard.BROWSER_WRITE, Router.Perm.OWNER, this::renameOwner)` | EXTRACT W-005/007/008 |
| `UserRoutes.java: .pattern(Router.POST, USER, Router.Guard.BROWSER_WRITE, Router.Perm.OWNER, this::update)` | EXTRACT W-005/007/008 |
| `UserRoutes.java: .pattern(Router.POST, ACTION, Router.Guard.BROWSER_WRITE, Router.Perm.OWNER, this::action)` | EXTRACT W-005/007/008 |
| `UserRoutes.java: .exact(Router.GET, "/api/v1/audit", Router.Guard.SESSION, Router.Perm.OWNER, this::audit);` | EXTRACT W-005/007/008 |
| `ViewerRoutes.java: router.exact(Router.POST, "/api/v1/bot/viewer", Router.Guard.ADDON_KEY, this::frame)` | REMOVE W-008 |
| `ViewerRoutes.java: .pattern(Set.of("GET"), STREAM, Router.Guard.SESSION, Router.Perm.VIEWER, this::stream)` | REMOVE W-008 |
| `ViewerRoutes.java: .pattern(Router.GET, STATE, Router.Guard.SESSION, Router.Perm.VIEWER, this::state);` | REMOVE W-008 |

DashboardRoutes regex `/api/v1/bots/:id/(settings|jobs|console|activity)`:
KEEP list/activity/jobs för lagrets drift och settings med scope=plugin;
REMOVE console och proxy/plugin:<id>-scopes. BotRoutes hello/state/next/results/
activity behålls för signerad lagerkommunikation, men console/viewer/proxy
capabilities och okända jobtyper ska inte accepteras.

Verifiering av kartläggningen: samtliga Git-spårade produktionsfiler, resurser,
testfiler och dev-generatorer ovan har beslut; referenssökning av Restocker,
StorageList, craft, MapTab och routerregistreringar gjord med rg. Inga
produktionsfiler ändras i W-004. Baslinjens testresultat finns i BASELINE.md.
