# گاوصندوق رمز من

برنامه اندرویدی فارسی برای نگهداری محلی و رمزگذاری‌شده اطلاعات حساس.

## ساخت APK
GitHub Actions با هر push به شاخه main فایل Debug APK را می‌سازد. از بخش Actions، آخرین اجرای موفق را باز کنید و artifact با نام `Ghasoor-Ramz-debug-apk` را دریافت کنید.

## امنیت
داده‌های گاوصندوق با AES-GCM و کلید Android Keystore رمزگذاری می‌شوند. رمز اصلی با PBKDF2-HMAC-SHA256 ذخیره می‌شود. اثر انگشت از Android BiometricPrompt برای تأیید بازکردن برنامه استفاده می‌کند.

## امضای دائمی APK و بروزرسانی بدون حذف

برای اینکه همه نسخه‌های بعدی روی نسخه نصب‌شده بروزرسانی شوند، باید چهار Secret در مسیر
**Settings → Secrets and variables → Actions → New repository secret**
ثبت شوند:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_STORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

فایل keystore و مقادیر این چهار Secret را محرمانه نگه دارید و هرگز در مخزن عمومی قرار ندهید.
Workflow فقط APK نوع Release را با همین کلید می‌سازد و در نبود Secretها عمداً متوقف می‌شود؛
دیگر APK آزمایشی با امضای موقت تولید نمی‌کند.

## پشتیبان خودکار

پس از ورود با رمز اصلی یا اثر انگشت، برنامه یک فایل رمزگذاری‌شده با نام
`GhasoorRamz-AutoBackup.gvb` در `Downloads/GhasoorRamz` می‌سازد و پس از هر تغییر اطلاعات آن را به‌روزرسانی می‌کند.
برای بازیابی پس از نصب مجدد، از بخش «گزارش‌ها → پشتیبان‌گیری و بازیابی امن» فایل را انتخاب و رمز اصلی را وارد کنید.
