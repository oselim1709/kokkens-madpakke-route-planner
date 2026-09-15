# Kokkens Madpakke Route Planner

Ruteplanlægger til madpakke-levering. Tilføj stops, generér balancerede
ruter til dine chauffører, og kopiér en pæn tekstversion af hver rute til
at sende i en chat.

## Kør appen

Kræver kun Java 21+ installeret (Maven følger med som wrapper).

```bash
./mvnw spring-boot:run
```

Åbn derefter `http://localhost:8080` — virker fint fra en telefonbrowser,
hvis telefonen er på samme netværk som computeren (brug computerens
lokale IP i stedet for `localhost`, fx `http://192.168.1.x:8080`).

Data gemmes i en lokal SQLite-database i `data/madpakke.db` og forsvinder
ikke, selvom du genstarter appen.

## Opsætning

1. Gå til fanen **Indstillinger** og indtast firmaets startadresse. Alle
   ruter starter her — chaufføren skal ikke tilbage til adressen bagefter.
2. Tilføj chauffører under **Chauffører**.
3. Tilføj stops under **Stops**, og tryk **Generér ruter** under **Ruter**
   for at beregne dagens ruter. Kør det igen, hvis du ændrer i stops —
   ruterne opdateres ikke automatisk.

## Geokodning (adresse → kort-koordinater)

Som standard bruges den gratis OpenStreetMap/Nominatim-tjeneste. Har du en
Google Maps API-nøgle, kan du sætte miljøvariablen `GOOGLE_MAPS_API_KEY`
før du starter appen, så bruges Google i stedet (med Nominatim som
fallback, hvis en opslag skulle fejle):

```bash
GOOGLE_MAPS_API_KEY=din-nøgle ./mvnw spring-boot:run
```

## Hvordan ruterne beregnes

Der er ikke tilsluttet en rutevejledningstjeneste (ingen live trafik) —
køretid estimeres ud fra lige-linje-afstand og en antaget gennemsnitsfart
(default 30 km/t, kan ændres i `application.properties`). Stops fordeles
geografisk mellem chaufførerne og forsøges balanceret efter estimeret
tid pr. rute; stops med en deadline besøges først, i deadline-rækkefølge.
