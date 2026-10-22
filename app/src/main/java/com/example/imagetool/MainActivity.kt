package com.example.imagetool

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color as CColor

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App(this) } }
    }
}

// ---------- helpers ----------
fun encode(bmp: Bitmap, fmt: String, q: Int): ByteArray {
    val o = ByteArrayOutputStream()
    if (fmt == "PNG") bmp.compress(Bitmap.CompressFormat.PNG, 100, o)
    else bmp.compress(Bitmap.CompressFormat.JPEG, q, o)
    return o.toByteArray()
}

fun compressTo(bmp: Bitmap, kb: Int): Int {
    var lo = 1; var hi = 100; var best = 1
    while (lo <= hi) {
        val m = (lo + hi) / 2
        if (encode(bmp, "JPEG", m).size <= kb * 1024) { best = m; lo = m + 1 } else hi = m - 1
    }
    return best
}

fun load(ctx: Context, uri: Uri): Pair<Bitmap, Int>? = try {
    val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    var sample = 1
    while (maxOf(o.outWidth, o.outHeight) / sample > 4096) sample *= 2
    var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    val deg = when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (deg != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
    Pair(bmp, bytes.size)
} catch (e: Exception) { null }

fun save(ctx: Context, bmp: Bitmap, fmt: String, q: Int, dpi: Float) {
    val ext = when (fmt) { "PNG" -> "png"; "PDF" -> "pdf"; "JPG" -> "jpg"; else -> "jpeg" }
    val mime = when (fmt) { "PNG" -> "image/png"; "PDF" -> "application/pdf"; else -> "image/jpeg" }
    val v = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, "image_${System.currentTimeMillis()}.$ext")
        put(MediaStore.Downloads.MIME_TYPE, mime)
        put(MediaStore.Downloads.RELATIVE_PATH, "Download")
    }
    val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
    ctx.contentResolver.openOutputStream(uri)!!.use { os ->
        if (fmt == "PDF") {
            val pw = (bmp.width / dpi * 72).roundToInt().coerceAtLeast(1)
            val ph = (bmp.height / dpi * 72).roundToInt().coerceAtLeast(1)
            val pdf = PdfDocument()
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, 1).create())
            page.canvas.drawBitmap(bmp, null, android.graphics.Rect(0, 0, pw, ph), Paint(Paint.FILTER_BITMAP_FLAG))
            pdf.finishPage(page); pdf.writeTo(os); pdf.close()
        } else os.write(encode(bmp, fmt, q))
    }
    Toast.makeText(ctx, "Saved to Download folder", Toast.LENGTH_LONG).show()
}

// ---------- app shell ----------
@Composable
fun App(a: Activity) {
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var ob by remember { mutableIntStateOf(0) }
    var crop by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { u -> load(a, u)?.let { (b, n) -> bmp = b; ob = n } }
    }
    Surface(Modifier.fillMaxSize().systemBarsPadding()) {
        val b = bmp
        if (crop && b != null) key(b) {
            CropScreen(a, b, { pick.launch("image/*") }, { crop = false }) {
                bmp = it; ob = encode(it, "JPEG", 95).size; crop = false
            }
        } else ResizerScreen(a, b, ob, { pick.launch("image/*") }, { if (b != null) { crop = true } else { Toast.makeText(a, "Upload an image first", Toast.LENGTH_SHORT).show() } }, { a.finish() })
    }
}

@Composable
fun Num(label: String, v: String, mod: Modifier = Modifier, onChange: (String) -> Unit) =
    OutlinedTextField(v, onChange, modifier = mod, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))

// ---------- Resizer ----------
@Composable
fun ResizerScreen(ctx: Context, bmp: Bitmap?, origBytes: Int, onPick: () -> Unit, onCrop: () -> Unit, onExit: () -> Unit) {
    var mode by remember { mutableIntStateOf(0) }
    var wMm by remember { mutableStateOf("210") }; var hMm by remember { mutableStateOf("297") } // A4 default
    var dpi by remember { mutableStateOf("300") }
    var wPx by remember { mutableStateOf("") }; var hPx by remember { mutableStateOf("") }
    var pct by remember { mutableStateOf("100") }; var lock by remember { mutableStateOf(true) }
    var q by remember { mutableFloatStateOf(90f) }; var kb by remember { mutableStateOf("") }
    var fmt by remember { mutableStateOf("JPEG") }
    var res by remember { mutableStateOf<Pair<Bitmap, Int>?>(null) }
    val scope = rememberCoroutineScope()
    val d = dpi.toFloatOrNull()?.coerceAtLeast(1f) ?: 300f

    LaunchedEffect(bmp) { if (bmp != null) { wPx = bmp.width.toString(); hPx = bmp.height.toString() } }
    LaunchedEffect(bmp, mode, wMm, hMm, dpi, wPx, hPx, pct, q, fmt) {
        val b = bmp ?: return@LaunchedEffect
        res = withContext(Dispatchers.Default) {
            val (w, h) = when (mode) {
                0 -> Pair((wMm.toFloatOrNull() ?: 210f) * d / 25.4f, (hMm.toFloatOrNull() ?: 297f) * d / 25.4f)
                1 -> Pair(wPx.toFloatOrNull() ?: b.width.toFloat(), hPx.toFloatOrNull() ?: b.height.toFloat())
                else -> { val p = (pct.toFloatOrNull() ?: 100f) / 100f; Pair(b.width * p, b.height * p) }
            }
            val sb = Bitmap.createScaledBitmap(b, w.toInt().coerceIn(1, 6000), h.toInt().coerceIn(1, 6000), true)
            Pair(sb, encode(sb, fmt, q.toInt()).size)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Image Resizer", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onPick) { Text("Upload Image") }
            OutlinedButton(onCrop) { Text("Crop") }   // Crop button next to Upload
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Size mm", "Manual px", "Percent").forEachIndexed { i, s -> FilterChip(mode == i, { mode = i }, { Text(s) }) }
        }
        when (mode) {
            0 -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Num("Height mm", hMm, Modifier.weight(1f)) { hMm = it }
                    Num("Length mm", wMm, Modifier.weight(1f)) { wMm = it }
                }
                Text("Output: ${((hMm.toFloatOrNull() ?: 0f) * d / 25.4f).roundToInt()} x ${((wMm.toFloatOrNull() ?: 0f) * d / 25.4f).roundToInt()} px  (H x L, A4 default)")
            }
            1 -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Num("L (px)", wPx, Modifier.weight(1f)) { wPx = it; val b = bmp
                    if (lock && b != null) it.toFloatOrNull()?.let { x -> hPx = (x * b.height / b.width).roundToInt().toString() } }
                Num("H (px)", hPx, Modifier.weight(1f)) { hPx = it; val b = bmp
                    if (lock && b != null) it.toFloatOrNull()?.let { x -> wPx = (x * b.width / b.height).roundToInt().toString() } }
                Checkbox(lock, { lock = it }); Text("Aspect")
            }
            else -> Num("Percent %", pct, Modifier.fillMaxWidth()) { pct = it }
        }
        bmp?.let {
            Text("Input size: Height ${it.height} px x Length ${it.width} px  (${String.format("%.0f", it.height / d * 25.4f)} x ${String.format("%.0f", it.width / d * 25.4f)} mm @ ${d.roundToInt()} dpi)",
                style = MaterialTheme.typography.labelLarge)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(20, 50, 100, 200, 500).forEach { OutlinedButton({ kb = it.toString() }) { Text("$it KB") } }
        }
        Text("Image Quality: ${q.toInt()}")
        Slider(q, { q = it }, valueRange = 1f..100f)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Num("Target KB", kb, Modifier.weight(1f)) { kb = it }
            Num("DPI", dpi, Modifier.weight(1f)) { dpi = it }
            Button({
                val t = kb.toIntOrNull(); val r = res
                if (t != null && r != null) scope.launch {
                    q = withContext(Dispatchers.Default) { compressTo(r.first, t) }.toFloat()
                    if (fmt == "PNG") fmt = "JPEG"
                }
            }) { Text("Compress") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().height(160.dp), contentScale = ContentScale.Fit) }
                Text("Original: ${String.format("%.1f", origBytes / 1024f)} KB")
            }
            Column(Modifier.weight(1f)) {
                res?.let { Image(it.first.asImageBitmap(), null, Modifier.fillMaxWidth().height(160.dp), contentScale = ContentScale.Fit) }
                Text("Resized: ${String.format("%.1f", (res?.second ?: 0) / 1024f)} KB")
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("JPEG", "PNG", "JPG", "PDF").forEach { f -> FilterChip(fmt == f, { fmt = f }, { Text(if (f == "JPEG") "JPEG (default)" else f) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({
                val r = res
                if (r == null) Toast.makeText(ctx, "Upload an image first", Toast.LENGTH_SHORT).show()
                else try { save(ctx, r.first, fmt, q.toInt(), d) } catch (e: Exception) { Toast.makeText(ctx, "Save failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }) { Text("Output / Download") }
            OutlinedButton(onExit) { Text("Exit") }
        }
    }
}

// ---------- Crop (4-corner perspective crop) ----------
data class Preset(val name: String, val w: Float?, val h: Float?)   // size in inches
val presets = listOf(
    Preset("Free", null, null),
    Preset("Passport 35x45mm", 35f / 25.4f, 45f / 25.4f),
    Preset("Mini 2x3", 2f, 3f), Preset("Wallet 2.5x3.5", 2.5f, 3.5f),
    Preset("Mini Wallet 1.75x2.5", 1.75f, 2.5f), Preset("Everyday 4x6", 4f, 6f),
    Preset("Medium 5x7", 5f, 7f), Preset("Display 1 8x10", 8f, 10f), Preset("Display 2 8x12", 8f, 12f))

@Composable
fun Layer(img: Bitmap, s: Float, off: Offset, rot: Float, mod: Modifier) {
    val ib = remember(img) { img.asImageBitmap() }
    Canvas(mod) {
        withTransform({
            translate(size.width / 2 + off.x, size.height / 2 + off.y)
            rotate(rot, Offset.Zero); scale(s, s, Offset.Zero)
            translate(-ib.width / 2f, -ib.height / 2f)
        }) { drawImage(ib) }
    }
}

@Composable
fun CropScreen(ctx: Context, bmp: Bitmap, onPick: () -> Unit, onBack: () -> Unit, onUse: (Bitmap) -> Unit) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var off by remember { mutableStateOf(Offset.Zero) }
    var rot by remember { mutableFloatStateOf(0f) }
    var quad by remember { mutableStateOf(listOf<Offset>()) }   // TL, TR, BR, BL in screen coords
    var preset by remember { mutableStateOf(presets[0]) }
    var land by remember { mutableStateOf(false) }
    val fit = if (size.width == 0) 1f else min(size.width / bmp.width.toFloat(), size.height / bmp.height.toFloat())
    val s = fit * zoom

    LaunchedEffect(size) {
        if (size.width > 0 && quad.isEmpty()) {
            val w = size.width.toFloat(); val h = size.height.toFloat()
            quad = listOf(Offset(w * .2f, h * .15f), Offset(w * .8f, h * .15f), Offset(w * .8f, h * .85f), Offset(w * .2f, h * .85f))
        }
    }

    fun outDims(): Pair<Int, Int> {
        val p = preset
        if (p.w != null) {   // 300 dpi
            var w = (p.w * 300).roundToInt(); var h = (p.h!! * 300).roundToInt()
            if (land) { val t = w; w = h; h = t }
            return Pair(w, h)
        }
        if (quad.size < 4) return Pair(1, 1)
        fun d(a: Offset, b: Offset) = (a - b).getDistance() / s
        val w = ((d(quad[0], quad[1]) + d(quad[3], quad[2])) / 2).roundToInt().coerceIn(1, 6000)
        val h = ((d(quad[0], quad[3]) + d(quad[1], quad[2])) / 2).roundToInt().coerceIn(1, 6000)
        return Pair(w, h)
    }

    fun makeCrop(): Bitmap {
        val fwd = Matrix()
        fwd.postTranslate(-bmp.width / 2f, -bmp.height / 2f); fwd.postRotate(rot); fwd.postScale(s, s)
        fwd.postTranslate(size.width / 2f + off.x, size.height / 2f + off.y)
        val inv = Matrix(); fwd.invert(inv)
        val src = FloatArray(8)
        quad.forEachIndexed { i, p -> src[i * 2] = p.x; src[i * 2 + 1] = p.y }
        inv.mapPoints(src)                                   // corners in bitmap coordinates
        val (w, h) = outDims()
        val dst = floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat())
        val pm = Matrix(); pm.setPolyToPoly(src, 0, dst, 0, 4)   // perspective warp quad -> rectangle
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out); c.drawColor(android.graphics.Color.WHITE)
        c.drawBitmap(bmp, pm, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onBack) { Text("Back") }
            Button(onPick) { Text("Upload Image") }
        }
        Text("Input size: Height ${bmp.height} px x Length ${bmp.width} px", style = MaterialTheme.typography.labelLarge)
        Text("Output size (inch)", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            presets.forEach { p -> FilterChip(preset == p, { preset = p }, { Text(p.name) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            FilterChip(land, { land = !land }, { Text("Swap H / L") })
            val (ow, oh) = outDims()
            Text("Output: $ow x $oh px")
        }
        Box(Modifier.fillMaxWidth().weight(1f).background(androidx.compose.ui.graphics.Color.Black)
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val idx = quad.indices.minByOrNull { (quad[it] - down.position).getDistance() }
                        ?.takeIf { (quad[it] - down.position).getDistance() < 90f }
                    do {
                        val ev = awaitPointerEvent()
                        if (idx != null) {
                            if (ev.changes.size == 1) {
                                val np = quad[idx] + ev.changes[0].positionChange()
                                quad = quad.toMutableList().also { l ->
                                    l[idx] = Offset(np.x.coerceIn(0f, size.width.toFloat()), np.y.coerceIn(0f, size.height.toFloat()))
                                }
                            }
                        } else {
                            zoom = (zoom * ev.calculateZoom()).coerceIn(0.3f, 10f)
                            off += ev.calculatePan()
                            rot = ((rot + ev.calculateRotation() + 180f) % 360f + 360f) % 360f - 180f
                        }
                        ev.changes.forEach { if (it.positionChanged()) it.consume() }
                    } while (ev.changes.any { it.pressed })
                }
            }) {
            Layer(bmp, s, off, rot, Modifier.fillMaxSize().blur(12.dp))                  // blurred outside
            Canvas(Modifier.fillMaxSize()) { drawRect(CColor(0x88000000)) }
            Box(Modifier.fillMaxSize().drawWithContent {                                 // sharp inside quad
                if (quad.size == 4) {
                    val path = Path().apply {
                        moveTo(quad[0].x, quad[0].y); lineTo(quad[1].x, quad[1].y)
                        lineTo(quad[2].x, quad[2].y); lineTo(quad[3].x, quad[3].y); close()
                    }
                    clipPath(path) { this@drawWithContent.drawContent() }
                }
            }) { Layer(bmp, s, off, rot, Modifier.fillMaxSize()) }
            Canvas(Modifier.fillMaxSize()) {
                if (quad.size == 4) {
                    for (i in 0..3) drawLine(CColor.White, quad[i], quad[(i + 1) % 4], strokeWidth = 4f)
                    quad.forEach { drawCircle(CColor(0xFF1E88E5), 22f, it); drawCircle(CColor.White, 22f, it, style = Stroke(4f)) }
                }
            }
        }
        Text("Drag each corner separately. Two fingers = zoom / pan / rotate image. Rotate: ${rot.roundToInt()}°")
        Slider(rot, { rot = it }, valueRange = -180f..180f)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ if (quad.size == 4) onUse(makeCrop()) }) { Text("Crop & Use in Resizer") }
            OutlinedButton({
                try { if (quad.size == 4) save(ctx, makeCrop(), "JPEG", 95, 300f) } catch (e: Exception) { Toast.makeText(ctx, "Save failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }) { Text("Save") }
        }
    }
}
