@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.torangrayaneh.ghasoor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.content.ContentValues
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import android.util.Base64
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import android.net.Uri
import android.widget.Toast
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Navy = Color(0xFF0B1220)
private val Panel = Color(0xFF151F30)
private val Gold = Color(0xFFD6B36A)
private val Muted = Color(0xFF9AA8BC)
private val Cream = Color(0xFFF4F0E6)

data class VaultEntry(
    val title: String,
    val category: String,
    val username: String,
    val secret: String,
    val note: String,
    val updatedAt: Long = System.currentTimeMillis(),
    val history: List<String> = emptyList(),
    val cardPhoto: String = ""
)

class MainActivity : FragmentActivity() {
    var cameraResultCallback: ((Boolean) -> Unit)? = null
    var cameraPermissionResultCallback: ((Boolean) -> Unit)? = null
    var documentResultCallback: ((Int, Uri?) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(11, 18, 32)
        window.navigationBarColor = android.graphics.Color.rgb(11, 18, 32)
        setContent { VaultTheme { VaultApp(this) } }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1202) {
            cameraPermissionResultCallback?.invoke(
                grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            )
            cameraPermissionResultCallback = null
        }
    }

    @Deprecated("Legacy camera activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1201) {
            cameraResultCallback?.invoke(resultCode == android.app.Activity.RESULT_OK)
        } else if (requestCode == 1301 || requestCode == 1302) {
            documentResultCallback?.invoke(requestCode, if (resultCode == RESULT_OK) data?.data else null)
        }
    }
}

@Composable
private fun VaultTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = Gold, onPrimary = Navy, secondary = Gold, background = Navy,
        surface = Panel, onSurface = Cream, onBackground = Cream,
        surfaceVariant = Color(0xFF202D40), onSurfaceVariant = Muted,
        outline = Color(0xFF34445B), error = Color(0xFFFF7777)
    )
    MaterialTheme(colorScheme = colors, content = content)
}

private class VaultStore(private val activity: FragmentActivity) {
    private val prefs = activity.getSharedPreferences("vault_settings", Context.MODE_PRIVATE)
    private val alias = "ghasoor_vault_key"
    fun hasMaster() = prefs.contains("master_hash")

    fun setMaster(password: String): Boolean = try {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = derive(password, salt)
        prefs.edit().putString("master_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("master_hash", Base64.encodeToString(hash, Base64.NO_WRAP)).apply()
        if (!hasKey()) createKey()
        if (!prefs.contains("vault_blob")) save(emptyList())
        true
    } catch (_: Exception) { false }

    fun saveAutoBackupPassword(password: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("auto_backup_password",
            Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)).apply()
    }

    fun getAutoBackupPassword(): String? {
        return try {
            val encoded = prefs.getString("auto_backup_password", null) ?: return null
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            if (packed.size <= 12) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
            String(cipher.doFinal(packed.copyOfRange(12, packed.size)), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    fun verify(password: String): Boolean = try {
        val salt = Base64.decode(prefs.getString("master_salt", "") ?: "", Base64.NO_WRAP)
        val expected = Base64.decode(prefs.getString("master_hash", "") ?: "", Base64.NO_WRAP)
        java.security.MessageDigest.isEqual(expected, derive(password, salt))
    } catch (_: Exception) { false }

    private fun derive(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, 180_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }
    private fun hasKey(): Boolean {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return ks.containsAlias(alias)
    }
    private fun createKey() {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        generator.generateKey()
    }
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return ks.getKey(alias, null) as SecretKey
    }
    fun load(): List<VaultEntry> {
        val blob = prefs.getString("vault_blob", null) ?: return emptyList()
        val packed = Base64.decode(blob, Base64.NO_WRAP)
        val iv = packed.copyOfRange(0, 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        val array = JSONArray(String(cipher.doFinal(packed.copyOfRange(12, packed.size)), Charsets.UTF_8))
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val oldHistory = o.optJSONArray("history") ?: JSONArray()
            VaultEntry(o.optString("title"), o.optString("category"), o.optString("username"),
                o.optString("secret"), o.optString("note"), o.optLong("updatedAt", System.currentTimeMillis()),
                (0 until oldHistory.length()).map { oldHistory.getString(it) }, o.optString("cardPhoto", ""))
        }
    }
    fun save(entries: List<VaultEntry>) {
        if (!hasKey()) createKey()
        val array = JSONArray()
        entries.forEach { e ->
            val h = JSONArray()
            e.history.forEach { h.put(it) }
            array.put(JSONObject().put("title", e.title).put("category", e.category)
                .put("username", e.username).put("secret", e.secret).put("note", e.note)
                .put("updatedAt", e.updatedAt).put("history", h).put("cardPhoto", e.cardPhoto))
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(array.toString().toByteArray(Charsets.UTF_8))
        prefs.edit().putString("vault_blob", Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)).apply()
    }

    fun createPortableBackup(entries: List<VaultEntry>, backupPassword: String): ByteArray {
        val array = JSONArray()
        entries.forEach { e ->
            val h = JSONArray()
            e.history.forEach { h.put(it) }
            array.put(JSONObject().put("title", e.title).put("category", e.category)
                .put("username", e.username).put("secret", e.secret).put("note", e.note)
                .put("updatedAt", e.updatedAt).put("history", h).put("cardPhoto", e.cardPhoto))
        }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val portableKey = SecretKeySpec(derive(backupPassword, salt), "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, portableKey)
        val encrypted = cipher.doFinal(array.toString().toByteArray(Charsets.UTF_8))
        return byteArrayOf(71, 86, 66, 49) + salt + cipher.iv + encrypted
    }

    fun restorePortableBackup(data: ByteArray, backupPassword: String): List<VaultEntry> {
        require(data.size > 32 && data[0] == 71.toByte() && data[1] == 86.toByte() &&
            data[2] == 66.toByte() && data[3] == 49.toByte()) { "فایل پشتیبان معتبر نیست." }
        val salt = data.copyOfRange(4, 20)
        val iv = data.copyOfRange(20, 32)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(derive(backupPassword, salt), "AES"), GCMParameterSpec(128, iv))
        val array = JSONArray(String(cipher.doFinal(data.copyOfRange(32, data.size)), Charsets.UTF_8))
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val oldHistory = o.optJSONArray("history") ?: JSONArray()
            VaultEntry(o.optString("title"), o.optString("category"), o.optString("username"),
                o.optString("secret"), o.optString("note"), o.optLong("updatedAt", System.currentTimeMillis()),
                (0 until oldHistory.length()).map { oldHistory.getString(it) }, o.optString("cardPhoto", ""))
        }    }

    /**
     * Writes a portable, password-encrypted backup to Downloads/GhasoorRamz.
     * A stable filename is updated in place so repeated saves do not fill storage.
     * On Android 10+ the Downloads entry survives uninstalling this app.
     */
    fun writeAutomaticBackup(entries: List<VaultEntry>, backupPassword: String) {
        require(backupPassword.length >= 6) { "رمز اصلی برای پشتیبان‌گیری کافی نیست." }
        val bytes = createPortableBackup(entries, backupPassword)
        val fileName = "GhasoorRamz-AutoBackup.gvb"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = activity.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val relativePath = Environment.DIRECTORY_DOWNLOADS + "/GhasoorRamz/"
            val existing = resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(fileName, relativePath),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) Uri.withAppendedPath(collection, cursor.getLong(0).toString()) else null
            }
            val uri = existing ?: resolver.insert(collection, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }) ?: throw IllegalStateException("ساخت فایل پشتیبان در Downloads ممکن نشد.")
            try {
                resolver.openOutputStream(uri, "wt")?.use { it.write(bytes); it.flush() }
                    ?: throw IllegalStateException("نوشتن فایل پشتیبان ممکن نشد.")
                if (existing == null) {
                    resolver.update(uri, ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }, null, null)
                }
            } catch (e: Exception) {
                if (existing == null) resolver.delete(uri, null, null)
                throw e
            }
        } else {
            val folder = File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: activity.filesDir, "GhasoorRamz")
            if (!folder.exists() && !folder.mkdirs()) throw IllegalStateException("ساخت پوشه پشتیبان ممکن نشد.")
            File(folder, fileName).writeBytes(bytes)
        }
    }

}

private enum class VaultTab(val title: String) {
    HOME("خانه"), BANKS("بانک‌ها"), APPS("برنامه‌ها"), HISTORY("سوابق رمزها"), REPORTS("گزارش‌ها")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultApp(activity: MainActivity) {
    val store = remember { VaultStore(activity) }
    var hasMaster by remember { mutableStateOf(store.hasMaster()) }
    var unlocked by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf(emptyList<VaultEntry>()) }
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<VaultEntry?>(null) }
    var tab by remember { mutableStateOf(VaultTab.HOME) }
    var revealed by remember { mutableStateOf(setOf<String>()) }
    var showAbout by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var cameraFile by remember { mutableStateOf<File?>(null) }
    var latestCardPhoto by remember { mutableStateOf<String?>(null) }
    var cameraErrorMessage by remember { mutableStateOf("") }
    var showBackupDialog by remember { mutableStateOf(false) }
    var backupPassword by remember { mutableStateOf("") }
    var sessionBackupPassword by remember { mutableStateOf("") }
    var backupMessage by remember { mutableStateOf("") }
    var pendingBackup by remember { mutableStateOf<ByteArray?>(null) }
    DisposableEffect(activity) {
        activity.cameraResultCallback = { success: Boolean ->
            val file = cameraFile
            if (success && file != null && file.exists() && file.length() > 0L) {
                val original = BitmapFactory.decodeFile(file.absolutePath)
                if (original != null) {
                    val ratio = minOf(1f, 1280f / maxOf(original.width, original.height))
                    val resized = if (ratio < 1f) Bitmap.createScaledBitmap(original, (original.width * ratio).toInt(), (original.height * ratio).toInt(), true) else original
                    val output = ByteArrayOutputStream()
                    resized.compress(Bitmap.CompressFormat.JPEG, 78, output)
                    latestCardPhoto = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
                    if (resized !== original) resized.recycle()
                    original.recycle()
                } else cameraErrorMessage = "خواندن عکس کارت ممکن نشد؛ دوباره عکس بگیرید."
            } else if (success) {
                cameraErrorMessage = "دوربین عکس را ذخیره نکرد؛ دوباره تلاش کنید."
            } else if (file != null && (!file.exists() || file.length() == 0L)) {
                cameraErrorMessage = "عکسی از دوربین دریافت نشد؛ دوباره تلاش کنید."
            }
            file?.delete()
            cameraFile = null
        }
        activity.documentResultCallback = { requestCode: Int, uri: Uri? ->
            try {
                if (uri != null && requestCode == 1301) {
                    val bytes = pendingBackup ?: throw IllegalStateException("نسخه پشتیبان آماده نیست.")
                    val output = activity.contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("ذخیره فایل انجام نشد.")
                    output.use { stream ->
                        stream.write(bytes)
                        stream.flush()
                    }
                    backupMessage = "انجام شد؛ فایل پشتیبان رمزگذاری‌شده با موفقیت ذخیره شد."
                    Toast.makeText(activity, backupMessage, Toast.LENGTH_LONG).show()
                } else if (requestCode == 1301 && uri == null) {
                    backupMessage = "ذخیره فایل لغو شد؛ فایل پشتیبان در گوشی ذخیره نشد."
                    Toast.makeText(activity, backupMessage, Toast.LENGTH_LONG).show()
                } else if (uri != null && requestCode == 1302) {
                    val bytes = activity.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("خواندن فایل ممکن نشد.")
                    val restored = store.restorePortableBackup(bytes, backupPassword)
                    store.save(restored)
                    entries = restored
                    try { store.writeAutomaticBackup(restored, backupPassword) } catch (_: Exception) { }
                    backupMessage = "انجام شد؛ بازیابی ${restored.size} مورد با موفقیت انجام شد."
                    Toast.makeText(activity, backupMessage, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                backupMessage = "عملیات ناموفق بود: ${e.localizedMessage ?: "رمز نادرست یا فایل نامعتبر"}"
            } finally {
                pendingBackup = null
            }
        }
        onDispose {
            activity.cameraResultCallback = null
            activity.documentResultCallback = null
        }
    }
    val authenticateBackup: (String, () -> Unit) -> Unit = { promptTitle, onAuthenticated ->
        val biometricManager = BiometricManager.from(activity)
        if (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) != BiometricManager.BIOMETRIC_SUCCESS) {
            backupMessage = "برای این عملیات ابتدا اثر انگشت را در تنظیمات گوشی فعال کنید."
        } else {
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        onAuthenticated()
                    }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        backupMessage = "تأیید اثر انگشت لغو شد: $errString"
                    }
                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed()
                        backupMessage = "اثر انگشت شناسایی نشد؛ دوباره تلاش کنید."
                    }
                })
            prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
                .setTitle(promptTitle)
                .setSubtitle("برای ادامه، هویت خود را با اثر انگشت تأیید کنید")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText("انصراف")
                .build())
        }
    }
    val launchCamera: () -> Unit = {
        try {
            val file = File(activity.cacheDir, "card_${System.currentTimeMillis()}.jpg")
            cameraFile = file
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
            val intent = Intent("android.media.action.IMAGE_CAPTURE").apply {
                putExtra("output", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                clipData = android.content.ClipData.newUri(activity.contentResolver, "card_photo", uri)
            }
            cameraErrorMessage = ""
            if (intent.resolveActivity(activity.packageManager) == null) {
                throw IllegalStateException("برنامه دوربین در گوشی پیدا نشد")
            }
            activity.startActivityForResult(intent, 1201)
        } catch (e: Exception) {
            cameraErrorMessage = "اجرای دوربین ناموفق بود: ${e.localizedMessage ?: "خطای نامشخص"}"
            cameraFile?.delete()
            cameraFile = null
        }
    }
    if (!unlocked) {
        Box(Modifier.fillMaxSize().background(Navy).padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(shape = RoundedCornerShape(28.dp), color = Panel, modifier = Modifier.size(104.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Shield, null, tint = Gold, modifier = Modifier.size(66.dp))
                    }
                }
                Text("گاوصندوق رمز من", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Cream)
                Text("امنیت اطلاعات محرمانه شما", color = Muted)
                Spacer(Modifier.height(8.dp))
                Text(if (hasMaster) "برای ورود، رمز اصلی را وارد کنید" else "برای شروع، رمز اصلی خود را بسازید", color = Cream)
                OutlinedTextField(password, { password = it }, label = { Text("رمز اصلی") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = {
                    if (password.length < 6) message = "رمز اصلی باید حداقل ۶ کاراکتر باشد."
                    else if (!hasMaster) {
                        if (store.setMaster(password)) {
                            hasMaster = true; unlocked = true; entries = store.load()
                            sessionBackupPassword = password
                            store.saveAutoBackupPassword(password)
                            try { store.writeAutomaticBackup(entries, sessionBackupPassword) } catch (_: Exception) { }
                            password = ""
                        }
                        else message = "ساخت گاوصندوق ناموفق بود."
                    } else if (store.verify(password)) {
                        try {
                            entries = store.load(); unlocked = true
                            sessionBackupPassword = password
                            store.saveAutoBackupPassword(password)
                            try { store.writeAutomaticBackup(entries, sessionBackupPassword) } catch (_: Exception) { }
                            password = ""; message = ""
                        }
                        catch (_: Exception) { message = "خواندن اطلاعات ممکن نشد." }
                    } else message = "رمز اصلی نادرست است."
                }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Navy)) {
                    Text(if (hasMaster) "ورود امن" else "ساخت گاوصندوق", fontWeight = FontWeight.Bold)
                }
                if (hasMaster && BiometricManager.from(activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS) {
                    OutlinedButton(onClick = {
                        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
                            object : BiometricPrompt.AuthenticationCallback() {
                                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                    super.onAuthenticationSucceeded(result)
                                    try {
                                        entries = store.load()
                                        sessionBackupPassword = store.getAutoBackupPassword().orEmpty()
                                        if (sessionBackupPassword.isNotBlank()) {
                                            try { store.writeAutomaticBackup(entries, sessionBackupPassword) } catch (_: Exception) { }
                                        }
                                        unlocked = true; message = ""
                                    } catch (_: Exception) { message = "بازکردن گاوصندوق ناموفق بود." }
                                }
                            })
                        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("ورود به گاوصندوق")
                            .setSubtitle("با اثر انگشت هویت خود را تأیید کنید")
                            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK)
                            .setNegativeButtonText("انصراف").build())
                    }, modifier = Modifier.fillMaxWidth()) { Text("ورود با اثر انگشت") }
                }
                if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
                Text("اطلاعات به‌صورت رمزگذاری‌شده روی همین دستگاه نگهداری می‌شود.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    } else {
        Scaffold(containerColor = Navy, topBar = {
            TopAppBar(
                title = { Column { Text("گاوصندوق رمز من", fontWeight = FontWeight.Bold, color = Cream); Text("مدیریت امن اطلاعات محرمانه", style = MaterialTheme.typography.labelSmall, color = Muted) } },
                actions = {
                    IconButton(onClick = { showAbout = true }) { Icon(Icons.Filled.Info, "درباره ما", tint = Gold) }
                    IconButton(onClick = { unlocked = false; entries = emptyList(); revealed = emptySet(); sessionBackupPassword = "" }) { Icon(Icons.Filled.Lock, "قفل برنامه", tint = Gold) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy)
            )
        }, bottomBar = {
            NavigationBar(containerColor = Panel, contentColor = Gold) {
                val tabs = listOf(VaultTab.HOME, VaultTab.BANKS, VaultTab.APPS, VaultTab.HISTORY, VaultTab.REPORTS)
                val icons = listOf(Icons.Filled.Dashboard, Icons.Filled.AccountBalance, Icons.Filled.Apps, Icons.Filled.History, Icons.Filled.Assessment)
                tabs.forEachIndexed { index, item ->
                    NavigationBarItem(selected = tab == item, onClick = { tab = item },
                        icon = { Icon(icons[index], contentDescription = item.title) },
                        label = { Text(item.title, style = MaterialTheme.typography.labelSmall) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Gold, selectedTextColor = Gold,
                            indicatorColor = Color(0xFF34445B), unselectedIconColor = Muted, unselectedTextColor = Muted))
                }
            }
        }, floatingActionButton = {
            if (tab == VaultTab.HOME || tab == VaultTab.BANKS || tab == VaultTab.APPS) {
                FloatingActionButton(onClick = { editing = null; showAdd = true }, containerColor = Gold, contentColor = Navy) {
                    Icon(Icons.Filled.Add, "افزودن رمز")
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                when (tab) {
                    VaultTab.HOME -> {
                        Spacer(Modifier.height(10.dp))
                        Surface(shape = RoundedCornerShape(24.dp), color = Panel, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.VerifiedUser, null, tint = Gold, modifier = Modifier.size(32.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Column { Text("خوش آمدید", color = Muted); Text("امنیت در یک نگاه", color = Cream, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                                }
                                Spacer(Modifier.height(18.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Metric("کل موارد", entries.size.toString())
                                    Metric("بانک‌ها", entries.count { it.category == "بانک و کارت بانکی" }.toString())
                                    Metric("برنامه‌ها", entries.count { it.category == "اپلیکیشن و شبکه اجتماعی" }.toString())
                                }
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                        Text("دسترسی سریع", color = Cream, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            QuickTile("بانک‌ها", Icons.Filled.AccountBalance, Modifier.weight(1f)) { tab = VaultTab.BANKS }
                            QuickTile("برنامه‌ها", Icons.Filled.Apps, Modifier.weight(1f)) { tab = VaultTab.APPS }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            QuickTile("سوابق رمز", Icons.Filled.History, Modifier.weight(1f)) { tab = VaultTab.HISTORY }
                            QuickTile("گزارش‌ها", Icons.Filled.Assessment, Modifier.weight(1f)) { tab = VaultTab.REPORTS }
                        }
                        Spacer(Modifier.height(18.dp))
                        SectionTitle("آخرین اطلاعات")
                        val recent = entries.sortedByDescending { it.updatedAt }.take(3)
                        if (recent.isEmpty()) EmptyState("هنوز اطلاعاتی ثبت نشده است.")
                        else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(recent) { entry -> EntryCard(entry, revealed.contains(entry.title), { revealed = if (revealed.contains(entry.title)) revealed - entry.title else revealed + entry.title }, { editing = entry; showAdd = true }, { entries = entries - entry; store.save(entries); if (sessionBackupPassword.isNotBlank()) { try { store.writeAutomaticBackup(entries, sessionBackupPassword) } catch (_: Exception) { backupMessage = "ذخیره اطلاعات انجام شد، اما تهیه پشتیبان خودکار ناموفق بود." } } }) }
                        }
                    }
                    VaultTab.BANKS -> EntryList("حساب‌ها و کارت‌های بانکی", entries.filter { it.category == "بانک و کارت بانکی" }, revealed, { key -> revealed = if (key in revealed) revealed - key else revealed + key }, { editing = it; showAdd = true }, { entry -> entries = entries - entry; store.save(entries) })
                    VaultTab.APPS -> EntryList("اپلیکیشن‌ها و شبکه‌های اجتماعی", entries.filter { it.category == "اپلیکیشن و شبکه اجتماعی" || it.category == "ایمیل" || it.category == "سایر" }, revealed, { key -> revealed = if (key in revealed) revealed - key else revealed + key }, { editing = it; showAdd = true }, { entry -> entries = entries - entry; store.save(entries) })
                    VaultTab.HISTORY -> {
                        Spacer(Modifier.height(16.dp)); SectionTitle("سوابق تغییر رمزها")
                        val historyItems = entries.filter { it.history.isNotEmpty() }.sortedByDescending { it.updatedAt }
                        if (historyItems.isEmpty()) EmptyState("هنوز سابقه تغییر رمزی ثبت نشده است. با ویرایش رمزها، نسخه قبلی در این بخش نگهداری می‌شود.")
                        else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(historyItems) { e ->
                                VaultCard {
                                    Text(e.title, color = Cream, fontWeight = FontWeight.Bold)
                                    Text(e.category, color = Gold, style = MaterialTheme.typography.labelMedium)
                                    Text("تعداد نسخه‌های قبلی: ${e.history.size}", color = Muted)
                                    e.history.forEachIndexed { i, old -> Text("نسخه ${i + 1}: ••••••••", color = Muted, style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                    }
                    VaultTab.REPORTS -> {
                        Spacer(Modifier.height(16.dp)); SectionTitle("گزارش وضعیت گاوصندوق")
                        VaultCard {
                            Text("گزارش کلی", color = Gold, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(12.dp))
                            ReportRow("مجموع اطلاعات ذخیره‌شده", entries.size.toString())
                            ReportRow("حساب‌ها و کارت‌های بانکی", entries.count { it.category == "بانک و کارت بانکی" }.toString())
                            ReportRow("برنامه‌ها و شبکه‌های اجتماعی", entries.count { it.category == "اپلیکیشن و شبکه اجتماعی" }.toString())
                            ReportRow("ایمیل‌ها", entries.count { it.category == "ایمیل" }.toString())
                            ReportRow("موارد دارای سابقه رمز", entries.count { it.history.isNotEmpty() }.toString())
                            ReportRow("آخرین بروزرسانی", if (entries.isEmpty()) "—" else date(entries.maxOf { it.updatedAt }))
                            Spacer(Modifier.height(16.dp))
                            OutlinedButton(onClick = { showBackupDialog = true }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Filled.Security, null); Spacer(Modifier.width(8.dp)); Text("پشتیبان‌گیری و بازیابی امن")
                            }
                            Button(onClick = {
                                val report = buildString {
                                    appendLine("گزارش گاوصندوق رمز من")
                                    appendLine("تاریخ تهیه: ${date(System.currentTimeMillis())}")
                                    appendLine("مجموع موارد: ${entries.size}")
                                    appendLine("بانک‌ها: ${entries.count { it.category == "بانک و کارت بانکی" }}")
                                    appendLine("برنامه‌ها: ${entries.count { it.category == "اپلیکیشن و شبکه اجتماعی" }}")
                                    appendLine("ایمیل‌ها: ${entries.count { it.category == "ایمیل" }}")
                                    appendLine("موارد دارای سابقه رمز: ${entries.count { it.history.isNotEmpty() }}")
                                    appendLine()
                                    appendLine("فهرست عناوین:")
                                    entries.forEach { appendLine("- ${it.title} | ${it.category} | آخرین بروزرسانی: ${date(it.updatedAt)}") }
                                    appendLine()
                                    appendLine("گزارش شامل رمزها و اطلاعات محرمانه نیست.")
                                }
                                val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "گزارش گاوصندوق رمز من"); putExtra(Intent.EXTRA_TEXT, report) }
                                context.startActivity(Intent.createChooser(intent, "اشتراک‌گذاری گزارش"))
                            }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Navy)) {
                                Icon(Icons.Filled.Share, null); Spacer(Modifier.width(8.dp)); Text("تهیه و اشتراک گزارش")
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("گزارش فقط شامل آمار و عناوین است و رمزهای محرمانه را صادر نمی‌کند.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    if (showBackupDialog) {
        AlertDialog(
            onDismissRequest = { showBackupDialog = false; backupMessage = "" },
            title = { Text("پشتیبان‌گیری رمزگذاری‌شده") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("از تمام اطلاعات، رمزها، سوابق و عکس کارت‌ها یک فایل رمزگذاری‌شده ساخته می‌شود. برای بازیابی در گوشی دیگر همین رمز لازم است.", color = Muted)
                    OutlinedTextField(value = backupPassword, onValueChange = { backupPassword = it },
                        label = { Text("رمز اختصاصی فایل پشتیبان (حداقل ۸ کاراکتر)") },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (backupMessage.isNotBlank()) Text(backupMessage, color = if (backupMessage.contains("موفق") || backupMessage.contains("انجام شد")) Gold else MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                Column {
                    TextButton(onClick = {
                        if (backupPassword.length < 8) backupMessage = "رمز پشتیبان باید حداقل ۸ کاراکتر باشد."
                        else authenticateBackup("تأیید اثر انگشت برای پشتیبان‌گیری") {
                            try {
                                val bytes = store.createPortableBackup(entries, backupPassword)
                                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                                val fileName = "GhasoorRamz-Backup-$stamp.gvb"
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    val values = ContentValues().apply {
                                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                                        put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/GhasoorRamz")
                                        put(MediaStore.Downloads.IS_PENDING, 1)
                                    }
                                    val uri = activity.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                                        ?: throw IllegalStateException("ساخت فایل در پوشه Downloads ممکن نشد.")
                                    try {
                                        activity.contentResolver.openOutputStream(uri)?.use { it.write(bytes); it.flush() }
                                            ?: throw IllegalStateException("نوشتن فایل پشتیبان ممکن نشد.")
                                        val ready = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                                        activity.contentResolver.update(uri, ready, null, null)
                                    } catch (e: Exception) {
                                        activity.contentResolver.delete(uri, null, null)
                                        throw e
                                    }
                                } else {
                                    val folder = File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: activity.filesDir, "GhasoorRamz")
                                    if (!folder.exists() && !folder.mkdirs()) throw IllegalStateException("ساخت پوشه پشتیبان ممکن نشد.")
                                    File(folder, fileName).writeBytes(bytes)
                                }
                                backupMessage = "انجام شد؛ فایل پشتیبان رمزگذاری‌شده به‌صورت خودکار ساخته شد."
                                Toast.makeText(activity, backupMessage, Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                backupMessage = "پشتیبان‌گیری ناموفق بود: ${e.localizedMessage ?: "خطای نامشخص"}"
                            }
                        }
                    }) { Text("پشتیبان‌گیری خودکار با اثر انگشت") }
                    TextButton(onClick = {
                        if (backupPassword.length < 8) backupMessage = "رمز پشتیبان باید حداقل ۸ کاراکتر باشد."
                        else authenticateBackup("تأیید اثر انگشت برای بازیابی") {
                            try {
                                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                    addCategory(Intent.CATEGORY_OPENABLE); type = "application/octet-stream"
                                }
                                activity.startActivityForResult(intent, 1302)
                            } catch (e: Exception) {
                                backupMessage = "بازکردن فایل پشتیبان ناموفق بود: ${e.localizedMessage}"
                            }
                        }
                    }) { Text("بازیابی با اثر انگشت") }
                }
            },
            dismissButton = { TextButton(onClick = { showBackupDialog = false; backupMessage = ""; backupPassword = "" }) { Text("بستن") } }
        )
    }

    if (showAbout) {
        AlertDialog(onDismissRequest = { showAbout = false },
            title = { Text("درباره ما") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("گاوصندوق رمز من", color = Gold, fontWeight = FontWeight.Bold)
                Text("برنامه‌نویس: kasra yarahmadi")
                Text("ایمیل: torangrayaneh@gmail.com", color = Cream)
                Text("نسخه ۱.۱", color = Muted)
            } },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text("بستن") } })
    }

    if (showAdd) {
        var title by remember { mutableStateOf(editing?.title ?: "") }
        var category by remember { mutableStateOf(editing?.category ?: if (tab == VaultTab.BANKS) "بانک و کارت بانکی" else "اپلیکیشن و شبکه اجتماعی") }
        var username by remember { mutableStateOf(editing?.username ?: "") }
        var secret by remember { mutableStateOf(editing?.secret ?: "") }
        var note by remember { mutableStateOf(editing?.note ?: "") }
        var cardPhoto by remember { mutableStateOf(editing?.cardPhoto ?: "") }
        LaunchedEffect(latestCardPhoto) {
            latestCardPhoto?.let {
                cardPhoto = it
                latestCardPhoto = null
                cameraErrorMessage = ""
            }
        }
        AlertDialog(
            onDismissRequest = { showAdd = false; editing = null },
            title = { Text(if (editing == null) "افزودن اطلاعات جدید" else "ویرایش اطلاعات") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title = it }, label = { Text("عنوان؛ مثلاً بانک ملت") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { expanded = true }) { Text(category) }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            listOf("بانک و کارت بانکی", "اپلیکیشن و شبکه اجتماعی", "ایمیل", "سایر").forEach {
                                DropdownMenuItem(text = { Text(it) }, onClick = { category = it; expanded = false })
                            }
                        }
                    }
                    OutlinedTextField(username, { username = it }, label = { Text("نام کاربری / شناسه / شماره کارت") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(secret, { secret = it }, label = { Text("رمز یا اطلاعات محرمانه") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(note, { note = it }, label = { Text("یادداشت") }, modifier = Modifier.fillMaxWidth())
                    if (category == "بانک و کارت بانکی") {
                        OutlinedButton(onClick = {
                            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                launchCamera()
                            } else {
                                activity.cameraPermissionResultCallback = { granted ->
                                    if (granted) launchCamera()
                                    else cameraErrorMessage = "برای عکس‌برداری باید دسترسی دوربین را مجاز کنید."
                                }
                                ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.CAMERA), 1202)
                            }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.CameraAlt, null); Spacer(Modifier.width(8.dp)); Text(if (cardPhoto.isBlank()) "عکس‌برداری از کارت" else "گرفتن عکس جدید")
                        }
                        if (cameraErrorMessage.isNotBlank()) Text(cameraErrorMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        if (cardPhoto.isNotBlank()) {
                            Text("پیش‌نمایش عکس کارت؛ تصویر همراه اطلاعات گاوصندوق رمزگذاری می‌شود.", color = Gold, style = MaterialTheme.typography.bodySmall)
                            val previewBytes = remember(cardPhoto) { Base64.decode(cardPhoto, Base64.NO_WRAP) }
                            val previewBitmap = remember(cardPhoto) {
                                BitmapFactory.decodeByteArray(previewBytes, 0, previewBytes.size)?.asImageBitmap()
                            }
                            if (previewBitmap != null) {
                                Image(
                                    bitmap = previewBitmap,
                                    contentDescription = "پیش‌نمایش کارت بانکی",
                                    modifier = Modifier.fillMaxWidth().height(130.dp),
                                    contentScale = ContentScale.Fit
                                )
                            }
                            TextButton(onClick = { cardPhoto = "" }) { Text("حذف عکس کارت") }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (title.isNotBlank() && secret.isNotBlank()) {
                        val old = editing
                        val updated = if (old == null) VaultEntry(title, category, username, secret, note, cardPhoto = cardPhoto)
                        else old.copy(title = title, category = category, username = username,
                            secret = secret, note = note, updatedAt = System.currentTimeMillis(), cardPhoto = cardPhoto,
                            history = if (secret != old.secret) old.history + old.secret else old.history)
                        entries = if (old == null) entries + updated else entries.map { if (it == old) updated else it }
                        store.save(entries); showAdd = false; editing = null
                    }
                }) { Text("ذخیره امن") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false; editing = null }) { Text("انصراف") } }
        )
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Gold, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(label, color = Muted, style = MaterialTheme.typography.labelSmall)
    }
}
@Composable
private fun VaultCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Panel, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
@Composable
private fun QuickTile(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier.height(104.dp), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = Gold, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp)); Text(label, color = Cream, fontWeight = FontWeight.SemiBold)
        }
    }
}
@Composable
private fun SectionTitle(title: String) {
    Text(title, color = Cream, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
}
@Composable
private fun EmptyState(text: String) {
    VaultCard { Icon(Icons.Filled.Inbox, null, tint = Gold, modifier = Modifier.size(30.dp)); Text(text, color = Muted) }
}
@Composable
private fun EntryList(title: String, list: List<VaultEntry>, revealed: Set<String>, toggle: (String) -> Unit, edit: (VaultEntry) -> Unit, delete: (VaultEntry) -> Unit) {
    Spacer(Modifier.height(12.dp)); SectionTitle(title)
    if (list.isEmpty()) EmptyState("هنوز موردی در این بخش ثبت نشده است.")
    else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(list) { e -> EntryCard(e, e.title in revealed, { toggle(e.title) }, { edit(e) }, { delete(e) }) }
    }
}
@Composable
private fun EntryCard(entry: VaultEntry, reveal: Boolean, toggle: () -> Unit, edit: () -> Unit, delete: () -> Unit) {
    VaultCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (entry.category == "بانک و کارت بانکی") Icons.Filled.AccountBalance else Icons.Filled.Apps, null, tint = Gold)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.title, color = Cream, fontWeight = FontWeight.Bold)
                Text(entry.category, color = Gold, style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = edit) { Icon(Icons.Filled.Edit, "ویرایش", tint = Muted) }
        }
        if (entry.username.isNotBlank()) Text("شناسه: ${entry.username}", color = Muted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (reveal) entry.secret else "••••••••", color = Cream, modifier = Modifier.weight(1f))
            IconButton(onClick = toggle) { Icon(if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "نمایش یا پنهان کردن رمز", tint = Gold) }
        }
        if (entry.cardPhoto.isNotBlank()) {
            val photoBytes = remember(entry.cardPhoto) { Base64.decode(entry.cardPhoto, Base64.NO_WRAP) }
            val photoBitmap = remember(entry.cardPhoto) { BitmapFactory.decodeByteArray(photoBytes, 0, photoBytes.size)?.asImageBitmap() }
            if (photoBitmap != null) Image(bitmap = photoBitmap, contentDescription = "عکس کارت بانکی ${entry.title}",
                modifier = Modifier.fillMaxWidth().height(150.dp), contentScale = ContentScale.Crop)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PhotoCamera, null, tint = Gold)
                Text("عکس کارت بانکی ذخیره شده", color = Gold, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (entry.note.isNotBlank()) Text(entry.note, color = Muted, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("بروزرسانی: ${date(entry.updatedAt)}", color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = delete) { Icon(Icons.Filled.DeleteOutline, null); Text("حذف") }
        }
    }
}
@Composable
private fun ReportRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted); Text(value, color = Cream, fontWeight = FontWeight.SemiBold)
    }
}
private fun date(time: Long): String = SimpleDateFormat("yyyy/MM/dd - HH:mm", Locale("fa", "IR")).format(Date(time))
