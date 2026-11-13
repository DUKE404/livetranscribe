# LiveTranscribe – instrukcja (wystarczy sam telefon)

## Najprościej: wersja z przeglądarki (bez pliku APK)

1. Otwórz w Chrome: **https://livetranscribe-1chw.onrender.com**
   (pierwsze otwarcie po dłuższej przerwie może trwać do minuty – darmowy serwer się wybudza).
2. Dotknij **Zainstaluj** na ekranie startowym (albo menu **⋮** → **Zainstaluj aplikację**). Ikona pojawi się na ekranie telefonu.
3. W aplikacji: **Ustawienia** → **Hasło dostępu do serwera** → wpisz hasło od właściciela → **Zapisz hasło**.
4. **ROZPOCZNIJ TRANSKRYPCJĘ** → przy pytaniu o mikrofon wybierz **Zezwól**.
5. Po zakończeniu: **GENERUJ PDF** → **POBIERZ PDF** (folder Pobrane) lub **UDOSTĘPNIJ PDF** (WhatsApp, Gmail, Messenger…).

Masz już gotowe nagranie (np. z dyktafonu)? Dotknij **WCZYTAJ NAGRANIE Z PLIKU** i wybierz plik (MP3, M4A, WAV, OGG, FLAC). Nie zamykaj strony, dopóki pasek wczytywania nie dojdzie do końca. W przeglądarce można wczytać plik do 400 MB; dłuższe nagrania wczytaj w aplikacji APK.

Wersja z przeglądarki nie ma trybu lokalnego (offline). Przy kilkugodzinnych spotkaniach włącz **Nie wygaszaj ekranu** na ekranie transkrypcji i podłącz ładowarkę – najpewniejsza jest wtedy aplikacja APK opisana niżej.

---

## iPhone i iPad

Na iPhonie działa wersja z przeglądarki (plik APK jest tylko dla Androida).

1. Otwórz w **Safari**: **https://livetranscribe-1chw.onrender.com**
2. Dotknij **Udostępnij** (kwadrat ze strzałką; w nowszym iOS najpierw **⋯** obok adresu) → **Do ekranu początkowego** → **Dodaj**.
3. Uruchamiaj LiveTranscribe **z ikony na ekranie początkowym** – wtedy iPhone nie usuwa zapisanych transkrypcji.
4. **Ustawienia** → **Hasło dostępu do serwera** → wpisz hasło od właściciela → **Zapisz hasło**.

**Nagrywanie na żywo:** **ROZPOCZNIJ TRANSKRYPCJĘ** → przy pytaniu o mikrofon **Zezwól** (iPhone może pytać przy każdym uruchomieniu). Ważne: iOS wyłącza mikrofon stronom internetowym, gdy ekran zgaśnie albo przełączysz się do innej aplikacji. Dlatego:

- zostaw włączone **Nie wygaszaj ekranu** (na iPhonie jest domyślnie włączone),
- nie blokuj telefonu i nie przełączaj aplikacji w trakcie nagrywania,
- jeśli aplikacja pokaże komunikat o wygaszaniu ekranu: Ustawienia iPhone'a → **Ekran i jasność** → **Automatyczna blokada** → **Nigdy** (na czas nagrania).

Jeśli mikrofon jednak się wyłączy, aplikacja powie, ile sekund nagrania przepadło, i nagrywa dalej.

**Długie spotkanie – najpewniejszy sposób na iPhonie:** nagraj je aplikacją **Dyktafon** (nagrywa też przy zablokowanym ekranie), a potem:

1. W Dyktafonie dotknij nagrania → **⋯** → **Zachowaj w Plikach** (np. w folderze „Na moim iPhonie”).
2. W LiveTranscribe: **WCZYTAJ NAGRANIE Z PLIKU** → **Przeglądaj** → wybierz zapisane nagranie.
3. Nie zamykaj aplikacji, dopóki pasek wczytywania nie dojdzie do końca (zwykle kilka–kilkanaście sekund). Tekst pojawia się na bieżąco.

Na iPhonie można wczytać naraz nagranie do 2 godzin (dłuższe podziel na części). Działają pliki M4A (Dyktafon), MP3, WAV, AAC, FLAC i filmy.

**PDF na iPhonie:** **POBIERZ PDF** otwiera menu, w którym wybierasz **Zachowaj w Plikach**. **UDOSTĘPNIJ PDF** wyśle dokument przez AirDrop, Mail, WhatsApp, Messengera itd.

---

## Aplikacja APK na Androida

Wszystko poniżej robisz na telefonie z Androidem 10 lub nowszym. Nie potrzebujesz komputera.

## 1. Pobierz aplikację

1. Otwórz na telefonie przeglądarkę (np. Chrome).
2. Wejdź na: **https://github.com/DUKE404/livetranscribe/releases/latest/download/LiveTranscribe.apk**
3. Jeśli przeglądarka zapyta, czy pobrać plik – potwierdź („Pobierz mimo to”). Plik ma ok. 40 MB.

## 2. Znajdź pobrany plik

- W Chrome: dotknij **⋮** (trzy kropki) → **Pobrane pliki** → `LiveTranscribe.apk`.
- Albo otwórz aplikację **Pliki** (Moje pliki) → **Pobrane** (Download) → `LiveTranscribe.apk`.

## 3. Zezwól na instalację

Po dotknięciu pliku Android zwykle pokazuje komunikat, że instalowanie z tego źródła jest zablokowane.

1. Dotknij **Ustawienia** w tym komunikacie.
2. Włącz przełącznik **Zezwalaj z tego źródła**.
3. Wróć strzałką wstecz.

Robisz to tylko raz. Po instalacji możesz ten przełącznik z powrotem wyłączyć.

## 4. Zainstaluj

1. Dotknij **Zainstaluj**.
2. Jeśli pojawi się okno Google Play Protect o nieznanej aplikacji, wybierz **Zainstaluj mimo to** (ewentualnie najpierw „Więcej szczegółów”). Aplikacja nie pochodzi ze sklepu Play, więc telefon jej nie zna.
3. Dotknij **Otwórz**.

## 5. Skonfiguruj transkrypcję (klucz API)

Najlepszą jakość daje tryb chmurowy. Wymaga on własnego klucza API – to rodzaj hasła do usługi rozpoznawania mowy. Domyślnie aplikacja korzysta z usługi **Groq**, która ma darmowy plan (z dziennymi limitami).

1. W przeglądarce wejdź na **https://console.groq.com** i załóż konto (można przez konto Google).
2. Otwórz menu → **API Keys** → **Create API Key**. Nadaj dowolną nazwę.
3. Skopiuj klucz (zaczyna się od `gsk_`). Zobaczysz go tylko raz.
4. W LiveTranscribe: **Ustawienia** (dolny pasek) → sekcja **Usługa w chmurze** → wklej klucz w polu **Klucz API**.
5. Dotknij **Zapisz i sprawdź klucz**. Powinien pojawić się napis „Klucz zapisany i działa”.

Inne możliwości w Ustawieniach:

- **Dostawca: OpenAI** – usługa płatna (klucz z platform.openai.com). Umożliwia rozpoznawanie rozmówców.
- **Silnik: Lokalnie na telefonie** – działa bez internetu i bez klucza, dźwięk nie opuszcza telefonu. Wymaga jednorazowego pobrania modelu (ok. 50 MB, przycisk **Pobierz model**). Jakość jest wyraźnie niższa i tekst nie ma przecinków.

Aplikacja zawsze pokazuje, czy dźwięk jest przetwarzany lokalnie, czy wysyłany do chmury.

## 6. Nadaj uprawnienie do mikrofonu

Przy pierwszym nagrywaniu telefon zapyta o dostęp:

- **Mikrofon** → wybierz **Podczas używania aplikacji**.
- **Powiadomienia** → **Zezwól** (dzięki temu widzisz, że nagrywanie trwa, i możesz je zatrzymać z paska powiadomień).

Jeśli przez pomyłkę odmówisz: Ustawienia telefonu → Aplikacje → LiveTranscribe → Uprawnienia → Mikrofon.

## 7. Rozpocznij nagrywanie spotkania

1. Na ekranie startowym dotknij **ROZPOCZNIJ TRANSKRYPCJĘ**.
2. Połóż telefon możliwie blisko mówiących, mikrofonem (dolna krawędź) w ich stronę.
3. Tekst pojawia się fragmentami, zwykle kilka–kilkanaście sekund po wypowiedzi.
4. Możesz zablokować telefon albo przejść do innej aplikacji – nagrywanie trwa dalej. Świadczy o tym stałe powiadomienie „Trwa transkrypcja”.
5. **PAUZA** wstrzymuje nagrywanie, **WZNÓW** je kontynuuje.

Brak internetu w trakcie spotkania niczego nie kasuje: nagranie jest zapisywane w telefonie, a tekst dokończy się sam, gdy połączenie wróci.

**Długie spotkania:** niektóre telefony (Xiaomi, Samsung, Huawei, Oppo) usypiają aplikacje w tle. Przed kilkugodzinnym nagraniem wejdź w Ustawienia telefonu → Aplikacje → LiveTranscribe → Bateria i wybierz **Bez ograniczeń**. Podłącz też ładowarkę.

## 8. Zakończ transkrypcję

1. Dotknij **ZAKOŃCZ** (albo czerwonego przycisku **STOP**) i potwierdź.
2. Aplikacja dokończy ostatnie fragmenty i otworzy dokument.
3. Ikona ołówka u góry pozwala poprawić tekst. Po zmianach dotknij **ZAPISZ**.
4. Nazwę dokumentu zmienisz w menu **⋮** → **Zmień nazwę**.

Wszystkie dokumenty znajdziesz w zakładce **Historia**.

## 8a. Transkrypcja gotowego nagrania (np. z dyktafonu)

Nie musisz nagrywać w aplikacji – możesz zamienić na tekst plik, który już masz.

1. Na ekranie startowym dotknij **WCZYTAJ NAGRANIE Z PLIKU**.
2. Wybierz plik w oknie systemowym (Pobrane, Dyktafon, Dysk Google…). Obsługiwane są m.in. **MP3, M4A, WAV, OGG, OPUS, FLAC, AMR, 3GP**, a także **filmy MP4** (liczy się ścieżka dźwiękowa).
3. Aplikacja skopiuje plik, sprawdzi go i otworzy nowy dokument. Pasek pokazuje postęp wczytywania, a tekst pojawia się na bieżąco.

Albo prościej: w aplikacji dyktafonu (lub w menedżerze plików) wybierz nagranie → **Udostępnij** → **LiveTranscribe**.

Dobrze wiedzieć:

- Wczytywanie trwa dalej po zablokowaniu telefonu – widać je w powiadomieniu „Wczytywanie nagrania”. Jeśli system zamknie aplikację, wczytywanie wznowi się samo od miejsca, w którym przerwało.
- Nagranie jest dzielone na fragmenty po 1–2 minuty, zawsze w pauzie między słowami. Nic nie jest pomijane poza całkowitą ciszą.
- Bez internetu fragmenty czekają w telefonie i dokończą się, gdy połączenie wróci – tak samo jak przy nagrywaniu.
- Oryginalny plik zostaje nietknięty w swoim folderze. Kopia w aplikacji jest usuwana zaraz po wczytaniu.

## 9. Wygeneruj PDF

1. W dokumencie dotknij **GENERUJ PDF**.
2. Zdecyduj, czy dodać znaczniki czasu, i dotknij **Generuj**.
3. Pojawią się trzy przyciski: **POBIERZ PDF**, **UDOSTĘPNIJ PDF**, **OTWÓRZ PDF**.

## 10. Zapisz PDF w pamięci telefonu

1. Dotknij **POBIERZ PDF**.
2. W razie potrzeby zmień nazwę pliku i dotknij **Pobierz**.
3. Plik trafia do folderu **Download/LiveTranscribe**. Znajdziesz go w aplikacji Pliki → Pobrane → LiveTranscribe.

## 11. Wyślij PDF przez WhatsApp lub Gmail

1. Dotknij **UDOSTĘPNIJ PDF**.
2. Otworzy się standardowe menu Androida. Wybierz **WhatsApp**, **Gmail**, **Messenger**, **Bluetooth** lub inną aplikację.
3. Wskaż odbiorcę i wyślij. PDF jest już dołączony jako załącznik.

---

## Dobrze wiedzieć

- **Prywatność.** Nagrania, teksty i klucz API są w prywatnej pamięci aplikacji. Dźwięk jest domyślnie usuwany zaraz po zamianie na tekst. W trybie chmurowym fragmenty nagrania są wysyłane szyfrowanym połączeniem do wybranej usługi (Groq lub OpenAI) i podlegają jej regulaminowi. Uprzedź uczestników spotkania, że je nagrywasz.
- **Usuwanie danych.** Ustawienia → **Usuń historię transkrypcji** albo **Usuń wszystkie dane aplikacji**.
- **Rozmówcy.** Rozpoznawanie rozmówców działa tylko u dostawcy OpenAI. Etykiety (Rozmówca A, B…) są nadawane osobno w każdym fragmencie nagrania, więc ta sama osoba może w różnych miejscach dostać inną literę.
- **Aktualizacje.** Nową wersję instalujesz tak samo jak pierwszą. Jeśli telefon zgłosi konflikt z zainstalowaną wersją, najpierw zapisz potrzebne dokumenty jako PDF, odinstaluj starą wersję i zainstaluj nową.
- **Gdy tekst się nie pojawia.** Sprawdź czerwony komunikat na ekranie transkrypcji. Najczęstsze przyczyny: brak internetu, brak lub błędny klucz API, wyczerpany dzienny limit darmowego planu. Nagranie czeka w kolejce – po usunięciu przyczyny dotknij **Ponów**.
