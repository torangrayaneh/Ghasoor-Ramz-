package com.torangrayaneh.ghasoor

import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.SecureRandom

data class VaultEntry(val title: String, val category: String, val username: String, val secret: String, val note: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { VaultApp(this) } }
    }
}

private class VaultStore(private val activity: ComponentActivity) {
    private val prefs = activity.getSharedPreferences("vault_settings", MODE_PRIVATE)
    private val alias = "ghasoor_vault_key"

    fun hasMaster() = prefs.contains("master_hash")

    fun setMaster(password: String): Boolean {
        return try {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val hash = derive(password, salt)
            prefs.edit().putString("master_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString("master_hash", Base64.encodeToString(hash, Base64.NO_WRAP)).apply()
            if (!hasKey()) createKey()
            if (!prefs.contains("vault_blob")) save(emptyList())
            true
        } catch (_: Exception) { false }
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
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
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
        val ciphertext = packed.copyOfRange(12, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        val array = JSONArray(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            VaultEntry(o.optString("title"), o.optString("category"), o.optString("username"), o.optString("secret"), o.optString("note"))
        }
    }

    fun save(entries: List<VaultEntry>) {
        if (!hasKey()) createKey()
        val array = JSONArray()
        entries.forEach { e -> array.put(JSONObject().put("title", e.title).put("category", e.category)
            .put("username", e.username).put("secret", e.secret).put("note", e.note)) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(array.toString().toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        prefs.edit().putString("vault_blob", Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }
}

@Composable
private fun VaultApp(activity: ComponentActivity) {
    val store = remember { VaultStore(activity) }
    var hasMaster by remember { mutableStateOf(store.hasMaster()) }
    var unlocked by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf(emptyList<VaultEntry>()) }
    var showAdd by remember { mutableStateOf(false) }
    var reveal by remember { mutableStateOf(false) }

    if (!unlocked) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("گاوصندوق رمز من", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            Text(if (hasMaster) "برای ورود، رمز اصلی را وارد کن." else "برای شروع یک رمز اصلی بساز.")
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("رمز اصلی") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                if (password.length < 6) message = "رمز اصلی باید حداقل ۶ کاراکتر باشد."
                else if (!hasMaster) {
                    if (store.setMaster(password)) { hasMaster = true; unlocked = true; entries = store.load(); password = "" }
                    else message = "ساخت گاوصندوق ناموفق بود."
                } else if (store.verify(password)) {
                    try { entries = store.load(); unlocked = true; password = ""; message = "" }
                    catch (_: Exception) { message = "خواندن اطلاعات ممکن نشد." }
                } else message = "رمز اصلی نادرست است."
            }, modifier = Modifier.fillMaxWidth()) { Text(if (hasMaster) "ورود" else "ساخت گاوصندوق") }
            if (hasMaster && BiometricManager.from(activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS) {
                TextButton(onClick = {
                    val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
                        object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                super.onAuthenticationSucceeded(result)
                                try { entries = store.load(); unlocked = true; message = "" }
                                catch (_: Exception) { message = "بازکردن گاوصندوق ناموفق بود." }
                            }
                        })
                    prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
                        .setTitle("ورود به گاوصندوق")
                        .setSubtitle("با اثر انگشت یا قفل زیستی دستگاه تأیید کن")
                        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK)
                        .setNegativeButtonText("انصراف").build())
                }, modifier = Modifier.fillMaxWidth()) { Text("ورود با اثر انگشت") }
            }
            if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(16.dp))
            Text("اطلاعات این نسخه روی همین دستگاه و به‌صورت رمزگذاری‌شده نگهداری می‌شود.", style = MaterialTheme.typography.bodySmall)
        }
    } else {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("گاوصندوق رمز من", style = MaterialTheme.typography.headlineSmall)
                    Text("تعداد موارد ذخیره‌شده: ${entries.size}")
                }
                TextButton(onClick = { unlocked = false; entries = emptyList() }) { Text("قفل") }
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) { Text("افزودن رمز یا کارت +") }
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(entries) { entry ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(entry.title, style = MaterialTheme.typography.titleMedium)
                            Text(entry.category, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            if (entry.username.isNotBlank()) Text("شناسه: ${entry.username}")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(if (reveal) entry.secret else "••••••••")
                                TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "پنهان" else "نمایش") }
                            }
                            if (entry.note.isNotBlank()) Text(entry.note, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = {
                                entries = entries - entry
                                store.save(entries)
                            }) { Text("حذف مورد") }
                        }
                    }
                }
            }
            if (entries.isEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text("هنوز موردی ذخیره نشده است. از دکمه بالا شروع کن.")
            }
        }
    }

    if (showAdd) {
        var title by remember { mutableStateOf("") }
        var category by remember { mutableStateOf("اپلیکیشن و شبکه اجتماعی") }
        var username by remember { mutableStateOf("") }
        var secret by remember { mutableStateOf("") }
        var note by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("افزودن اطلاعات") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title = it }, label = { Text("عنوان؛ مثلاً بانک ملت") }, singleLine = true)
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { expanded = true }) { Text(category) }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            listOf("بانک و کارت بانکی", "اپلیکیشن و شبکه اجتماعی", "ایمیل", "سایر").forEach {
                                DropdownMenuItem(text = { Text(it) }, onClick = { category = it; expanded = false })
                            }
                        }
                    }
                    OutlinedTextField(username, { username = it }, label = { Text("نام کاربری / شماره کارت") }, singleLine = true)
                    OutlinedTextField(secret, { secret = it }, label = { Text("رمز یا اطلاعات محرمانه") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    OutlinedTextField(note, { note = it }, label = { Text("یادداشت") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (title.isNotBlank() && secret.isNotBlank()) {
                        entries = entries + VaultEntry(title, category, username, secret, note)
                        store.save(entries)
                        showAdd = false
                    }
                }) { Text("ذخیره رمزگذاری‌شده") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("انصراف") } }
        )
    }
}
