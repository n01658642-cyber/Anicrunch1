package com.anilili.ui.adaptive

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.anilili.diagnostics.DiagnosticsLog
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.withTimeoutOrNull

enum class TvTextInputType {
    TEXT,
    EMAIL,
    PASSWORD,
    NUMBER,
}

/**
 * A real Android [EditText] hosted inside Compose for TV.
 *
 * Fire TV's IME can acknowledge a show request without ever attaching to a Compose text input.
 * A native editor provides the platform input connection expected by Fire TV IME, TalkBack, and
 * phone-remote keyboards. D-pad focus alone does not summon the keyboard; Select/Enter/Button A
 * explicitly begins editing.
 */
@Composable
fun TvNativeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    inputType: TvTextInputType = TvTextInputType.TEXT,
    imeAction: Int = EditorInfo.IME_ACTION_DONE,
    onImeAction: () -> Unit = {},
    onMoveDown: (() -> Unit)? = null,
    onMoveRight: (() -> Unit)? = null,
    onMoveUp: (() -> Unit)? = null,
    tvFocusTarget: TvFocusTarget? = null,
    focusable: Boolean = true,
    autoOpenKeyboard: Boolean = false,
    autoOpenToken: Any? = null,
    minHeight: androidx.compose.ui.unit.Dp = 56.dp,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(10.dp),
) {
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnImeAction by rememberUpdatedState(onImeAction)
    val currentOnMoveDown by rememberUpdatedState(onMoveDown)
    val currentOnMoveRight by rememberUpdatedState(onMoveRight)
    val currentOnMoveUp by rememberUpdatedState(onMoveUp)
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val surfaceColor = MaterialTheme.colorScheme.surface.toArgb()
    val outlineColor = MaterialTheme.colorScheme.outline.toArgb()
    val focusedOutlineColor = MaterialTheme.colorScheme.primary.toArgb()
    var focused by remember { mutableStateOf(false) }
    var editorHolder by remember { mutableStateOf<SafeTvEditText?>(null) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .clip(shape)
            .background(androidx.compose.ui.graphics.Color(surfaceColor))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = androidx.compose.ui.graphics.Color(
                    if (focused) focusedOutlineColor else outlineColor,
                ),
                shape = shape,
            ),
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .tvFocusTarget(tvFocusTarget),
            factory = { context ->
                SafeTvEditText(context).apply {
                    editorHolder = this
                    tag = NativeTvTextWatcher(this, currentOnValueChange).also(::addTextChangedListener)
                    setText(value)
                    setSelection(value.length)
                    setSingleLine(true)
                    this.hint = hint
                    setTextColor(textColor)
                    setHintTextColor(hintColor)
                    textSize = 18f
                    background = ColorDrawable(Color.TRANSPARENT)
                    val horizontal = (16 * resources.displayMetrics.density).toInt()
                    setPadding(horizontal, 0, horizontal, 0)
                    this.inputType = inputType.androidValue
                    this.imeOptions = imeAction
                    isFocusable = focusable
                    isFocusableInTouchMode = focusable
                    showSoftInputOnFocus = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    disableSelectionActionMode()

                    setOnFocusChangeListener { _, hasFocus ->
                        focused = hasFocus
                        if (!hasFocus) stopRequestingKeyboard()
                    }
                    setOnClickListener { beginNativeTvEditing(this) }
                    setOnKeyListener { _, keyCode, event ->
                        when {
                            event.action == KeyEvent.ACTION_DOWN && keyCode in TV_SELECT_KEYS -> {
                                beginNativeTvEditing(this)
                                true
                            }
                            event.action == KeyEvent.ACTION_DOWN &&
                                keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
                                currentOnMoveDown != null -> {
                                hideNativeTvKeyboard(this)
                                runCatching { currentOnMoveDown?.invoke() }
                                    .onFailure {
                                        DiagnosticsLog.throwable(
                                            "TvNativeTextField Down focus target detached",
                                            it,
                                        )
                                        moveNativeFocusSafely(View.FOCUS_DOWN)
                                    }
                                true
                            }
                            event.action == KeyEvent.ACTION_DOWN &&
                                keyCode == KeyEvent.KEYCODE_DPAD_UP &&
                                currentOnMoveUp != null -> {
                                // The same escape as Down, for screens with a control above.
                                hideNativeTvKeyboard(this)
                                runCatching { currentOnMoveUp?.invoke() }
                                    .onFailure {
                                        DiagnosticsLog.throwable("TvNativeTextField Up focus target detached", it)
                                        moveNativeFocusSafely(View.FOCUS_UP)
                                    }
                                true
                            }
                            event.action == KeyEvent.ACTION_DOWN &&
                                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT &&
                                currentOnMoveRight != null &&
                                selectionStart == text.length &&
                                selectionEnd == text.length -> {
                                // A native EditText consumes Right to move its cursor before
                                // Compose can run spatial focus search. Screens with a control to
                                // the field's right can provide an escape once the caret is at the
                                // end, while retaining normal cursor movement within the query.
                                hideNativeTvKeyboard(this)
                                runCatching { currentOnMoveRight?.invoke() }
                                    .onFailure {
                                        DiagnosticsLog.throwable(
                                            "TvNativeTextField Right focus target detached",
                                            it,
                                        )
                                        moveNativeFocusSafely(View.FOCUS_RIGHT)
                                    }
                                true
                            }
                            else -> false
                        }
                    }
                    setOnEditorActionListener { _, actionId, event ->
                        val requestedAction = actionId == imeAction ||
                            actionId == EditorInfo.IME_ACTION_DONE ||
                            actionId == EditorInfo.IME_ACTION_SEARCH ||
                            actionId == EditorInfo.IME_ACTION_NEXT
                        val enterKey = event?.action == KeyEvent.ACTION_DOWN &&
                            event.keyCode in TV_SELECT_KEYS
                        if (requestedAction || enterKey) {
                            hideNativeTvKeyboard(this)
                            currentOnImeAction()
                            true
                        } else {
                            false
                        }
                    }
                }
            },
            update = { editor ->
                // Every assignment here is guarded, because this block runs on each recomposition
                // and TextView setters are not free. `setInputType` in particular calls
                // InputMethodManager.restartInput(), which rebuilds the input connection and asks
                // hasEditorInFocusSearchDirection() — that lands in Compose's two-dimensional focus
                // search, which forces the results LazyGrid to lay out beyond its visible bounds.
                // Re-running that per keystroke starved the main thread until Android killed the
                // app for not responding to input: the "typing does nothing, then it closes"
                // report. Reproduced on an Android TV emulator; the phone path never hits it
                // because it uses a Compose text field with no input-connection probe.
                if (editor.hint != hint) editor.hint = hint
                if (editor.currentTextColor != textColor) editor.setTextColor(textColor)
                if (editor.currentHintTextColor != hintColor) editor.setHintTextColor(hintColor)
                if (editor.inputType != inputType.androidValue) editor.inputType = inputType.androidValue
                if (editor.imeOptions != imeAction) editor.imeOptions = imeAction
                if (editor.isFocusable != focusable) editor.isFocusable = focusable
                if (editor.isFocusableInTouchMode != focusable) editor.isFocusableInTouchMode = focusable
                val watcher = editor.tag as? NativeTvTextWatcher
                watcher?.onValueChange = currentOnValueChange
                val echoes = watcher?.pendingEchoes
                val echoIndex = echoes?.indexOf(value) ?: -1
                when {
                    // This value is the field's own keystroke coming back. The editor has already
                    // moved past it, so writing it would delete whatever was typed since.
                    echoIndex >= 0 -> repeat(echoIndex + 1) { echoes?.removeFirst() }
                    // A genuine external change: a picked history term, a reset, a restored query.
                    editor.text.toString() != value -> {
                        echoes?.clear()
                        val selection = editor.selectionStart.coerceAtLeast(0)
                        editor.setText(value)
                        editor.setSelection(selection.coerceAtMost(value.length))
                    }
                }
            },
        )
    }

    // Screens whose whole purpose is one entry — the OAuth login editor — open the keyboard
    // themselves rather than waiting for Select. The token re-triggers the open when the hosted
    // field swaps (email → password inside one dialog), which is exactly the swap whose
    // input-type change used to leave the keyboard dismissed-but-recorded-as-shown.
    if (autoOpenKeyboard) {
        LaunchedEffect(autoOpenToken) {
            val editor = editorHolder ?: return@LaunchedEffect
            // The hosting screen focuses the field on its own schedule (the dialog waits ~80ms);
            // a show before focus lands is refused, so wait for it — briefly, never forever.
            withTimeoutOrNull(KEYBOARD_FOCUS_WAIT_MS) {
                while (!editor.hasFocus() && editor.isAttachedToWindow) awaitFrame()
            }
            if (editor.hasFocus() && editor.isAttachedToWindow) beginNativeTvEditing(editor)
        }
    }
}

private class NativeTvTextWatcher(
    private val editor: EditText,
    var onValueChange: (String) -> Unit,
) : TextWatcher {
    /**
     * Values this field has pushed up that have not yet come back down through recomposition.
     *
     * Compose delivers state a frame or more later, so a second keystroke can land in the EditText
     * before the first one's value arrives in `update`. Writing that stale value back would undo
     * the newer keystroke — typing "Naruto" on a TV landed as "Nuo". Anything still queued here is
     * an echo of the user's own typing and must not be written back.
     */
    val pendingEchoes = ArrayDeque<String>()

    override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
    override fun afterTextChanged(text: Editable?) {
        val value = text?.toString().orEmpty()
        pendingEchoes.addLast(value)
        // A value the caller silently rewrites never comes back to clear its entry, so cap the
        // queue rather than letting a long session grow it without bound.
        while (pendingEchoes.size > MAX_PENDING_ECHOES) pendingEchoes.removeFirst()
        onValueChange(value)
        editor.postInvalidate()
    }

    private companion object {
        const val MAX_PENDING_ECHOES = 32
    }
}

private fun beginNativeTvEditing(editor: SafeTvEditText) {
    editor.requestKeyboardOnFocus()
    editor.requestFocus()
    editor.setSelection(editor.text.length)
    val manager = editor.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    if (manager == null) {
        DiagnosticsLog.event("TvNativeTextField no InputMethodManager")
        return
    }
    editor.post {
        // Ask with no flags at all — an *explicit* request. Both of the flags tried before this
        // are wrong for a television, for opposite reasons, and the platform says why:
        //
        // - SHOW_IMPLICIT is refused outright. `InputMethodService.onShowInputRequested` drops a
        //   non-explicit request when the IME would be fullscreen ("too disruptive"), and a TV
        //   keyboard is fullscreen. 0.1.67 shipped this and took Search away from every viewer.
        // - SHOW_FORCED is explicit, so it raises the keyboard — but it also latches
        //   `mShowForced`, and `hideCurrentInputLocked` refuses any hide carrying HIDE_NOT_ALWAYS
        //   while that is set. The system hides the IME with exactly that flag when the focused
        //   window changes, so a forced keyboard survives the viewer navigating away and is left
        //   floating over whatever screen they went to. That is the "keyboard will not go away"
        //   report, and no amount of cancelling our own retry can fix it: the app never asked for
        //   the re-show, it asked for a dismissal the system declined.
        //
        // No flags is explicit and not forced: fullscreen IMEs honour it, and every dismissal
        // path — Back, focus change, leaving the screen — still works.
        //
        // One hide goes first. Swapping the hosted field (email → password in the OAuth editor)
        // changes inputType and imeOptions, both of which restart the input connection — and on
        // a TV that restart dismisses the visible keyboard while the system still records it as
        // shown. Every show afterwards no-ops against that stale state, and the half-attached
        // IME keeps input focus, swallowing the remote's keys so even Select cannot reach the
        // field. A hide clears it; when the keyboard is genuinely up the show right behind the
        // hide re-raises it, so the cycle is safe either way.
        manager.hideSoftInputFromWindow(editor.windowToken, 0)
        val accepted = manager.showSoftInput(editor, 0)
        editor.disarmKeyboardOnFocus()
        DiagnosticsLog.event(
            "TvNativeTextField show accepted=$accepted ime=${manager.enabledInputMethodList.joinToString { it.id }}",
        )
        // Fire TV can accept a show request and never raise the keyboard, so one retry follows.
        // It is scheduled through the editor, which withdraws it on hide, focus loss, detach,
        // Back, and the window losing focus to the keyboard, so a dismissal still stands.
        editor.scheduleKeyboardRetry {
            if (!editor.isAttachedToWindow || editor.isImeVisible()) return@scheduleKeyboardRetry
            val retried = manager.showSoftInput(editor, 0)
            DiagnosticsLog.event("TvNativeTextField retry show accepted=$retried")
        }
    }
}

/**
 * Turns off the floating text-selection toolbar and its smart-selection classifier.
 *
 * A TV has no cursor to drag a selection with, so the toolbar is unreachable anyway — but the
 * platform still kicks off `SelectionActionModeHelper`'s async TextClassification task, and when
 * its result lands after the view has been re-laid-out the framework dereferences a null
 * `Layout`: `NullPointerException … Layout.getSelection` in
 * `convertSelectionToRectangles`, a fatal crash inside android.widget with no app frame to fix.
 * Seen on an Android TV 14 device. Removing the feature removes the task that crashes.
 */
private fun EditText.disableSelectionActionMode() {
    // Deliberately NOT setTextIsSelectable(false): on an EditText that also clears the movement
    // method, which breaks the caret and typing outright.
    isLongClickable = false
    val refuse = object : android.view.ActionMode.Callback {
        override fun onCreateActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?) = false
        override fun onPrepareActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?) = false
        override fun onActionItemClicked(mode: android.view.ActionMode?, item: android.view.MenuItem?) = false
        override fun onDestroyActionMode(mode: android.view.ActionMode?) = Unit
    }
    customSelectionActionModeCallback = refuse
    // Selection callbacks go back to API 11, but the *insertion* one arrived in API 23. minSdk is
    // 22 because Fire OS 5 sticks report exactly that, and on one (AFTT, Android 5.1.1) this line
    // threw NoSuchMethodError the moment the field entered composition — a fatal crash on every
    // screen with a text box.
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
        customInsertionActionModeCallback = refuse
    }
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        setTextClassifier(android.view.textclassifier.TextClassifier.NO_OP)
    }
}

private fun hideNativeTvKeyboard(editor: SafeTvEditText) {
    editor.stopRequestingKeyboard()
    val manager = editor.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    manager?.hideSoftInputFromWindow(editor.windowToken, 0)
}

private fun View.moveNativeFocusSafely(direction: Int) {
    runCatching {
        focusSearch(direction)
            ?.takeUnless { it === this }
            ?.takeIf { it.isAttachedToWindow && it.isFocusable }
            ?.requestFocus()
    }
}

private fun View.isImeVisible(): Boolean =
    ViewCompat.getRootWindowInsets(this)?.isVisible(WindowInsetsCompat.Type.ime()) == true

private val TvTextInputType.androidValue: Int
    get() = when (this) {
        TvTextInputType.TEXT -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        TvTextInputType.EMAIL -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        TvTextInputType.PASSWORD ->
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        TvTextInputType.NUMBER -> InputType.TYPE_CLASS_NUMBER
    }

private val TV_SELECT_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER,
    KeyEvent.KEYCODE_BUTTON_A,
    KeyEvent.KEYCODE_BUTTON_SELECT,
)

/** How long the auto-open waits for the hosting screen's focus request to land on the field. */
private const val KEYBOARD_FOCUS_WAIT_MS = 1_500L

/**
 * An [EditText] that guards against Compose focus search race conditions on Fire TV / Android 9.
 *
 * When an on-screen keyboard (IME) opens or requests input connection, Android's native
 * [TextView.onCreateInputConnection] calls [focusSearch]. If Compose focus state is currently
 * in transition, [super.focusSearch] can throw `IllegalStateException: ActiveParent must have a focusedChild`.
 */
private const val KEYBOARD_RETRY_DELAY_MS = 250L

private class SafeTvEditText(context: Context) : EditText(context) {
    /**
     * True only while the IME is building an input connection.
     *
     * `TextView.onCreateInputConnection` calls `hasEditorInFocusSearchDirection`, which runs a
     * focus search purely to decide whether the keyboard should offer a "next field" action. Here
     * that search crosses into Compose and forces the surrounding lazy grid through a
     * beyond-bounds layout pass — a whole screen of work to answer a cosmetic question. The
     * screens using this field set `imeOptions` explicitly, so the answer is not wanted anyway.
     */
    private var buildingInputConnection = false

    /**
     * The one pending "the keyboard never actually opened" retry, held so it can be withdrawn.
     *
     * Fire OS reports no IME window insets, so the retry's own visibility check cannot see a
     * keyboard that is already up and always fires. Keeping the handle is what makes dismissal
     * stick.
     */
    private var keyboardRetry: Runnable? = null

    /**
     * True from the moment this field asks for the keyboard until something dismisses it.
     *
     * It tracks `showSoftInputOnFocus` — a field that is no longer asking must not summon the IME
     * again just by holding focus — and it is what tells [onDetachedFromWindow] whether the
     * keyboard on screen is this field's to close.
     */
    private var keyboardRequested = false

    fun requestKeyboardOnFocus() {
        keyboardRequested = true
        showSoftInputOnFocus = true
    }

    /**
     * Stops focus alone from raising the keyboard, while the field stays the one that asked for it.
     *
     * `showSoftInputOnFocus` is armed only long enough for [requestFocus] to land; after the
     * explicit request has gone out it is a liability. Compose re-asserts focus on its interop
     * views when the window regains focus, which is exactly the moment a fullscreen keyboard has
     * just been dismissed — an armed field answers that by summoning the keyboard straight back,
     * and does it from Compose's handler, before this view's own window-focus callback can
     * intervene. Disarming here means no ordering between the two callbacks matters.
     */
    fun disarmKeyboardOnFocus() {
        showSoftInputOnFocus = false
    }

    fun stopRequestingKeyboard() {
        keyboardRequested = false
        showSoftInputOnFocus = false
        cancelKeyboardRetry()
    }

    fun scheduleKeyboardRetry(action: () -> Unit) {
        cancelKeyboardRetry()
        val retry = Runnable {
            keyboardRetry = null
            action()
        }
        keyboardRetry = retry
        postDelayed(retry, KEYBOARD_RETRY_DELAY_MS)
    }

    fun cancelKeyboardRetry() {
        keyboardRetry?.let(::removeCallbacks)
        keyboardRetry = null
    }

    /**
     * Back closes the keyboard, and the app must not answer by opening it again.
     *
     * The IME consumes Back itself to dismiss, so this never sees a second press — its job is to
     * drop the pending retry and stop the field asking for input the moment it regains focus.
     * Returning false lets the IME close as usual, leaving the next Back free to leave the screen.
     *
     * A fullscreen TV keyboard never delivers Back here at all: its window holds input focus, so
     * the press never reaches this window's pre-IME dispatch. [onWindowFocusChanged] covers that
     * case; this one still matters for the keyboards that dock instead.
     */
    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) stopRequestingKeyboard()
        return super.onKeyPreIme(keyCode, event)
    }

    /**
     * Window focus is the one reliable "is the keyboard up?" signal a television gives us.
     *
     * A fullscreen IME takes input focus, so losing it right after asking for the keyboard means
     * the keyboard arrived — a far better test than [isImeVisible], which reads nothing on Fire OS.
     * Getting focus back means that keyboard is gone, however it went: Back, a remote's own
     * dismiss, the system taking it away. Either way the field must stop asking, or the focus it
     * still holds summons the IME straight back and the viewer cannot leave.
     */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        cancelKeyboardRetry()
        if (hasWindowFocus) stopRequestingKeyboard()
        super.onWindowFocusChanged(hasWindowFocus)
    }

    /**
     * The field is leaving the screen, so the keyboard has nothing left to type into. Dismissing it
     * here is what closes it when the viewer navigates away rather than pressing Back.
     *
     * Only when this field is the one still asking: several screens carry more than one of these,
     * and the window token they would hide is shared, so an unguarded hide here would close the
     * keyboard out from under a sibling that is being typed into.
     */
    override fun onDetachedFromWindow() {
        if (keyboardRequested) {
            val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            windowToken?.let { manager?.hideSoftInputFromWindow(it, 0) }
        }
        stopRequestingKeyboard()
        super.onDetachedFromWindow()
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        buildingInputConnection = true
        return try {
            super.onCreateInputConnection(outAttrs)
        } finally {
            buildingInputConnection = false
        }
    }

    override fun focusSearch(direction: Int): View? {
        if (buildingInputConnection) return null
        return try {
            super.focusSearch(direction)
        } catch (e: IllegalStateException) {
            // Compose focus state can be mid-transition: "ActiveParent must have a focusedChild".
            this
        } catch (e: Exception) {
            this
        }
    }
}
