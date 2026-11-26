package tech.xydhub.xyd.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.xydhub.xyd.data.Repo
import java.io.ByteArrayOutputStream

/**
 * Bloc « Profil » des réglages : photo (choisie dans la galerie, recadrée en carré) et pseudo.
 * Ils s'affichent auprès des autres, par exemple dans les participants d'une soirée ciné.
 */
@Composable
fun ProfileEditor() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val session by Repo.session.collectAsStateWithLifecycle()
    val profile by Repo.profile.collectAsStateWithLifecycle()
    val s = session ?: return
    val savedName = profile?.name ?: s.name
    var name by remember(savedName) { mutableStateOf(savedName) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }

    fun run(block: suspend () -> Unit) {
        busy = true; msg = null
        scope.launch {
            msg = try { block(); "Profil enregistré ✓" } catch (e: Exception) { e.message ?: "Erreur réseau" }
            busy = false
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        run {
            val (bmp, jpeg) = withContext(Dispatchers.IO) { squareJpeg(ctx, uri) }
            preview = bmp
            Repo.saveProfile(null, jpeg)
            preview = null
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            UserAvatar(savedName, profile?.avatar, 72.dp, ring = Xyd.Line)
            // Aperçu immédiat de la nouvelle photo pendant l'envoi.
            preview?.let { Image(it.asImageBitmap(), null, Modifier.size(72.dp).clip(CircleShape), contentScale = ContentScale.Crop) }
        }
        Column(Modifier.padding(start = 16.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(savedName, fontSize = 16.sp, color = Xyd.Text)
            Text(s.email, color = Xyd.Muted, fontSize = 13.sp)
            Row {
                TextButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !busy) {
                    Text(if (profile?.avatar != null) "Changer la photo" else "Ajouter une photo", color = Xyd.Grey)
                }
                if (profile?.avatar != null) {
                    TextButton(onClick = { run { Repo.saveProfile(null, null, removePhoto = true) } }, enabled = !busy) {
                        Text("Retirer", color = Xyd.Muted)
                    }
                }
            }
        }
    }
    Spacer(Modifier.padding(top = 6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = name, onValueChange = { if (it.length <= 30) name = it },
            label = { Text("Pseudo") }, singleLine = true, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Xyd.Grey, unfocusedBorderColor = Xyd.Line, focusedLabelColor = Xyd.Grey),
            shape = RoundedCornerShape(12.dp),
        )
        if (name.trim() != savedName && name.trim().length >= 2) {
            Spacer(Modifier.width(10.dp))
            OutlinedButton(onClick = { run { Repo.saveProfile(name.trim(), null) } }, enabled = !busy, shape = RoundedCornerShape(12.dp)) {
                Text("Enregistrer", color = Xyd.Grey)
            }
        }
    }
    Text(
        when {
            busy -> "Enregistrement…"
            msg != null -> msg!!
            else -> "Ta photo et ton pseudo sont visibles par les autres, par exemple dans les participants d'une soirée ciné."
        },
        color = Xyd.Muted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
}

/** Photo choisie → carré centré de 384 px, JPEG ~85 % (≈ 30-60 Ko). Orientation EXIF respectée (Android 9+). */
private fun squareJpeg(ctx: Context, uri: Uri): Pair<Bitmap, ByteArray> {
    val src: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
            val s = info.size
            val k = (minOf(s.width, s.height) / 768).coerceAtLeast(1)
            d.setTargetSize(s.width / k, s.height / k)
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var k = 1
        while (minOf(o.outWidth, o.outHeight) / (k * 2) >= 768) k *= 2
        ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = k }) }!!
    }
    val side = minOf(src.width, src.height)
    val sq = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
    val out = Bitmap.createScaledBitmap(sq, 384, 384, true)
    val bytes = ByteArrayOutputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 85, it); it.toByteArray() }
    return out to bytes
}
