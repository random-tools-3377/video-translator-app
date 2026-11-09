package app.videotranslator

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A subtitle file tapped in a file manager (or shared) opens with the app, whatever type the file
 * manager gives it, and an ordinary text file does not offer the app.
 */
@RunWith(AndroidJUnit4::class)
class OpenSubtitleFileTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun opensWithApp(intent: Intent): Boolean =
        context.packageManager
            .queryIntentActivities(intent.setPackage(context.packageName), PackageManager.MATCH_DEFAULT_ONLY)
            .isNotEmpty()

    private fun view(uri: String, type: String) = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), type)

    @Test
    fun subtitleFilesOpenWithTheApp() {
        // As the app saves them: two dots in the name.
        val srt = "content://com.example.files/device_storage/0/Download/VID_20250101.en.srt"
        for (type in listOf("application/x-subrip", "text/x-subrip", "application/octet-stream", "text/plain")) {
            assertTrue("a .srt given as $type", opensWithApp(view(srt, type)))
        }
        assertTrue(opensWithApp(view("content://media/external/file/1234", "application/x-subrip")))
        assertTrue(opensWithApp(view("content://com.example.files/Download/meeting.vtt", "text/vtt")))
        assertTrue(opensWithApp(view("content://com.example.files/Download/captions.sbv", "application/octet-stream")))
        assertTrue(opensWithApp(Intent(Intent.ACTION_SEND).setType("application/x-subrip")))
        assertTrue(opensWithApp(Intent(Intent.ACTION_SEND).setType("video/mp4")))
    }

    @Test
    fun otherFilesDoNotOfferTheApp() {
        assertFalse(opensWithApp(view("content://com.example.files/Download/notes.txt", "text/plain")))
        assertFalse(opensWithApp(view("content://com.example.files/Download/archive.zip", "application/octet-stream")))
        assertFalse(opensWithApp(Intent(Intent.ACTION_SEND).setType("text/plain")))
    }
}
