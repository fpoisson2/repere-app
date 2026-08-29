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
    fun refreshToken():String=get("refresh_token","")
    fun setRefreshToken(value:String){put("refresh_token",value)}
    fun oauthTransient():String=get("oauth_transient","")
    fun setOauthTransient(value:String){put("oauth_transient",value)}
    fun pendingWearOperations():String=get("pending_wear_operations","[]")
    fun setPendingWearOperations(value:String){put("pending_wear_operations",value)}
    fun bacWeightKg():Double?=get("bac_weight_kg","").toDoubleOrNull()
    fun bacDistributionRatio():Double?=get("bac_distribution_ratio","").toDoubleOrNull()
    fun bacEliminationRate():Double=get("bac_elimination_rate","0.015").toDoubleOrNull()?:0.015
    fun bodySex():String=get("body_sex","unspecified")
    fun bodyHeightCm():Double?=get("body_height_cm","").toDoubleOrNull()
    fun saveBodyMetrics(sex:String,heightCm:Double?){put("body_sex",sex);put("body_height_cm",heightCm?.toString().orEmpty())}
    fun saveBacProfile(weightKg:Double,distributionRatio:Double,eliminationRate:Double=.015){
        put("bac_weight_kg",weightKg.toString());put("bac_distribution_ratio",distributionRatio.toString());put("bac_elimination_rate",eliminationRate.toString())
    }
    fun cachedApi(path:String):String=get("api_cache_${path.hashCode()}","")
    fun cacheApi(path:String,value:String){put("api_cache_${path.hashCode()}",value)}
    fun pendingApiOperations():String=get("pending_api_operations","[]")
    fun setPendingApiOperations(value:String){put("pending_api_operations",value)}
    fun syncDataVersion():Int=get("sync_data_version","0").toIntOrNull()?:0
    fun setSyncDataVersion(value:Int){put("sync_data_version",value.toString())}
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
