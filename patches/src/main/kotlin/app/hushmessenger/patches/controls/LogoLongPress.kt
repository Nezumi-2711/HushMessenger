package app.hushmessenger.patches.controls

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/*
 * The Messenger logo at the top of Chats is the "messenger" wordmark Litho draws as the Chats tab's toolbar title
 * (M4TabNavigationFragment.updateToolbar builds that title with the INBOX tab type). Litho gives the title a long press
 * and no tap. The long press calls the tab's title shortcut handler with the toolbar's Context and reports the press as
 * handled, so Android plays its long-press vibration. For Chats that handler is a small class whose only field is the
 * FbUserSession. Its one method asks the TitleShortcut plugin, whose only implementation is Meta's internal one and is
 * gated off for everyone else, so on a normal account the press does nothing. The Menu tab's handler holds its tab
 * content instead of the session, which keeps it out. While chats are being selected, Messenger swaps the logo for
 * "Select chats" or "N selected" text (InboxTabContentImplementation's selection mode), which has no long press.
 */

/**
 * The plugin spec a long press on a home tab's toolbar title asks for. Messenger looks plugins up by these names at
 * runtime, so Redex keeps the string while it renames the classes around it.
 */
internal const val TITLE_SHORTCUT_SPEC =
    "com_facebook_messaging_navigation_home_tabs_plugins_interfaces_tabtoolbartitleshortcut_TabToolbarTitleShortcutHandlerInterfaceSpec"
private const val CONTEXT = "Landroid/content/Context;"

/** Opens HushMessenger settings for a long press on the Messenger logo. Takes the toolbar's Context. */
internal const val LOGO_LONG_PRESSED = "$SETTINGS->logoLongPressed($CONTEXT)V"

/** The Chats title handlers settings discovery found in this run, for Open settings from menu. Cleared at the end. */
internal var logoLongPressSites: List<Method> = emptyList()

/**
 * Every Chats title long press handler: an instance (Context)V method that asks the TitleShortcut plugin, in a class
 * with one interface and the FbUserSession as its only instance field. Every supported build has exactly one.
 */
internal fun findLogoLongPress(classes: Iterable<ClassDef>): List<Method> = classes.asSequence()
    .filter { type -> type.interfaces.size == 1 && type.instanceFields.map { it.type } == listOf(FB_USER_SESSION) }
    .flatMap { type -> type.methods.asSequence().filter { it.asksTitleShortcut() } }
    .toList()

private fun Instruction.loadsTitleShortcutSpec() =
    ((this as? ReferenceInstruction)?.reference as? StringReference)?.string == TITLE_SHORTCUT_SPEC

private fun Method.asksTitleShortcut() = !AccessFlags.STATIC.isSet(accessFlags) && returnType == "V" &&
    parameterTypes.map { it.toString() } == listOf(CONTEXT) &&
    implementation?.instructions?.any { it.loadsTitleShortcutSpec() } == true

/**
 * Where the call to Settings goes in a Chats title handler: its one return, the last instruction. Messenger's own plugin
 * lookup runs first that way, and nothing before it can jump past the call. The Context must still be in its parameter
 * register there. Throws, naming what changed, for any other shape.
 */
internal fun Method.logoLongPressIndex(): Int {
    fun changed(detail: String): Nothing =
        throw PatchException("The Chats title's long press handler $definingClass->$name $detail")
    val code = implementation?.instructions?.toList() ?: changed("has no body")
    if (!AccessFlags.PUBLIC.isSet(accessFlags) || AccessFlags.STATIC.isSet(accessFlags) || returnType != "V" ||
        parameterTypes.map { it.toString() } != listOf(CONTEXT)) changed("is no longer a public (Context)V instance method")
    if (code.count { it.loadsTitleShortcutSpec() } != 1) changed("no longer asks the title shortcut plugin exactly once")
    if (code.any { it is OffsetInstruction } || implementation!!.tryBlocks.isNotEmpty()) changed("no longer runs straight through")
    if (code.lastOrNull()?.opcode != Opcode.RETURN_VOID || code.count { it.opcode == Opcode.RETURN_VOID || it.opcode == Opcode.THROW } != 1) {
        changed("no longer ends in its only return")
    }
    val context = parameterRegister(1)
    val overwritten = code.any { instruction ->
        val written = (instruction as? OneRegisterInstruction)?.registerA
        instruction.opcode.setsRegister() && written != null &&
            (written == context || (instruction.opcode.setsWideRegister() && written + 1 == context))
    }
    if (overwritten) changed("writes over its Context (v$context) before the return")
    return code.lastIndex
}

/** Calls Settings with the handler's Context right before its return, after Messenger's own handling of the press. */
internal fun MutableMethod.injectLogoLongPress() {
    val exit = logoLongPressIndex()
    addInstructions(exit, "invoke-static/range {p1 .. p1}, $LOGO_LONG_PRESSED")
}

private fun skipLogoLongPress(why: String) = java.util.logging.Logger.getLogger("").warning(
    "Open settings from menu: $why, so a long press on the Messenger logo won't open HushMessenger settings. " +
        "The Menu tab and side menu rows still do.",
)

/** The one handler to change, or null after logging why the logo stays as it was. */
internal fun logoLongPressSite(sites: List<Method>): Method? {
    val site = sites.singleOrNull() ?: run {
        skipLogoLongPress("found ${sites.size} Chats title long press handlers instead of one")
        return null
    }
    return try {
        site.logoLongPressIndex()
        site
    } catch (failure: PatchException) {
        skipLogoLongPress(failure.message.orEmpty())
        null
    }
}

/**
 * Makes a long press on the Messenger logo at the top of Chats open HushMessenger settings too. A build that doesn't
 * have exactly one Chats title handler of the tested shape keeps its logo as it was, the patcher log says why, and the
 * patch that asked still applies everything else. The handler is checked before its class is requested for editing.
 */
internal fun BytecodePatchContext.hookLogoLongPress(sites: List<Method> = logoLongPressSites) {
    val site = logoLongPressSite(sites) ?: return
    val target = runCatching { mutableClassDefBy(site.definingClass).methods.single { it.hookId() == site.hookId() } }
        .getOrElse {
            skipLogoLongPress("${site.hookId()} isn't in this APK")
            return
        }
    target.injectLogoLongPress()
}
