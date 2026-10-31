package com.pixelx.app

import android.graphics.BitmapFactory
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------- PROMPT

@Composable
fun PromptScreen(vm: AppVm) {
    val lines = vm.prompt.count { it == '\n' } + 1
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 10.dp)) {
        TopBar("Describe your video") { vm.back() }
        StepDots(2)
        Gap(14)
        Text("What should happen?", style = ts(28, FontWeight.Bold))
        Gap(6)
        Text("Describe how you want your image to move. Up to 500 lines.", style = ts(13, color = C.Muted, line = 21))
        Gap(16)
        OutlinedTextField(
            value = vm.prompt,
            onValueChange = { v -> if (v.count { it == '\n' } < 500 && v.length <= 30000) vm.prompt = v },
            modifier = Modifier.fillMaxWidth().heightIn(min = 230.dp),
            textStyle = ts(15, line = 24),
            placeholder = { Text("Describe how you want your image to move...", style = ts(15, color = C.Muted)) },
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = C.Accent, unfocusedBorderColor = C.Border,
                focusedContainerColor = C.Surface, unfocusedContainerColor = C.Surface,
                cursorColor = C.Accent, focusedTextColor = C.Text, unfocusedTextColor = C.Text
            )
        )
        Gap(8)
        Text("$lines / 500 lines", style = ts(12, color = if (lines >= 480) C.Warn else C.Muted, family = Mono))
        Gap(20)
        Text("Need ideas? Tap one", style = ts(13, FontWeight.SemiBold, C.Muted))
        Gap(8)
        Texts.examples.forEach { ex ->
            Box(
                Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(14.dp)).background(C.Surface)
                    .border(1.dp, C.Border, RoundedCornerShape(14.dp)).clickable { vm.prompt = ex }.padding(14.dp)
            ) { Text(ex, style = ts(13, color = C.Text, line = 20)) }
        }
        Gap(18)
        PxButton("Confirm", { vm.screen = Screen.DETAILS }, Modifier.fillMaxWidth(), enabled = vm.prompt.isNotBlank())
        Gap(30)
    }
}

// ---------------------------------------------------------------- DETAILS

@Composable
fun DetailsScreen(vm: AppVm) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 10.dp)) {
        TopBar("Final details") { vm.back() }
        StepDots(3)
        Gap(14)
        PxCard(Modifier.fillMaxWidth()) {
            Column {
                Text("Video time", style = ts(17, FontWeight.SemiBold))
                Text("Maximum 25 seconds", style = ts(12, color = C.Muted))
                Gap(10)
                Text("${vm.seconds} s", style = ts(44, FontWeight.Bold, C.Accent2))
                Slider(
                    value = vm.seconds.toFloat(), onValueChange = { vm.seconds = it.toInt().coerceIn(5, 25) },
                    valueRange = 5f..25f, steps = 19,
                    colors = SliderDefaults.colors(thumbColor = C.Accent, activeTrackColor = C.Accent, inactiveTrackColor = C.Surface2)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(8, 10, 15, 25).forEach { s ->
                        val on = vm.seconds == s
                        Box(
                            Modifier.clip(RoundedCornerShape(50)).background(if (on) C.Accent else C.Surface2)
                                .clickable { vm.seconds = s }.padding(horizontal = 16.dp, vertical = 8.dp)
                        ) { Text("${s}s", style = ts(13, FontWeight.Medium, if (on) Color_White else C.Muted)) }
                    }
                }
            }
        }
        Gap(14)
        PxCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Audio", style = ts(17, FontWeight.SemiBold))
                    Text("Add sound to your video", style = ts(12, color = C.Muted))
                }
                Pill("COMING SOON", C.Warn)
                Switch(checked = false, onCheckedChange = null, enabled = false, modifier = Modifier.padding(start = 10.dp))
            }
        }
        Gap(14)
        PxCard(Modifier.fillMaxWidth()) {
            Column {
                Text("This video costs 1 credit", style = ts(15, FontWeight.SemiBold))
                Text("You have ${vm.credits} of ${Credits.MAX} credits today.", style = ts(12, color = C.Muted))
                Gap(6)
                Text("Estimated time: about ${fmtEta(Generator.estimateSeconds(vm.seconds))}", style = ts(12, color = C.Muted))
            }
        }
        Gap(22)
        PxButton("Confirm", { vm.startJob(ctx) }, Modifier.fillMaxWidth())
        Gap(30)
    }
    if (vm.showNoCredits) {
        MessageDialog("You have used all 5 credits for today. A new credit arrives in ${Credits.refillText()}.") { vm.showNoCredits = false }
    }
}

private val Color_White = androidx.compose.ui.graphics.Color.White

fun fmtEta(sec: Int): String = if (sec < 60) "$sec s" else "${sec / 60} min ${sec % 60} s"

// ---------------------------------------------------------------- PROCESS

@Composable
fun ProcessScreen(vm: AppVm) {
    val ctx = LocalContext.current
    val st by Hub.state.collectAsState()
    val tips = listOf(
        "Every pixel block is moved by our own engine.",
        "Your video carries an invisible evidence mark.",
        "Rendering continues if you leave the app.",
        "No GPU farm, just clever pixels."
    )
    var tip by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(3800); tip = (tip + 1) % tips.size } }
    LaunchedEffect(st) {
        val s = st
        if (s is Hub.State.Done) { vm.result = s.project; vm.refreshCredits(); vm.screen = Screen.RESULT }
    }

    Box(Modifier.fillMaxSize()) {
        PixelField(Modifier.fillMaxSize().alpha(0.32f), periodMs = 2400, cell = 18.dp, strength = 0.6f)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(C.Bg.copy(alpha = 0.4f), C.Bg.copy(alpha = 0.94f)))))
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(26.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val s = st
            if (s is Hub.State.Failed) {
                Text("Something went wrong", style = ts(24, FontWeight.Bold, C.Danger), textAlign = TextAlign.Center)
                Gap(12)
                Text(s.message, style = ts(14, color = C.Muted, line = 22), textAlign = TextAlign.Center)
                Gap(28)
                PxButton("Back", { Hub.state.value = Hub.State.Idle; vm.screen = Screen.DETAILS }, Modifier.fillMaxWidth())
            } else {
                val run = s as? Hub.State.Running
                val p by animateFloatAsState(run?.progress ?: 0f, tween(500), label = "p")
                Text("Doing your process", style = ts(26, FontWeight.Bold))
                Gap(26)
                Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val w = 12.dp.toPx()
                        val pad = w / 2f
                        val sz = Size(size.width - w, size.height - w)
                        drawArc(color = C.Surface2, startAngle = -90f, sweepAngle = 360f, useCenter = false, topLeft = Offset(pad, pad), size = sz, style = Stroke(w))
                        drawArc(
                            brush = Brush.sweepGradient(listOf(C.Accent, C.Accent2, C.Accent)),
                            startAngle = -90f, sweepAngle = 360f * p, useCenter = false,
                            topLeft = Offset(pad, pad), size = sz, style = Stroke(w, cap = StrokeCap.Round)
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text((p * 100).toInt().toString() + "%", style = ts(46, FontWeight.Bold))
                        Text("approx. " + fmtEta(run?.etaSec ?: 0) + " left", style = ts(12, color = C.Muted, family = Mono))
                    }
                }
                Gap(26)
                Text(run?.stage ?: "Working", style = ts(16, FontWeight.SemiBold, C.Accent2), textAlign = TextAlign.Center)
                val d = run?.detail.orEmpty()
                if (d.isNotEmpty()) {
                    Gap(10)
                    Text(d, style = ts(12, color = C.Muted, line = 19), textAlign = TextAlign.Center, maxLines = 5)
                }
                Gap(34)
                Text(tips[tip], style = ts(12, color = C.Muted, family = Mono), textAlign = TextAlign.Center)
                Gap(30)
                PxButton("Cancel", { vm.cancelJob(ctx) }, Modifier.fillMaxWidth(), secondary = true)
            }
        }
    }
}

// ---------------------------------------------------------------- RESULT

@Composable
fun ResultScreen(vm: AppVm) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = vm.result
    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 12.dp)) {
        Logo(20)
        Gap(18)
        if (p == null) {
            Text("No video found.", style = ts(16, color = C.Muted))
            Gap(16)
            PxButton("New project", { vm.newProject() }, Modifier.fillMaxWidth())
        } else {
            Text("Your video is ready", style = ts(28, FontWeight.Bold))
            Gap(14)
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.Surface).border(1.dp, C.Border, RoundedCornerShape(20.dp))) {
                AndroidView(
                    factory = { c ->
                        VideoView(c).apply {
                            setVideoPath(p.video)
                            setOnPreparedListener { mp -> mp.isLooping = true; start() }
                            setOnClickListener { if (isPlaying) pause() else start() }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().aspectRatio(p.w.toFloat() / p.h.toFloat())
                )
            }
            Gap(8)
            Text("Tap the video to pause or play.", style = ts(11, color = C.Muted, family = Mono))
            if (p.summary.isNotBlank()) {
                Gap(10)
                Text(p.summary, style = ts(13, color = C.Muted, line = 20))
            }
            Gap(24)
            PxButton("Download MP4", {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { Saver.saveVideo(ctx, File(p.video)) }
                    Toast.makeText(ctx, if (ok) "Saved to Movies/PIXEL-X" else "Could not save the video. Check the save permission.", Toast.LENGTH_LONG).show()
                }
            }, Modifier.fillMaxWidth())
            Gap(12)
            PxButton("New project", { vm.newProject() }, Modifier.fillMaxWidth(), secondary = true)
        }
        Gap(30)
    }
}

// ---------------------------------------------------------------- HISTORY

@Composable
fun HistoryScreen(vm: AppVm) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf(History.all()) }
    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 10.dp)) {
        TopBar("History") { vm.back() }
        Gap(8)
        if (list.isEmpty()) {
            Gap(60)
            Text("No finished projects yet.", style = ts(15, color = C.Muted), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
        list.forEach { p ->
            val thumb = remember(p.thumb) { BitmapFactory.decodeFile(p.thumb)?.asImageBitmap() }
            PxCard(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                Column {
                    if (thumb != null) {
                        Image(thumb, contentDescription = null, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.FillWidth)
                        Gap(10)
                    }
                    Text(p.prompt, style = ts(14, FontWeight.Medium, line = 21), maxLines = 3)
                    Gap(4)
                    Text(
                        SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(p.createdAt)) + "  •  ${p.seconds}s",
                        style = ts(11, color = C.Muted, family = Mono)
                    )
                    Gap(12)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PxButton("Open", { vm.result = p; vm.screen = Screen.RESULT }, Modifier.weight(1f))
                        PxButton("Save", {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { Saver.saveVideo(ctx, File(p.video)) }
                                Toast.makeText(ctx, if (ok) "Saved to Movies/PIXEL-X" else "Could not save.", Toast.LENGTH_LONG).show()
                            }
                        }, Modifier.weight(1f), secondary = true)
                        PxButton("Delete", { History.remove(p); list = History.all() }, Modifier.weight(1f), secondary = true)
                    }
                }
            }
        }
        Gap(30)
    }
}

// ---------------------------------------------------------------- DIALOGS

@Composable
fun MessageDialog(text: String, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("OK", style = ts(14, FontWeight.SemiBold, C.Accent)) } },
        text = { Text(text, style = ts(14, color = C.Text, line = 22)) },
        containerColor = C.Surface
    )
}

@Composable
private fun SettingRow(title: String, sub: String, danger: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(16.dp)).background(C.Surface2)
            .clickable(onClick = onClick).padding(16.dp)
    ) {
        Text(title, style = ts(15, FontWeight.SemiBold, if (danger) C.Danger else C.Text))
        Text(sub, style = ts(12, color = C.Muted, line = 18))
    }
}

@Composable
fun SettingsDialog(vm: AppVm, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var showApi by remember { mutableStateOf(false) }
    var confirmRefresh by remember { mutableStateOf(false) }
    var verifyText by remember { mutableStateOf<String?>(null) }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            verifyText = "Checking video…"
            scope.launch { verifyText = withContext(Dispatchers.IO) { try { Evidence.verify(ctx, uri) } catch (e: Exception) { "Could not read this video." } } }
        }
    }

    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(C.Surface)
                .border(1.dp, C.Border, RoundedCornerShape(24.dp)).padding(20.dp)
        ) {
            Text("Settings", style = ts(22, FontWeight.Bold))
            Gap(14)
            SettingRow("History", "Your finished projects") { onClose(); vm.screen = Screen.HISTORY }
            SettingRow("API change", "Groq key and model") { showApi = true }
            SettingRow("Verify video evidence", "Check a video for the PIXEL•X invisible mark") { videoPicker.launch("video/*") }
            SettingRow("Refresh", "Remove everything and restart the app fully", danger = true) { confirmRefresh = true }
            Gap(4)
            Text("PIXEL•X  v" + BuildConfig.VERSION_NAME, style = ts(11, color = C.Muted, family = Mono), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
    }
    if (showApi) ApiDialog { showApi = false }
    if (confirmRefresh) {
        AlertDialog(
            onDismissRequest = { confirmRefresh = false },
            title = { Text("Refresh everything?", style = ts(18, FontWeight.Bold)) },
            text = {
                Text(
                    "This deletes all projects, videos and saved settings, then restarts the app from the beginning. Your daily credit usage is kept.",
                    style = ts(14, color = C.Muted, line = 22)
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmRefresh = false; onClose(); vm.refreshApp(ctx) }) { Text("Refresh", style = ts(14, FontWeight.SemiBold, C.Danger)) }
            },
            dismissButton = { TextButton(onClick = { confirmRefresh = false }) { Text("Cancel", style = ts(14, color = C.Muted)) } },
            containerColor = C.Surface
        )
    }
    verifyText?.let { MessageDialog(it) { verifyText = null } }
}

@Composable
fun ApiDialog(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf(Store.userKey) }
    var model by remember { mutableStateOf(Store.model) }
    var status by remember { mutableStateOf("") }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = C.Accent, unfocusedBorderColor = C.Border,
        focusedContainerColor = C.Surface2, unfocusedContainerColor = C.Surface2,
        cursorColor = C.Accent, focusedTextColor = C.Text, unfocusedTextColor = C.Text
    )
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(C.Surface)
                .border(1.dp, C.Border, RoundedCornerShape(24.dp)).padding(20.dp)
        ) {
            Text("API change", style = ts(22, FontWeight.Bold))
            Gap(6)
            Text("Get a free key at console.groq.com. Leave empty to use the key built into this app.", style = ts(12, color = C.Muted, line = 19))
            Gap(14)
            OutlinedTextField(
                value = key, onValueChange = { key = it }, singleLine = true, label = { Text("Groq API key", style = ts(12, color = C.Muted)) },
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                textStyle = ts(14, family = Mono), shape = RoundedCornerShape(14.dp), colors = colors
            )
            Gap(10)
            OutlinedTextField(
                value = model, onValueChange = { model = it }, singleLine = true, label = { Text("Vision model", style = ts(12, color = C.Muted)) },
                modifier = Modifier.fillMaxWidth(), textStyle = ts(13, family = Mono), shape = RoundedCornerShape(14.dp), colors = colors
            )
            if (status.isNotEmpty()) {
                Gap(10)
                Text(status, style = ts(12, color = if (status.startsWith("✓")) C.Good else C.Warn, line = 18))
            }
            Gap(16)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PxButton("Test", {
                    status = "Testing…"
                    val k = key.trim().ifBlank { Store.activeKey() }
                    val m = model.trim()
                    scope.launch {
                        status = withContext(Dispatchers.IO) {
                            try {
                                if (k.isBlank()) "No key set." else {
                                    val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", "Reply with OK"))
                                    Groq.complete(k, m, msgs, false, 8)
                                    "✓ Connection works"
                                }
                            } catch (e: Exception) { e.message ?: "Failed" }
                        }
                    }
                }, Modifier.weight(1f), secondary = true)
                PxButton("Save", { Store.userKey = key; Store.model = model; onClose() }, Modifier.weight(1f))
            }
        }
    }
}
