package tech.xydhub.xyd.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Chiffrement des vidéos téléchargées.
 *
 * - Une clé de contenu AES-256 aléatoire par installation.
 * - Elle est stockée chiffrée (AES-GCM) par une clé maître non exportable de l'Android Keystore
 *   (matériel sécurisé / TEE quand disponible). Sans ce téléphone, les fichiers sont illisibles.
 * - Les vidéos sont chiffrées en AES-256-CTR : chiffrement en flux, accès aléatoire en O(1)
 *   (seek instantané dans le lecteur), aucun surcoût de taille, et reprise de téléchargement possible.
 *
 * Format fichier : "XYD1" (4 octets) + IV/nonce (16 octets) + données chiffrées.
 */
object Vault {
    const val HEADER = 20
    private val MAGIC = byteArrayOf('X'.code.toByte(), 'Y'.code.toByte(), 'D'.code.toByte(), '1'.code.toByte())
    private const val ALIAS = "xyd_master"
    private val rnd = SecureRandom()

    @Volatile private var contentKey: SecretKeySpec? = null

    fun key(ctx: Context): SecretKeySpec {
        contentKey?.let { return it }
        synchronized(this) {
            contentKey?.let { return it }
            val prefs = ctx.getSharedPreferences("xyd_vault", Context.MODE_PRIVATE)
            val master = masterKey()
            val wrapped = prefs.getString("ck", null)
            val raw: ByteArray = if (wrapped != null) {
                val blob = Base64.decode(wrapped, Base64.NO_WRAP)
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, master, GCMParameterSpec(128, blob, 0, 12))
                c.doFinal(blob, 12, blob.size - 12)
            } else {
                val k = ByteArray(32).also { rnd.nextBytes(it) }
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.ENCRYPT_MODE, master)
                val blob = c.iv + c.doFinal(k)
                prefs.edit().putString("ck", Base64.encodeToString(blob, Base64.NO_WRAP)).commit()
                k
            }
            return SecretKeySpec(raw, "AES").also { contentKey = it }
        }
    }

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun newHeader(): ByteArray = MAGIC + ByteArray(16).also { rnd.nextBytes(it) }

    /** Lit l'IV depuis l'en-tête ; null si le fichier n'est pas un fichier xyd. */
    fun ivFrom(header: ByteArray): ByteArray? {
        if (header.size < HEADER) return null
        for (i in 0 until 4) if (header[i] != MAGIC[i]) return null
        return header.copyOfRange(4, HEADER)
    }

    /**
     * Cipher AES-CTR positionné à l'octet [offset] du flux clair.
     * Le compteur est avancé de offset/16 blocs, puis on consomme offset%16 octets.
     */
    fun cipherAt(ctx: Context, iv: ByteArray, offset: Long, mode: Int = Cipher.DECRYPT_MODE): Cipher {
        val counter = iv.copyOf()
        var carry = offset / 16
        var i = 15
        while (i >= 0 && carry != 0L) {
            val sum = (counter[i].toLong() and 0xFF) + (carry and 0xFF)
            counter[i] = sum.toByte()
            carry = (carry ushr 8) + (sum ushr 8)
            i--
        }
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(mode, key(ctx), IvParameterSpec(counter))
        val skip = (offset % 16).toInt()
        if (skip > 0) c.update(ByteArray(skip))
        return c
    }
}
