<div align="center">

# Atölye Android

**Telefonun için kurulum merkezi.** Uygulamalarını seç, tek dokunuşla kur.

[Atölye](https://github.com/MustafaCanDere/Auto-Setup)'nin Android sürümü.

</div>

## Neden?

Yeni bir telefon kurarken Play Store'da tek tek arama yapmak zaman kaybı. Atölye mühendislik, yazılım ve iş araçlarını tek ekranda toplar: işaretle, **Seçilenleri Kur**'a bas, sırayla kursun.

- **701 uygulama, 27 kategori:** müzik ve podcast, oyunlar, çocuk ve aile, araç ve sürüş, eğitim, finans ve bankalar, sağlık ve spor, seyahat, alışveriş ve yemek, sosyal medya, haber, eğlence ve dizi-film, akıllı ev, iş yeri ve ofis, yapay zeka, siber güvenlik ve ağ, kodlama, DevOps ve uzak erişim, tasarım, donanım, elektrik-elektronik ve IoT, makine/CAD, inşaat ve harita, kimya/biyo/tıp, uzay ve havacılık, matematik ve bilim, verimlilik.
- **26 hazır profil:** Temel Paket, Türkiye Paketi, Açık Kaynak Paketi, Müzik, Oyuncu, Çocuk & Aile, Araç, Öğrenci, Finans, Sağlık, Seyahat, Akıllı Ev, İş Yeri, Yapay Zeka, Siber Güvenlik, Yazılım, DevOps, Donanım, Tasarım ve her mühendislik dalı için bir profil.
- **İki kurulum yolu:**
  - **F-Droid** (113 açık kaynak uygulama): APK resmî F-Droid deposundan indirilir, işlemcine uygun sürüm seçilir ve Android'in kurulum ekranı açılır. Sen sadece **Yükle**'ye basarsın.
  - **Play Store:** uygulamanın sayfası açılır, **Yükle**'ye basıp geri dönersin, Atölye sıradakine geçer.
- **Zaten kurulu olanları tanır**, atlar ve kartında ✓ gösterir.
- Canlı arama, kategori filtresi, kurulum ekranında ilerleme, indirme hızı ve sayaçlar (kuruldu / zaten kurulu / Play'de bekliyor / atlandı).
- Seçimlerini hatırlar. Açık ve koyu tema.

> Android, mağaza dışındaki uygulamaların kullanıcı onayı olmadan kurulmasına izin vermez. Bu yüzden her uygulama için sistemin kendi onay ekranı çıkar; Atölye geri kalan her şeyi (bulma, indirme, sıraya koyma, kontrol) yapar.

## Kurulum

1. [Releases](../../releases/latest) sayfasından en yeni `Atolye-*.apk` dosyasını telefonuna indir.
2. Aç ve kur. Android "bilinmeyen kaynak" izni isteyebilir.
3. İlk F-Droid kurulumunda Atölye'ye **bu kaynaktan uygulama yükleme** izni ver (bir kerelik).

## Proje yapısı

| Dosya | Görev |
|---|---|
| `app/src/main/assets/catalog.json` | Uygulama, kategori ve profil veritabanı |
| `app/src/main/assets/index.html` | Arayüz ve kurulum sırası |
| `app/src/main/java/com/atolye/app/MainActivity.java` | WebView ve JS köprüsü (kurulu kontrolü, Play Store, ayarlar) |
| `app/src/main/java/com/atolye/app/FdroidInstaller.java` | F-Droid'den indirme, ABI seçimi, PackageInstaller ile kurulum |
| `scripts/check_packages.py` | Paket kimliklerinin F-Droid ve Play'de var olduğunu doğrular |
| `tests/` | Katalog tutarlılık testleri |
| `.github/workflows/apk.yml` | `main`'e her push'ta APK derler ve Releases'e yükler |

## Uygulama eklemek

`catalog.json` içindeki `programs` listesine ekle:

```json
{"id": "termux", "name": "Termux", "category": "dev", "source": "fdroid",
 "pkg": "com.termux", "desc": "Linux terminali", "default": true}
```

`source` için F-Droid'de varsa `fdroid`, yoksa `play` yaz. Sonra testleri çalıştır:

```bash
python -m unittest discover -s tests -v
python scripts/check_packages.py
```

## İmza

APK, depo ayarlarında şu sırlar varsa sabit anahtarla imzalanır: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Yoksa her derleme farklı bir hata ayıklama anahtarı kullanır; bu durumda yeni sürümü kurmadan önce eskisini kaldırman gerekebilir.

## Lisans

MIT.
