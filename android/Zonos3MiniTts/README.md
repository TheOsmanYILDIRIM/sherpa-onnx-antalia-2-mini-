# ZONOS3 Mini TR Android Q2 deneyi

Bu dizin Antalia Mini uygulamasından bağımsız bir deneydir. Amaç ZONOS3-Mini'nin düşük bit GGUF sürümünü ARM64 Android'de llama.cpp/libmtmd ile çalıştırmak ve modeli APK'dan tamamen ayırmaktır.

## Model paketi

APK hiçbir .gguf dosyası içermez. Uygulamada **Model ZIP seç / değiştir** düğmesi Android Storage Access Framework dosya seçicisini açar. ZIP uygulamanın noBackupFilesDir alanına kopyalanır; manifestteki dosya boyutları ve SHA-256 değerleri doğrulanmadan etkinleştirilmez. APK güncellemesi bu dizini silmez, dolayısıyla aynı model tekrar indirilmez. Uygulamayı kaldırmak model verisini de kaldırır.

Paket kökünde zonos3-package.json ve onun gösterdiği iki GGUF bulunur: Q2_K backbone ve quantize edilmiş ZONOS3 mmproj/codec. ZIP slip, bilinmeyen ek dosya, toplam açılmış boyut >900 MiB, boyut veya SHA uyuşmazlığı reddedilir. Etkin model atomik next -> current geçişiyle değiştirilir.

## Native runtime

CI, ggml-org/llama.cpp commit e6d624b9a4b95c6b52f5ca384d70b75581f9821a sürümünü sabitler. Yalnız ARM64 CPU backend, llama, llama-common ve libmtmd derlenir. JNI akışı resmi llama-tts örneğinin ZONOS3 üretim yolunu izler ve ham F32 PCM'yi PCM16'ya dönüştürür. Uygulama aynı zamanda Android TextToSpeechService olarak kaydolur.

## Deney quantları

zonos3-q2-model-package.yml aynı Q2_K backbone ile mmproj için Q8_0 ve Q4_K_M adaylarını üretir. Her aday kısa bir Türkçe llama-tts smoke testi geçmeden ZIP artifact olarak yayımlanmaz. Smoke testi kalite kabulü değildir; gerçek telefon dinleme testi ayrı kanıttır.

Model ZIP'i APK sürümünden bağımsızdır; uygulama güncellenirken mevcut current model dizini korunur.
