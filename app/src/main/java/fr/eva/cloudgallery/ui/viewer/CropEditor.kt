package fr.eva.cloudgallery.ui.viewer

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Rotate90DegreesCw
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.domain.model.MediaDetails
import fr.eva.cloudgallery.ui.components.OneUiIconButton
import fr.eva.cloudgallery.ui.image.fullImageRequest
import fr.eva.cloudgallery.ui.theme.OneUiTheme
import fr.eva.cloudgallery.ui.theme.ViewerColors
import kotlin.math.max

private val CropRectSaver = Saver<CropRect, List<Float>>(
    save = { listOf(it.left, it.top, it.right, it.bottom) },
    restore = { CropRect(it[0], it[1], it[2], it[3]) },
)

/**
 * Fullscreen crop editor. Always saves a COPY (the original is never modified).
 * The rectangle passed to [onSave] is normalized in the space of the image rotated by [rotationDegrees]
 * (clockwise), with EXIF orientation already applied.
 */
@Composable
internal fun CropEditor(
    media: MediaDetails,
    uri: Uri,
    saving: Boolean,
    onCancel: () -> Unit,
    onSave: (rect: CropRect, rotationDegrees: Int) -> Unit,
) {
    BackHandler(onBack = onCancel)
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current

    var rotation by rememberSaveable { mutableIntStateOf(0) }
    var aspect by rememberSaveable { mutableStateOf(CropAspect.Free) }
    var rect by rememberSaveable(stateSaver = CropRectSaver) { mutableStateOf(CropRect.Full) }
    var imageSize by remember { mutableStateOf<Size?>(null) }
    var areaW by remember { mutableFloatStateOf(0f) }
    var areaH by remember { mutableFloatStateOf(0f) }

    val maxSide = remember(configuration.screenWidthDp, configuration.screenHeightDp, density.density) {
        (max(configuration.screenWidthDp, configuration.screenHeightDp) * density.density).toInt().coerceAtLeast(512)
    }
    val request = remember(uri, media.dateModified, maxSide) { fullImageRequest(context, uri, media.dateModified.toString(), maxSide) }

    val size = imageSize
    val rotatedW = if (size == null) 0f else if (rotation % 180 == 0) size.width else size.height
    val rotatedH = if (size == null) 0f else if (rotation % 180 == 0) size.height else size.width
    val rotatedAspect = if (rotatedH > 0f) rotatedW / rotatedH else 1f
    val canSave = size != null && !saving && !CropMath.isIdentity(rect, rotation)

    Box(
        Modifier
            .fillMaxSize()
            .background(ViewerColors.background)
            .pointerInput(Unit) { blockPointersBelow() } // blocks gestures to the viewer underneath
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(Modifier.fillMaxSize()) {
            // Top bar: Cancel | Crop | Save
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(OneUiTheme.dimens.appBarHeight)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EditorTextButton(label = stringResource(R.string.common_cancel), enabled = !saving, onClick = onCancel)
                Text(
                    text = stringResource(R.string.viewer_crop),
                    style = OneUiTheme.typography.appBarTitle,
                    color = ViewerColors.content,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                if (saving) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = ViewerColors.content, strokeWidth = 2.dp)
                    }
                } else {
                    EditorTextButton(
                        label = stringResource(R.string.viewer_crop_save),
                        enabled = canSave,
                        onClick = { onSave(rect, rotation) },
                    )
                }
            }

            // Image area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(28.dp)
                    .onSizeChanged {
                        areaW = it.width.toFloat()
                        areaH = it.height.toFloat()
                    },
                contentAlignment = Alignment.Center,
            ) {
                val (fw, fh) = if (size != null) ZoomMath.fitSize(rotatedW, rotatedH, areaW, areaH) else (areaW to areaH)
                val unrotW = if (rotation % 180 == 0) fw else fh
                val unrotH = if (rotation % 180 == 0) fh else fw
                Box(
                    modifier = Modifier
                        .requiredSize(with(density) { unrotW.toDp() }, with(density) { unrotH.toDp() })
                        .graphicsLayer { rotationZ = rotation.toFloat() },
                ) {
                    AsyncImage(
                        model = request,
                        contentDescription = media.displayName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        onSuccess = { success ->
                            val intrinsic = success.painter.intrinsicSize
                            if (intrinsic.isSpecified && intrinsic.width > 0f && intrinsic.height > 0f) imageSize = intrinsic
                        },
                    )
                }
                if (size != null && fw > 0f && fh > 0f) {
                    CropOverlay(
                        rect = rect,
                        fittedW = fw,
                        fittedH = fh,
                        enabled = !saving,
                        onDrag = { handle, dx, dy ->
                            rect = CropMath.drag(rect, handle, dx, dy, aspect.ratio, rotatedAspect)
                        },
                    )
                }
            }

            Text(
                text = stringResource(R.string.viewer_crop_info),
                style = OneUiTheme.typography.listSubtitle,
                color = ViewerColors.contentSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            )

            // Aspect ratios + rotation
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CropAspect.entries.forEach { preset ->
                    AspectChip(
                        label = stringResource(preset.labelRes),
                        selected = preset == aspect,
                        enabled = !saving && size != null,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            aspect = preset
                            rect = if (preset.ratio == null) rect else CropMath.fitAspect(preset.ratio, rotatedAspect)
                        },
                    )
                }
                OneUiIconButton(
                    icon = Icons.Outlined.Rotate90DegreesCw,
                    contentDescription = stringResource(R.string.viewer_crop_rotate),
                    enabled = !saving && size != null,
                    tint = ViewerColors.content,
                    onClick = {
                        rotation = (rotation + 90) % 360
                        val newAspect = if (rotatedAspect > 0f) 1f / rotatedAspect else 1f
                        rect = if (aspect.ratio == null) CropMath.rotateClockwise(rect) else CropMath.fitAspect(aspect.ratio, newAspect)
                    },
                )
            }
        }
    }
}

private val CropAspect.labelRes: Int
    get() = when (this) {
        CropAspect.Free -> R.string.viewer_crop_free
        CropAspect.Square -> R.string.viewer_crop_square
        CropAspect.FourThree -> R.string.viewer_crop_4_3
        CropAspect.SixteenNine -> R.string.viewer_crop_16_9
    }

/**
 * Crop frame: dims the outside, rule-of-thirds grid, corner handles.
 * The touch area extends [TouchPadding] beyond the image to catch handles at the edge.
 */
@Composable
private fun CropOverlay(
    rect: CropRect,
    fittedW: Float,
    fittedH: Float,
    enabled: Boolean,
    onDrag: (handle: CropHandle, dx: Float, dy: Float) -> Unit,
) {
    val density = LocalDensity.current
    val pad = with(density) { TouchPadding.toPx() }
    val touchRadius = with(density) { 24.dp.toPx() }
    val currentRect by rememberUpdatedState(rect)
    val currentOnDrag by rememberUpdatedState(onDrag)
    var active by remember { mutableStateOf<CropHandle?>(null) }

    Canvas(
        modifier = Modifier
            .requiredSize(with(density) { (fittedW + 2 * pad).toDp() }, with(density) { (fittedH + 2 * pad).toDp() })
            .pointerInput(enabled, fittedW, fittedH) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { pos ->
                        val r = currentRect
                        active = CropMath.hitTest(
                            x = pos.x - pad,
                            y = pos.y - pad,
                            left = r.left * fittedW,
                            top = r.top * fittedH,
                            right = r.right * fittedW,
                            bottom = r.bottom * fittedH,
                            radius = touchRadius,
                        )
                    },
                    onDragEnd = { active = null },
                    onDragCancel = { active = null },
                    onDrag = { change, amount ->
                        val handle = active ?: return@detectDragGestures
                        change.consume()
                        currentOnDrag(handle, amount.x / fittedW, amount.y / fittedH)
                    },
                )
            },
    ) {
        val l = pad + rect.left * fittedW
        val t = pad + rect.top * fittedH
        val r = pad + rect.right * fittedW
        val b = pad + rect.bottom * fittedH
        val imgL = pad
        val imgT = pad
        val imgR = pad + fittedW
        val imgB = pad + fittedH
        val dim = Color(0xA6000000)
        // Dimmed outside (4 bands).
        drawRect(dim, Offset(imgL, imgT), Size(fittedW, t - imgT))
        drawRect(dim, Offset(imgL, b), Size(fittedW, imgB - b))
        drawRect(dim, Offset(imgL, t), Size(l - imgL, b - t))
        drawRect(dim, Offset(r, t), Size(imgR - r, b - t))
        // Rule-of-thirds grid.
        val grid = Color(0x66FFFFFF)
        val thin = 1.dp.toPx()
        for (i in 1..2) {
            val x = l + (r - l) * i / 3f
            val y = t + (b - t) * i / 3f
            drawLine(grid, Offset(x, t), Offset(x, b), thin)
            drawLine(grid, Offset(l, y), Offset(r, y), thin)
        }
        // Frame.
        drawRect(Color.White, Offset(l, t), Size(r - l, b - t), style = Stroke(1.5.dp.toPx()))
        drawCorners(l, t, r, b, active)
    }
}

private val TouchPadding = 24.dp

/**
 * Makes the surface "touchable": a hit pointer-input node prevents hit testing of sibling elements
 * underneath (the viewer). Consumes nothing so as not to cancel the children's clicks.
 */
internal suspend fun PointerInputScope.blockPointersBelow() {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
    }
}

private fun DrawScope.drawCorners(l: Float, t: Float, r: Float, b: Float, active: CropHandle?) {
    val len = 20.dp.toPx().coerceAtMost(minOf(r - l, b - t) / 2f)
    val w = 3.dp.toPx()
    val c = Color.White
    val half = w / 2f
    fun corner(x: Float, y: Float, sx: Float, sy: Float, handle: CropHandle) {
        val stroke = if (active == handle) w * 1.5f else w
        drawLine(c, Offset(x - sx * half, y), Offset(x + sx * len, y), stroke)
        drawLine(c, Offset(x, y - sy * half), Offset(x, y + sy * len), stroke)
    }
    corner(l, t, 1f, 1f, CropHandle.TopLeft)
    corner(r, t, -1f, 1f, CropHandle.TopRight)
    corner(l, b, 1f, -1f, CropHandle.BottomLeft)
    corner(r, b, -1f, -1f, CropHandle.BottomRight)
}

@Composable
private fun EditorTextButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = OneUiTheme.typography.button,
            color = if (enabled) ViewerColors.content else ViewerColors.content.copy(alpha = 0.38f),
        )
    }
}

@Composable
private fun AspectChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .then(if (selected) Modifier.background(Color(0x33FFFFFF)) else Modifier)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 8.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = OneUiTheme.typography.body.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal),
            color = if (enabled) ViewerColors.content else ViewerColors.content.copy(alpha = 0.38f),
            maxLines = 1,
        )
    }
}
