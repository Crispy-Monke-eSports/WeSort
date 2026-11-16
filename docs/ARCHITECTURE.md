# Arkitektur efter steg 0-rensning

Klienten delar kod i `src/main` och små versionsadaptrar i `src/<mc>`.
`modules/StashSorter` äger den befintliga fysiska sorteringen. Stash-/kit-/order-
algoritmer i `stash/`, `craft/` och modules/ återanvänds. ContainerPacing,
AutoHotbar, Containers, ChestStack, PlaceSearch, WalkProgress och SurveyRetry
behålls för inventory, placering och navigering. SnapshotSearch och head-mixin
behålls för säker läsning av player heads på 1.21.11.

Stash Window har två backendvägar: LocalHub/LocalCapture/LocalFile och
LocalOrderStore för klientens egen observation/order, respektive SmClient/
ServerSource/ServerOrderBackend för Manager. Ägar-/scope- och sekretesskontroll
sker på servern; klientens UI är ingen auktoritetsgräns.

Manager är ett separat Java 21-projekt med HttpServer och inbyggd webb.
IndexStore innehåller observationer och heartbeat per server/stash. OrderStore
hanterar den befintliga lokala ordermodellen. AddonRoutes/OrderRoutes hanterar
signerad kommunikation. BotHub behåller settings-jobb, botstatus och activity,
med endast eget plugin-scope; generella kommandon kan inte köas.

Auth, Signer, Sessions, AccessControl, Owners, Users och AuditLog behålls.
Trusted reverse proxies är en säkerhetsfunktion för klient-IP/rate limits och
ska inte blandas ihop med den borttagna AquariusProxy-administrationen.
Indexerade positioner och DeliveryBook.onViewers (öppna leveranskistor)
behålls; generell world/HUD/chat-viewer är borta. Webbens ikoner, fonter,
creative tabs, teman, shulkerpreview, cart och leveranspreview finns kvar.

Detta är inte den framtida centrala reservation-/guildordermotorn. Observation
är inte realtidssaldo och färdigpackad order bevisar inte fysisk mottagning.
Nästa faser modellerar dessa gränser och inför Linux Docker och Minecraft 26.3.
