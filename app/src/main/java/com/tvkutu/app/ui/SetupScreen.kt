package com.tvkutu.app.ui

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import com.tvkutu.app.data.BackupFiles
import com.tvkutu.app.data.BackupEntry
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvkutu.app.AppViewModel
import com.tvkutu.app.BuildConfig

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupScreen(vm: AppViewModel) {
    var newName by rememberSaveable { mutableStateOf("") }
    var newUrl by rememberSaveable { mutableStateOf("") }
    var newEpg by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    val qrFocus = remember { FocusRequester() }
    val firstRun = !vm.setupFromSettings || vm.sources.isEmpty()

    BackHandler(enabled = vm.setupFromSettings && vm.activeSource != null) { vm.closeSettings() }
    // Kumandayla en kolay yol telefondan kurulum: odağı oraya koy.
    LaunchedEffect(Unit) { runCatching { qrFocus.requestFocus() } }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Palette.Accent,
        unfocusedBorderColor = Palette.SurfaceHigh,
        focusedTextColor = Palette.Text,
        unfocusedTextColor = Palette.Text,
        focusedLabelColor = Palette.Accent,
        unfocusedLabelColor = Palette.TextDim,
        focusedPlaceholderColor = Palette.TextDim,
        unfocusedPlaceholderColor = Palette.TextDim,
        cursorColor = Palette.Accent,
    )

    // ---- Yedekleme durumu ----
    val context = LocalContext.current
    var restoreReplace by remember { mutableStateOf(false) }
    var backupMsg by remember { mutableStateOf<String?>(null) }
    var backupErr by remember { mutableStateOf(false) }
    var backupList by remember { mutableStateOf<List<BackupEntry>?>(null) }
    var confirmEntry by remember { mutableStateOf<BackupEntry?>(null) }

    fun report(text: String, error: Boolean = false) { backupMsg = text; backupErr = error }

    fun doRestore(read: () -> String) {
        try {
            report(vm.restoreBackupText(read(), restoreReplace))
            backupList = null
        } catch (e: Exception) {
            report(e.message ?: "Yedek yüklenemedi", error = true)
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            runCatching { BackupFiles.writeTo(context, uri, vm.backupJson()) }
                .onSuccess { report("Yedek kaydedildi") }
                .onFailure { report("Kaydedilemedi: ${it.message}", error = true) }
        }
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) doRestore { BackupFiles.readFrom(context, uri) }
    }

    fun saveBackup() = vm.withPin("Yedek", "Yedek almak için PIN'i gir") {
        try {
            saveLauncher.launch(BackupFiles.fileName())
        } catch (e: ActivityNotFoundException) {
            // Dosya seçici yok (bazı TV kutuları): İndirilenler klasörüne yaz.
            runCatching { BackupFiles.saveToDownloads(context, vm.backupJson()) }
                .onSuccess { report("Yedek kaydedildi: $it") }
                .onFailure { report("Kaydedilemedi: ${it.message}", error = true) }
        }
    }

    fun openRestore() = vm.withPin("Geri yükle", "Geri yüklemek için PIN'i gir") {
        backupList = runCatching { BackupFiles.list(context, vm.repo) }.getOrDefault(emptyList())
    }

    fun pickFile() {
        try {
            openLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        } catch (e: ActivityNotFoundException) {
            report("Bu cihazda dosya seçici yok. Listeden bir yedek seç ya da telefondan yükle.", error = true)
        }
    }

    fun chooseEntry(e: BackupEntry) {
        if (restoreReplace) confirmEntry = e else doRestore(e.read)
    }

    fun addNew() {
        error = vm.addAccountManual(newName, newUrl, newEpg)
        if (error == null) { newName = ""; newUrl = ""; newEpg = "" }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Bg)) {
        val wide = maxWidth >= 600.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = if (wide) 64.dp else 20.dp, vertical = if (wide) 40.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
                if (firstRun) {
                    Text("TvKutu", color = Palette.Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("Hoş geldin", color = Palette.Text, fontSize = if (wide) 34.sp else 28.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("Başlamak için IPTV hesabını (playlist linkini) ekle.", color = Palette.TextDim, fontSize = 16.sp)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Ayarlar", color = Palette.Text, fontSize = if (wide) 34.sp else 28.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f))
                        TvButton("Kapat", onClick = { vm.closeSettings() })
                    }
                }

                // ---- Hesaplar ----
                Section("Hesaplar")
                Text(
                    "Her playlist linki ayrı bir hesaptır; TV'de seçtiğin hesap görünür. Favoriler, son kanal ve EPG her hesapta ayrı tutulur.",
                    color = Palette.TextDim, fontSize = 15.sp,
                )
                Spacer(Modifier.height(16.dp))
                Focusable(
                    onClick = { vm.openQrSetup() },
                    shape = RoundedCornerShape(50),
                    focusedScale = 1.03f,
                    selected = true,
                    modifier = Modifier.focusRequester(qrFocus),
                ) { focused ->
                    Row(Modifier.padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("📱", fontSize = 20.sp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Telefondan ekle ve yönet (karekod)",
                            color = if (focused) Palette.Accent else Palette.Text,
                            fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                vm.sources.forEach { a ->
                    val isActive = a.id == vm.activeId
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp)
                            .background(Palette.Surface, RoundedCornerShape(12.dp))
                            .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(36.dp).background(parseHex(a.color), RoundedCornerShape(9.dp)))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                (if (a.locked && vm.hasPin) "🔒 " else "") + a.name,
                                color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                when {
                                    a.error.isNotEmpty() -> "Bağlanamadı: ${a.error}"
                                    isActive -> "Açık · ${if (a.count > 0) "${a.count} kanal" else "yükleniyor"}"
                                    a.count > 0 -> "${a.count} kanal"
                                    else -> a.url
                                },
                                color = when {
                                    a.error.isNotEmpty() -> Palette.Danger
                                    isActive -> Palette.Accent
                                    else -> Palette.TextDim
                                },
                                fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        if (!isActive) TvButton("Aç", onClick = { vm.openAccount(a.id) })
                        TvButton(if (a.locked && vm.hasPin) "Kilidi aç" else "Kilitle", onClick = { vm.toggleAccountLock(a.id) })
                        TvButton("Sil", onClick = { vm.deleteAccount(a.id) })
                    }
                }
                if (vm.sources.isNotEmpty()) {
                    TvButton("Bağlantıları kontrol et", onClick = { vm.checkAll(force = true) })
                }

                Spacer(Modifier.height(16.dp))
                Text("Elle hesap ekle:", color = Palette.TextDim, fontSize = 15.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Hesap adı (isteğe bağlı)") },
                    singleLine = true,
                    colors = fieldColors,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = newUrl,
                    onValueChange = { newUrl = it; error = null },
                    label = { Text("Playlist linki (m3u / m3u8)") },
                    placeholder = { Text("https://…/playlist.m3u") },
                    singleLine = true,
                    colors = fieldColors,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = newEpg,
                    onValueChange = { newEpg = it },
                    label = { Text("EPG linki (isteğe bağlı)") },
                    placeholder = { Text("Boş kalırsa listedeki url-tvg kullanılır") },
                    singleLine = true,
                    colors = fieldColors,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addNew() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                (error ?: vm.loadError)?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = Palette.Danger, fontSize = 14.sp)
                }
                Spacer(Modifier.height(12.dp))
                TvButton("＋ Ekle ve aç", primary = true, onClick = { addNew() })

                // ---- Ebeveyn kilidi ----
                Section("Ebeveyn kilidi")
                Text(
                    "4 haneli bir PIN ile hesapları ve kategorileri kilitleyebilirsin. Kilit, uygulama arka plana gidince yeniden devreye girer.",
                    color = Palette.TextDim, fontSize = 15.sp,
                )
                Spacer(Modifier.height(14.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (!vm.hasPin) {
                        TvButton("PIN belirle", primary = true, onClick = { vm.createPin() })
                    } else {
                        TvButton("PIN'i değiştir", onClick = { vm.changePin() })
                        TvButton("PIN'i kaldır", onClick = { vm.removePin() })
                    }
                }
                val lockable = vm.groups.filter { it.id.startsWith("grp:") }
                if (vm.hasPin && lockable.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    Text(
                        "Kilitli kategoriler (${vm.activeSource?.name ?: ""}): seçili olanlar PIN sorar ve \"Tüm kanallar\"da gizlenir.",
                        color = Palette.TextDim, fontSize = 15.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        lockable.forEach { g ->
                            Chip((if (g.locked) "🔒 " else "") + g.label, selected = g.locked) { vm.toggleGroupLock(g.id) }
                        }
                    }
                }

                // ---- Yedekleme ----
                Section("Yedekleme")
                Text(
                    "Hesaplar, favoriler, kaldığın yerler, kilitli kategoriler ve görünüm ayarları tek dosyada. PIN yedeğe girmez. " +
                        "Hesaplar değiştikçe cihazın içine otomatik yedek de alınır (son 5).",
                    color = Palette.TextDim, fontSize = 15.sp,
                )
                Spacer(Modifier.height(14.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TvButton("⤓ Yedeği kaydet", primary = true, onClick = { saveBackup() })
                    TvButton("⤒ Geri yükle", onClick = { openRestore() })
                }
                backupMsg?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = if (backupErr) Palette.Danger else Palette.Accent, fontSize = 14.sp)
                }
                backupList?.let { list ->
                    Spacer(Modifier.height(16.dp))
                    Text("Geri yükleme biçimi:", color = Palette.TextDim, fontSize = 15.sp)
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Chip("Hesaplarıma ekle", selected = !restoreReplace) { restoreReplace = false }
                        Chip("Hepsini değiştir", selected = restoreReplace) { restoreReplace = true }
                    }
                    Spacer(Modifier.height(14.dp))
                    if (list.isEmpty()) {
                        Text("Cihazda kayıtlı yedek bulunamadı.", color = Palette.TextDim, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                    }
                    list.take(12).forEach { e ->
                        Focusable(
                            onClick = { chooseEntry(e) },
                            shape = RoundedCornerShape(12.dp),
                            focusedScale = 1.02f,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        ) { focused ->
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(e.label, color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${formatDateTime(e.timeMs)} · ${e.detail}", color = Palette.TextDim, fontSize = 13.sp)
                                }
                                Text("Yükle", color = if (focused) Palette.Accent else Palette.TextDim, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvButton("Dosya seç…", onClick = { pickFile() })
                        TvButton("Vazgeç", onClick = { backupList = null })
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Başka bir cihazdaki yedeği yüklemek için telefon sayfasındaki Yedek sekmesini de kullanabilirsin.",
                        color = Palette.TextDim, fontSize = 13.sp,
                    )
                }
                confirmEntry?.let { e ->
                    Spacer(Modifier.height(12.dp))
                    Column(
                        Modifier.fillMaxWidth().background(Color(0xFF2C1B1D), RoundedCornerShape(12.dp)).padding(16.dp),
                    ) {
                        Text("Bu cihazdaki tüm hesaplar yedektekilerle değiştirilsin mi?", color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TvButton("Evet, değiştir", primary = true, onClick = { confirmEntry = null; doRestore(e.read) })
                            TvButton("Vazgeç", onClick = { confirmEntry = null })
                        }
                    }
                }

                // ---- Wi‑Fi eşitleme ----
                WifiSyncSection(vm, fieldColors)

                // ---- Görünüm ----
                Section("Görünüm")
                Text("Tema", color = Palette.TextDim, fontSize = 15.sp)
                Spacer(Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Themes.all.forEach { t -> ThemeChip(t, selected = vm.themeId == t.id) { vm.setTheme(t.id) } }
                }
                Spacer(Modifier.height(20.dp))
                Text("Kanal kartları nasıl görünsün?", color = Palette.TextDim, fontSize = 15.sp)
                Spacer(Modifier.height(14.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    listOf(
                        "auto" to "Otomatik (filmler afiş, TV logo)",
                        "always" to "Hep afiş",
                        "never" to "Hep logo",
                    ).forEach { (v, label) -> Chip(label, selected = vm.posterMode == v) { vm.setPosterModeOption(v) } }
                }
                if (vm.posterMode != "never") {
                    Spacer(Modifier.height(18.dp))
                    Text("Afiş kartında ad nerede dursun?", color = Palette.TextDim, fontSize = 15.sp)
                    Spacer(Modifier.height(14.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Chip("Afişin üstünde", selected = vm.posterTitle == "overlay") { vm.setPosterTitleOption("overlay") }
                        Chip("Afişin altında", selected = vm.posterTitle == "below") { vm.setPosterTitleOption("below") }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "TV'de ad, kumanda afişin üzerine gelince görünür. \"Tüm kanallar\" seçiliyken kategoriler satır satır listelenir.",
                        color = Palette.TextDim, fontSize = 13.sp,
                    )
                }

                // ---- Oynatıcı ----
                Section("Oynatıcı")
                Text("Kanallar hangi oynatıcıda açılsın?", color = Palette.TextDim, fontSize = 15.sp)
                Spacer(Modifier.height(14.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Chip("Dahili (ExoPlayer)", selected = vm.playerMode == AppViewModel.PLAYER_INTERNAL) {
                        vm.setPlayer(AppViewModel.PLAYER_INTERNAL)
                    }
                    Chip("Harici uygulama (VLC, MX…)", selected = vm.playerMode == AppViewModel.PLAYER_EXTERNAL) {
                        vm.setPlayer(AppViewModel.PLAYER_EXTERNAL)
                    }
                }
                if (vm.playerMode == AppViewModel.PLAYER_INTERNAL) {
                    Spacer(Modifier.height(18.dp))
                    Text(
                        "Yayın biçimi: çoğu liste için Otomatik doğrudur. Bir kanal açılmıyorsa HLS veya MPEG-TS'yi dene.",
                        color = Palette.TextDim, fontSize = 15.sp,
                    )
                    Spacer(Modifier.height(14.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        listOf("auto" to "Otomatik", "hls" to "HLS (m3u8)", "ts" to "MPEG-TS").forEach { (f, label) ->
                            Chip(label, selected = vm.streamFormat == f) { vm.setFormat(f) }
                        }
                    }
                } else {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Kanala basınca cihazdaki oynatıcılardan birini seçmen istenir. Yayın akışı ve kanal değiştirme TvKutu içinde kalır.",
                        color = Palette.TextDim, fontSize = 14.sp,
                    )
                }

                // ---- Otomatik güncelleme ----
                Section("Otomatik güncelleme")
                Text("Liste ve yayın akışı, uygulama açıkken seçtiğin aralıkla yenilenir.",
                    color = Palette.TextDim, fontSize = 15.sp)
                Spacer(Modifier.height(14.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    listOf(6 to "6 saatte bir", 12 to "12 saatte bir", 24 to "24 saatte bir", 0 to "Kapalı").forEach { (h, label) ->
                        Chip(label, selected = vm.updateHours == h) { vm.setUpdateInterval(h) }
                    }
                }

                // ---- Hakkında ----
                Section("Hakkında")
                Text("TvKutu ${BuildConfig.VERSION_NAME}", color = Palette.Text, fontSize = 15.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "TvKutu yalnızca bir oynatıcıdır; kanal, yayın veya abonelik içermez. " +
                        "Yasal olarak abone olduğun sağlayıcının listesini kullan. Hesap, reklam ve analitik yoktur; linkler yalnızca bu cihazda saklanır.",
                    color = Palette.TextDim, fontSize = 13.sp,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(32.dp))
    Text(title.uppercase(), color = Palette.Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
    Spacer(Modifier.height(8.dp))
    HorizontalDivider(color = Palette.SurfaceHigh)
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Focusable(onClick = onClick, shape = RoundedCornerShape(50), selected = selected, focusedScale = 1.04f) { focused ->
        Text(
            (if (selected) "✓  " else "") + label,
            color = if (focused || selected) Palette.Text else Palette.TextDim,
            fontSize = 16.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 11.dp),
        )
    }
}

@Composable
private fun ThemeChip(t: AppTheme, selected: Boolean, onClick: () -> Unit) {
    Focusable(onClick = onClick, shape = RoundedCornerShape(50), selected = selected, focusedScale = 1.04f) { focused ->
        Row(Modifier.padding(start = 10.dp, end = 18.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(28.dp).background(t.bg, CircleShape)
                    .border(2.dp, if (selected) Palette.Accent else t.surfaceHigh, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.size(12.dp).background(t.accent, CircleShape)) }
            Spacer(Modifier.width(10.dp))
            Text(
                (if (selected) "✓ " else "") + t.name,
                color = if (focused || selected) Palette.Text else Palette.TextDim, fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

/** Ayarlar › Wi‑Fi eşitleme · Yerel (kod, diğer cihazın kodu, ağdakiler, eşleşmiş cihazlar). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WifiSyncSection(vm: AppViewModel, fieldColors: androidx.compose.material3.TextFieldColors) {
    var other by rememberSaveable { mutableStateOf("") }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            vm.refreshSyncCode()
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000)
        }
    }
    Spacer(Modifier.height(32.dp))
    Column(
        Modifier.fillMaxWidth().background(Palette.Surface, RoundedCornerShape(20.dp)).padding(22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("📶", fontSize = 22.sp)
            Spacer(Modifier.width(12.dp))
            Text("Wi‑Fi eşitleme", color = Palette.Text, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Text(" · Yerel", color = Palette.TextDim, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Chip(if (vm.autoSync) "Otomatik: açık" else "Otomatik: kapalı", selected = vm.autoSync) { vm.setAutoSyncOption(!vm.autoSync) }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Hesaplar, favoriler, kaldığın yerler, kilitli kategoriler, görünüm ayarları ve tema aynı Wi‑Fi'deki TvKutu'larla eşitlenir. " +
                "İnternete hiçbir şey gönderilmez; PIN eşitlenmez.",
            color = Palette.TextDim, fontSize = 15.sp,
        )
        Spacer(Modifier.height(16.dp))
        val left = ((vm.syncCodeExpires - now) / 1000).coerceAtLeast(0)
        Text(
            "Kod: ${vm.syncCode.substring(0, 3)} ${vm.syncCode.substring(3)}",
            color = Palette.Accent, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp,
        )
        Text(
            "Diğer cihazda \"Diğer cihazın kodu\" kutusuna bu kodu yaz. %d:%02d geçerli.".format(left / 60, left % 60),
            color = Palette.TextDim, fontSize = 14.sp,
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = other,
                onValueChange = { v -> other = v.filter { it.isDigit() || it == ' ' }.take(7) },
                label = { Text("Diğer cihazın kodu") },
                singleLine = true,
                colors = fieldColors,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.pairWithCode(other) }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            TvButton(vm.syncBusy?.let { "Bekle…" } ?: "Bağlan", primary = true, onClick = { if (vm.syncBusy == null) vm.pairWithCode(other) })
        }
        vm.syncBusy?.let { Spacer(Modifier.height(8.dp)); Text(it, color = Palette.TextDim, fontSize = 14.sp) }
        vm.syncError?.let { Spacer(Modifier.height(8.dp)); Text(it, color = Palette.Danger, fontSize = 14.sp) }

        Spacer(Modifier.height(18.dp))
        Text("AĞDA BULUNANLAR", color = Palette.TextDim, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp)
        Spacer(Modifier.height(8.dp))
        if (vm.nearby.isEmpty()) {
            Text("Aranıyor… Diğer cihazda TvKutu açık ve aynı Wi‑Fi'de olmalı.", color = Palette.TextDim, fontSize = 14.sp)
        }
        vm.nearby.forEach { d ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 6.dp).background(Palette.SurfaceHigh, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).background(Palette.Accent, CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(d.name, color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(if (vm.peers.any { it.deviceId == d.deviceId }) "Eşleşmiş" else "Kodunu yazarak bağlan", color = Palette.TextDim, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("EŞLEŞMİŞ CİHAZLAR", color = Palette.TextDim, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp)
        Spacer(Modifier.height(8.dp))
        if (vm.peers.isEmpty()) Text("Henüz eşleşmiş cihaz yok.", color = Palette.TextDim, fontSize = 14.sp)
        vm.peers.forEach { p ->
            val online = vm.isPeerOnline(p)
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp).background(Palette.SurfaceHigh, RoundedCornerShape(12.dp))
                    .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).background(if (online) Palette.Accent else Palette.TextDim.copy(alpha = 0.5f), CircleShape))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        (if (online) "● Bağlı" else "Ağda görünmüyor") + " · " +
                            (if (p.lastSyncMs > 0) "son eşitleme ${formatDateTime(p.lastSyncMs)}" else "henüz eşitlenmedi"),
                        color = Palette.TextDim, fontSize = 13.sp,
                    )
                }
                TvButton("Şimdi eşitle", onClick = { vm.syncNow(p.deviceId) })
                TvButton("Kaldır", onClick = { vm.removePeer(p.deviceId) }, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }

    vm.syncChoice?.let { choice ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { vm.cancelFirstSync() }) {
            val first = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
            Column(Modifier.widthIn(max = 640.dp).background(Palette.Surface, RoundedCornerShape(24.dp)).padding(28.dp)) {
                Text("${choice.peer.name} ile ilk eşitleme", color = Palette.Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("Hangi veriler esas alınsın?", color = Palette.Text, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(16.dp))
                listOf(
                    Triple("merge", "Birleştir (önerilen)", "İki cihazdaki her şey bir araya gelir; aynı link tek hesap olur."),
                    Triple("mine", "Bu cihazdakini kullan", "Diğer cihazdaki hesaplar ve geçmiş bu cihazdakiyle değişir."),
                    Triple("theirs", "Diğer cihazdakini kullan", "Bu cihazdaki hesaplar ve geçmiş diğer cihazdakiyle değişir."),
                ).forEachIndexed { i, (id, title, desc) ->
                    Focusable(
                        onClick = { vm.firstSync(id) },
                        shape = RoundedCornerShape(14.dp),
                        focusedScale = 1.02f,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                    ) { focused ->
                        Column(
                            Modifier.fillMaxWidth().background(if (focused) Palette.Accent else Palette.SurfaceHigh).padding(horizontal = 18.dp, vertical = 14.dp),
                        ) {
                            Text(title, color = if (focused) Palette.Ink else Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                            Text(desc, color = if (focused) Palette.Ink.copy(alpha = 0.75f) else Palette.TextDim, fontSize = 14.sp)
                        }
                    }
                }
                Text("Sonraki eşitlemelerde iki cihazdaki değişiklikler kendiliğinden birleşir.", color = Palette.TextDim, fontSize = 13.sp)
            }
        }
    }
}
