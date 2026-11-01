package com.alexdiab.stadiatv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.alexdiab.stadiatv.ui.theme.Palette
import com.alexdiab.stadiatv.ui.theme.Shapes
import com.alexdiab.stadiatv.ui.theme.Type
import kotlinx.coroutines.android.awaitFrame

/**
 * A text field built for a remote. The row is a normal focusable card; pressing select turns it into an editor and
 * opens the on-screen keyboard (or voice input), and "Done" returns focus to the card so D-pad navigation carries on.
 */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    secret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onDone: (() -> Unit)? = null,
) {
    var editing by remember { mutableStateOf(false) }
    // Whether the editor has actually held focus yet. It reports "not focused" the moment it appears, before focus has
    // moved to it; treating that as the user leaving would close the editor before it could ever open.
    var editorHadFocus by remember { mutableStateOf(false) }
    val inputFocus = remember { FocusRequester() }
    val cardFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    fun stopEditing() {
        editing = false
        editorHadFocus = false
        keyboard?.hide()
        runCatching { cardFocus.requestFocus() }
    }

    LaunchedEffect(editing) {
        if (!editing) return@LaunchedEffect
        editorHadFocus = false
        // The editor only exists once this has composed and been placed, so wait for it, then take focus. If focus
        // can't be taken, give up and leave the card as it was rather than sit in a half-open state.
        var focused = false
        repeat(6) {
            if (!focused) {
                awaitFrame()
                focused = runCatching { inputFocus.requestFocus() }.getOrDefault(false)
            }
        }
        if (focused) keyboard?.show() else editing = false
    }

    FocusCard(
        onClick = { editing = true },
        modifier = modifier.fillMaxWidth().heightIn(min = 92.dp).focusRequester(cardFocus),
        shape = RoundedCornerShape(Shapes.Lg),
        focusedScale = 1.015f,
        restingBorder = if (editing) Palette.Accent else Palette.Hairline,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label.uppercase(), style = Type.Overline, color = if (editing) Palette.AccessibleAccent else Palette.TextSecondary)
                if (editing) {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        singleLine = true,
                        textStyle = Type.Headline.copy(color = Palette.TextPrimary),
                        cursorBrush = SolidColor(Palette.AccessibleAccent),
                        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboardType, imeAction = imeAction),
                        keyboardActions = KeyboardActions(onAny = {
                            stopEditing()
                            onDone?.invoke()
                        }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(inputFocus)
                            .onFocusChanged {
                                if (it.isFocused) editorHadFocus = true
                                else if (editorHadFocus && editing) { editorHadFocus = false; editing = false; keyboard?.hide() }
                            }
                            // Select on the open editor brings the keyboard back if it was dismissed with Back.
                            .onPreviewKeyEvent { event ->
                                if (event.key == Key.DirectionCenter && event.type == KeyEventType.KeyDown) { keyboard?.show(); true } else false
                            },
                    )
                } else {
                    val shown = when {
                        value.isEmpty() -> placeholder
                        secret -> "•".repeat(value.length.coerceAtMost(24))
                        else -> value
                    }
                    Text(
                        shown,
                        style = Type.Headline,
                        color = if (value.isEmpty()) Palette.TextTertiary else Palette.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!editing) Icon(Icons.Rounded.Edit, null, tint = Palette.TextTertiary, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
fun FieldGroup(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) { content() }
}
