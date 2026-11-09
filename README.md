# TvKutu — Android TV için IPTV oynatıcı

Kumandayla kullanılan, sade ve hızlı bir IPTV oynatıcı. Kendi m3u/m3u8 listeni ve isteğe bağlı XMLTV yayın akışını eklersin; uygulama kanalları, şu an yayında olanı ve programı gösterir.

> **TvKutu yalnızca bir oynatıcıdır.** Kanal, yayın, liste veya abonelik içermez ve satmaz. Yasal olarak abone olduğun bir IPTV sağlayıcısının listesini kullanmalısın.

## İndir

[⬇ En son APK](../../releases/latest) — `TvKutu.apk`

Her `main` dalına gönderilen değişiklikte GitHub Actions APK'yı otomatik derler ve yeni bir sürüm (Release) olarak yayınlar.

## Özellikler (v0.9)

- **Kumanda için tasarlandı:** her ekran ok tuşları, OK ve Geri ile çalışır.
- **Hesaplar:** her playlist linki ayrı bir hesap (renkli kutucuk). TV son açılan hesapla başlar; üst çubuktaki hesap adına basınca "Hesap Seç" ekranı açılır. Favoriler, son kanal ve EPG her hesapta ayrı.
- **Hesap durumu:** kutucukta kanal sayısı ya da kırmızı "Bağlanamadı" (abonelik bitmiş, sunucu kapalı).
- **Ebeveyn kilidi:** 4 haneli PIN ile hesap ve kategori kilitleme; uygulama arka plana gidince kilit yeniden devreye girer.
- **Telefon kumandası:** TV'deki karekodu okut; telefonda gerçek kumanda (yön tuşları + OK, kanal ▲▼, ses +/−/sessiz, geri, ana ekran, menü, ⏯, ±10 sn, şu an oynayan ve dokunarak sarma), Hesap Seç (seç, ekle, düzenle, sil), kanal arama ("TV'de aç") ve yedek. Adres kalıcıdır, yer imlerine eklenebilir.
- **Yedek ve geri yükleme:** Ayarlar › Yedekleme ya da telefon sayfası. Hesaplar, favoriler, kaldığın yerler, kilitli kategoriler ve görünüm ayarları tek dosyada (PIN hariç). Dosya seçici olmayan kutularda İndirilenler/TvKutu klasörüne kaydedilir; ayrıca cihazın içinde son 5 otomatik yedek tutulur. "Hesaplarıma ekle" ya da "Hepsini değiştir".
- **Afiş kartları:** film ve dizi kategorilerinde afiş kartı tamamen doldurur (otomatik algılama, ya da hep afiş / hep logo). Ad afişin üstünde ya da altında. TV'de solda kategoriler, sağda afiş ızgarası; kumandanın üzerindeki afiş büyür ve adını gösterir.
- **Kategori satırları:** "Tüm kanallar" seçiliyken her kategori yatay kayan bir satır olarak listelenir ("Tümü" ile kategoriye geçilir).
- **Kanal ızgarası:** logo, şu anki program ve ilerleme çubuğu; kategoriler listedeki sırayla.
- **Kanal logoları:** `tvg-logo` / `logo` (göreli yollar da), yoksa EPG'deki kanal ikonu; PNG, JPG, WebP ve SVG. Logo yoksa kanal adının baş harfleri görünür.
- **Favoriler:** oynatıcıda OK → "Favorilere ekle". Favoriler kategorisi en üstte görünür.
- **Yayın akışı (EPG):** XMLTV (`xml`, `xml.gz`, `zip`) ya da listedeki `url-tvg` linki. Kanallar `tvg-id` veya isimle eşleştirilir.
- **Kaldığın yerden izle:** yarıda kalan film/diziler "▶ Devam et" kategorisinde ve "Tüm kanallar"ın en üstünde, ilerleme çubuğu ve "18 dk kaldı" ile. Açınca "Kaldığın yerden devam et / Baştan başla / Listeden kaldır" sorulur (10 sn sonra kendiliğinden devam eder). Her hesapta ayrı tutulur.
- **Sarma çubuğu:** film/dizide süre çubuğu, geçen/toplam süre ve bitiş saati; ◀ ▶ ile 10 sn (basılı tutunca 30–60 sn), telefonda çubuğa dokun/sürükle ya da çift dokun. Canlı yayında çubuk programın ilerlemesini gösterir. Ses ve altyazı parçası seçimi.
- **Oynatıcı:** dahili Media3 ExoPlayer (HLS ve MPEG-TS) ya da harici uygulama (VLC, MX Player…). Yayın biçimi Otomatik / HLS / MPEG-TS seçilebilir. Kopmada otomatik yeniden bağlanma (5 deneme), donanım çözücü sorununda yedek çözücü, anlaşılır hata mesajları.
- **Dokunmatik kontroller:** telefonda videoya dokununca oynat/duraklat, önceki/sonraki, kanallar, yayın akışı ve favori butonları çıkar; panelde çözünürlük ve codec görünür.
- **Kanal başına başlıklar:** `#EXTVLCOPT:http-user-agent`, `http-referrer` ve `url|User-Agent=…&Referer=…` biçimi.
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

## İmza

APK'lar `app/signing/tvkutu-release.jks` anahtarıyla imzalanır (şifreli PKCS12). Şifre repoda değil, GitHub'da
**Settings › Secrets and variables › Actions** altında `KEYSTORE_PASSWORD` adlı gizli değişkende durur.
Böylece her yeni sürüm eskisinin üzerine kurulur ve veriler korunur. Şifreyi güvenli bir yerde sakla.

## Geliştirme

Kotlin, Jetpack Compose, Media3 ExoPlayer, OkHttp, Coil.

```
app/src/main/java/com/tvkutu/app/
  MainActivity.kt        ekran geçişleri
  AppViewModel.kt        uygulama durumu
  data/M3uParser.kt      m3u/m3u8 ayrıştırıcı
  data/XmltvParser.kt    XMLTV (xml/gz/zip) akış ayrıştırıcı
  data/Repository.kt     indirme, önbellek, ayarlar, favoriler
  data/RemoteServer.kt   telefon kumandası için yerel ağ sunucusu
  data/RemoteApi.kt      kumanda API'si (PIN kuralları, yedek)
  assets/remote.html     telefon sayfası
  ui/                    Ayarlar, Karekod kurulumu, Ana ekran, Oynatıcı
```

Yerelde derlemek için projeyi Android Studio'da aç ya da `gradle assembleRelease` çalıştır.

## Yol haritası

Arşiv/catch-up, program araması, uygulama içi güncelleme, İngilizce arayüz.
