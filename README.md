<img src="src/main/resources/META-INF/pluginIcon.svg" width="72" alt="Ikona BetterJ">

# BetterJ

**Codzienne usprawnienia IntelliJ IDEA: review, commity, branche i pakiety, bez zgadywania.**

Aktualna wersja: **<!-- version -->0.20.8<!-- /version -->** · [⬇ Pobierz plugin](dist/BetterJ-0.20.8.zip) ·
[Instrukcja instalacji](INSTALL.md) · [Co nowego](CHANGELOG.md)

<!-- Screenshot: docs/screenshots/overview.png -->

## Co robi, w skrócie

- Pokazuje rozmiar zmian: `+858 −8` przy PR-ze, przy lokalnych zmianach i dla całego brancha (także w stosie PR-ów).
- Podświetla w drzewie PR-a i w oknie Commit plik otwarty w edytorze.
- Pilnuje zaznaczonych do commitu plików: zmieniony po zaznaczeniu plik dostaje kreskę `[-]`, a diff pokaże, co
  zmieniło się od zaznaczenia.
- Zapamiętuje zaznaczenia w oknie Commit, także po restarcie IDE i po `⌘K`.
- Oznacza w PR-ze pliki do ponownego przeczytania i pliki z niewysłanymi komentarzami.
- Wysyła review (Approve / Request Changes / Comment) prosto z IDE.
- Kopiuje komentarze, wstawia emoji `:smile:`, wkleja zrzuty ekranu jako załączniki GitHuba.
- Koloruje pakiety: drzewo projektu, drzewa zmian, zakładki edytora, linia `package` (też w diffie).
- Trzyma kod na środku monitora, a nie edytora, gdy otwierasz boczne panele.
- Każdą funkcję wyłączysz jednym kliknięciem: *Tools → BetterJ*.

## Lepsze pull requesty

- **Rozmiar PR-a od razu.** Górny wiersz drzewa PR-a pokazuje dodane i usunięte linie oraz nowe i usunięte pliki:
  `+760 −45 · 3 new · 1 deleted`.
- **Wiesz, gdzie jesteś.** Plik otwarty w edytorze delikatnie pulsuje na fioletowo w drzewie PR-a.
- **Wiesz, co zostało.** Plik oznaczony jako przeczytany, który zmienił się w nowym commicie, dostaje kreskę zamiast
  zwykłej kropki. Kliknięcie w zaznaczony plik też stawia kreskę („przeczytaj jeszcze raz”).
- **Niewysłane komentarze widać.** Pliki z komentarzami Twojego niewysłanego review mają bursztynowy pasek i licznik.
- **Podwójny klik otwiera plik**, a nie diff (na wybranym branchu PR-a). Alt+podwójny klik otwiera diff.
- **Review bez przeglądarki.** Przycisk *Review…* obok Merge: Approve, Request Changes, Comment, także gdy ktoś już
  zatwierdził.
- **Wygodniejsze komentarze.** Kopiowanie komentarza (`⌘C` albo przycisk), emoji przez `:smi` i okno wyboru,
  wklejanie zrzutów ekranu jako natywnych załączników GitHuba.

<!-- Screenshot: docs/screenshots/pull-request-tree.png -->
<!-- Screenshot: docs/screenshots/comments.png -->

## Lepsza praca z lokalnymi zmianami

- **Rozmiar zmian.** Okno Commit pokazuje przy liście zmian `+120 −34`, a obok rozmiar całego brancha:
  `vs develop +858 −8`. Branch w stosie PR-ów liczy się od brancha pod nim (`vs ZT-847 +859 −9`), więc zgadza się
  z tym, co pokazuje GitHub.
- **Zaznaczenie, któremu można ufać.** Plik zaznaczony do commitu dostaje kreskę `[-]`, gdy jego treść się zmieni,
  np. przez Ciebie albo agenta AI. Cofniesz zmianę, ptaszek wraca. Cykl kliknięć: `[ ] → [v] → [-] → [ ]`.
- **Zaznaczenia nie znikają.** `⌘K` nie zaznacza wszystkiego na nowo, a po restarcie IDE wracają Twoje ptaszki.
  Plik zmieniony przy zamkniętym IDE dostaje kreskę.
- **Te same checkboxy w drzewie projektu**, przed ikoną zmienionego pliku.
- **Otwarty plik widać** w oknie Commit, tak jak w drzewie PR-a.

<!-- Screenshot: docs/screenshots/line-counts.png -->
<!-- Screenshot: docs/screenshots/commit-checkboxes.png -->

## Szerokie monitory: kod na środku

IntelliJ potrafi wyśrodkować kod (*Toggle Center View*), ale liczy środek edytora. Otwierasz panel z lewej, edytor
się zwęża i kod ucieka w prawo. BetterJ liczy środek **całego okna**: kod zostaje tam, gdzie patrzysz, a boczne
panele otwierają się obok niego. Gdy miejsca robi się za mało, kod przesuwa się tylko tyle, ile musi.

Włączasz w *Tools → BetterJ → Center code on window* (domyślnie wyłączone).

## Kolory pakietów

Prawy klik na folderze w drzewie projektu (albo na zakładce edytora) → *Package Color*. Kolor widać w drzewie
projektu, w drzewach zmian (Commit, PR), na zakładkach edytora i za linią `package` w edytorze i w diffie. Paletę
zmienisz w *Settings → Tools → Package Colors*.

<!-- Screenshot: docs/screenshots/package-colours.png -->

## Włączanie i wyłączanie funkcji

*Tools → BetterJ* w pasku menu pokazuje wszystkie funkcje z ptaszkiem: kliknięcie włącza albo wyłącza. To samo, z
opisem każdej funkcji, jest w *Settings → Tools → BetterJ* (wyszukiwarka Settings je znajduje).

## Instalacja

Pobierz [BetterJ-0.20.8.zip](dist/BetterJ-0.20.8.zip) i zainstaluj z dysku: *Settings → Plugins → ⚙ → Install
Plugin from Disk…*. Krok po kroku w [instrukcji instalacji](INSTALL.md). Obsługiwane: IntelliJ IDEA 2026.1–2026.3.

## Wszystkie funkcje w szczegółach

1. **Kopiowanie komentarzy review** - przycisk kopiowania obok edycji / usuwania / reakcji po najechaniu na
   komentarz, `⌘C` / `Ctrl+C`, gdy komentarz ma fokus (zaznaczenie albo cały komentarz, gdy nic nie zaznaczono), i
   menu kontekstowe z *Copy Comment* i *Copy Selection*. Działa na osi czasu PR-a, w wątkach diffu i we wstawkach
   edytora.
2. **Emoji w komentarzach** - wpisz `:smi` w polu komentarza i wybierz z podpowiedzi (skróty GitHuba, wstawiane jako
   znaki Unicode). Przycisk 😀 pod polem, `Ctrl+Shift+;` (`⌘⇧;` na macOS) albo *Edit → Insert Emoji…* otwiera okno
   z ponad 1900 emoji GitHuba, wyszukiwarką i ostatnio używanymi na górze.
3. **Wklejanie zrzutów ekranu** - skrót wklejania, przycisk obrazka pod polem komentarza albo *Edit → Paste Image as
   Attachment* z obrazkiem w schowku (lub plikami obrazków skopiowanymi z Findera) wstawia `![Uploading …]()` i
   wysyła obrazek jako zwykły załącznik GitHuba (`github.com/user-attachments/assets/...`). Link działa na
   github.com, w mailach i w IDE, także w prywatnych repozytoriach. Tekst obok obrazka wygrywa, chyba że to tylko
   adres obrazka; skopiowane pliki zawsze liczą się jako pliki. *Paste as Plain Text* zawsze wkleja tekst.
4. **Wysyłanie review z IDE** - przycisk *Review…* w szczegółach PR-a obok Merge (i *Submit Review…* w menu
   kontekstowych szczegółów, zmian i osi czasu) otwiera okno GitHuba: Approve, Request Changes, Comment, z polem
   podsumowania i odrzuceniem oczekujących komentarzy. Jest dostępny zawsze, także gdy ktoś już zatwierdził (wtedy
   wbudowany plugin chowa swój przycisk). Autor PR-a może tylko komentować, jak na github.com.
5. **Gdzie jesteś w drzewie zmian** - plik otwarty w wybranej zakładce edytora dostaje w drzewie PR-a delikatnie
   pulsujące fioletowe tło i pasek przy lewej krawędzi, ponad zaznaczeniem i kolorami plików. Dopasowanie jest po
   ścieżce, więc działa, gdy otwarty jest prawdziwy plik (review na wybranym branchu); zakładka diffu się nie
   dopasowuje. Plik po zmianie nazwy dopasowuje się po nowej ścieżce, usunięty nigdy. Drzewo okna Commit (i Local
   Changes) oznacza otwarty plik tak samo, z checkboxami i bez.
6. **Gdzie są niewysłane komentarze** - pliki z komentarzami Twojego oczekującego review (nowe wątki i odpowiedzi)
   dostają bursztynowy pasek przy lewej krawędzi i okrągły licznik (gdy wcięcie zostawia na niego miejsce). Zwinięty
   folder dostaje jaśniejszy pasek; rozwinięte foldery nic, ich pliki tak. Znaczniki znikają po wysłaniu review.
   Klucze motywu: `BetterCodeReview.CurrentFile.background`, `BetterCodeReview.PendingComments.foreground`,
   `BetterCodeReview.PendingComments.text`.
7. **Które przeczytane pliki zmieniły się znowu** - GitHub zdejmuje ptaszek „viewed”, gdy późniejszy commit zmieni
   plik, a wbudowany plugin pokazuje wtedy tę samą kropkę co przy pliku nigdy nieotwartym. BetterJ rysuje w tym
   miejscu kreskę: kropka = nieprzeczytany, ptaszek = przeczytany, kreska = przeczytany, ale zmieniony. Kliknięcia
   przechodzą przez stany: zaznaczony plik po kliknięciu dostaje kreskę („przeczytaj jeszcze raz”), po kolejnym jest
   odznaczony, po kolejnym zaznaczony. GitHub zna tylko „zaznaczony albo nie”, więc kreska po kliknięciu to własny
   znacznik pluginu, trzymany per projekt i PR (500 ostatnich). Cykl działa na zwykłe kliknięcia lewym przyciskiem;
   klawiatura i menu kontekstowe idą prosto do GitHuba. Klucze motywu: `BetterCodeReview.ViewedDismissed.dash`,
   `.dashOnCurrent` (wiersz otwartego pliku) i `.dashInBox` (kreska w checkboxie).
8. **Podwójny klik otwiera plik** - podwójny klik albo Enter na pliku w drzewie PR-a otwiera plik w edytorze, czego
   potrzeba przy czytaniu na wybranym branchu; Alt+podwójny klik i *Show Diff* otwierają diff jak dotąd. Gdy branch
   PR-a nie jest wybrany, otwiera się diff, a dymek mówi dlaczego (raz na drzewo). „Wybrany” dotyczy brancha, nie
   tego, czy kopia robocza równa się głowie PR-a: niepobrane commity albo lokalne zmiany mogą przesunąć komentarze
   do linii. Dotyczy tylko drzewa całego PR-a z GitHuba: w drzewie pojedynczego commita i w merge requeście GitLaba
   otwiera się diff.
9. **Okno Commit: które zaznaczone pliki się zmieniły** - plik zaznaczony kliknięciem w checkbox dostaje kreskę,
   gdy jego treść różni się od tej z chwili zaznaczenia: zmieniony w edytorze, nadpisany na dysku przez narzędzie
   (np. agenta AI) albo usunięty. Przywrócony do tamtej treści (dopisane „a” i usunięte) znowu ma ptaszek. Pliki
   binarne dostają kreskę przy każdym zapisie. Zaznaczony plik kliknięty raz też dostaje kreskę, jako znak „do
   ponownego przejrzenia”; plik z kreską dalej wchodzi do commitu. Kliknięcie w plik z kreską odznacza go, a ponowne
   zaznaczenie bierze aktualną treść jako nową bazę. *Show Changes Since Tick* w menu kontekstowym okna Commit i
   drzewa projektu otwiera diff: treść z chwili zaznaczenia po lewej, obecna po prawej (dla zaznaczeń sprzed
   restartu IDE zostaje sama kreska). Pliki zaznaczone przez IDE albo klawiaturą nie mają bazy, więc
   same nie dostają kreski. Plik z zaznaczoną tylko częścią linii zostawia własny checkbox platformy.
   **Pamięć zaznaczeń:** ptaszki, kreski i treść z chwili zaznaczenia są zapisywane w pliku workspace projektu (nie
   trafia do repo). Po restarcie IDE przez pierwsze 30 sekund plugin cofa zaznaczanie wszystkiego przez IDE; Twoje
   pierwsze kliknięcie kończy ten czas. Plik zmieniony przy zamkniętym IDE dostaje kreskę. Commit (`⌘K`) zostawia
   checkboxy tak, jak były; bez pluginu zaznacza wszystkie zmiany aktywnej listy.
   **Liczniki linii:** obok liczby plików każda lista zmian pokazuje dodane i usunięte linie (`+120 −34`), liczone jak
   w podglądzie diffu (zmieniona linia to jedna dodana i jedna usunięta), z niezapisanymi zmianami. Pliki binarne i
   pliki ponad dwa miliony znaków liczą się jako zero. Drzewo PR-a pokazuje to samo w górnym wierszu, a po nim nowe
   i usunięte pliki (`· 3 new · 1 deleted`), czytane z gita dla porównywanych rewizji; otwarcie drzewa pobiera
   commity PR-a, tak jak otwarcie diffu. Klucze motywu: `BetterCodeReview.LineStats.added`, `.deleted`.
   **Cały branch:** górny wiersz okna Commit pokazuje też cały branch względem jego bazy (`vs develop +858 −8`): od
   miejsca, w którym branch od niej odszedł, do plików na dysku, czyli commity i to, czego jeszcze nie
   zacommitowano (`git diff --numstat`). Niezapisane zmiany i pliki niedodane do gita są pominięte. Widać to też bez
   lokalnych zmian, więc po przełączeniu na branch do review od razu znasz jego rozmiar; na samym develop jest
   ukryte. Baza to branch, z którego wyrósł bieżący, czyli ten, któremu brakuje najmniej jego commitów: develop dla
   zwykłego brancha, branch poniżej dla brancha w stosie (`vs ZT-847 +859 −9`). Przy remisie wygrywa develop, a kopia
   zdalna (`origin/…`) z lokalną; git starszy niż 2.41 zawsze używa develop.
   **Drzewo projektu** powtarza checkboxy okna Commit przed ikoną każdego zmienionego lub nowego pliku, z tym samym
   cyklem i tą samą kreską. Widać je tylko wtedy, gdy okno Commit pokazuje checkboxy.
10. **Kolory pakietów** - prawy klik na folderze albo pliku w drzewie projektu (lub na zakładce edytora) i kolor z
    *Package Color*. Kolor jest tłem w drzewie projektu, w drzewach zmian (Commit, PR) i na zakładkach edytora, dla
    folderu i wszystkiego w środku; zagnieżdżony folder może mieć własny kolor. Pliki w pokolorowanym pakiecie mają
    też kolor za linią `package` w edytorze i po obu stronach diffu (Java, Kotlin, Scala, Groovy, Go). Plik w folderze
    bez koloru (np. test obok pokolorowanego kodu w `src/main`) i starsza rewizja w diffie biorą kolor folderu, który
    wskazuje ich pakiet. Paletę zmienisz w *Settings → Tools → Package
    Colors* (nazwa i kolor z przezroczystością, wyłączenie linii `package`). Ustawienia leżą w
    `.idea/betterCodeReviewColors.xml`. Przy pierwszym użyciu plugin importuje kolory i paletę pluginu *ProjectTree
    Color Highlighter* z `.idea/highlightedFiles.xml`; tamten plugin potem odinstaluj, bo przy obu jego kolory
    wygrywają.
11. **Kod na środku okna** - *Center code on window* trzyma kolumnę kodu (do prawego marginesu) na środku okna IDE,
    a nie edytora, więc otwarcie albo zmiana szerokości panelu z boku nie przesuwa kodu. Edytor dostaje pusty pas po
    lewej stronie numerów linii, zwężany, gdy panel zostawia za mało miejsca; zmiana miejsca kodu jest płynna. Pas
    liczy się w trakcie układania edytora, w tym samym kroku co przesunięcie, dlatego funkcja włącza tryb *Center
    View* IntelliJ z jego własnym marginesem ustawionym na 0 (*Advanced Settings*, `editor.distraction.free.margin`).
    Wyłączenie funkcji przywraca oba ustawienia. Domyślnie wyłączone. Duże zmiany pasa trafiają do `idea.log` jako
    `Center on window:`.

## Wymagania

- IntelliJ IDEA 2026.1–2026.3 (buildy 261–263) z włączonymi wbudowanymi pluginami *GitHub* i *Git*.
- Konto GitHub dodane w *Settings → Version Control → GitHub* (wysyłanie załączników używa jego tokenu).
- Załączniki: tylko github.com. GitHub Enterprise nie jest obsługiwany, ma inny adres wysyłania.

## Wersje i wydania

- `main` trzyma zatwierdzone zmiany pluginu. Nie każdy push jest wydaniem.
- Wydanie to push, który podnosi też `version` w `build.gradle.kts`, dodaje wpis do [CHANGELOG.md](CHANGELOG.md) i
  uruchamia `scripts/release.sh`: skrypt buduje `dist/BetterJ-<wersja>.zip`, usuwa poprzedni zip i przestawia linki
  do pobrania oraz wersję na górze tego pliku.
- Każdy build dany do testów ma własny numer: poprawka podnosi ostatnią część (`0.20.0` → `0.20.1`), nowe funkcje
  środkową. Dwa różne zipy nigdy nie mają tej samej wersji.

## Budowanie ze źródeł

```bash
JAVA_HOME=<JDK 21 lub nowszy> ./gradlew buildPlugin
```

Zip ląduje w `build/distributions/` (`BetterJ-<wersja>.zip`). `scripts/install-local.sh` buduje plugin i od razu
instaluje go w lokalnym IntelliJ IDEA: zamyka IDE jak ⌘Q, podmienia folder `BetterJ` w katalogu pluginów i otwiera IDE
z tymi samymi projektami (`--no-build` instaluje ostatnio zbudowany zip, `IDEA_APP` / `IDEA_CONFIG` wskazują inne IDE). `gradle.properties` wskazuje w `platformLocalPath`
lokalne IDE, więc build nie pobiera 1 GB; usuń tę właściwość, żeby budować na pobranym IntelliJ IDEA Community 2026.1.

Testy: `./gradlew test` (testy jednostkowe i testy platformy w IDE bez okna). `./gradlew verifyPlugin` sprawdza
zgodność z 2026.1, 2026.2 i 2026.3.

## Zastrzeżenia

- Wysyłanie załączników używa `uploads.github.com/user-attachments/assets`, którego GitHub nie dokumentuje. To ten
  sam adres, którego używa strona github.com, i dziś przyjmuje token OAuth z IDE; jeśli GitHub go zmieni, wklejanie
  pokaże dymek z błędem i nic nie przepadnie (zastępczy tekst zostanie usunięty).
- Plugin korzysta z wewnętrznych szczegółów interfejsu pluginu GitHub (nazwa panelu HTML komentarza, plik
  `Dummy.md` pod polem komentarza, układ przycisków pod polem). Duża aktualizacja IDE może wymagać drobnej poprawki.
- Znacznik otwartego pliku to przezroczysta nakładka na drzewo zmian, rozpoznawana po nazwie klasy drzewa;
  aktualizacja IDE może wymagać poprawki.
- Znaczniki niewysłanych komentarzy i kreska „viewed” czytają dane review przez prywatne pola modelu listy zmian
  (same dane pochodzą z publicznych, buforowanych strumieni, więc nie ma dodatkowych zapytań). Gdy pól nie ma tam,
  gdzie plugin ich szuka, znaczniki się wyłączają, a log IDE mówi o tym na poziomie INFO („Tree marks ...”). Oba
  działają tylko w drzewie całego PR-a, nie pojedynczego commita.
- Cykle kliknięć przechwytują kliknięcia w checkboxy na poziomie toolkitu, przed własnymi obsługami drzew. Wszystko
  jest cofane, gdy plugin się wyłącza.
- Przycisk *Review…* jest rozpoznawany po kształcie wiersza akcji w szczegółach, który mają też merge requesty
  GitLaba; jest dodawany tylko tam, gdzie w kontekście jest PR z GitHuba. Wysyłanie review korzysta z modelu review
  pluginu GitHub przez jego wymienne okno, czyli wewnętrzne API.
- Reakcje na komentarze są ograniczone do ośmiu, na które pozwala GitHub; to limit API GitHuba, nie IDE.
