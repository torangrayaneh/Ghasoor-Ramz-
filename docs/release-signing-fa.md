# راه‌اندازی امضای دائمی APK

برای اینکه اندروید نسخه جدید را به‌عنوان به‌روزرسانی بپذیرد، تمام APKهای انتشار باید با **همان فایل keystore** امضا شوند. فایل keystore و رمزهای آن را در مخزن عمومی GitHub قرار ندهید.

## یک‌بار ساخت کلید انتشار

روی رایانه‌ای که JDK نصب دارد، این دستور را اجرا کنید:

```bash
keytool -genkeypair -v -keystore ghasoor-release.jks -alias ghasoor -keyalg RSA -keysize 2048 -validity 10000
```

فایل `ghasoor-release.jks` و دو رمز واردشده را در یک محل امن و جداگانه پشتیبان‌گیری کنید. این کلید را برای نسخه‌های بعدی عوض نکنید.

## ثبت GitHub Actions secrets

محتوای Base64 فایل را بسازید:

- macOS/Linux: `base64 -w 0 ghasoor-release.jks`
- Windows PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("ghasoor-release.jks"))`

در GitHub به مسیر Settings → Secrets and variables → Actions → New repository secret بروید و این چهار Secret را بسازید:

| Secret name | مقدار |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | خروجی Base64 فایل keystore |
| `ANDROID_STORE_PASSWORD` | رمز keystore |
| `ANDROID_KEY_ALIAS` | `ghasoor` |
| `ANDROID_KEY_PASSWORD` | رمز کلید |

پس از ثبت Secretها، از Actions، گردش‌کار Build signed Android release APK را با Run workflow اجرا کنید. فایل خروجی در بخش Artifacts همان اجرا قرار می‌گیرد.

**مهم درباره نصب فعلی:** اگر نسخه نصب‌شده قبلی با کلید موقتی GitHub Actions امضا شده باشد، APK جدید با کلید دائمی نمی‌تواند روی آن نصبِ به‌روزرسانی شود. برای اثبات سازگاری باید گواهی APK فعلی با گواهی کلید دائمی مقایسه شود. اگر کلید قبلی در دسترس نباشد، ممکن است فقط یک‌بار نصب مجدد لازم شود. قبل از هر حذف، از اطلاعات برنامه نسخه پشتیبان رمزگذاری‌شده بگیرید.
