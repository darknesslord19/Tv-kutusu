# TvKutu — Android TV için IPTV oynatıcı

Kumandayla kullanılan, sade ve hızlı bir IPTV oynatıcı. Kendi m3u/m3u8 listeni ve isteğe bağlı XMLTV yayın akışını eklersin; uygulama kanalları, şu an yayında olanı ve programı gösterir.

> **TvKutu yalnızca bir oynatıcıdır.** Kanal, yayın, liste veya abonelik içermez ve satmaz. Yasal olarak abone olduğun bir IPTV sağlayıcısının listesini kullanmalısın.

## İndir

[⬇ En son APK](../../releases/latest) — `TvKutu.apk`

Her `main` dalına gönderilen değişiklikte GitHub Actions APK'yı otomatik derler ve yeni bir sürüm (Release) olarak yayınlar.

## Özellikler (v0.4)

- **Kumanda için tasarlandı:** her ekran ok tuşları, OK ve Geri ile çalışır.
- **Birden çok liste:** istediğin kadar m3u/m3u8 linki ekle (en fazla 20). Her listenin kategorileri ana ekranda o listenin başlığı altında ayrı görünür; bir liste açılmazsa diğerleri çalışmaya devam eder.
- **Kanal ızgarası:** logo, şu anki program ve ilerleme çubuğu; kategoriler listedeki sırayla.
- **Kanal logoları:** `tvg-logo` / `logo` (göreli yollar da), yoksa EPG'deki kanal ikonu; PNG, JPG, WebP ve SVG. Logo yoksa kanal adının baş harfleri görünür.
- **Favoriler:** oynatıcıda OK → "Favorilere ekle". Favoriler kategorisi en üstte görünür.
- **Yayın akışı (EPG):** XMLTV (`xml`, `xml.gz`, `zip`) ya da listedeki `url-tvg` linki. Kanallar `tvg-id` veya isimle eşleştirilir.
- **Oynatıcı:** dahili Media3 ExoPlayer (HLS ve MPEG-TS) ya da harici uygulama (VLC, MX Player…). Yayın biçimi Otomatik / HLS / MPEG-TS seçilebilir. Kopmada otomatik yeniden bağlanma (5 deneme), donanım çözücü sorununda yedek çözücü, anlaşılır hata mesajları.
- **Dokunmatik kontroller:** telefonda videoya dokununca oynat/duraklat, önceki/sonraki, kanallar, yayın akışı ve favori butonları çıkar; panelde çözünürlük ve codec görünür.
- **Kanal başına başlıklar:** `#EXTVLCOPT:http-user-agent`, `http-referrer` ve `url|User-Agent=…&Referer=…` biçimi.
- **Telefondan kurulum (karekod):** TV ekranındaki karekodu telefonla okut, açılan sayfaya bir ya da birden çok linki yapıştır (toplu yapıştırma da var), "Gönder"e dokun. Sayfa yalnızca ev ağında çalışır, tek kullanımlık kodla korunur ve gönderimden sonra kapanır.
- **Otomatik güncelleme:** liste ve yayın akışı 6, 12 veya 24 saatte bir yenilenir (ya da kapalı).
- TV, telefon ve tablette çalışır; telefonda dikey düzen desteklenir.
- Hesap, reklam, analitik yok. Linkler yalnızca cihazda saklanır.

## Kurulum

1. Releases'tan `TvKutu.apk` dosyasını indir.
2. TV kutusuna aktar (USB bellek, dosya yöneticisi, "Send files to TV" vb.) ve aç.
3. İstenirse "bilinmeyen kaynaklardan uygulama yükleme" iznini ver.
4. TvKutu'yu aç, **Telefondan kur (karekod)**'a bas ve telefonunla karekodu okut. İstersen linki kumandayla elle de girebilirsin.

Gereksinim: Android 7.0 veya üstü (Android TV / Google TV; telefonda da çalışır).

## Oynatıcıda kumanda

| Tuş | İşlev |
|---|---|
| OK | Bilgi paneli: favori, duraklat, kanallar, yayın akışı |
| ▲ ▼ / CH+ CH− | Önceki / sonraki kanal |
| ◀ | Kanal listesi |
| ▶ / Menu / Guide | Kanalın yayın akışı |
| Oynat/Duraklat | Duraklat / devam |
| Geri | Paneli kapat · oynatıcıdan çık |

## Geliştirme

Kotlin, Jetpack Compose, Media3 ExoPlayer, OkHttp, Coil.

```
app/src/main/java/com/tvkutu/app/
  MainActivity.kt        ekran geçişleri
  AppViewModel.kt        uygulama durumu
  data/M3uParser.kt      m3u/m3u8 ayrıştırıcı
  data/XmltvParser.kt    XMLTV (xml/gz/zip) akış ayrıştırıcı
  data/Repository.kt     indirme, önbellek, ayarlar, favoriler
  data/SetupServer.kt    telefondan kurulum için yerel ağ sunucusu
  ui/                    Ayarlar, Karekod kurulumu, Ana ekran, Oynatıcı
```

Yerelde derlemek için projeyi Android Studio'da aç ya da `gradle assembleRelease` çalıştır.

## Yol haritası

Arşiv/catch-up, program araması, uygulama içi güncelleme, İngilizce arayüz.
