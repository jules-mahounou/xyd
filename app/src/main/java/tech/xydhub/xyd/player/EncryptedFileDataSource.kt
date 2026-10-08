@file:OptIn(UnstableApi::class)

package tech.xydhub.xyd.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import tech.xydhub.xyd.crypto.Vault
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import javax.crypto.Cipher

/** Lit un fichier .xyd et le déchiffre à la volée (AES-CTR, seek en O(1)). Le clair ne touche jamais le disque. */
class EncryptedFileDataSource(private val ctx: Context) : BaseDataSource(false) {
    private var file: RandomAccessFile? = null
    private var cipher: Cipher? = null
    private var uri: Uri? = null
    private var remaining = 0L
    private var opened = false
    private var scratch = ByteArray(0)

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val f = RandomAccessFile(File(dataSpec.uri.path ?: throw IOException("Chemin invalide")), "r")
        file = f
        val header = ByteArray(Vault.HEADER)
        f.readFully(header)
        val iv = Vault.ivFrom(header) ?: throw IOException("Fichier vidéo corrompu")
        val plainLen = f.length() - Vault.HEADER
        if (dataSpec.position > plainLen) throw EOFException()
        f.seek(Vault.HEADER + dataSpec.position)
        cipher = Vault.cipherAt(ctx, iv, dataSpec.position, Cipher.DECRYPT_MODE)
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length
        else plainLen - dataSpec.position
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = minOf(length.toLong(), remaining).toInt()
        if (scratch.size < toRead) scratch = ByteArray(maxOf(toRead, 64 * 1024))
        val n = file!!.read(scratch, 0, toRead)
        if (n <= 0) return C.RESULT_END_OF_INPUT
        val m = cipher!!.update(scratch, 0, n, buffer, offset)
        remaining -= n
        bytesTransferred(n)
        return m
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        cipher = null
        try {
            file?.close()
        } finally {
            file = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    class Factory(private val ctx: Context) : DataSource.Factory {
        override fun createDataSource(): DataSource = EncryptedFileDataSource(ctx)
    }
}
