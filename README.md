# LiveTranscribe

Aplikacja na Androida (10+), która zamienia mowę na tekst na żywo – także przy wygaszonym ekranie – i tworzy z transkrypcji dokument PDF do pobrania lub wysłania.

**Pobieranie:** gotowy plik `LiveTranscribe.apk` jest w zakładce [Releases](../../releases/latest).
**Instrukcja dla użytkownika:** [INSTRUKCJA.md](INSTRUKCJA.md).
**iPhone / iPad:** wersja webowa (`web/`, PWA instalowana z Safari) – z poprawkami pod iOS: mikrofon przez natywną
częstotliwość i własne przepróbkowanie, domyślnie niewygaszany ekran, zapis plików przez arkusz udostępniania,
ostrzeżenie o przerwach w nagraniu, wczytywanie nagrań z Dyktafonu (do 2 h).

## Jak to działa

```
mikrofon → RecordingService (usługa pierwszoplanowa)
         → Chunker: cięcie dźwięku na fragmenty w pauzach mowy
         → zapis fragmentu na dysk + wpis w bazie Room (status PENDING)
         → TranscriptionQueue: silnik chmurowy (API zgodne z OpenAI) albo lokalny (Vosk)
         → tekst w bazie → ekran na żywo → edycja → PDF → pobranie / udostępnienie

plik nagrania (wybór pliku albo „Udostępnij” z dyktafonu)
         → AudioImport: kopia w aplikacji + sprawdzenie formatu → dokument IMPORTING
         → ImportWorker (WorkManager, powiadomienie): AudioDecoder (MediaExtractor + MediaCodec)
           → PcmConverter (16 kHz mono, filtr antyaliasingowy) → FileSplitter (fragmenty 1–2 min, cięcie w pauzach)
         → ta sama kolejka TranscriptionQueue co nagrania na żywo
```

- **Wczytywanie plików:** MP3, M4A/AAC, WAV, OGG/Opus, FLAC, AMR, 3GP i ścieżka dźwiękowa z MP4. Po przerwaniu
  wczytywanie wznawia się od końca ostatniego zapisanego fragmentu. W wersji webowej dekoduje przeglądarka
  (`decodeAudioData`), a podział robi ten sam algorytm (`FileSplitter` w `web/public/core.js`).

- **Architektura:** MVVM, Kotlin, Jetpack Compose, Room, Foreground Service.
- **Odporność:** każdy fragment jest najpierw zapisany, potem przetwarzany. Brak internetu, błąd serwera
  lub zamknięcie aplikacji nie kasują nagrania – fragmenty czekają w kolejce i kończą się później.
- **Silniki:** chmura (Groq / OpenAI / dowolna usługa zgodna z `/v1/audio/transcriptions`) oraz
  lokalny Vosk z polskim modelem. Oba implementują `TranscriptionEngine`.
- **Klucz API** nie jest w kodzie – użytkownik wpisuje go w Ustawieniach, a aplikacja trzyma go
  w `EncryptedSharedPreferences`.
- **PDF:** `android.graphics.pdf.PdfDocument`, A4, paginacja, nagłówki, numery stron.
- **Pliki:** MediaStore (`Download/LiveTranscribe`), FileProvider + systemowe menu udostępniania.

## Budowanie

Każdy push do gałęzi `main` uruchamia GitHub Actions (`.github/workflows/build.yml`): testy jednostkowe,
`assembleRelease` i `bundleRelease`, a wynik (APK + AAB) trafia do Releases.

Lokalnie: `./gradlew testDebugUnitTest assembleRelease` (JDK 17, Android SDK 35).

### Podpisywanie

Wydanie APK wymaga jednego sekretu repozytorium: `KEYSTORE_PASSWORD` (Settings → Secrets and variables → Actions),
z dowolnym hasłem (co najmniej 6 znaków). Przy pierwszej budowie GitHub sam tworzy stały klucz podpisu
`signing/release.jks`, zaszyfrowany tym hasłem, i zapisuje go w repozytorium. Dzięki temu każda nowa wersja APK
instaluje się na poprzedniej bez utraty danych. Bez sekretu budowa i testy działają, ale wydanie jest pomijane.
