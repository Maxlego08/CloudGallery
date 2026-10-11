package fr.eva.cloudgallery.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.eva.cloudgallery.ui.theme.OneUiTheme

/**
 * Bouton en pilule One UI (écran d'autorisation, bandeaux, états vides).
 * [filled] = fond d'accent et texte blanc ; sinon fond teinté discret et texte d'accent.
 */
@Composable
fun OneUiPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    enabled: Boolean = true,
) {
    val colors = OneUiTheme.colors
    val container = if (filled) colors.accent else colors.tabIndicator
    val content = if (filled) Color.White else colors.accent
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(ComponentShapes.pill)
            .background(if (enabled) container else container.copy(alpha = container.alpha * 0.38f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = OneUiTheme.typography.button,
            color = if (enabled) content else content.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )
    }
}
