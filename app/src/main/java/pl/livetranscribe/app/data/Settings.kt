package pl.livetranscribe.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AppSettings(
    /** "pl", "auto", "en", "de", "uk" … */
    val language: String = "pl",
    /** "cloud" albo "local" */
    val engine: String = "cloud",
    /** "groq", "openai" albo "custom" */
    val provider: String = "groq",
    val customBaseUrl: String = "",
    /** Pusty = model dobierany automatycznie do jakości. */
    val customModel: String = "",
    /** "fast", "balanced", "accurate" */
    val quality: String = "balanced",
    val autosave: Boolean = true,
    val timestamps: Boolean = false,
    val speakers: Boolean = false,
    /** "small", "medium", "large" */
    val pdfFontSize: String = "medium",
    val pdfHeader: Boolean = true,
    /** "system", "light", "dark" */
    val theme: String = "system",
    val keepAudio: Boolean = false,
    /** Nazwiska, nazwy firm i żargon – podpowiedź dla modelu przy każdym fragmencie. */
    val glossary: String = "",
    /** Czy pokazano już przypomnienie o uprzedzeniu uczestników nagrania. */
    val consentShown: Boolean = false,
) {
    val baseUrl: String
        get() = when (provider) {
            "openai" -> "https://api.openai.com/v1"
            "groq" -> "https://api.groq.com/openai/v1"
            else -> customBaseUrl.trim().trimEnd('/')
        }

    /** Rozpoznawanie rozmówców obsługuje tylko model diaryzujący OpenAI. */
    val diarizationAvailable: Boolean get() = engine == "cloud" && provider == "openai"

    val model: String
        get() {
            if (customModel.isNotBlank()) return customModel.trim()
            return when (provider) {
                "openai" -> when {
                    speakers -> "gpt-4o-transcribe-diarize"
                    quality == "fast" -> "gpt-4o-mini-transcribe"
                    else -> "gpt-4o-transcribe"
                }
                "groq" -> if (quality == "fast") "whisper-large-v3-turbo" else "whisper-large-v3"
                else -> "whisper-1"
            }
        }

    /** Model językowy do notatek AI (Groq zaleca gpt-oss-120b w miejsce wycofanego Llama 3.3 70B). */
    val summaryModel: String
        get() = when (provider) {
            "openai" -> "gpt-5-mini"
            else -> "openai/gpt-oss-120b"
        }

    /** Długość fragmentów: krótsze = tekst pojawia się szybciej, dłuższe = więcej kontekstu. */
    val chunkMinMs: Int get() = when (quality) { "fast" -> 6_000; "accurate" -> 20_000; else -> 10_000 }
    val chunkMaxMs: Int get() = when (quality) { "fast" -> 15_000; "accurate" -> 45_000; else -> 25_000 }

    val host: String get() = baseUrl.removePrefix("https://").removePrefix("http://").substringBefore('/')
}

class SettingsStore(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<AppSettings> = _flow
    val current: AppSettings get() = _flow.value

    /** Klucze API leżą w magazynie szyfrowanym kluczem z Android Keystore. */
    private val secure: SharedPreferences? by lazy {
        try {
            EncryptedSharedPreferences.create(
                "secure_prefs",
                MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            null
        }
    }

    val secureStorageAvailable: Boolean get() = secure != null

    fun apiKey(provider: String = current.provider): String =
        try { secure?.getString("api_key_$provider", "") ?: "" } catch (e: Exception) { "" }

    fun setApiKey(provider: String, key: String): Boolean {
        val s = secure ?: return false
        return try {
            s.edit().putString("api_key_$provider", key.trim()).apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun clearSecrets() {
        try { secure?.edit()?.clear()?.apply() } catch (_: Exception) { }
    }

    fun update(block: (AppSettings) -> AppSettings) {
        val n = block(_flow.value)
        prefs.edit()
            .putString("language", n.language)
            .putString("engine", n.engine)
            .putString("provider", n.provider)
            .putString("customBaseUrl", n.customBaseUrl)
            .putString("customModel", n.customModel)
            .putString("quality", n.quality)
            .putBoolean("autosave", n.autosave)
            .putBoolean("timestamps", n.timestamps)
            .putBoolean("speakers", n.speakers)
            .putString("pdfFontSize", n.pdfFontSize)
            .putBoolean("pdfHeader", n.pdfHeader)
            .putString("theme", n.theme)
            .putBoolean("keepAudio", n.keepAudio)
            .putString("glossary", n.glossary)
            .putBoolean("consentShown", n.consentShown)
            .apply()
        _flow.value = n
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            language = prefs.getString("language", d.language) ?: d.language,
            engine = prefs.getString("engine", d.engine) ?: d.engine,
            provider = prefs.getString("provider", d.provider) ?: d.provider,
            customBaseUrl = prefs.getString("customBaseUrl", d.customBaseUrl) ?: d.customBaseUrl,
            customModel = prefs.getString("customModel", d.customModel) ?: d.customModel,
            quality = prefs.getString("quality", d.quality) ?: d.quality,
            autosave = prefs.getBoolean("autosave", d.autosave),
            timestamps = prefs.getBoolean("timestamps", d.timestamps),
            speakers = prefs.getBoolean("speakers", d.speakers),
            pdfFontSize = prefs.getString("pdfFontSize", d.pdfFontSize) ?: d.pdfFontSize,
            pdfHeader = prefs.getBoolean("pdfHeader", d.pdfHeader),
            theme = prefs.getString("theme", d.theme) ?: d.theme,
            keepAudio = prefs.getBoolean("keepAudio", d.keepAudio),
            glossary = prefs.getString("glossary", d.glossary) ?: d.glossary,
            consentShown = prefs.getBoolean("consentShown", d.consentShown),
        )
    }
}
