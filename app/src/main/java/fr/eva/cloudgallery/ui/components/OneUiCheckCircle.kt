package fr.eva.cloudgallery.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import fr.eva.cloudgallery.ui.theme.OneUiTheme

/**
 * One UI round selection checkbox laid over thumbnails: white ring on a dark scrim
 * when unchecked, accent disc with a white tick when checked.
 * Purely visual (semantics are carried by the cell).
 */
@Composable
fun OneUiCheckCircle(
    checked: Boolean,
    modifier: Modifier = Modifier,
) {
    CheckCircleImpl(
        checked = checked,
        uncheckedFill = Color(0x33000000),
        ringColor = OneUiTheme.colors.checkboxBorder,
        modifier = modifier,
    )
}

/** Variant for the app's light/dark background (selection bar "All"). */
@Composable
internal fun SelectAllCircle(checked: Boolean, modifier: Modifier = Modifier) {
    CheckCircleImpl(
        checked = checked,
        uncheckedFill = Color.Transparent,
        ringColor = OneUiTheme.colors.textTertiary,
        modifier = modifier,
    )
}

@Composable
private fun CheckCircleImpl(
    checked: Boolean,
    uncheckedFill: Color,
    ringColor: Color,
    modifier: Modifier,
) {
    val accent = OneUiTheme.colors.accent
    val fill by animateColorAsState(if (checked) accent else uncheckedFill, tween(150), label = "checkFill")
    val ring by animateColorAsState(if (checked) accent else ringColor, tween(150), label = "checkRing")
    Box(
        modifier = modifier
            .size(22.dp)
            .background(fill, CircleShape)
            .border(1.5.dp, ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
