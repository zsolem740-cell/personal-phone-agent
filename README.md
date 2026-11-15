# personal-phone-agent

Helyi-first személyes telefonasszisztens Androidra.

## Parancsok

- `szia` — üdvözlet
- `idő` — pontos idő
- `dátum` — mai dátum
- `akku` — akkumulátor töltöttsége
- `számológép` / `nyisd meg a számológépet` — jóváhagyás után megnyitja
- `ébresztők` — jóváhagyás után megnyitja az ébresztőket
- `keresés <szöveg>` — jóváhagyás után webes keresés
- `segítség` — elérhető parancsok listája

## Technikai jellemzők

- Kotlin, Android 8.0 (API 26) vagy újabb
- Nincs hálózati engedély, nincs adatküldés
- A telefonos műveletekhez minden esetben megerősítő ablak jelenik meg
- Stabil aláírás (GitHub Actions titokban tárolt kulccsal), így a frissítések a telepített verzió fölé telepíthetők
- CI: fordítás, lint, unit teszt és APK-diagnosztika GitHub Actions-szel
