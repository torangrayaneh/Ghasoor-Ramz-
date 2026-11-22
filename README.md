# گاوصندوق رمز من

برنامه اندرویدی فارسی برای نگهداری محلی و رمزگذاری‌شده اطلاعات حساس.

## ساخت APK
GitHub Actions با هر push به شاخه main فایل Debug APK را می‌سازد. از بخش Actions، آخرین اجرای موفق را باز کنید و artifact با نام `Ghasoor-Ramz-debug-apk` را دریافت کنید.

## امنیت
داده‌های گاوصندوق با AES-GCM و کلید Android Keystore رمزگذاری می‌شوند. رمز اصلی با PBKDF2-HMAC-SHA256 ذخیره می‌شود. اثر انگشت از Android BiometricPrompt برای تأیید بازکردن برنامه استفاده می‌کند.
