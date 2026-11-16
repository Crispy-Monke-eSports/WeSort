# Steg 3 – lagergrunden

Steg 3 är påbörjat med W-018 (#19). Övriga issues i steget återstår. Arbetet
ligger direkt på main och varje issue får en egen commit som pushas innan
nästa påbörjas. GitHubs checklistor och issuestatusar ändras inte.

## W-018 i denna commit

Fysiska container-ID:n, serverbekräftade observationsbevis, SQLite-revisioner,
färskhetspolicy, automatisk rescan, guildsaldo, saldo per stash och fysiska
källor med dimension och senaste verifiering finns nu i koden. För gamla,
oåtkomliga och omstartade observationer ger noll orderbart lager men behåller
historiken. Läs [observationskontrakt och drift](MC_26_3_STOCK.md).

Rescan söker även efter nya och återställda containrar när scannern redan
kör. En verklig borttagen/återställd kista avslöjade den tidigare luckan i
den aktiva scannerns medlemslista; återupptäckten sker vid nästa säkra
jobbgräns och kräver sedan ny serveravläsning.

## Verifiering

Lokal JUnit: samtliga klienttargets kör 455 tester vardera utan fel. Manager
kör 160 tester, 159 passerar och ett befintligt prov mot gamla 1.21.x-speljar
hoppar över när de inte finns. Webbens DOM-prov visar guild-/stashsaldo och
osäkra fysiska källor, och behandlar itemnamn med HTML som vanlig text.
Det befintliga riktiga 26.3-provet för ikoner, webb/API och Stash Window
passerar också mot den nya Manager-imagen.
Inventory-regressionen jämför efter avbrott/omstart både variantmängder och
fulla fysiska innehållsfingerprints. Observationshashen kan ändras när en
källa blir stale och används därför inte som bevis på oförändrat innehåll.
Dockerproven verifierar non-root, noexec-/tmp, signerade anrop, Owner-bootstrap,
persistens, processlås och TLS; isoleringsprovet använder en egen ny datakatalog.

Det separata Minecraft-provet använder riktig vanilla 26.3, Fabric/Meteor/
WeSort, faktisk Baritone för scan-only-förflyttningar och Docker Manager.
Det verifierar ordnade fulla inventory-paket, tvetydiga dubbelöppningar,
vanlig/trapped/dubbel kista och barrel, faktisk QUICK_MOVE med retur, TTL,
förändrad servermängd, äldre signerad snapshot, blockerad/borttagen/återställd
källa samt Manager-omstart. Hela fixturens server-NBT är identisk före/efter.

Lokal spelrapport och skärmdump ligger i [verification](verification/).
De anger bascommit plus dirty-worktree eftersom provet kördes före denna
issue-commit. CI är separat: [physical stock](https://github.com/Crispy-Monke-eSports/WeSort/actions/workflows/mc26-stock.yml)
bygger commitens egen image och laddar upp commitbundna spelartefakter.
Befintliga baseline-/26.3-workflows körs också efter push; lokal spelkörning
är inte i sig bevis för en grön hosted CI-körning.

Manager-versionen höjs till `0.1.0-dev.2` för den nya image-publiceringen;
redan publicerade versioner ersätts inte. Releaseflödet ska även verifiera
SBOM och anonym pull/start av den publicerade digesten.

## Återstående i steget

W-019 avser sortering/signkategorier och skyddade varor; W-020 fysisk shulker-
hierarki; W-021 repack/bulk; W-022 exakta kits; W-023 hållbar fysisk journal;
W-024 global atomisk reservationshierarki. Dessa är inte färdiga genom W-018.
Orderhållningar är fortfarande aggregerade per stash (`reservationPrecision`
är `aggregate`); exakt fysisk reservation/allokering visas därför inte som
om den redan fanns. Delade fysiska källor räknas en gång men spärras för nya
order tills hierarkin finns. Full cross-stash-orderallokering hör till W-040.
Ingen Qwerte-data eller befintlig användarvärld läses/importeras.
