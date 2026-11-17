# Fysiska orderreservationer i Minecraft 26.3

Manager lagrar varje order och dess allokering i samma orderpost. En
allokering anger kanoniskt container-ID, slotväg, variantens fingerprint,
observationsrevision och reserverat antal. Serverns stashnamn är
medlemskap i lagret. Två stashnamn kan därför se samma fysiska källa.

En allokering håller hela yttersta sloten. En hel shulker och en
innehållsrad i samma shulker konkurrerar om samma lås. Flera valda
innehållsrader från samma order kan dela låset för sin shulker. Ett
gammalt index utan en verifierad slothierarki kan fortfarande visa
aggregerat lager. Helboxorder kräver den verifierade hierarkin.

Orderbok, senaste stockobservation och sorteringslease kontrolleras
under samma lås i Manager. Den tilldelade agenten får påbörja en fysisk
order med exakt verifierade slotar. Agentens plockplan och native
klickspärr respekterar både egna allokerade rötter och andra orders
reserverade rötter. Sortering, repack och kitpackning använder den
serverexklusiva leasen. Under en aktiv fysisk order väntar nya
reservationer tills agenten har rapporterat ett utfall. Det håller
agentens lista över låsta slots giltig fram till nästa tilldelning.

Avbokning före tilldelning frigör sloten direkt. Efter tilldelning
behålls reservationen vid avbrott eller fel tills efterföljande färska
observationer verifierar de allokerade källorna och den fysiska journalen
är avstämd. En ändrad slothierarki gör en gammal allokering otillgänglig
för en ny claim. Återplanering över flera stashes hör till W-040.

Manager visar reserverat och tillgängligt lager i `/api/v1/stock`. Den
enskilda fysiska källan visar separat observerat, reserverat och
tillgängligt antal per orderreferens. Owner kan se order-ID,
slotväg, revision och fingerprint för allokeringarna. En slot kan
innehålla fler enheter än det reserverade antalet eftersom hela sloten
hålls för ordern.

## Prov

Java-tester kör verklig SQLite och parallella orderanrop för en hel
shulker och dess innehåll från två stashalias. De täcker också
serverns sorteringslease, omstart, avbokning, ändrad snapshot
och frigöring efter verifierad felhantering.

Det riktiga spelprovet läser tre shulkers med flera länkade
innehållsvarianter och skickar den signerade slothierarkin till Docker
Manager. Två samtidiga order konkurrerar om samma boxar. Därefter
provas den signerade sorteringsleasen och avbokning. RCON bekräftar
att serverns blockinventarier behåller exakta antal och metadata.

```sh
docker build -t wesort-manager:reservation-tests .
xvfb-run -a python3 scripts/mc26/hierarchy-harness.py --manager-image wesort-manager:reservation-tests --reservations
```

[GitHub Actions för slothierarki och reservationer](https://github.com/Crispy-Monke-eSports/WeSort/actions/workflows/mc26-hierarchy.yml)
