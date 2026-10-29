# MuratTv

M3U listesini (varsayılan: M3UYakala Gist'i) açan sade IPTV oynatıcı.
Kotlin Multiplatform + Compose: Android, Android TV ve iOS aynı arayüzü kullanır.

Varsayılan liste adresi kodda değildir. Derlemede `MURATTV_LIST_URL` ortam değişkeninden (GitHub Actions'ta aynı
adlı secret) ya da `local.properties`'teki `murattv.listUrl=` satırından okunur. İkisi de yoksa uygulama ilk açılışta
liste adresini sorar.

## Kumanda

| Tuş | Oynatırken | Liste açıkken |
| --- | --- | --- |
| Yukarı / Aşağı | Önceki / sonraki kanal (açılmayan kanallar atlanır) | Listede gez |
| OK, Sağ | Kanal listesini aç (video küçük pencereye geçer) | OK: kanalı küçük pencerede aç, tekrar OK: tam ekran |
| Sol | Son izlenen kanala dön | Kategori sütununa geç |
| Sağ (kanal sütununda) | | Menü: düzenle / taşı / sil |
| Menü / Bilgi | Görüntü ve ses paneli | |
| OK basılı tut | Kaynak seçme penceresi | Kanal menüsü |
| 0-9 | O numaralı kanala git (1,5 sn bekler ya da OK) | İmleci o numaraya götür |
| Geri | Çıkış | Listeyi kapat |

Numaralar, seçili kategorideki sıradır.

## Arama, yayın akışı, görüntü ve ses

- **Kanal ara:** kategori sütununun ikinci satırı. OK ile klavye açılır, Aşağı ile sonuçlara inilir.
  Türkçe harf ve büyük/küçük harf farkı gözetmez.
- **Yayın akışı:** şimdi oynayan program (ilerleme çubuğuyla) ve sıradaki program; kanal bilgisinde ve listede.
  Varsayılan kaynak epgshare Türkiye listesi; "Liste adresini değiştir" ekranından değiştirilebilir.
  Listede tvg-id olmadığından kanallar adlarıyla eşleştirilir ("SHOW TV HD" = "Show TV").
- **Açılmayan kanallar:** açılmayan kaynak "açılmadı" diye işaretlenir; kanal geçişinde yalnızca hiçbir kaynağı
  açılmayan kanal atlanır. Kaynak açılınca işaret kalkar; 2 saat sonra kendiliğinden yeniden denenir.
- **Kaynak seçimi:** kanal her zaman en hızlı açılan kaynağıyla açılır. Açıldıktan sonra diğer kaynakları arka planda
  denenir (durumları güncellenir); belirgin şekilde daha kaliteli biri açılırsa ilk 30 sn içinde bir kez sessizce ona
  geçilir. İzlerken kaynak bir daha kendiliğinden değişmez. Elle seçilen ya da sabitlenen kaynağa dokunulmaz.
  Bu kontrolden sonra oynayan kaynak kanal için hatırlanır ve sonraki açılışta önce o denenir; o kaynak açılmaz
  olursa sıra kendiliğinden hıza göre sıralamaya döner.
- **Kaynakları tara:** tüm linkler aynı anda en çok 24, aynı sunucuya en çok 12 bağlantıyla denenir. Tarama sürerken
  yayın durur ve kanal değiştirilemez; Geri ya da dokunmatikte "☰ Kanallar" taramayı iptal eder.
- **Arka plan kontrolü:** elle taramaya gerek kalmasın diye uygulama 2 dakikada bir 30 linki (en çok 3'ü aynı anda)
  dener: önce hiç denenmemişler, sonra açılmayanlar (saatte bir), sonra çalışanlar (12 saatte bir). İzlenen panelin
  linkleri izlerken denenmez.
- **Bu ağda açılmayan linkler:** sunucu erişimi reddederse (403, ör. IP'ye bağlı kabloweb token'ları) link ölü
  sayılmaz; "bu ağda açılmıyor" yazar ve sona alınır.
- **Ölü linkler:** 3 günden uzun süredir açılmayan linkler (token varsa) Gist'teki `olu.txt`'ye yazılır.
  `gist_guncelle.py` bunları bilgisayardan yeniden dener; hâlâ açılmıyorsa Gist'e yüklemez (yerel listede kalır),
  açılıyorsa listeye geri koyar.
- **Kaynak penceresi:** açılmayan kaynaklar gizlidir; kalan kaynaklar en kaliteliden aşağı, aynı kalitede hızlı olan
  önce sıralanır.
- **Görüntü ve ses:** "Görüntü ve ses" satırı ya da kumandada Menü tuşu. Görüntü: Sığdır / Doldur / Uzat.
  Ses: yayında birden fazla ses dili varsa sırayla seçilir (iOS'ta henüz yok).

## Yedek linkler

Listede hiçbir kanal birleştirilmez; her link kendi satırında durur ve ayrıca açılabilir.
Açılan link 10 sn içinde açılmazsa ya da hata verirse aynı kanalın yedeğine kendiliğinden geçilir
("… açılmadı · yedek 1/2 açılıyor"). Listede yedeği olan kanalların yanında "+N yedek" yazar.

Bir link ancak şu durumda yedek sayılır:
- adı aynı (HD/FHD, parantez ve "Yedek N" ekleri hariç) ve **aynı kategoride**,
- **ve** ya yayın akışında o adla gerçek bir TV kanalı var ya da adında "Yedek" geçiyor,
- **ve** `.mp4/.mkv` gibi dosya linki değil; açılınca da canlı yayın olduğu doğrulanır.

Böylece aynı adı taşıyan farklı program yayınları ("TRT Canlı Akış" gibi) birbirinin yedeği sayılmaz.
Bir kanala yedeğini kendin tanımlamak için listede adını "Kanal Adı Yedek 1" yap.
Otomatik geçiş "Görüntü ve ses" panelinden kapatılabilir.

## Anında kanal geçişi

İzlenen kanalın bir altındaki ve bir üstündeki kanal arka planda ilk 2 saniyesine kadar yüklenir.
Yukarı/aşağı ile geçişte görüntü ~0,2 sn'de gelir (önceden yüklenmemiş kanalda birkaç saniye). Yalnızca Android.

## Sıralama eşitleme

Kanal sırası Gist'te `murattv.json` dosyasında tutulur; TV, telefon ve tablette aynı olur.
Değişiklikten 3 sn sonra gönderilir (GitHub token gerekir); token'ı olmayan cihazlar yalnızca okur.
Elle eşitlemek için "Görüntü ve ses" → "Sıralamayı eşitle". En son değişiklik geçerli olur.

## Kanal düzenleme (ad, kategori, logo)

Listede kanalın üzerinde Sağ → "✎ Düzenle". Açılınca kanala uygun logolar kendiliğinden aranır ve resim olarak
dizilir: resme gelip OK'a basman yeterli ("Logo yok" logoyu kaldırır, "Adresi elle yaz" adres girmek içindir).
Kategori yazılabilir ya da alttaki var olan kategorilerden seçilebilir.

Kaydedince değişiklik bu cihazda hemen uygulanır; token varsa Gist'teki `playlist.m3u`'ya da yazılır ve
`duzenlemeler.json`'a kaydedilir. PC'de `gist_guncelle.py` bu düzenlemeleri yerel `playlist.m3u`'ya da işler,
böylece yerel liste Gist'i güncellediğinde düzenlemeler kaybolmaz.

Kanallar linkin `?` öncesiyle tanınır: token'ı her gün değişen linklerde sıralama, silme ve düzenleme kaybolmaz.

## Logo düzeltme (PC)

`python logo_duzelt.py` açılmayan ya da hiç olmayan logoları tv-logo/tv-logos deposunda arar ve listeler
(Türkiye → International → ABD → İngiltere → Avrupa → diğer bölgeler; yurt içi kategoriler yalnızca Türkiye).
`python logo_duzelt.py --uygula` yerel `playlist.m3u`'yu günceller (yedek: `playlist.logo-yedek.m3u`).

## Geri tuşu

Tam ekranda Geri kanal listesini açar; uygulama kapanmaz. Çıkmak için kategori sütununun en altındaki "Çıkış".

## Kanal silme

Listede kanalın üzerindeyken Sağ → "Kanalı sil" → "Evet, sil". Kanal bu cihazda hemen gizlenir.

Gist'ten de silinmesi için Liste adresini değiştir ekranına bir GitHub token'ı gir:
github.com/settings/tokens → "Generate new token (classic)" → yalnızca **gist** kutusunu işaretle.
Token cihazda saklanır; başka yetki verme.

Uygulama silinen kanalı Gist'teki `playlist.m3u`'dan çıkarır ve tüm satırlarıyla `silinen.txt`'ye taşır.
PC'de `gist_guncelle.py` bu kanalları Gist'e yüklemez ama yerel `playlist.m3u`'dan silmez.

Geri almak için: kategori sütununun en altında "↺ Silinenleri geri getir". Cihazda gizlenenler geri gelir,
Gist'teki `silinen.txt`'deki kanallar listeye geri eklenir.

## Sıralama

Kanalın üzerinde Sağ → "⇅ Taşı" → Yukarı/Aşağı ile yer değiştir → OK.
Sıralama bu cihazda saklanır (Gist'teki sıra değişmez). "Sıralamayı sıfırla" ile listedeki sıraya dönülür.

Liste her açılışta Gist'ten kendiliğinden güncellenir. Elle yenilemek için: listeyi aç → Sol → kategori sütununun en üstündeki "↻ Listeyi yenile".

Dokunmatik: dokun = liste, yukarı/aşağı kaydır = kanal değiştir, sağa kaydır = son kanal,
ekrana uzun bas = görüntü ve ses, listede kanala uzun bas = menü.

## Android

```bash
./gradlew :composeApp:assembleDebug
```

APK: `composeApp/build/outputs/apk/debug/composeApp-debug.apk`

## iOS (IPA)

Mac + Xcode gerekir; Windows'ta iOS derlenemez.

1. Mac'te bu klasörü aç, `./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64` ile derlemeyi doğrula.
2. Xcode'da yeni bir iOS App projesi oluştur (`iosApp`), `iosApp/iosApp/iOSApp.swift` dosyasını kullan.
3. Build Phases'e "Run Script" ekle: `cd "$SRCROOT/.." && ./gradlew :composeApp:embedAndSignAppleFrameworkForXcode`
4. Build Settings > Framework Search Paths: `$(SRCROOT)/../composeApp/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)`
5. Assets > AppIcon'a `iosApp/AppIcon-1024.png` dosyasını sürükle; Display Name: `MuratTv`.
6. Info.plist'e `NSAppTransportSecurity > NSAllowsArbitraryLoads = YES` ekle (http yayınlar için).
7. Product > Archive ile IPA al.
