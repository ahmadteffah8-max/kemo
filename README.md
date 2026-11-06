# KEMO — Neon Pixel Calculator (ORIGINA)

Android 9+ (minSdk 28) calculator with an original pixel-art digital girl, liquid-glass keys,
neon ripples, pixel explosions and an original electronic sound set + chiptune BGM.
Pure Android framework (no libraries) → small and fast, runs well on weak phones.

## الحصول على APK (أسهل طريقة — GitHub Actions)
1. ارفع كل محتويات هذا المجلد إلى مستودع `kemo` (فرع `main`).
2. افتح تبويب **Actions** → سيعمل `Build KEMO APK` تلقائياً (حوالي 3–5 دقائق).
3. بعد النجاح، حمّل الـ APK من:
   `https://github.com/<USER>/kemo/releases/latest/download/kemo.apk`
   (أو من Actions → آخر تشغيل → Artifacts → `kemo-apk`).

## أو عبر Android Studio
File → Open → اختر هذا المجلد → انتظر Sync → Build → Build APK(s).

## الهيكل
- `app/src/main/java/com/kemo/calc/` — الكود (Calc = المحرك، KemoView = الشخصية، GlassButton، DisplayView، FxView، SoundManager…)
- `app/src/main/res/raw/` — الأصوات (مولّدة بالكود، أصلية)
- `tools/gen_sprites.py`, `tools/gen_sounds.py` — سكربتات توليد الشخصية والأصوات (يمكن تعديلها وإعادة تشغيلها: `python3 tools/gen_sounds.py .`)

## التحكم بالصوت
زر ♪ أعلى اليمين: مستوى مستقل لكل من: الكبسات، الحذف، النتيجة، المسح، الموسيقى.
اضغط مطولاً على زر الحذف لمسح كل شيء.
