package fr.eva.cloudgallery.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import fr.eva.cloudgallery.R
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fr.eva.cloudgallery.ui.theme.OneUiTheme

/**
 * Boîte de dialogue One UI : carte arrondie flottant en bas de l'écran, boutons texte séparés
 * par un trait vertical. [destructive] colore le bouton de confirmation en rouge.
 */
@Composable
fun OneUiDialog(
    title: String?,
    onDismiss: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    dismissLabel: String? = null,
    message: String? = null,
    destructive: Boolean = false,
    content: (@Composable () -> Unit)? = null,
) {
    OneUiDialogImpl(
        title = title,
        onDismiss = onDismiss,
        confirmLabel = confirmLabel,
        onConfirm = onConfirm,
        dismissLabel = dismissLabel,
        message = message,
        destructive = destructive,
        confirmEnabled = true,
        content = content,
    )
}

@Composable
internal fun OneUiDialogImpl(
    title: String?,
    onDismiss: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    dismissLabel: String?,
    message: String?,
    destructive: Boolean,
    confirmEnabled: Boolean,
    content: (@Composable () -> Unit)?,
) {
    val colors = OneUiTheme.colors
    val dimens = OneUiTheme.dimens
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Zone plein écran : un toucher hors de la carte ferme la boîte de dialogue.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(interactionSource = null, indication = null, onClick = onDismiss)
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(dimens.dialogRadius))
                    .background(if (colors.isDark) colors.sheet else colors.card)
                    // Absorbe les clics pour ne pas fermer la boîte en touchant la carte.
                    .clickable(interactionSource = null, indication = null, onClick = {})
                    .padding(top = 24.dp, bottom = 8.dp),
            ) {
                if (title != null) {
                    Text(
                        text = title,
                        style = OneUiTheme.typography.dialogTitle,
                        color = colors.textPrimary,
                        modifier = Modifier
                            .padding(horizontal = 24.dp)
                            .semantics { heading() },
                    )
                }
                if (message != null) {
                    if (title != null) Spacer(Modifier.height(12.dp))
                    Text(
                        text = message,
                        style = OneUiTheme.typography.body,
                        color = if (title != null) colors.textSecondary else colors.textPrimary,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
                if (content != null) {
                    Spacer(Modifier.height(if (title != null || message != null) 12.dp else 0.dp))
                    Box(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        content()
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (dismissLabel != null) {
                        DialogTextButton(
                            label = dismissLabel,
                            color = colors.textPrimary,
                            enabled = true,
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            Modifier
                                .width(1.dp)
                                .height(20.dp)
                                .background(colors.divider),
                        )
                    }
                    DialogTextButton(
                        label = confirmLabel,
                        color = if (destructive) colors.danger else colors.textPrimary,
                        enabled = confirmEnabled,
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DialogTextButton(
    label: String,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(ComponentShapes.small)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = OneUiTheme.typography.button,
            color = if (enabled) color else color.copy(alpha = 0.38f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Boîte de saisie de texte One UI (création / renommage d'album…). Le texte initial est
 * entièrement sélectionné ; la confirmation est désactivée tant que la saisie est vide.
 * [message] : texte facultatif affiché au-dessus du champ.
 */
@Composable
fun OneUiTextInputDialog(
    title: String,
    initialValue: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    placeholder: String? = null,
    message: String? = null,
) {
    val colors = OneUiTheme.colors
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialValue, selection = TextRange(0, initialValue.length)))
    }
    val focusRequester = remember { FocusRequester() }
    val canConfirm = value.text.isNotBlank()
    val confirm: () -> Unit = { if (value.text.isNotBlank()) onConfirm(value.text.trim()) }

    OneUiDialogImpl(
        title = title,
        onDismiss = onDismiss,
        confirmLabel = confirmLabel,
        onConfirm = confirm,
        dismissLabel = stringResource(R.string.common_cancel),
        message = message,
        destructive = false,
        confirmEnabled = canConfirm,
        content = {
            Column(Modifier.padding(horizontal = 24.dp)) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    textStyle = OneUiTheme.typography.listTitle.copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.accent),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { confirm() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .padding(vertical = 10.dp),
                    decorationBox = { inner ->
                        Box {
                            if (value.text.isEmpty() && placeholder != null) {
                                Text(
                                    text = placeholder,
                                    style = OneUiTheme.typography.listTitle,
                                    color = colors.textTertiary,
                                )
                            }
                            inner()
                        }
                    },
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(colors.accent),
                )
                LaunchedEffect(Unit) {
                    // Le champ vit dans la fenêtre du Dialog : ignorer si le nœud n'est pas encore attaché.
                    runCatching { focusRequester.requestFocus() }
                }
            }
        },
    )
}
