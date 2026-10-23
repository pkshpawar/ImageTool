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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color as CColor

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App(this) } }
    }
}

// ---------- data ----------
data class Paper(val name: String, val w: Float, val h: Float)   // mm
val papers = listOf(
    Paper("A4", 210f, 297f), Paper("A5", 148f, 210f), Paper("A3", 297f, 420f), Paper("Letter", 215.9f, 279.4f),
    Paper("Smart Card 85.6x54mm", 85.6f, 54f), Paper("4x6 in", 101.6f, 152.4f), Paper("5x7 in", 127f, 177.8f), Paper("8x10 in", 203.2f, 254f))
val bgOptions = listOf("White" to 0xFFFFFFFF.toInt(), "Gray" to 0xFFBDBDBD.toInt(), "Black" to 0xFF000000.toInt(), "Blue" to 0xFF1E88E5.toInt())

fun f1(x: Float): String = String.format(Locale.US, "%.1f", x)
fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

// ---------- image helpers ----------
fun encode(bmp: Bitmap, fmt: String, q: Int): ByteArray {
    val o = ByteArrayOutputStream()
    if (fmt == "PNG") bmp.compress(Bitmap.CompressFormat.PNG, 100, o)
    else bmp.compress(Bitmap.CompressFormat.JPEG, q, o)
    return o.toByteArray()
}

fun load(ctx: Context, uri: Uri, maxDim: Int = 4096): Pair<Bitmap, Int>? = try {
    val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    var sample = 1
    while (maxOf(o.outWidth, o.outHeight) / sample > maxDim) sample *= 2
    var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    val deg = when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (deg != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
    Pair(bmp, bytes.size)
} catch (e: Throwable) { null }

/** Professional scaling: repeated halving then a final filtered step (sharper, no aliasing). */
fun smoothScale(src: Bitmap, w: Int, h: Int, pro: Boolean): Bitmap {
    if (!pro) return Bitmap.createScaledBitmap(src, w, h, true)
    var cur = src
    while (cur.width / 2 >= w && cur.height / 2 >= h) cur = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
    return Bitmap.createScaledBitmap(cur, w, h, true)
}

/** Normal = stretch. Professional = keep aspect ratio; fit=true puts it on a paper of tw x th, fit=false returns only the image. */
fun render(src: Bitmap, tw: Int, th: Int, pro: Boolean, fit: Boolean, bg: Int): Bitmap {
    if (!pro) return Bitmap.createScaledBitmap(src, tw, th, true)
    val k = min(tw / src.width.toFloat(), th / src.height.toFloat())
    val iw = (src.width * k).roundToInt().coerceAtLeast(1)
    val ih = (src.height * k).roundToInt().coerceAtLeast(1)
    val img = smoothScale(src, iw, ih, true)
    if (!fit) return img
    val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(out)
    c.drawColor(bg)
    c.drawBitmap(img, (tw - iw) / 2f, (th - ih) / 2f, null)
    return out
}

fun openDownload(ctx: Context, name: String, mime: String): OutputStream {
    val v = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.MIME_TYPE, mime)
        put(MediaStore.Downloads.RELATIVE_PATH, "Download")
    }
    val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
    return ctx.contentResolver.openOutputStream(uri)!!
}

fun save(ctx: Context, bmp: Bitmap, fmt: String, q: Int, dpi: Float) {
    val ext = when (fmt) { "PNG" -> "png"; "PDF" -> "pdf"; "JPG" -> "jpg"; else -> "jpeg" }
    val mime = when (fmt) { "PNG" -> "image/png"; "PDF" -> "application/pdf"; else -> "image/jpeg" }
    openDownload(ctx, "image_${System.currentTimeMillis()}.$ext", mime).use { os ->
        if (fmt == "PDF") {
            val pw = (bmp.width / dpi * 72).roundToInt().coerceAtLeast(1)
            val ph = (bmp.height / dpi * 72).roundToInt().coerceAtLeast(1)
            val pdf = PdfDocument()
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, 1).create())
            page.canvas.drawBitmap(bmp, null, android.graphics.Rect(0, 0, pw, ph), Paint(Paint.FILTER_BITMAP_FLAG))
            pdf.finishPage(page); pdf.writeTo(os); pdf.close()
        } else os.write(encode(bmp, fmt, q))
    }
}

fun imagesToPdf(ctx: Context, uris: List<Uri>, a4: Boolean) {
    val pdf = PdfDocument()
    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    uris.forEachIndexed { i, u ->
        val b = load(ctx, u, 2400)?.first ?: return@forEachIndexed
        val (pw, ph) = if (a4) { if (b.width > b.height) Pair(842, 595) else Pair(595, 842) }
        else Pair((b.width * 72f / 150f).roundToInt().coerceAtLeast(1), (b.height * 72f / 150f).roundToInt().coerceAtLeast(1))
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
        val c = page.canvas
        if (a4) {
            c.drawColor(android.graphics.Color.WHITE)
            val m = 20f; val k = min((pw - 2 * m) / b.width, (ph - 2 * m) / b.height)
            val w = b.width * k; val h = b.height * k; val l = (pw - w) / 2; val t = (ph - h) / 2
            c.drawBitmap(b, null, RectF(l, t, l + w, t + h), paint)
        } else c.drawBitmap(b, null, android.graphics.Rect(0, 0, pw, ph), paint)
        pdf.finishPage(page)
    }
    openDownload(ctx, "images_${System.currentTimeMillis()}.pdf", "application/pdf").use { pdf.writeTo(it) }
    pdf.close()
}

// ---------- compact UI ----------
@Composable
fun Btn(t: String, primary: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    val pad = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
    if (primary) Button(onClick, Modifier.height(30.dp), enabled = enabled, contentPadding = pad) { Text(t, fontSize = 12.sp) }
    else OutlinedButton(onClick, Modifier.height(30.dp), enabled = enabled, contentPadding = pad) { Text(t, fontSize = 12.sp) }
}

@Composable
fun Chip(t: String, sel: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick), shape = RoundedCornerShape(6.dp),
        color = if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = if (sel) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
    ) { Text(t, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) }
}

@Composable
fun HRow(content: @Composable RowScope.() -> Unit) =
    Row(Modifier.horizontalScroll(rememberScrollState()), Arrangement.spacedBy(5.dp), Alignment.CenterVertically, content = content)

@Composable
fun Num(label: String, v: String, mod: Modifier = Modifier, onChange: (String) -> Unit) =
    OutlinedTextField(v, onChange, modifier = mod, label = { Text(label, fontSize = 11.sp) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))

@Composable
fun PreviewBox(bm: Bitmap?) {
    Box(Modifier.fillMaxWidth().height(190.dp), contentAlignment = Alignment.Center) {
        if (bm != null) {
            val ib = remember(bm) { bm.asImageBitmap() }
            Image(ib, null, Modifier.aspectRatio(bm.width / bm.height.toFloat()).border(1.dp, CColor.Gray), contentScale = ContentScale.Fit)
        }
    }
}

// ---------- state holders (kept when you press Back) ----------
class RS {
    var mode by mutableIntStateOf(0)
    var wMm by mutableStateOf("210"); var hMm by mutableStateOf("297")   // A4 default
    var dpi by mutableStateOf("300")
    var wPx by mutableStateOf(""); var hPx by mutableStateOf(""); var pct by mutableStateOf("100")
    var lock by mutableStateOf(true)
    var q by mutableFloatStateOf(90f); var kb by mutableStateOf("")
    var fmt by mutableStateOf("JPEG")
    var pro by mutableStateOf(true); var fit by mutableStateOf(true)
    var bg by mutableIntStateOf(0xFFFFFFFF.toInt())
    var cSig by mutableStateOf(""); var cScale by mutableFloatStateOf(1f)
    var res by mutableStateOf<Pair<Bitmap, Int>?>(null)
    var forBmp: Bitmap? = null
}

class CS {
    var zoom by mutableFloatStateOf(1f)
    var off by mutableStateOf(Offset.Zero)
    var rot by mutableFloatStateOf(0f)
    var quad by mutableStateOf(listOf<Offset>())
    var preset by mutableIntStateOf(0)
    var land by mutableStateOf(false)
}

class MS {
    val bmps = mutableStateListOf<Bitmap>()
    val rects = mutableStateListOf<Rect>()   // mm on paper
    var paper by mutableIntStateOf(0)
    var land by mutableStateOf(false)
    var auto by mutableStateOf(true)
    var bg by mutableIntStateOf(0xFFFFFFFF.toInt())
    var sel by mutableIntStateOf(-1)
    var pv by mutableStateOf(IntSize.Zero)
    val pw: Float get() = if (land) papers[paper].h else papers[paper].w
    val ph: Float get() = if (land) papers[paper].w else papers[paper].h
    fun relayout() { val l = autoLayout(bmps, pw, ph); rects.clear(); rects.addAll(l); sel = -1 }
}

class PS { val uris = mutableStateListOf<Uri>(); var a4 by mutableStateOf(true) }

fun autoLayout(bmps: List<Bitmap>, pw: Float, ph: Float, margin: Float = 8f, gap: Float = 4f): List<Rect> {
    val n = bmps.size
    if (n == 0) return emptyList()
    var best: List<Rect> = emptyList(); var bestArea = -1f
    for (cols in 1..n) {
        val rows = (n + cols - 1) / cols
        val cw = (pw - 2 * margin - (cols - 1) * gap) / cols
        val ch = (ph - 2 * margin - (rows - 1) * gap) / rows
        if (cw <= 0f || ch <= 0f) continue
        var area = 0f
        val l = bmps.mapIndexed { i, b ->
            val k = min(cw / b.width, ch / b.height)
            val iw = b.width * k; val ih = b.height * k
            val x = margin + (i % cols) * (cw + gap) + (cw - iw) / 2
            val y = margin + (i / cols) * (ch + gap) + (ch - ih) / 2
            area += iw * ih
            Rect(x, y, x + iw, y + ih)
        }
        if (area > bestArea) { bestArea = area; best = l }
    }
    return best
}

fun renderMerged(bmps: List<Bitmap>, rects: List<Rect>, pw: Float, ph: Float, bg: Int, dpi: Float): Bitmap {
    val k = dpi / 25.4f
    val out = Bitmap.createBitmap((pw * k).roundToInt(), (ph * k).roundToInt(), Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(out); c.drawColor(bg)
    bmps.forEachIndexed { i, b ->
        val r = rects[i]
        val sm = smoothScale(b, (r.width * k).roundToInt().coerceAtLeast(1), (r.height * k).roundToInt().coerceAtLeast(1), true)
        c.drawBitmap(sm, r.left * k, r.top * k, null)
    }
    return out
}

// ---------- app shell ----------
@Composable
fun App(a: Activity) {
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var ob by remember { mutableIntStateOf(0) }
    var screen by remember { mutableStateOf("resize") }
    var cs by remember { mutableStateOf(CS()) }
    val rs = remember { RS() }; val ms = remember { MS() }; val ps = remember { PS() }
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { u -> load(a, u)?.let { (b, n) -> bmp = b; ob = n; cs = CS() } }
    }
    val pickMerge = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val l = withContext(Dispatchers.IO) { uris.mapNotNull { load(a, it, 2400)?.first } }
            ms.bmps.addAll(l); ms.relayout()
        }
    }
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris -> ps.uris.addAll(uris) }

    // Back = go back (state is kept). On main screen, Back just sends the app to background.
    BackHandler { if (screen != "resize") screen = "resize" else a.moveTaskToBack(true) }

    Surface(Modifier.fillMaxSize().systemBarsPadding()) {
        val b = bmp
        when (screen) {
            "crop" -> {
                if (b != null) CropScreen(a, b, cs, { pick.launch("image/*") }, { screen = "resize" }) {
                    bmp = it; ob = encode(it, "JPEG", 95).size; cs = CS(); screen = "resize"
                }
            }
            "merge" -> MergeScreen(a, ms, { pickMerge.launch("image/*") }) { screen = "resize" }
            "pdf" -> PdfScreen(a, ps, { pickPdf.launch("image/*") }) { screen = "resize" }
            else -> ResizerScreen(a, rs, b, ob, { pick.launch("image/*") },
                { if (b != null) { screen = "crop" } else { toast(a, "Upload an image first") } },
                { screen = "merge" }, { screen = "pdf" },
                { a.finishAndRemoveTask() })   // Exit = close and clear the app
        }
    }
}

// ---------- Resizer ----------
fun targetDims(st: RS, b: Bitmap, scale: Float): Pair<Int, Int> {
    val d = st.dpi.toFloatOrNull()?.coerceAtLeast(1f) ?: 300f
    val (w, h) = when (st.mode) {
        0 -> Pair((st.wMm.toFloatOrNull() ?: 210f) * d / 25.4f, (st.hMm.toFloatOrNull() ?: 297f) * d / 25.4f)
        1 -> Pair(st.wPx.toFloatOrNull() ?: b.width.toFloat(), st.hPx.toFloatOrNull() ?: b.height.toFloat())
        else -> { val p = (st.pct.toFloatOrNull() ?: 100f) / 100f; Pair(b.width * p, b.height * p) }
    }
    return Pair((w * scale).roundToInt().coerceIn(1, 5000), (h * scale).roundToInt().coerceIn(1, 5000))
}

fun renderRes(st: RS, b: Bitmap, scale: Float): Bitmap {
    val (w, h) = targetDims(st, b, scale)
    return if (st.mode == 2) smoothScale(b, w, h, st.pro) else render(b, w, h, st.pro, st.fit, st.bg)
}

/** Lowers quality (down to 30), then shrinks pixels, until the file is <= maxBytes. */
fun compressFit(st: RS, b: Bitmap, maxBytes: Int): Triple<Int, Float, Int> {
    var sc = 1f
    repeat(25) {
        val bm = renderRes(st, b, sc)
        var lo = 30; var hi = 100; var best = -1
        while (lo <= hi) {
            val m = (lo + hi) / 2
            if (encode(bm, "JPEG", m).size <= maxBytes) { best = m; lo = m + 1 } else hi = m - 1
        }
        if (best > 0) return Triple(best, sc, encode(bm, "JPEG", best).size)
        sc *= 0.85f
    }
    return Triple(30, sc, encode(renderRes(st, b, sc), "JPEG", 30).size)
}

@Composable
fun ResizerScreen(ctx: Context, st: RS, bmp: Bitmap?, origBytes: Int, onPick: () -> Unit, onCrop: () -> Unit,
                  onMerge: () -> Unit, onPdf: () -> Unit, onExit: () -> Unit) {
    val scope = rememberCoroutineScope()
    val d = st.dpi.toFloatOrNull()?.coerceAtLeast(1f) ?: 300f
    if (bmp != null && st.forBmp !== bmp) { st.forBmp = bmp; st.wPx = bmp.width.toString(); st.hPx = bmp.height.toString(); st.cSig = "" }

    val sig = "${st.mode}|${st.wMm}|${st.hMm}|${st.dpi}|${st.wPx}|${st.hPx}|${st.pct}|${st.pro}|${st.fit}|${st.bg}|${System.identityHashCode(bmp)}"
    val eff = if (st.cSig == sig) st.cScale else 1f
    LaunchedEffect(sig, eff, st.q, st.fmt) {
        val b = bmp
        if (b == null) { st.res = null; return@LaunchedEffect }
        st.res = withContext(Dispatchers.Default) { val bm = renderRes(st, b, eff); Pair(bm, encode(bm, st.fmt, st.q.toInt()).size) }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Image Resizer", style = MaterialTheme.typography.titleMedium)
        HRow {
            Btn("Upload Image") { onPick() }
            Btn("Crop", false) { onCrop() }
            Btn("Merge", false) { onMerge() }
            Btn("Images to PDF", false) { onPdf() }
        }
        HRow { listOf("Size mm", "Manual px", "Percent").forEachIndexed { i, s -> Chip(s, st.mode == i) { st.mode = i } } }
        if (st.mode == 0) {
            HRow { papers.forEach { p -> Chip(p.name, false) { st.mode = 0; st.wMm = f1(p.w).removeSuffix(".0"); st.hMm = f1(p.h).removeSuffix(".0") } } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Num("Height mm", st.hMm, Modifier.weight(1f)) { st.hMm = it }
                Num("Length mm", st.wMm, Modifier.weight(1f)) { st.wMm = it }
            }
            Text("Output: ${((st.hMm.toFloatOrNull() ?: 0f) * d / 25.4f).roundToInt()} x ${((st.wMm.toFloatOrNull() ?: 0f) * d / 25.4f).roundToInt()} px (H x L)", fontSize = 11.sp)
        } else if (st.mode == 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Num("L (px)", st.wPx, Modifier.weight(1f)) { st.wPx = it; val b = bmp
                    if (st.lock && b != null) it.toFloatOrNull()?.let { x -> st.hPx = (x * b.height / b.width).roundToInt().toString() } }
                Num("H (px)", st.hPx, Modifier.weight(1f)) { st.hPx = it; val b = bmp
                    if (st.lock && b != null) it.toFloatOrNull()?.let { x -> st.wPx = (x * b.width / b.height).roundToInt().toString() } }
                Checkbox(st.lock, { st.lock = it }); Text("Aspect", fontSize = 11.sp)
            }
        } else Num("Percent %", st.pct, Modifier.fillMaxWidth()) { st.pct = it }

        if (st.mode != 2) {
            HRow {
                Chip("Professional resize", st.pro) { st.pro = true }
                Chip("Normal (stretch)", !st.pro) { st.pro = false }
                Chip("Fit to paper", st.fit) { st.fit = true }
                Chip("Resize only image", !st.fit) { st.fit = false }
            }
            if (st.pro && st.fit) HRow {
                Text("Paper:", fontSize = 11.sp)
                bgOptions.forEach { (n, c) -> Chip(n, st.bg == c) { st.bg = c } }
            }
        } else HRow { Chip("Professional", st.pro) { st.pro = true }; Chip("Normal", !st.pro) { st.pro = false } }

        bmp?.let {
            Text("Input size: Height ${it.height} px x Length ${it.width} px  (${f1(it.height / d * 25.4f)} x ${f1(it.width / d * 25.4f)} mm @ ${d.roundToInt()} dpi)",
                style = MaterialTheme.typography.labelLarge)
        }
        HRow { listOf(20, 50, 100, 200, 500).forEach { Chip("$it KB", st.kb == it.toString()) { st.kb = it.toString() } } }
        Text("Image Quality: ${st.q.toInt()}" + if (eff < 1f) "   (size reduced to ${(eff * 100).roundToInt()}%)" else "", fontSize = 12.sp)
        Slider(st.q, { st.q = it }, valueRange = 1f..100f)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Num("Target KB", st.kb, Modifier.weight(1f)) { st.kb = it }
            Num("DPI", st.dpi, Modifier.weight(1f)) { st.dpi = it }
            Btn("Compress") {
                val b = bmp ?: return@Btn
                val target = (st.kb.toIntOrNull() ?: max(1, origBytes / 2048)).coerceAtLeast(1)   // blank = 50% of original
                scope.launch {
                    val (qq, sc, bytes) = withContext(Dispatchers.Default) { compressFit(st, b, target * 1024) }
                    if (st.fmt == "PNG") st.fmt = "JPEG"
                    st.q = qq.toFloat(); st.cSig = sig; st.cScale = sc
                    toast(ctx, "Compressed to ${bytes / 1024} KB")
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Before (original)", style = MaterialTheme.typography.labelMedium)
                PreviewBox(bmp)
                Text("${bmp?.width ?: 0} x ${bmp?.height ?: 0} px\n${f1(origBytes / 1024f)} KB", fontSize = 11.sp)
            }
            Column(Modifier.weight(1f)) {
                Text("After (required)", style = MaterialTheme.typography.labelMedium)
                PreviewBox(st.res?.first)
                Text("${st.res?.first?.width ?: 0} x ${st.res?.first?.height ?: 0} px\n${f1((st.res?.second ?: 0) / 1024f)} KB", fontSize = 11.sp)
            }
        }
        HRow { listOf("JPEG", "PNG", "JPG", "PDF").forEach { f -> Chip(if (f == "JPEG") "JPEG (default)" else f, st.fmt == f) { st.fmt = f } } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Download") {
                val r = st.res
                if (r == null) toast(ctx, "Upload an image first")
                else scope.launch {
                    try { withContext(Dispatchers.IO) { save(ctx, r.first, st.fmt, st.q.toInt(), d) }; toast(ctx, "Saved to Download folder") }
                    catch (e: Throwable) { toast(ctx, "Save failed: ${e.message}") }
                }
            }
            Btn("Exit", false) { onExit() }
        }
    }
}

// ---------- Crop ----------
data class Preset(val name: String, val w: Float?, val h: Float?)   // inches
val presets = listOf(
    Preset("Rectangle", null, null), Preset("Free", null, null),
    Preset("Smart Card 85.6x54mm", 85.6f / 25.4f, 54f / 25.4f), Preset("Passport 35x45mm", 35f / 25.4f, 45f / 25.4f),
    Preset("Mini 2x3", 2f, 3f), Preset("Wallet 2.5x3.5", 2.5f, 3.5f), Preset("Mini Wallet 1.75x2.5", 1.75f, 2.5f),
    Preset("Everyday 4x6", 4f, 6f), Preset("Medium 5x7", 5f, 7f), Preset("Display 1 8x10", 8f, 10f), Preset("Display 2 8x12", 8f, 12f))

fun ratioOf(p: Preset, land: Boolean): Float? = if (p.w == null) null else if (land) p.h!! / p.w else p.w / p.h!!

fun hit(p: Offset, r: Rect, t: Float): Int {   // 1=L 2=T 4=R 8=B ; two bits = corner
    var m = 0
    val inY = p.y in (r.top - t)..(r.bottom + t); val inX = p.x in (r.left - t)..(r.right + t)
    if (inY && abs(p.x - r.left) < t) m = m or 1
    if (inY && abs(p.x - r.right) < t) m = m or 4
    if (inX && abs(p.y - r.top) < t) m = m or 2
    if (inX && abs(p.y - r.bottom) < t) m = m or 8
    return m
}

fun Int.has(f: Int) = (this and f) != 0

fun moveBox(r: Rect, m: Int, d: Offset, ratio: Float?, min: Float, bounds: IntSize): Rect {
    var l = r.left; var t = r.top; var rt = r.right; var b = r.bottom
    if (m.has(1)) l = (l + d.x).coerceIn(0f, rt - min)
    if (m.has(4)) rt = (rt + d.x).coerceIn(l + min, bounds.width.toFloat())
    if (m.has(2)) t = (t + d.y).coerceIn(0f, b - min)
    if (m.has(8)) b = (b + d.y).coerceIn(t + min, bounds.height.toFloat())
    if (ratio != null) { val h = (rt - l) / ratio; if (m.has(2) && !m.has(8)) t = b - h else b = t + h }
    return Rect(l, t, rt, b)
}

fun fitRatio(r: Rect, ratio: Float): Rect {
    var w = r.width; var h = w / ratio
    if (h > r.height) { h = r.height; w = h * ratio }
    val c = r.center
    return Rect(c.x - w / 2, c.y - h / 2, c.x + w / 2, c.y + h / 2)
}

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
fun CropScreen(ctx: Context, bmp: Bitmap, cs: CS, onPick: () -> Unit, onBack: () -> Unit, onUse: (Bitmap) -> Unit) {
    val scope = rememberCoroutineScope()
    var size by remember { mutableStateOf(IntSize.Zero) }
    val p = presets[cs.preset]
    val free = p.name == "Free"
    val fit = if (size.width == 0) 1f else min(size.width / bmp.width.toFloat(), size.height / bmp.height.toFloat())
    val s = fit * cs.zoom

    LaunchedEffect(size) {
        if (size.width > 0 && cs.quad.isEmpty()) {
            val w = size.width.toFloat(); val h = size.height.toFloat()
            cs.quad = listOf(Offset(w * .15f, h * .15f), Offset(w * .85f, h * .15f), Offset(w * .85f, h * .85f), Offset(w * .15f, h * .85f))
        }
    }

    fun setRect(r: Rect) { cs.quad = listOf(r.topLeft, r.topRight, r.bottomRight, r.bottomLeft) }
    fun curRect(): Rect {
        val q = cs.quad
        return Rect(min(q[0].x, q[3].x), min(q[0].y, q[1].y), max(q[1].x, q[2].x), max(q[2].y, q[3].y))
    }
    fun choose(i: Int) {
        cs.preset = i
        if (presets[i].name == "Free" || cs.quad.size < 4) return
        var r = curRect()
        val rt = ratioOf(presets[i], cs.land)
        if (rt != null) r = fitRatio(r, rt)
        setRect(r)     // rectangle again (also when coming back from Free)
    }
    fun toggleLand() {
        cs.land = !cs.land
        val rt = ratioOf(presets[cs.preset], cs.land)
        if (rt != null && cs.quad.size == 4) setRect(fitRatio(curRect(), rt))
    }
    fun outDims(): Pair<Int, Int> {
        if (p.w != null) {   // 300 dpi
            var w = (p.w * 300).roundToInt(); var h = (p.h!! * 300).roundToInt()
            if (cs.land) { val t = w; w = h; h = t }
            return Pair(w, h)
        }
        if (cs.quad.size < 4) return Pair(1, 1)
        val q = cs.quad
        fun d(a: Offset, b: Offset) = (a - b).getDistance() / s
        return Pair(((d(q[0], q[1]) + d(q[3], q[2])) / 2).roundToInt().coerceIn(1, 6000),
            ((d(q[0], q[3]) + d(q[1], q[2])) / 2).roundToInt().coerceIn(1, 6000))
    }
    fun makeCrop(): Bitmap {
        val fwd = Matrix()
        fwd.postTranslate(-bmp.width / 2f, -bmp.height / 2f); fwd.postRotate(cs.rot); fwd.postScale(s, s)
        fwd.postTranslate(size.width / 2f + cs.off.x, size.height / 2f + cs.off.y)
        val inv = Matrix(); fwd.invert(inv)
        val src = FloatArray(8)
        cs.quad.forEachIndexed { i, pt -> src[i * 2] = pt.x; src[i * 2 + 1] = pt.y }
        inv.mapPoints(src)
        val (w, h) = outDims()
        val dst = floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat())
        val pm = Matrix(); pm.setPolyToPoly(src, 0, dst, 0, 4)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out); c.drawColor(android.graphics.Color.WHITE)
        c.drawBitmap(bmp, pm, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Btn("Back", false) { onBack() }
            Btn("Upload Image") { onPick() }
            Text("Input: H ${bmp.height} x L ${bmp.width} px", fontSize = 11.sp)
        }
        HRow {
            presets.forEachIndexed { i, pr -> Chip(pr.name, cs.preset == i) { choose(i) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (p.w != null) Chip("Swap H / L", cs.land) { toggleLand() }
            val (ow, oh) = outDims()
            Text("Output: $ow x $oh px", fontSize = 11.sp)
        }
        Box(Modifier.fillMaxWidth().weight(1f).background(androidx.compose.ui.graphics.Color.Black)
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val q0 = cs.quad
                    val isFree = presets[cs.preset].name == "Free"
                    val idx = if (q0.size == 4 && isFree)
                        (q0.indices.minByOrNull { (q0[it] - down.position).getDistance() }
                            ?.takeIf { (q0[it] - down.position).getDistance() < 90f } ?: -1) else -1
                    val mask = if (q0.size == 4 && !isFree) hit(down.position, curRect(), 64f) else 0
                    val grab = idx >= 0 || mask != 0
                    do {
                        val ev = awaitPointerEvent()
                        if (grab) {
                            if (ev.changes.size == 1) {
                                val dlt = ev.changes[0].positionChange()
                                if (idx >= 0) {
                                    val np = cs.quad[idx] + dlt
                                    cs.quad = cs.quad.toMutableList().also { l ->
                                        l[idx] = Offset(np.x.coerceIn(0f, size.width.toFloat()), np.y.coerceIn(0f, size.height.toFloat()))
                                    }
                                } else setRect(moveBox(curRect(), mask, dlt, ratioOf(presets[cs.preset], cs.land), 100f, size))
                            }
                        } else {
                            cs.zoom = (cs.zoom * ev.calculateZoom()).coerceIn(0.3f, 10f)
                            cs.off += ev.calculatePan()
                            cs.rot = ((cs.rot + ev.calculateRotation() + 180f) % 360f + 360f) % 360f - 180f
                        }
                        ev.changes.forEach { if (it.positionChanged()) it.consume() }
                    } while (ev.changes.any { it.pressed })
                }
            }) {
            Layer(bmp, s, cs.off, cs.rot, Modifier.fillMaxSize().blur(12.dp))
            Canvas(Modifier.fillMaxSize()) { drawRect(CColor(0x88000000)) }
            Box(Modifier.fillMaxSize().drawWithContent {
                val q = cs.quad
                if (q.size == 4) {
                    val path = Path().apply { moveTo(q[0].x, q[0].y); lineTo(q[1].x, q[1].y); lineTo(q[2].x, q[2].y); lineTo(q[3].x, q[3].y); close() }
                    clipPath(path) { this@drawWithContent.drawContent() }
                }
            }) { Layer(bmp, s, cs.off, cs.rot, Modifier.fillMaxSize()) }
            Canvas(Modifier.fillMaxSize()) {
                val q = cs.quad
                if (q.size == 4) {
                    for (i in 0..3) drawLine(CColor.White, q[i], q[(i + 1) % 4], strokeWidth = 4f)
                    q.forEach { drawCircle(CColor(0xFF1E88E5), 22f, it); drawCircle(CColor.White, 22f, it, style = Stroke(4f)) }
                    if (!free) for (i in 0..3) {
                        val mid = (q[i] + q[(i + 1) % 4]) / 2f
                        drawCircle(CColor(0xFF1E88E5), 14f, mid); drawCircle(CColor.White, 14f, mid, style = Stroke(3f))
                    }
                }
            }
        }
        Text(if (free) "Free transform: drag each corner separately." else "Drag corners / edges. Two fingers = zoom, move, rotate image.   Rotate: ${cs.rot.roundToInt()}°", fontSize = 11.sp)
        Slider(cs.rot, { cs.rot = it }, valueRange = -180f..180f)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Crop & Use in Resizer") { if (cs.quad.size == 4) onUse(makeCrop()) }
            Btn("Save", false) {
                if (cs.quad.size == 4) scope.launch {
                    try { withContext(Dispatchers.Default) { save(ctx, makeCrop(), "JPEG", 95, 300f) }; toast(ctx, "Saved to Download folder") }
                    catch (e: Throwable) { toast(ctx, "Save failed: ${e.message}") }
                }
            }
        }
    }
}

// ---------- Merge images on one paper ----------
@Composable
fun MergeScreen(ctx: Context, ms: MS, onAdd: () -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    fun scaleSel(f: Float) {
        val i = ms.sel
        if (i in ms.rects.indices) {
            val r = ms.rects[i]; val c = r.center; val w = r.width * f; val h = r.height * f
            ms.rects[i] = Rect(c.x - w / 2, c.y - h / 2, c.x + w / 2, c.y + h / 2)
        }
    }
    fun export(pdf: Boolean) {
        if (ms.bmps.isEmpty()) { toast(ctx, "Add images first"); return }
        scope.launch {
            try {
                withContext(Dispatchers.Default) {
                    val bm = renderMerged(ms.bmps.toList(), ms.rects.toList(), ms.pw, ms.ph, ms.bg, 250f)
                    save(ctx, bm, if (pdf) "PDF" else "JPEG", 95, 250f)
                }
                toast(ctx, "Saved to Download folder")
            } catch (e: Throwable) { toast(ctx, "Save failed: ${e.message}") }
        }
    }
    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        HRow {
            Btn("Back", false) { onBack() }
            Btn("Add Images") { onAdd() }
            Btn("Clear", false) { ms.bmps.clear(); ms.rects.clear(); ms.sel = -1 }
        }
        HRow {
            Text("Paper:", fontSize = 11.sp)
            papers.forEachIndexed { i, p -> Chip(p.name, ms.paper == i) { ms.paper = i; ms.relayout() } }
            Chip("Landscape", ms.land) { ms.land = !ms.land; ms.relayout() }
        }
        HRow {
            Chip("Auto", ms.auto) { ms.auto = true; ms.relayout() }
            Chip("Manual", !ms.auto) { ms.auto = false }
            Text("Color:", fontSize = 11.sp)
            bgOptions.forEach { (n, c) -> Chip(n, ms.bg == c) { ms.bg = c } }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.aspectRatio(ms.pw / ms.ph).background(CColor(ms.bg)).border(1.dp, CColor.Gray)
                .onSizeChanged { ms.pv = it }
                .pointerInput(ms.auto) {
                    if (!ms.auto) awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val k = ms.pv.width / ms.pw
                        if (k <= 0f) return@awaitEachGesture
                        val pt = down.position / k
                        val hitIdx = ms.rects.indexOfLast { it.contains(pt) }
                        ms.sel = hitIdx
                        do {
                            val ev = awaitPointerEvent()
                            val i = ms.sel
                            if (hitIdx >= 0 && i in ms.rects.indices) {
                                val z = ev.calculateZoom(); val pan = ev.calculatePan() / k
                                val r = ms.rects[i]; val c = r.center + pan
                                val w = r.width * z; val h = r.height * z
                                ms.rects[i] = Rect(c.x - w / 2, c.y - h / 2, c.x + w / 2, c.y + h / 2)
                            }
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        } while (ev.changes.any { it.pressed })
                    }
                }) {
                Canvas(Modifier.fillMaxSize()) {
                    val k = size.width / ms.pw
                    ms.rects.forEachIndexed { i, r ->
                        if (i < ms.bmps.size) drawImage(ms.bmps[i].asImageBitmap(),
                            dstOffset = IntOffset((r.left * k).roundToInt(), (r.top * k).roundToInt()),
                            dstSize = IntSize((r.width * k).roundToInt().coerceAtLeast(1), (r.height * k).roundToInt().coerceAtLeast(1)),
                            filterQuality = FilterQuality.Medium)
                        if (i == ms.sel && !ms.auto) drawRect(CColor(0xFF1E88E5), Offset(r.left * k, r.top * k), Size(r.width * k, r.height * k), style = Stroke(3f))
                    }
                }
            }
        }
        Text(if (ms.auto) "Auto layout: images are arranged to fit the paper. Choose Manual to place them yourself."
        else "Manual: tap an image, drag to move, pinch (or Bigger/Smaller) to resize.", fontSize = 11.sp)
        HRow {
            Btn("Bigger", false, !ms.auto) { scaleSel(1.1f) }
            Btn("Smaller", false, !ms.auto) { scaleSel(0.9f) }
            Btn("Remove", false, !ms.auto) {
                val i = ms.sel
                if (i in ms.rects.indices) { ms.bmps.removeAt(i); ms.rects.removeAt(i); ms.sel = -1 }
            }
            Btn("Auto arrange", false) { ms.relayout() }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Save Image") { export(false) }
            Btn("Save PDF") { export(true) }
        }
    }
}

// ---------- Images -> PDF ----------
@Composable
fun PdfScreen(ctx: Context, ps: PS, onAdd: () -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HRow {
            Btn("Back", false) { onBack() }
            Btn("Select Images") { onAdd() }
            Btn("Clear", false) { ps.uris.clear() }
        }
        HRow {
            Text("Page:", fontSize = 11.sp)
            Chip("A4 (fit image)", ps.a4) { ps.a4 = true }
            Chip("Same as image", !ps.a4) { ps.a4 = false }
        }
        Text("${ps.uris.size} image(s) - one page each, in this order", fontSize = 11.sp)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(ps.uris) { i, u ->
                val th by produceState<Bitmap?>(null, u) { value = withContext(Dispatchers.IO) { load(ctx, u, 160)?.first } }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(44.dp).border(1.dp, CColor.Gray), contentAlignment = Alignment.Center) {
                        th?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                    }
                    Text("Page ${i + 1}", Modifier.weight(1f), fontSize = 12.sp)
                    Btn("Up", false, i > 0) { val t = ps.uris[i]; ps.uris[i] = ps.uris[i - 1]; ps.uris[i - 1] = t }
                    Btn("Down", false, i < ps.uris.size - 1) { val t = ps.uris[i]; ps.uris[i] = ps.uris[i + 1]; ps.uris[i + 1] = t }
                    Btn("X", false) { ps.uris.removeAt(i) }
                }
            }
        }
        Btn("Convert to PDF (Download folder)") {
            if (ps.uris.isEmpty()) toast(ctx, "Select images first")
            else scope.launch {
                try { withContext(Dispatchers.IO) { imagesToPdf(ctx, ps.uris.toList(), ps.a4) }; toast(ctx, "PDF saved to Download folder") }
                catch (e: Throwable) { toast(ctx, "Failed: ${e.message}") }
            }
        }
    }
}
