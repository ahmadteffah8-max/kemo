# Simple Player

مشغل صوت وفيديو بسيط وخفيف وسريع لأندرويد (Android 7.0+)، مبني على AndroidX Media3 ExoPlayer.
بدون إعلانات، بدون إنترنت، بدون حساب.

A simple, light and fast audio/video player for Android, built on AndroidX Media3 ExoPlayer.
No ads, no internet permission, no account.

## الواجهتان / Two separate UIs

التطبيق يكتشف نوع الملف تلقائياً ويفتح الواجهة المناسبة ولا يخلط بينهما.

- **الموسيقى (MP3, OGG, WAV, FLAC, AAC, M4A):** غلاف كبير مربع، اسم الأغنية والفنان، تشغيل/إيقاف، السابق/التالي، شريط التقدم والمدة، التحكم بالصوت.
- **الفيديو (MP4, MKV, MOV, WEBM):** ملء الشاشة، أفقي وعمودي، Zoom بالقرص، ضغطتان يميناً +10 ثوانٍ ويساراً -10 ثوانٍ، زر سرعة ×2 / ×1، الصوت، شريط التقدم، ملاءمة/ملء، وزر ملء الشاشة.

## البناء / Build

```
gradle assembleRelease
```

الناتج: `app/build/outputs/apk/release/app-release.apk`

كل دفعة (push) إلى `main` تبني الـ APK تلقائياً عبر GitHub Actions وتنشره في **Releases → latest** باسم `simple-player.apk`.

## ملاحظة / Note

AVI: لا يدعمه Media3 مباشرةً إلا لبعض الملفات (الصيغ الداخلية غير المدعومة تُظهر رسالة خطأ).
