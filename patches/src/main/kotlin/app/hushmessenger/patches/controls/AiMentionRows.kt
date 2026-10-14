package app.hushmessenger.patches.controls

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * The checks behind the Meta AI, imagine and imagine yourself rows in Messenger 582's newer @ list. That list is a
 * render of its own that asks these checks directly instead of running the mention plugin dispatchers. The Meta AI row
 * asks a different check per chat type: regular chats, encrypted chats and community channels.
 */
internal const val AI_MENTION_ROWS = "ai_mention_rows"

internal const val CAPABILITIES = "Lcom/facebook/xapp/messaging/capability/vector/Capabilities;"

/** The server flags each Meta AI row check reads in 582, and the one each imagine check reads. */
internal val META_AI_ROW_FLAGS = setOf(0x81063b011b2d8aL, 0x81063b01042d83L)
internal val META_AI_CHAT_FLAGS = setOf(0x81063b011b2d8aL, 0x81063b00042d40L)
internal const val META_AI_ENCRYPTED_FLAG = 0x81063b00052d41L
internal const val IMAGINE_ROW_FLAG = 0x81063b00402d51L
internal const val IMAGINE_YOURSELF_ROW_FLAG = 0x81063b00f92d7fL

/** One build's numbering of those flags. Meta renumbers them with each release, so all five checks must agree on one. */
internal class AiRowFlags(val row: Set<Long>, val chat: Set<Long>, val encrypted: Long, val imagine: Long, val imagineYourself: Long)
internal val AI_ROW_FLAGS_582 = AiRowFlags(META_AI_ROW_FLAGS, META_AI_CHAT_FLAGS, META_AI_ENCRYPTED_FLAG, IMAGINE_ROW_FLAG, IMAGINE_YOURSELF_ROW_FLAG)
internal val AI_ROW_FLAGS_583 = AiRowFlags(setOf(0x810637011b2d49L, 0x81063701042d42L), setOf(0x810637011b2d49L, 0x81063700042cffL),
    0x81063700052d00L, 0x81063700402d10L, 0x81063700f92d3eL)
internal val AI_ROW_BUILDS = listOf(AI_ROW_FLAGS_582, AI_ROW_FLAGS_583)

private fun rowsChanged(detail: String): Nothing =
    throw PatchException("Messenger controls: the @ list's Meta AI checks moved ($detail)")

private const val CAPABILITY = "$CAPABILITIES->A00(I)Z"

private fun Method.serverFlags(): Set<Long> = implementation?.instructions
    ?.filter { it.opcode == Opcode.CONST_WIDE }
    ?.mapNotNull { (it as? WideLiteralInstruction)?.wideLiteral }
    ?.toSet().orEmpty()

/**
 * The (FbUserSession)Z sibling the encrypted-chat check defers to, as named by its call, or null when it asks none or
 * a capability isn't checked first.
 */
private fun Method.encryptedChatSibling(): String? {
    val calls = implementation?.instructions?.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }.orEmpty()
    if (calls.none { it.toString() == CAPABILITY }) return null
    return calls.singleOrNull { it.definingClass == definingClass && it.returnType == "Z" &&
        it.parameterTypes.map { p -> p.toString() } == listOf(FB_USER_SESSION) }?.toString()
}

/**
 * Which row this instance check answers for, by its parameters and the server flag it reads:
 *
 *     ()Z                                                  both META_AI_ROW_FLAGS: Meta AI in community channels
 *     (FbUserSession, Capabilities)Z                       both META_AI_CHAT_FLAGS: Meta AI in regular chats
 *     (FbUserSession, Capabilities)Z, no flag of its own   a capability, then a sibling reading META_AI_ENCRYPTED_FLAG:
 *                                                          Meta AI in encrypted chats
 *     (FbUserSession, ThreadKey, Capabilities, Z, Z)Z      IMAGINE_ROW_FLAG: imagine
 *     (FbUserSession, ThreadKey, Capabilities)Z            IMAGINE_YOURSELF_ROW_FLAG: imagine yourself
 *
 * [sibling] looks a method up by its id; without it the encrypted-chat check is judged by its own shape alone. The flags
 * are any one build's numbering.
 */
internal fun Method.aiMentionRow(sibling: ((String) -> Method?)? = null): String? =
    AI_ROW_BUILDS.firstNotNullOfOrNull { aiMentionRow(it, sibling) }

private fun Method.aiMentionRow(build: AiRowFlags, sibling: ((String) -> Method?)?): String? {
    if (AccessFlags.STATIC.isSet(accessFlags) || returnType != "Z" || implementation == null) return null
    val params = parameterTypes.map { it.toString() }
    val flags = serverFlags()
    return when {
        params.isEmpty() && flags.containsAll(build.row) -> "meta_ai"
        params == listOf(FB_USER_SESSION, CAPABILITIES) && flags.containsAll(build.chat) -> "meta_ai_chats"
        params == listOf(FB_USER_SESSION, CAPABILITIES) && flags.isEmpty() && encryptedChatSibling()?.let { id ->
            sibling == null || sibling(id)?.serverFlags()?.contains(build.encrypted) == true } == true -> "meta_ai_encrypted"
        params == listOf(FB_USER_SESSION, THREAD_KEY_TYPE, CAPABILITIES, "Z", "Z") && build.imagine in flags -> "imagine"
        params == listOf(FB_USER_SESSION, THREAD_KEY_TYPE, CAPABILITIES) && build.imagineYourself in flags -> "imagine_yourself"
        else -> null
    }
}

/** The five checks, exactly one of each, all on one class and all under one build's flags, or none. */
internal fun findAiMentionRows(classes: Iterable<ClassDef>): List<Method> = AI_ROW_BUILDS.firstNotNullOfOrNull { build ->
    val rows = classes.flatMap { cls ->
        val sibling = { id: String -> cls.methods.firstOrNull { it.hookId() == id } }
        cls.methods.mapNotNull { method -> method.aiMentionRow(build, sibling)?.let { it to method } }
    }.groupBy({ it.first }, { it.second })
    if (rows.size != 5 || rows.values.any { it.size != 1 }) return@firstNotNullOfOrNull null
    rows.values.map { it.single() }.takeIf { checks -> checks.map { it.definingClass }.toSet().size == 1 }
}.orEmpty()

internal fun MutableMethod.validateAiMentionRows() {
    if (aiMentionRow() == null) rowsChanged("unexpected check ${hookId()}")
    validateSwitch()
}

/** With the switch on each check answers false, as it does for an account without the feature, so its row isn't built. */
internal fun MutableMethod.injectAiMentionRows() {
    validateAiMentionRows()
    injectSwitch("$SETTINGS->hideMetaAi()Z", "0x0")
}
