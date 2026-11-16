# personal-phone-agent

Telefonasszisztens: személyes asszisztens Androidra (8.0 / API 26 vagy újabb), magyar nyelvű
parancsokkal, opcionális AI-csevegéssel és a Solaris háttérfigyelő adatainak értelmezésével.

## Parancsok

- `szia` – üdvözlet
- `idő`, `hány óra van` – pontos idő
- `dátum`, `milyen nap van` – mai dátum
- `akku` – akkumulátor töltöttsége
- `számológép`, `ébresztők`, `böngésző`, `beállítások`, `kamera`, `wi-fi`, `telefon`, `üzenetek`,
  `névjegyek`, `térkép` – jóváhagyás után megnyitja a megfelelő alkalmazást
- `jegyzetelj <szöveg>` / `jegyzet: <szöveg>` – jegyzet mentése; `jegyzetek`, `jegyzetek törlése`
- `emlékeztess 18:30 <szöveg>`, `emlékeztess 20 perc múlva <szöveg>`, `emlékeztess 2 óra múlva <szöveg>`;
  `emlékeztetők`, `emlékeztetők törlése`
- `hívd fel <név>` – névjegy keresése, majd jóváhagyás után tárcsázás
- `keresés <szöveg>` – jóváhagyás után webes keresés
- `felolvasás be`, `felolvasás ki` – a válaszok felolvasása
- `segítség` – az elérhető parancsok listája

A parancsok kis- és nagybetűtől függetlenek; a jegyzet, az emlékeztető, a keresés és a név
az eredeti írásmódjával kerül mentésre.

## AI-csevegés

Ha nincs ismert parancs, a kérdés a beállított AI-szolgáltatóhoz megy (Google Gemini, OpenAI
vagy Anthropic). A modell szolgáltatónként listából választható (az ajánlott elöl), más
név az „Egyéni modellnév” ponttal adható meg. Az API-kulcsot a felhasználó adja meg, és csak a telefonon tárolódik: a
Google-mentésből és az új telefonra átvitelből ki van zárva, ezért telefoncsere után újra
meg kell adni. A Gemini napi ingyenes keretének kimerülésekor az app kiírja, mikor nullázódik
a keret (csendes-óceáni éjfél, a telefon időzónájában megadva).

## Solaris-integráció

- Óránkénti háttérletöltés a `https://zsolem740-cell.github.io/solaris/data/status.json` címről
  (JobScheduler, hálózati feltétellel). Itt AI-hívás nem történik.
- Értesítés, ha változnak az aktív figyelmeztetések, vagy aznap még nem volt értesítés;
  22 és 7 óra között csendes. Letiltott értesítésnél nem jegyzi fel, hogy szólt.
- Az értesítésre koppintás, a menü Solaris-pontja vagy a Solaris oldalról megosztott szöveg
  indítja az AI-értelmezést. Az adat életkorát az értelmezés pillanatában számolja:
  3 óra felett figyelmeztet, 24 óra felett nem tekinti aktuálisnak.
- Ugyanarra az adatra (és ugyanazzal a modellel) nem kér új AI-választ, hanem a mentett
  értelmezést mutatja. Mivel a Solaris óránként frissül, óránként legfeljebb egy AI-kérés megy el.
- A helyi integrációs napló a telefonon marad. A Diagnosztika menüből fájlba exportálható,
  vagy jóváhagyás után fájlként megosztható (csak időpontok, események, hibakódok,
  karakterszámok és a modell neve; kulcs és üzenetszöveg nincs benne).
- Ha az értesítések le vannak tiltva, az app naponta legfeljebb egyszer szól, és a
  beállításokba visz; az állapot a Diagnosztika menüben is látszik.

## Engedélyek

- `INTERNET`, `ACCESS_NETWORK_STATE` – AI-hívások és a Solaris-letöltés
- `POST_NOTIFICATIONS` – Solaris-értesítések és emlékeztetők
- `RECORD_AUDIO` – hangbevitel
- `READ_CONTACTS` – `hívd fel` parancs
- `SET_ALARM` – ébresztők megnyitása

A telefonos műveletekhez minden esetben megerősítő ablak jelenik meg.

## Fejlesztés

- Kotlin, androidx nélkül; OkHttp 4.12
- Stabil aláírás (GitHub Actions titokban tárolt kulccsal), így a frissítések a telepített
  verzió fölé telepíthetők
- CI („Android checks”): debug- és release-fordítás, lint, unit tesztek
- Kiadás („Publish test APK”): a `main` ágra feltöltéskor fut, ha a
  `.github/workflows/apk-release.yml` változik, vagy kézzel indítva. Verzióemeléskor az
  `app/build.gradle.kts` mellett az ottani `APK_NAME` sort is át kell írni.
