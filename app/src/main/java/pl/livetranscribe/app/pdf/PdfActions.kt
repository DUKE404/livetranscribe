package pl.livetranscribe.app.pdf

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/** Pobieranie, udostępnianie i otwieranie plików – wszystko natywnie na Androidzie. */
object PdfActions {

    const val DOWNLOAD_FOLDER = "LiveTranscribe"

    /** Bezpieczna nazwa pliku z tytułu dokumentu (bez rozszerzenia). */
    fun safeName(title: String): String {
        val cleaned = title.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
            .replace(Regex("\\s+"), "_")
            .trim('_', '.')
            .take(80)
        return cleaned.ifEmpty { "Transkrypcja" }
    }

    private fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)

    /**
     * Zapisuje PDF w pamięci telefonu: Download/LiveTranscribe/.
     * MediaStore nie wymaga żadnych uprawnień do pamięci na Androidzie 10+.
     * Zwraca ścieżkę do pokazania użytkownikowi.
     */
    fun saveToDownloads(context: Context, file: File, displayName: String): String {
        val name = safeName(displayName.removeSuffix(".pdf").removeSuffix(".PDF")) + ".pdf"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOAD_FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Nie udało się utworzyć pliku w folderze Download")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IllegalStateException("Brak dostępu do pliku")
            out.use { o -> file.inputStream().use { it.copyTo(o) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        // System mógł dodać numer, jeśli plik o tej nazwie już istniał.
        var finalName = name
        try {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) finalName = c.getString(0) ?: name
            }
        } catch (_: Exception) {
        }
        return "Download/$DOWNLOAD_FOLDER/$finalName"
    }

    /** Otwiera standardowe menu udostępniania Androida (Gmail, WhatsApp, Messenger, Bluetooth…). */
    fun share(context: Context, file: File, subject: String) {
        val uri = uriFor(context, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            clipData = ClipData.newRawUri(subject, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, "Udostępnij PDF").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    /** Otwiera PDF w zainstalowanej przeglądarce PDF. Zwraca false, gdy żadnej nie ma. */
    fun open(context: Context, file: File): Boolean {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriFor(context, file), "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(view)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    /** Udostępnia zachowane nagrania audio sesji (jeśli użytkownik włączył ich przechowywanie). */
    fun shareAudio(context: Context, files: List<File>) {
        val uris = ArrayList<Uri>(files.map { uriFor(context, it) })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "audio/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Udostępnij nagranie").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}
