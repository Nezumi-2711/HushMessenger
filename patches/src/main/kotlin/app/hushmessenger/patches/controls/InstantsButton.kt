package app.hushmessenger.patches.controls

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val INSTANTS_BUTTON = "instants_button"

/**
 * The two events the chat list logs when it attaches the Instants button: once before it inflates the button's view,
 * once after, with whether a view was already there. Messenger calls the button a "corner peek", since it slides in at
 * the bottom right corner of the chat list, and it keeps both names on every build.
 */
internal const val CORNER_PEEK_ATTACH = "qs_corner_peek_attach"
internal const val CORNER_PEEK_ATTACHED = "qs_corner_peek_attached"

private fun Method.strings() = implementation?.instructions
    ?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }?.toSet().orEmpty()

/**
 * The step that attaches the Instants button to the chat list: static and void, handed the chat list fragment it's
 * declared on, it inflates the button's view from its stub, builds the controller that slides it in and out with the
 * list, and logs both events. The fragment's layout pass, its scroll listener and its resume coroutine all go through
 * it, and none of them logs the events itself, so the events pick out the one method.
 */
internal fun Method.isInstantsButtonAttach(): Boolean {
    if (!AccessFlags.STATIC.isSet(accessFlags) || returnType != "V") return false
    if (parameterTypes.size != 1 || parameterTypes.single().toString() != definingClass) return false
    val strings = strings()
    return CORNER_PEEK_ATTACH in strings && CORNER_PEEK_ATTACHED in strings
}

internal fun findInstantsButton(classes: Iterable<ClassDef>): List<Method> =
    classes.flatMap { it.methods }.filter { it.isInstantsButtonAttach() }

internal fun MutableMethod.validateInstantsButton() {
    if (!isInstantsButtonAttach()) throw PatchException("Messenger controls: ${hookId()} isn't the Instants button's attach step")
    validateSwitch()
}

/**
 * While the switch is on the attach step returns before it inflates the button, so the chat list never shows it. Every
 * later call returns the same way, since the fragment keeps asking until a button is attached. Off, Pause and safe mode
 * run Messenger's own attach, button and all.
 */
internal fun MutableMethod.injectInstantsButton() {
    validateInstantsButton()
    injectSwitch("$SETTINGS->hideInstantsButton()Z", "0x0")
}
