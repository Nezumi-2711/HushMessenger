package app.hushmessenger.patches.controls

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

/**
 * Puts what you pick in your gallery app into the chat's tray above the message box, the way Messenger's own gallery
 * does, instead of the editor or the Send to sheet. Nothing goes out until you tap Send (#101).
 */
internal const val GALLERY_TO_CHAT = "gallery_to_chat"

private const val MONTAGE_MODEL = "Lcom/facebook/messaging/montage/composer/model/"
internal const val EDITOR_BUILDER = "${MONTAGE_MODEL}MontageComposerFragmentParams\$Builder;"
internal const val EDITOR_PARAMS = "${MONTAGE_MODEL}MontageComposerFragmentParams;"
internal const val INSERT_PREVIEW = "Lcom/facebook/xapp/messaging/composer/attachmentpreview/events/InsertAttachmentPreviewEvent;"
internal const val INSERT_PREVIEW_INIT = "$INSERT_PREVIEW-><init>(${AUTO_REPLY_MEDIA}Ljava/util/List;)V"
internal const val GALLERY_TO_CHAT_CALL = "$SETTINGS->galleryToChat(Landroid/content/Intent;)[Landroid/content/Intent;"
internal const val GALLERY_CHAT_ITEM_CALL = "$SETTINGS->galleryChatItem(Landroid/content/Intent;Ljava/lang/Object;)Ljava/util/List;"
private const val ITEM_TYPE_CALL = "Landroid/content/Intent;->getType()Ljava/lang/String;"
private const val INVOKE_SUSPEND = "invokeSuspend(Ljava/lang/Object;)Ljava/lang/Object;"

/**
 * How a pick reaches the chat's tray, read at discovery: the chat's handler for a photo from another app and the result
 * event's intent field it reads, the coroutine it starts that copies the item and opens the editor, and the chat's event
 * dispatcher with the chat type it takes.
 */
internal data class GalleryChatRoute(val items: String, val itemsIntent: String, val editor: String, val dispatcher: String,
    val chat: String)

/** Set by [findGalleryToChat] and cleared once the controls are in. */
internal var galleryChatRoute: GalleryChatRoute? = null

private fun galleryChatChanged(detail: String): Nothing =
    throw PatchException("Messenger controls: the gallery result moved ($detail)")

private fun Instruction.endsRun() = this is OffsetInstruction || opcode in GALLERY_ENDS

/**
 * The photo handler's result event register, when the hook fits: four locals below the parameters for the result, the
 * items, the count and one item, and the event where a plain iget and iput reach it.
 */
private fun Method.galleryChatEvent(): Int? {
    val event = onlyParameter(ACTIVITY_RESULT_EVENT)?.let(::parameterRegister) ?: return null
    return event.takeIf { parameterRegister(0) >= 4 && it <= 15 }
}

/** Where the coroutine opens the editor with the item it copied, and the registers the hook there uses. */
internal class GalleryChatSite(val at: Int, val intent: Int, val media: Int, val chat: Int, val chatType: String, val params: Int,
    val unit: String)

/**
 * The coroutine copies the picked item into a MediaResource and builds the editor's params with it right after:
 *
 *     invoke-static {vX}, <copy>(...)MediaResource
 *     move-result-object vMedia
 *     new-instance vParams, MontageComposerFragmentParams$Builder          <- at
 *     ...
 *     iput-object vMedia, vParams, <builder>->?:MediaResource
 *     ...
 *     invoke-static {...}, <open>(?, MontageComposerFragmentParams, NavigationTrigger, I)V
 *     sget-object vU, <Unit>
 *     return-object vU
 *
 * The run of code into it, with no branch out and none landing inside, reads the intent's type and casts the chat to
 * one of [chats], and neither register changes after. The hook at the builder uses all four registers, so each is a
 * local a plain invoke reaches, and no try covers it. A branch that lands on the builder itself skips the hook, which is
 * the coroutine's way round the copy when the result has no item.
 */
internal fun Method.galleryChatSite(chats: Set<String>): GalleryChatSite? {
    val code = implementation?.instructions?.toList() ?: return null
    val at = code.indices.filter { code[it].opcode == Opcode.NEW_INSTANCE && code[it].galleryType() == EDITOR_BUILDER }.singleOrNull()
        ?: return null
    if (at < 2 || code[at - 1].opcode != Opcode.MOVE_RESULT_OBJECT) return null
    val copied = code[at - 2].galleryMethod() ?: return null
    if (code[at - 2].opcode != Opcode.INVOKE_STATIC && code[at - 2].opcode != Opcode.INVOKE_STATIC_RANGE) return null
    if (copied.parameterTypes.size != 1 || copied.returnType != AUTO_REPLY_MEDIA) return null
    val media = (code[at - 1] as OneRegisterInstruction).registerA
    val params = (code[at] as OneRegisterInstruction).registerA
    // On from the builder, straight to the editor and the return after it.
    var open = at + 1
    while (open < code.size) {
        val ref = code[open].galleryMethod()
        if (code[open].opcode == Opcode.INVOKE_STATIC && ref != null && ref.returnType == "V" &&
            ref.parameterTypes.map { it.toString() }.let { it.size == 4 && it[1] == EDITOR_PARAMS && it[2] == NAVIGATION_TRIGGER && it[3] == "I" }) break
        if (code[open].endsRun()) return null
        open++
    }
    if (open + 2 >= code.size || code[open + 1].opcode != Opcode.SGET_OBJECT || code[open + 2].opcode != Opcode.RETURN_OBJECT ||
        (code[open + 1] as OneRegisterInstruction).registerA != (code[open + 2] as OneRegisterInstruction).registerA) return null
    val unit = code[open + 1].galleryField()?.takeIf { it.type == it.definingClass } ?: return null
    if ((at + 1 until open).none { i ->
            val put = code[i] as? TwoRegisterInstruction
            code[i].opcode == Opcode.IPUT_OBJECT && put!!.registerA == media && put.registerB == params &&
                code[i].galleryField()?.let { it.definingClass == EDITOR_BUILDER && it.type == AUTO_REPLY_MEDIA } == true
        }) return null
    // Back from the copy to the last branch, return or switch.
    var start = at - 2
    while (start > 0 && !code[start - 1].endsRun()) start--
    val landings = jumpTargets()
    if ((start + 1 until at).any { it in landings }) return null
    fun lastWrite(register: Int, from: Int) = (from + 1 until at).none { code[it].galleryWrites(register) }
    val typed = (start until at).filter { code[it].opcode == Opcode.INVOKE_VIRTUAL && code[it].galleryMethod()?.toString() == ITEM_TYPE_CALL }
        .singleOrNull() ?: return null
    val intent = (code[typed] as FiveRegisterInstruction).registerC
    val cast = (start until at).filter { code[it].opcode == Opcode.CHECK_CAST && code[it].galleryType() in chats }.singleOrNull() ?: return null
    val chat = (code[cast] as OneRegisterInstruction).registerA
    if (!lastWrite(intent, typed) || !lastWrite(chat, cast)) return null
    val registers = listOf(intent, media, chat, params)
    if (registers.distinct().size != 4 || registers.any { it > 15 || it >= parameterRegister(0) }) return null
    val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
    if (implementation!!.tryBlocks.any { addresses[at] - it.startCodeAddress in 0 until it.codeUnitCount }) return null
    return GalleryChatSite(at, intent, media, chat, code[cast].galleryType()!!, params, unit.toString())
}

/**
 * The chat's event dispatchers by the chat type they take: the public static call Messenger makes with a new tray insert
 * right after building it, as the camera's result does with `new InsertAttachmentPreviewEvent(null, items)`.
 */
private fun chatDispatchers(methods: List<Method>, byType: Map<String, ClassDef>): Map<String, Set<String>> {
    val found = mutableMapOf<String, MutableSet<String>>()
    for (method in methods) {
        val code = method.implementation?.instructions?.toList() ?: continue
        for (i in 0 until code.size - 1) {
            if (code[i].opcode != Opcode.INVOKE_DIRECT || code[i].galleryMethod()?.toString() != INSERT_PREVIEW_INIT) continue
            val call = code[i + 1]
            val ref = call.galleryMethod() ?: continue
            if (call.opcode != Opcode.INVOKE_STATIC || ref.returnType != "V" || ref.parameterTypes.size != 2 ||
                ref.parameterTypes[1].toString() != COMPOSER_EVENT ||
                (call as FiveRegisterInstruction).registerD != (code[i] as FiveRegisterInstruction).registerC) continue
            found.getOrPut(ref.parameterTypes[0].toString()) { mutableSetOf() }.add(ref.toString())
        }
    }
    fun publicStatic(id: String): Boolean {
        val owner = byType[id.substringBefore("->")] ?: return false
        val m = owner.methods.singleOrNull { it.hookId() == id } ?: return false
        return AccessFlags.PUBLIC.isSet(owner.accessFlags) && AccessFlags.PUBLIC.isSet(m.accessFlags) && AccessFlags.STATIC.isSet(m.accessFlags)
    }
    return found.filterValues { ids -> ids.all(::publicStatic) }
}

/**
 * The chat's handler for a photo from another app and the coroutine it starts, or none. The handler opens one item in
 * Messenger's editor, so the hook at its start hands it the pick one item at a time, and the hook in the coroutine puts
 * each copied item in the chat's tray instead of opening the editor. That needs the handler exactly as Open your gallery
 * app finds it, with the result event's intent field one it can write, one coroutine it starts, the editor site there,
 * and one public dispatcher for the chat type the coroutine casts to.
 */
internal fun findGalleryToChat(classes: Iterable<ClassDef>): List<Method> {
    galleryChatRoute = null
    val all = classes.toList()
    val methods = all.flatMap { it.methods }
    val byType = all.associateBy { it.type }
    val (items, itemsIntent) = methods.mapNotNull { method -> method.galleryItemsIntent(byType)?.let { method to it } }.singleOrNull()
        ?: return emptyList()
    if (items.galleryChatEvent() == null) return emptyList()
    val field = byType[ACTIVITY_RESULT_EVENT]!!.fields
        .single { it.name == itemsIntent.name && it.type == itemsIntent.type && !AccessFlags.STATIC.isSet(it.accessFlags) }
    if (!AccessFlags.PUBLIC.isSet(field.accessFlags) || AccessFlags.FINAL.isSet(field.accessFlags)) return emptyList()
    val started = items.implementation!!.instructions.filter { it.opcode == Opcode.NEW_INSTANCE }.mapNotNull { it.galleryType() }
        .distinct().mapNotNull { byType[it] }
        .mapNotNull { cls -> cls.methods.singleOrNull { it.hookId().substringAfter("->") == INVOKE_SUSPEND } }
    val editor = started.singleOrNull() ?: return emptyList()
    val insert = byType[INSERT_PREVIEW] ?: return emptyList()
    if (!AccessFlags.PUBLIC.isSet(insert.accessFlags) ||
        insert.methods.none { it.hookId() == INSERT_PREVIEW_INIT && AccessFlags.PUBLIC.isSet(it.accessFlags) }) return emptyList()
    val dispatchers = chatDispatchers(methods, byType).filterValues { it.size == 1 }
    val site = editor.galleryChatSite(dispatchers.keys) ?: return emptyList()
    galleryChatRoute = GalleryChatRoute(items.hookId(), itemsIntent.toString(), editor.hookId(),
        dispatchers.getValue(site.chatType).single(), site.chatType)
    return listOf(items, editor)
}

internal fun MutableMethod.validateGalleryToChat(): Int {
    val route = galleryChatRoute ?: galleryChatChanged("no route")
    return when (hookId()) {
        route.items -> galleryChatEvent()?.let { 0 } ?: galleryChatChanged("the photo result handler's registers")
        route.editor -> (galleryChatSite(setOf(route.chat)) ?: galleryChatChanged("the editor")).at
        else -> throw PatchException("Messenger controls: unexpected gallery hook ${hookId()}")
    }
}

/**
 * With the switch on, a pick from the pick screen comes back as one result per item, and the handler runs once for each,
 * the event's intent swapped for that item and put back after. Each run copies its item as Messenger always does, and
 * the coroutine hands it to the chat's tray where it would open the editor. Off, Pause, safe mode, or a result that isn't
 * the pick screen's get Messenger's own handling, and so does any item the extension doesn't mark.
 */
internal fun MutableMethod.injectGalleryToChat() {
    val at = validateGalleryToChat()
    val route = galleryChatRoute!!
    if (hookId() == route.items) {
        val event = galleryChatEvent()!!
        val last = implementation!!.registerCount - parameterRegister(0) - 1
        addInstructionsWithLabels(at, """
            iget-object v0, v$event, ${route.itemsIntent}
            invoke-static {v0}, $GALLERY_TO_CHAT_CALL
            move-result-object v1
            if-eqz v1, :stock_chat
            const/4 v2, 0x0
            :each
            array-length v3, v1
            if-ge v2, v3, :sent
            aget-object v3, v1, v2
            iput-object v3, v$event, ${route.itemsIntent}
            invoke-static/range {p0 .. p$last}, ${route.items}
            add-int/lit8 v2, v2, 0x1
            goto :each
            :sent
            iput-object v0, v$event, ${route.itemsIntent}
            return-void
        """.trimIndent(), ExternalLabel("stock_chat", getInstruction(at)))
        return
    }
    val site = galleryChatSite(setOf(route.chat))!!
    val intent = "v${site.intent}"
    val media = "v${site.media}"
    val list = "v${site.params}"
    addInstructionsWithLabels(at, """
        invoke-static {$intent, $media}, $GALLERY_CHAT_ITEM_CALL
        move-result-object $list
        if-eqz $list, :editor
        new-instance $intent, $INSERT_PREVIEW
        const/4 $media, 0x0
        invoke-direct {$intent, $media, $list}, $INSERT_PREVIEW_INIT
        invoke-static {v${site.chat}, $intent}, ${route.dispatcher}
        sget-object $intent, ${site.unit}
        return-object $intent
    """.trimIndent(), ExternalLabel("editor", getInstruction(at)))
}
