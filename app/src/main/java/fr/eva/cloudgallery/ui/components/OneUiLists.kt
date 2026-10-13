package fr.eva.cloudgallery.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.eva.cloudgallery.ui.theme.OneUiTheme

/** One UI rounded card laid on `groupedBackground` (settings screens, menu). */
@Composable
fun OneUiCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(OneUiTheme.dimens.cardRadius))
            .background(OneUiTheme.colors.card),
        content = content,
    )
}

/** Small category title above a card. */
@Composable
fun OneUiCategoryHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = OneUiTheme.typography.categoryHeader,
        color = OneUiTheme.colors.textSecondary,
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = OneUiTheme.dimens.cardPadding + 4.dp,
                end = OneUiTheme.dimens.cardPadding,
                top = 20.dp,
                bottom = 8.dp,
            )
            .semantics { heading() },
    )
}

/**
 * One UI list row (inside a [OneUiCard]). Clickable if [onClick] is provided.
 * [destructive] colours the title red (irreversible action, e.g. sign-out).
 */
@Composable
fun OneUiListItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    destructive: Boolean = false,
) {
    ListRow(
        modifier = if (onClick != null) {
            modifier.clickable(role = Role.Button, onClick = onClick)
        } else {
            modifier
        },
        title = title,
        subtitle = subtitle,
        icon = icon,
        trailing = trailing,
        titleColor = if (destructive) OneUiTheme.colors.danger else OneUiTheme.colors.textPrimary,
    )
}

/** Row with a switch; the whole row toggles the value. */
@Composable
fun OneUiSwitchItem(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val colors = OneUiTheme.colors
    ListRow(
        modifier = modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        title = title,
        subtitle = subtitle,
        icon = null,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = colors.switchTrackOn,
                    checkedBorderColor = colors.switchTrackOn,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = colors.textTertiary.copy(alpha = 0.6f),
                    uncheckedBorderColor = Color.Transparent,
                ),
            )
        },
    )
}

@Composable
private fun ListRow(
    modifier: Modifier,
    title: String,
    subtitle: String?,
    icon: ImageVector?,
    trailing: (@Composable () -> Unit)?,
    titleColor: Color = OneUiTheme.colors.textPrimary,
) {
    val colors = OneUiTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = OneUiTheme.dimens.listItemMinHeight)
            .padding(horizontal = OneUiTheme.dimens.cardPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.textPrimary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(text = title, style = OneUiTheme.typography.listTitle, color = titleColor)
            if (!subtitle.isNullOrEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(text = subtitle, style = OneUiTheme.typography.listSubtitle, color = colors.textSecondary)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** Thin One UI divider; [startIndent] aligns the line with the rows' text. */
@Composable
fun OneUiDivider(
    modifier: Modifier = Modifier,
    startIndent: Dp = 0.dp,
) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = startIndent)
            .height(0.5.dp)
            .background(OneUiTheme.colors.divider),
    )
}
