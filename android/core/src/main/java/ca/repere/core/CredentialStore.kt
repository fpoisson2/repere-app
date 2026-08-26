package ca.repere.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small Android Keystore-backed store; legacy plain preferences are migrated on first read. */
class CredentialStore(private val context:Context) {
    private val secure=context.getSharedPreferences("repere_secure",Context.MODE_PRIVATE)
    private val legacy=context.getSharedPreferences("repere",Context.MODE_PRIVATE)
    private val alias="${context.packageName}.repere.credentials.v1"

    fun server(defaultValue:String=""):String=get("server",defaultValue)
    fun token():String=get("token","")
    fun syncEnabled():Boolean=secure.getBoolean("sync_enabled",false)
    fun setSyncEnabled(enabled:Boolean){secure.edit().putBoolean("sync_enabled",enabled).apply()}
    fun save(server:String,token:String){put("server",server.trimEnd('/'));put("token",token);setSyncEnabled(true);legacy.edit().remove("server").remove("token").apply()}
    fun clear(){secure.edit().clear().apply();legacy.edit().remove("server").remove("token").apply()}

    private fun get(name:String,defaultValue:String):String {
        secure.getString(name,null)?.let{return runCatching{decrypt(it)}.getOrDefault(defaultValue)}
        val old=legacy.getString(name,null) ?: return defaultValue
        put(name,old);legacy.edit().remove(name).apply();return old
    }

    private fun put(name:String,value:String){secure.edit().putString(name,encrypt(value)).apply()}
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (store.getKey(alias,null) as? SecretKey)?.let{return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun encrypt(value:String):String {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.ENCRYPT_MODE,key())}
        return Base64.encodeToString(cipher.iv+cipher.doFinal(value.toByteArray()),Base64.NO_WRAP)
    }
    private fun decrypt(value:String):String {
        val bytes=Base64.decode(value,Base64.NO_WRAP);val iv=bytes.copyOfRange(0,12);val encrypted=bytes.copyOfRange(12,bytes.size)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,iv))}
        return cipher.doFinal(encrypted).decodeToString()
    }
}
