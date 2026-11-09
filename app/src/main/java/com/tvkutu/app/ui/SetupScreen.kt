package com.tvkutu.app.ui

import androidx.compose.runtime.key
import androidx.compose.foundation.layout.fillMaxHeight
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

    var showAdd by remember { mutableStateOf(false) }
    var cat by rememberSaveable { mutableStateOf("hes") }
    var narrowOpen by rememberSaveable { mutableStateOf(false) }

    @Composable
    fun AddForm() {
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
    }

    @Composable
    fun QrButton(modifier: Modifier = Modifier) {
        Focusable(
            onClick = { vm.openQrSetup() },
            shape = RoundedCornerShape(50),
            focusedScale = 1.03f,
            selected = true,
            modifier = modifier,
        ) { focused ->
            Row(Modifier.padding(horizontal = 22.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("📱", fontSize = 18.sp)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Telefondan ekle (karekod)",
                    color = if (focused) Palette.Accent else Palette.Text,
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }

    @Composable
    fun AccountList() {
        vm.sources.forEach { a ->
            val isActive = a.id == vm.activeId
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp)
                    .background(Palette.Surface, RoundedCornerShape(14.dp))
                    .then(if (isActive) Modifier.border(2.dp, Palette.Accent.copy(alpha = 0.6f), RoundedCornerShape(14.dp)) else Modifier)
                    .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(44.dp).background(parseHex(a.color), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(a.name.take(1).uppercase(), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            (if (a.locked && vm.hasPin) "🔒 " else "") + a.name,
                            color = Palette.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                        )
                        if (isActive) Text("  ● Açık", color = Palette.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        when {
                            a.error.isNotEmpty() -> "Bağlanamadı: ${a.error}"
                            a.count > 0 -> "${a.count} kanal"
                            isActive -> "yükleniyor"
                            else -> a.url
                        },
                        color = if (a.error.isNotEmpty()) Palette.Danger else Palette.TextDim,
                        fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                if (!isActive) TvButton("Aç", onClick = { vm.openAccount(a.id) })
                TvButton(if (a.locked && vm.hasPin) "Kilidi aç" else "Kilitle", onClick = { vm.toggleAccountLock(a.id) })
                TvButton("Sil", onClick = { vm.deleteAccount(a.id) })
            }
        }
    }

    // ---- Kategori içerikleri ----

    @Composable
    fun AccountsPanel(wide: Boolean) {
        AccountList()
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton("＋ Hesap ekle", primary = true, onClick = { error = null; showAdd = true })
            QrButton()
            if (vm.sources.isNotEmpty()) TvButton("Bağlantıları kontrol et", onClick = { vm.checkAll(force = true) })
        }
        Spacer(Modifier.height(20.dp))
        SettingRow("Otomatik güncelleme", "Liste ve yayın akışı uygulama açıkken bu aralıkla yenilenir", wide) {
            listOf(0 to "Kapalı", 6 to "6 sa", 12 to "12 sa", 24 to "24 sa").forEach { (h, label) ->
                SegChip(label, vm.updateHours == h) { vm.setUpdateInterval(h) }
            }
        }
    }

    @Composable
    fun AppearancePanel(wide: Boolean) {
        Label("TEMA")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Themes.all.forEach { t -> ThemeCard(t, selected = vm.themeId == t.id) { vm.setTheme(t.id) } }
        }
        Spacer(Modifier.height(20.dp))
        SettingRow("Kanal kartları", "Otomatik: filmler afiş, canlı TV logo", wide) {
            listOf("auto" to "Otomatik", "always" to "Hep afiş", "never" to "Hep logo").forEach { (v, label) ->
                SegChip(label, vm.posterMode == v) { vm.setPosterModeOption(v) }
            }
        }
        if (vm.posterMode != "never") {
            SettingRow("Afişte film adı", "TV'de ad, kumanda afişin üzerine gelince görünür", wide) {
                SegChip("Afişin üstünde", vm.posterTitle == "overlay") { vm.setPosterTitleOption("overlay") }
                SegChip("Afişin altında", vm.posterTitle == "below") { vm.setPosterTitleOption("below") }
            }
        }
    }

    @Composable
    fun PlayerPanel(wide: Boolean) {
        SettingRow("Oynatıcı", "Harici: VLC, MX Player gibi yüklü bir uygulama", wide) {
            SegChip("Dahili", vm.playerMode == AppViewModel.PLAYER_INTERNAL) { vm.setPlayer(AppViewModel.PLAYER_INTERNAL) }
            SegChip("Harici uygulama", vm.playerMode == AppViewModel.PLAYER_EXTERNAL) { vm.setPlayer(AppViewModel.PLAYER_EXTERNAL) }
        }
        if (vm.playerMode == AppViewModel.PLAYER_INTERNAL) {
            SettingRow("Yayın biçimi", "Açılmayan kanallarda HLS ya da MPEG-TS'yi dene", wide) {
                listOf("auto" to "Otomatik", "hls" to "HLS", "ts" to "MPEG-TS").forEach { (f, label) ->
                    SegChip(label, vm.streamFormat == f) { vm.setFormat(f) }
                }
            }
        }
        Text(
            if (vm.playerMode == AppViewModel.PLAYER_INTERNAL)
                "Dahili oynatıcı: ses/altyazı seçimi, sarma çubuğu, kaldığın yerden devam ve telefon kumandası."
            else
                "Kanala basınca cihazdaki oynatıcılardan biri açılır. Kaldığın yerden devam ve telefon kumandası harici oynatıcıda çalışmaz.",
            color = Palette.TextDim, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 4.dp),
        )
    }

    @Composable
    fun LockPanel() {
        Row(
            Modifier.fillMaxWidth().background(Palette.Surface, RoundedCornerShape(14.dp)).padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(46.dp).background(if (vm.hasPin) Palette.Accent else Palette.SurfaceHigh, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("🔒", fontSize = 20.sp) }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(if (vm.hasPin) "PIN açık" else "PIN belirlenmedi", color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    if (vm.hasPin) "Uygulama arka plana gidince kilit yeniden devreye girer."
                    else "PIN belirleyince hesap ve kategori kilitleyebilirsin.",
                    color = Palette.TextDim, fontSize = 14.sp,
                )
            }
            if (vm.hasPin) {
                TvButton("PIN'i değiştir", onClick = { vm.changePin() })
                TvButton("PIN'i kaldır", onClick = { vm.removePin() })
            } else {
                TvButton("PIN belirle", primary = true, onClick = { vm.createPin() })
            }
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth().background(Palette.Surface, RoundedCornerShape(14.dp)).padding(18.dp)) {
            if (vm.sources.isNotEmpty()) {
                Label("HESAPLAR")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    vm.sources.forEach { a ->
                        val on = a.locked && vm.hasPin
                        SegChip((if (on) "🔒 " else "") + a.name, on, filledOff = true) { vm.toggleAccountLock(a.id) }
                    }
                }
            }
            val lockable = vm.groups.filter { it.id.startsWith("grp:") }
            if (lockable.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Label("KATEGORİLER · ${(vm.activeSource?.name ?: "").uppercase()}")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    lockable.forEach { g ->
                        val on = g.locked && vm.hasPin
                        SegChip((if (on) "🔒 " else "") + g.label, on, filledOff = true) { vm.toggleGroupLock(g.id) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Kilitli kategoriler PIN sorar ve \"Tüm kanallar\"da gizlenir.", color = Palette.TextDim, fontSize = 13.sp)
            }
        }
    }

    @Composable
    fun BackupPanel(wide: Boolean) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            BigAction("⤓ Yedeği kaydet", "Dosyaya ya da İndirilenler/TvKutu klasörüne", primary = true, modifier = Modifier.weight(1f)) { saveBackup() }
            BigAction("⤒ Geri yükle", "Dosyadan ya da cihazdaki yedekten", modifier = Modifier.weight(1f)) { openRestore() }
        }
        backupMsg?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = if (backupErr) Palette.Danger else Palette.Accent, fontSize = 14.sp)
        }
        Spacer(Modifier.height(12.dp))
        SettingRow("Geri yüklerken", "Yedekteki hesaplar ne yapılsın?", wide) {
            SegChip("Hesaplarıma ekle", !restoreReplace) { restoreReplace = false }
            SegChip("Hepsini değiştir", restoreReplace) { restoreReplace = true }
        }
        backupList?.let { list ->
            Column(Modifier.fillMaxWidth().background(Palette.Surface, RoundedCornerShape(14.dp)).padding(16.dp)) {
                Label("CİHAZDAKİ YEDEKLER")
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
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(e.label, color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
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
        }
        confirmEntry?.let { e ->
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier.fillMaxWidth().background(Palette.Danger.copy(alpha = 0.15f), RoundedCornerShape(12.dp)).padding(16.dp),
            ) {
                Text("Bu cihazdaki tüm hesaplar yedektekilerle değiştirilsin mi?", color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton("Evet, değiştir", primary = true, onClick = { confirmEntry = null; doRestore(e.read) })
                    TvButton("Vazgeç", onClick = { confirmEntry = null })
                }
            }
        }
    }

    @Composable
    fun AboutPanel() {
        Column(Modifier.fillMaxWidth().background(Palette.Surface, RoundedCornerShape(14.dp)).padding(20.dp)) {
            Text("TvKutu ${BuildConfig.VERSION_NAME}", color = Palette.Text, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(4.dp))
            Text("Güncellemeler eskisinin üzerine kurulur.", color = Palette.TextDim, fontSize = 15.sp)
            Spacer(Modifier.height(14.dp))
            InfoLine("Cihaz adı", vm.deviceName)
            vm.remoteUrl?.let { InfoLine("Telefon kumandası", it) }
            InfoLine("Veriler", "Yalnızca bu cihazda · reklam ve analitik yok")
            Spacer(Modifier.height(12.dp))
            Text(
                "TvKutu yalnızca bir oynatıcıdır; kanal, yayın veya abonelik içermez. " +
                    "Yasal olarak abone olduğun sağlayıcının listesini kullan.",
                color = Palette.TextDim, fontSize = 13.sp,
            )
        }
    }

    // ---- Kategoriler ----
    val updLabel = when (vm.updateHours) { 0 -> "güncelleme kapalı"; else -> "${vm.updateHours} saatte bir" }
    val lockCount = vm.sources.count { it.locked } + vm.groups.count { it.id.startsWith("grp:") && it.locked }
    val cats = listOf(
        Cat("hes", "👤", "Hesaplar", "${vm.sources.size} hesap · $updLabel", "Playlist linklerin. Açık olan hesap TV açılınca yüklenir."),
        Cat("gor", "🎨", "Görünüm", "${Themes.byId(vm.themeId).name} teması", "Tema, kanal kartları ve afiş düzeni."),
        Cat(
            "oyn", "▶", "Oynatıcı",
            if (vm.playerMode == AppViewModel.PLAYER_INTERNAL)
                "Dahili · " + when (vm.streamFormat) { "hls" -> "HLS"; "ts" -> "MPEG-TS"; else -> "Otomatik" }
            else "Harici uygulama",
            "Kanallar hangi oynatıcıda ve nasıl açılsın?",
        ),
        Cat("kil", "🔒", "Ebeveyn kilidi", if (vm.hasPin) "PIN açık · $lockCount kilit" else "PIN yok", "4 haneli PIN ile hesap ve kategori kilitle."),
        Cat(
            "wifi", "📶", "Wi‑Fi eşitleme",
            "${vm.peers.size} cihaz · " + if (vm.autoSync) "otomatik" else "elle",
            "Aynı Wi‑Fi'deki TvKutu'larla eşitle. İnternete hiçbir şey gönderilmez.",
        ),
        Cat("yed", "💾", "Yedekleme", "Kaydet ya da geri yükle", "Hesaplar, favoriler, kaldığın yerler ve ayarlar tek dosyada (PIN hariç)."),
        Cat("hak", "ℹ", "Hakkında", "Sürüm ${BuildConfig.VERSION_NAME}", "Sürüm ve cihaz bilgileri."),
    )
    val current = cats.firstOrNull { it.id == cat } ?: cats[0]

    @Composable
    fun CategoryContent(wide: Boolean) {
        when (current.id) {
            "hes" -> AccountsPanel(wide)
            "gor" -> AppearancePanel(wide)
            "oyn" -> PlayerPanel(wide)
            "kil" -> LockPanel()
            "wifi" -> WifiSyncSection(vm, fieldColors, wide)
            "yed" -> BackupPanel(wide)
            else -> AboutPanel()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Bg)) {
        val wide = maxWidth >= 720.dp
        if (firstRun) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = if (wide) 64.dp else 20.dp, vertical = if (wide) 40.dp else 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
                    Text("TvKutu", color = Palette.Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("Hoş geldin", color = Palette.Text, fontSize = if (wide) 34.sp else 28.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("Başlamak için IPTV hesabını (playlist linkini) ekle.", color = Palette.TextDim, fontSize = 16.sp)
                    Spacer(Modifier.height(22.dp))
                    QrButton(Modifier.focusRequester(qrFocus))
                    Spacer(Modifier.height(18.dp))
                    AccountList()
                    Spacer(Modifier.height(8.dp))
                    Text("Elle hesap ekle:", color = Palette.TextDim, fontSize = 15.sp)
                    Spacer(Modifier.height(10.dp))
                    AddForm()
                    Spacer(Modifier.height(12.dp))
                    TvButton("＋ Ekle ve aç", primary = true, onClick = { addNew() })
                    Spacer(Modifier.height(24.dp))
                }
            }
            LaunchedEffect(Unit) { runCatching { qrFocus.requestFocus() } }
        } else if (wide) {
            val navFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { navFocus.requestFocus() } }
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier.width(320.dp).fillMaxHeight().background(Palette.Surface)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 26.dp, end = 16.dp, top = 30.dp, bottom = 20.dp),
                ) {
                    Text("TvKutu", color = Palette.Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("Ayarlar", color = Palette.Text, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(18.dp))
                    cats.forEach { c ->
                        CategoryItem(
                            c, selected = c.id == current.id,
                            onFocus = { cat = c.id },
                            onClick = { cat = c.id; runCatching { focusManager.moveFocus(FocusDirection.Right) } },
                            modifier = if (c.id == current.id) Modifier.focusRequester(navFocus) else Modifier,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    TvButton("← Kapat", onClick = { vm.closeSettings() }, modifier = Modifier.fillMaxWidth())
                }
                key(current.id) {
                    Column(
                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())
                            .padding(horizontal = 40.dp, vertical = 30.dp),
                    ) {
                        Text(current.label, color = Palette.Text, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                        Spacer(Modifier.height(4.dp))
                        Text(current.desc, color = Palette.TextDim, fontSize = 16.sp)
                        Spacer(Modifier.height(20.dp))
                        CategoryContent(wide = true)
                        Spacer(Modifier.height(30.dp))
                    }
                }
            }
        } else {
            // Telefon: önce kategori listesi, dokununca ayrıntı.
            BackHandler(enabled = narrowOpen) { narrowOpen = false }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 20.dp),
            ) {
                if (!narrowOpen) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Ayarlar", color = Palette.Text, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                        TvButton("Kapat", onClick = { vm.closeSettings() })
                    }
                    Spacer(Modifier.height(16.dp))
                    cats.forEach { c ->
                        CategoryItem(c, selected = false, onFocus = null, onClick = { cat = c.id; narrowOpen = true })
                    }
                } else {
                    TvButton("‹ Ayarlar", onClick = { narrowOpen = false })
                    Spacer(Modifier.height(10.dp))
                    Text(current.label, color = Palette.Text, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(4.dp))
                    Text(current.desc, color = Palette.TextDim, fontSize = 15.sp)
                    Spacer(Modifier.height(16.dp))
                    CategoryContent(wide = false)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showAdd) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showAdd = false }) {
            val first = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
            Column(
                Modifier.widthIn(max = 620.dp).background(Palette.Surface, RoundedCornerShape(24.dp)).padding(26.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text("Hesap ekle", color = Palette.Text, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(4.dp))
                Text("Linki telefondan göndermek daha kolay: karekodu okut.", color = Palette.TextDim, fontSize = 14.sp)
                Spacer(Modifier.height(16.dp))
                Box(Modifier.focusRequester(first)) { QrButton() }
                Spacer(Modifier.height(16.dp))
                AddForm()
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton("＋ Ekle ve aç", primary = true, onClick = {
                        addNew()
                        if (error == null) showAdd = false
                    })
                    TvButton("Vazgeç", onClick = { showAdd = false })
                }
            }
        }
    }
}

private data class Cat(val id: String, val icon: String, val label: String, val sub: String, val desc: String)

@Composable
private fun CategoryItem(c: Cat, selected: Boolean, onFocus: (() -> Unit)?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Focusable(
        onClick = onClick, onFocus = onFocus, shape = RoundedCornerShape(14.dp), focusedScale = 1.02f,
        modifier = modifier.fillMaxWidth().padding(bottom = 4.dp),
    ) { focused ->
        Row(
            Modifier.fillMaxWidth().background(if (selected) Palette.SurfaceHigh else Color.Transparent)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(36.dp).background(if (selected || focused) Palette.Accent else Palette.Bg, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) { Text(c.icon, fontSize = 17.sp, color = if (selected || focused) Palette.Ink else Palette.Text) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(c.label, color = Palette.Text, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    c.sub, color = if (selected) Palette.Accent else Palette.TextDim, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Ayar satırı: solda ad ve açıklama, sağda seçenekler (dar ekranda alta iner). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingRow(label: String, help: String, wide: Boolean, controls: @Composable () -> Unit) {
    val mod = Modifier.fillMaxWidth().padding(bottom = 10.dp).background(Palette.Surface, RoundedCornerShape(14.dp))
        .padding(horizontal = 18.dp, vertical = 14.dp)
    if (wide) {
        Row(mod, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text(label, color = Palette.Text, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                Text(help, color = Palette.TextDim, fontSize = 14.sp)
            }
            Row(
                Modifier.background(Palette.Bg, RoundedCornerShape(50)).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) { controls() }
        }
    } else {
        Column(mod) {
            Text(label, color = Palette.Text, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
            Text(help, color = Palette.TextDim, fontSize = 14.sp)
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { controls() }
        }
    }
}

/** Seçenek düğmesi: seçili olan vurgu renginde dolu. */
@Composable
private fun SegChip(label: String, selected: Boolean, filledOff: Boolean = false, onClick: () -> Unit) {
    Focusable(onClick = onClick, shape = RoundedCornerShape(50), focusedScale = 1.06f) { focused ->
        Text(
            label,
            color = when {
                selected -> Palette.Ink
                focused -> Palette.Text
                filledOff -> Palette.Text
                else -> Palette.TextDim
            },
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(if (selected) Palette.Accent else if (filledOff) Palette.SurfaceHigh else Color.Transparent)
                .padding(horizontal = 16.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text, color = Palette.TextDim, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.6.sp)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun InfoLine(label: String, value: String) {
    HorizontalDivider(color = Palette.SurfaceHigh)
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(label, color = Palette.TextDim, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun BigAction(title: String, sub: String, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Focusable(onClick = onClick, shape = RoundedCornerShape(14.dp), focusedScale = 1.03f, modifier = modifier) { focused ->
        Column(
            Modifier.fillMaxWidth().background(if (primary) Palette.Accent else Palette.Surface).padding(18.dp),
        ) {
            Text(title, color = if (primary) Palette.Ink else Palette.Text, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
            Text(sub, color = if (primary) Palette.Ink.copy(alpha = 0.75f) else Palette.TextDim, fontSize = 14.sp)
        }
    }
}

@Composable
private fun ThemeCard(t: AppTheme, selected: Boolean, onClick: () -> Unit) {
    Focusable(onClick = onClick, shape = RoundedCornerShape(14.dp), selected = selected, focusedScale = 1.06f) { _ ->
        Column(
            Modifier.width(118.dp).background(t.bg)
                .then(if (selected) Modifier.border(3.dp, Palette.Accent, RoundedCornerShape(14.dp)) else Modifier)
                .padding(10.dp),
        ) {
            Row(Modifier.fillMaxWidth().height(28.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.weight(2f).fillMaxHeight().background(t.surfaceHigh, RoundedCornerShape(6.dp)))
                Box(Modifier.weight(1f).fillMaxHeight().background(t.accent, RoundedCornerShape(6.dp)))
            }
            Spacer(Modifier.height(8.dp))
            Text((if (selected) "✓ " else "") + t.name, color = t.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Ayarlar › Wi‑Fi eşitleme · Yerel (kod, diğer cihazın kodu, ağdakiler, eşleşmiş cihazlar). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WifiSyncSection(vm: AppViewModel, fieldColors: androidx.compose.material3.TextFieldColors, wide: Boolean) {
    var other by rememberSaveable { mutableStateOf("") }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            vm.refreshSyncCode()
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000)
        }
    }
    SettingRow("Otomatik eşitle", "Hesaplar, favoriler, kaldığın yerler, kilitler, görünüm ve tema · PIN hariç", wide) {
        SegChip("Açık", vm.autoSync) { vm.setAutoSyncOption(true) }
        SegChip("Kapalı", !vm.autoSync) { vm.setAutoSyncOption(false) }
    }
    Column(
        Modifier.fillMaxWidth().background(Palette.Surface, RoundedCornerShape(14.dp)).padding(18.dp),
    ) {
        Text("BU CİHAZIN KODU", color = Palette.TextDim, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp)
        val left = ((vm.syncCodeExpires - now) / 1000).coerceAtLeast(0)
        Text(
            "${vm.syncCode.substring(0, 3)} ${vm.syncCode.substring(3)}",
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
