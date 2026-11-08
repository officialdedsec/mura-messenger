package com.mura.mesh.ui

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/**
 * Incognito keyboard: every text field inside [content] asks the keyboard not to learn from what you
 * type (Android's IME_FLAG_NO_PERSONALIZED_LEARNING — the same flag browsers use in private tabs).
 * Gboard and other well-behaved keyboards then don't save words, suggestions or typing history.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun IncognitoKeyboard(enabled: Boolean, content: @Composable () -> Unit) {
    // Always installed (so toggling it never rebuilds the screen); the flag is read for each new text field.
    val on = androidx.compose.runtime.rememberUpdatedState(enabled)
    InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            val noLearning = object : PlatformTextInputMethodRequest {
                override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                    val ic = request.createInputConnection(outAttributes)
                    if (on.value) outAttributes.imeOptions = outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    return ic
                }
            }
            nextHandler.startInputMethod(noLearning)
        },
        content = content,
    )
}
