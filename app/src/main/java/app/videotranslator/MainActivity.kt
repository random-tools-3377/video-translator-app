package app.videotranslator

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.videotranslator.engine.Phase
import app.videotranslator.engine.TranslationEngine
import app.videotranslator.llm.LanguageModels
import app.videotranslator.ui.AppTheme
import app.videotranslator.ui.BusyDialog
import app.videotranslator.ui.MainScreen
import app.videotranslator.ui.ModelActions

class MainActivity : ComponentActivity() {

    /** A video that arrived while another was being translated, waiting for the user's answer. */
    private var waiting by mutableStateOf<Uri?>(null)

    /** The user said to replace the running one: start this as soon as the old one has stopped. */
    private var queued by mutableStateOf<Uri?>(null)

    /**
     * The progress notification needs Android's notification permission to be seen in the
     * notification shade (the translation runs in the background either way). Asked once.
     */
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private var askedNotifications = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        LanguageModels.refresh(this)
        if (savedInstanceState == null) handleShare(intent)

        setContent {
            AppTheme {
                val state by TranslationEngine.state.collectAsStateWithLifecycle()
                val modelState by LanguageModels.state.collectAsStateWithLifecycle()
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                    if (uri != null) request(uri)
                }
                // The language model file, picked once from Downloads. Read once, copied in, checked.
                val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) LanguageModels.import(this@MainActivity, uri)
                }

                LaunchedEffect(state.phase, queued) {
                    val next = queued
                    if (next != null && state.phase != Phase.Working) {
                        queued = null
                        TranslationEngine.start(this@MainActivity, next)
                    }
                }

                MainScreen(
                    state = state,
                    modelState = modelState,
                    onPick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                    },
                    onCancel = { TranslationEngine.cancel() },
                    modelActions = ModelActions(
                        onDownload = { model -> openInBrowser(model.downloadUrl) },
                        onLoad = { modelPicker.launch(arrayOf("*/*")) },
                        onCancelLoad = { LanguageModels.cancelImport() },
                        onRemove = { LanguageModels.remove(this@MainActivity) },
                    ),
                )

                waiting?.let { uri ->
                    BusyDialog(
                        currentName = state.videoName,
                        onReplace = {
                            waiting = null
                            queued = uri
                            TranslationEngine.cancel()
                        },
                        onKeep = { waiting = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    /** A video shared from Samsung Gallery (or any app): one item, read once, never copied. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val type = intent.type ?: return
        if (!type.startsWith("video/")) return
        val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            ?: return
        request(uri)
    }

    /**
     * Opens a download link in the phone's browser. The browser does the download; this app has no
     * network permission and never sees the connection.
     */
    private fun openInBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            // No browser: nothing to do.
        }
    }

    private fun askForNotificationsOnce() {
        if (askedNotifications) return
        askedNotifications = true
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun request(uri: Uri) {
        askForNotificationsOnce()
        if (TranslationEngine.isBusy) {
            waiting = uri
        } else {
            TranslationEngine.start(this, uri)
        }
    }
}
