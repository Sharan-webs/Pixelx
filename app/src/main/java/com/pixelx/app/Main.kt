package com.pixelx.app

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Screen { TERMS, PERMS, SETUP, BOOT, HOME, CONFIRM_IMAGE, PROMPT, DETAILS, PROCESS, RESULT, HISTORY }

object Perms {
    fun notif(c: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun storage(c: Context): Boolean =
        Build.VERSION.SDK_INT >= 29 ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun battery(c: Context): Boolean =
        c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)

    fun requiredOk(c: Context) = notif(c) && storage(c)
}

class AppVm(app: Application) : AndroidViewModel(app) {
    var screen by mutableStateOf(
        if (!Store.termsAccepted) Screen.TERMS else if (!Perms.requiredOk(app)) Screen.PERMS else if (!Store.setupDone) Screen.SETUP else Screen.BOOT
    )
    var resumeTick by mutableIntStateOf(0)
    var notifAsked by mutableStateOf(false)
    var imagePath by mutableStateOf<String?>(null)
    var imagePreview by mutableStateOf<ImageBitmap?>(null)
    var imageInfo by mutableStateOf("")
    var prompt by mutableStateOf("")
    var seconds by mutableIntStateOf(10)
    var result by mutableStateOf<Project?>(null)
    var credits by mutableIntStateOf(Credits.remaining())
    var message by mutableStateOf<String?>(null)
    var showNoCredits by mutableStateOf(false)

    init {
        // the app was closed while a video was rendering: pick the job up again
        if (Store.termsAccepted) {
            when (val s = Hub.state.value) {
                is Hub.State.Running, is Hub.State.Failed -> screen = Screen.PROCESS
                is Hub.State.Done -> { result = s.project; screen = Screen.RESULT }
                else -> {}
            }
        }
    }

    fun refreshCredits() { credits = Credits.remaining() }

    fun pickImage(uri: Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val f = File(ctx.cacheDir, "input_" + System.currentTimeMillis() + ".img")
                val ins = ctx.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("Could not open the image.")
                ins.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                val bounds = ImageIO.bounds(f.absolutePath)
                val bm = ImageIO.load(f.absolutePath, 1024)
                var iw = bounds[0]
                var ih = bounds[1]
                if ((bm.width > bm.height) != (iw > ih)) { val t = iw; iw = ih; ih = t }
                val mb = f.length() / 1048576.0
                val info = "$iw × $ih px  •  " + String.format("%.2f", mb) + " MB"
                withContext(Dispatchers.Main) {
                    imagePath?.let { File(it).delete() }
                    imagePath = f.absolutePath
                    imagePreview = bm.asImageBitmap()
                    imageInfo = info
                    screen = Screen.CONFIRM_IMAGE
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { message = e.message ?: "Could not read this image." }
            }
        }
    }

    fun startJob(ctx: Context) {
        val path = imagePath ?: return
        if (Credits.remaining() <= 0) { showNoCredits = true; return }
        Hub.pending = JobSpec(path, prompt.trim(), seconds)
        Hub.state.value = Hub.State.Running("Starting engines", 0f, Generator.estimateSeconds(seconds), "")
        ContextCompat.startForegroundService(ctx, Intent(ctx, RenderService::class.java))
        screen = Screen.PROCESS
    }

    fun cancelJob(ctx: Context) {
        ctx.stopService(Intent(ctx, RenderService::class.java))
        Hub.state.value = Hub.State.Idle
        screen = Screen.DETAILS
    }

    fun newProject() {
        Hub.state.value = Hub.State.Idle
        imagePath = null; imagePreview = null; imageInfo = ""; prompt = ""; seconds = 10; result = null
        refreshCredits()
        screen = Screen.HOME
    }

    fun refreshApp(ctx: Context) {
        ctx.stopService(Intent(ctx, RenderService::class.java))
        Store.wipe(ctx)
        Hub.state.value = Hub.State.Idle
        imagePath = null; imagePreview = null; imageInfo = ""; prompt = ""; seconds = 10; result = null
        refreshCredits()
        screen = Screen.TERMS
    }

    fun back() {
        screen = when (screen) {
            Screen.CONFIRM_IMAGE -> Screen.HOME
            Screen.PROMPT -> Screen.CONFIRM_IMAGE
            Screen.DETAILS -> Screen.PROMPT
            Screen.HISTORY -> Screen.HOME
            Screen.RESULT -> Screen.HOME
            else -> screen
        }
    }
}

class MainActivity : ComponentActivity() {
    private val vm: AppVm by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent { PxTheme { PixelXRoot(vm) } }
    }

    override fun onResume() {
        super.onResume()
        vm.resumeTick++
        vm.refreshCredits()
    }
}

@Composable
fun PixelXRoot(vm: AppVm) {
    BackHandler(enabled = vm.screen in listOf(Screen.CONFIRM_IMAGE, Screen.PROMPT, Screen.DETAILS, Screen.HISTORY, Screen.RESULT)) { vm.back() }
    Box(Modifier.fillMaxSize().background(C.Bg)) {
        Crossfade(targetState = vm.screen, animationSpec = tween(320), label = "screens") { s ->
            when (s) {
                Screen.TERMS -> TermsScreen(vm)
                Screen.PERMS -> PermsScreen(vm)
                Screen.SETUP -> SetupScreen(vm)
                Screen.BOOT -> BootScreen(vm)
                Screen.HOME -> HomeScreen(vm)
                Screen.CONFIRM_IMAGE -> ConfirmImageScreen(vm)
                Screen.PROMPT -> PromptScreen(vm)
                Screen.DETAILS -> DetailsScreen(vm)
                Screen.PROCESS -> ProcessScreen(vm)
                Screen.RESULT -> ResultScreen(vm)
                Screen.HISTORY -> HistoryScreen(vm)
            }
        }
    }
}
