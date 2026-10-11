package fr.eva.cloudgallery.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import fr.eva.cloudgallery.ui.theme.OneUiTheme

data class OneUiMenuItem(
    val label: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * Menu contextuel One UI (coins très arrondis, fond « sheet », sans icônes).
 * À ancrer dans un Box contenant le bouton ⋮. Chaque clic ferme le menu puis exécute l'action.
 */
@Composable
fun OneUiOverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    items: List<OneUiMenuItem>,
) {
    val colors = OneUiTheme.colors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 200.dp),
        offset = DpOffset(0.dp, 4.dp),
        shape = ComponentShapes.medium,
        containerColor = colors.sheet,
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
    ) {
        items.forEach { item ->
            DropdownMenuItem(
                text = { Text(item.label, style = OneUiTheme.typography.listTitle) },
                onClick = {
                    onDismiss()
                    item.onClick()
                },
                enabled = item.enabled,
                modifier = Modifier.heightIn(min = 52.dp),
                colors = MenuDefaults.itemColors(
                    textColor = colors.textPrimary,
                    disabledTextColor = colors.textTertiary,
                ),
                contentPadding = PaddingValues(horizontal = 24.dp),
            )
        }
    }
}
