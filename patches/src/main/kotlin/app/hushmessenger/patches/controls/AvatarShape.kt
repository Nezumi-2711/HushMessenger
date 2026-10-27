package app.hushmessenger.patches.controls

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/**
 * Profile pictures are round because Fresco is told to round them, in one of two ways. Vito, the newer image view,
 * gets a RoundingOptions whose isCircular is true, and every Vito path (drawable, decode-time rounding, Litho clip,
 * the Vito2 shape) reads that flag off the one constructor. The older drawee views get a RoundingParams whose
 * roundAsCircle flag one static helper hands to each rounded layer, followed by its corner radii.
 */
internal const val AVATAR_SHAPE = "avatar_shape"

/** How Vito's RoundingOptions.toString() starts, with isCircular the first boolean it prints. */
internal const val ROUNDING_OPTIONS_NAME = "RoundingOptions(isCircular="

/** RoundingOptions' isCircular field, proved by findAvatarShape before either hook is checked again. */
internal var vitoCircleField: String = ""

private fun avatarShapeChanged(detail: String): Nothing =
    throw PatchException("Messenger controls: profile picture rounding changed ($detail)")

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.field() = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun Instruction.invoked() = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.reads(): List<Int> =
    (this as? FiveRegisterInstruction)?.let { listOf(it.registerC, it.registerD, it.registerE, it.registerF, it.registerG).take(it.registerCount) }.orEmpty()

private const val APPEND_BOOLEAN = "Ljava/lang/StringBuilder;->append(Z)Ljava/lang/StringBuilder;"

/**
 * The isCircular field: the boolean RoundingOptions' toString() appends right after its name. 584.1 reads every field
 * up front, so it's the field this object's own iget-boolean last put into that register, with no branch in between.
 */
private fun ClassDef.vitoCircle(): String? {
    val printer = methods.singleOrNull { it.name == "toString" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;" } ?: return null
    if (AccessFlags.STATIC.isSet(printer.accessFlags)) return null
    val code = printer.code()
    val named = code.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string?.startsWith(ROUNDING_OPTIONS_NAME) == true }
    if (named < 0) return null
    val append = (named + 1 until code.size).firstOrNull { code[it].invoked()?.toString() == APPEND_BOOLEAN } ?: return null
    if (code.take(append).any { it is OffsetInstruction } || (1..append).any { it in printer.jumpTargets() }) return null
    val value = code[append].reads().getOrNull(1) ?: return null
    val read = (append - 1 downTo 0).firstOrNull { (code[it] as? OneRegisterInstruction)?.registerA == value } ?: return null
    if (code[read].opcode != Opcode.IGET_BOOLEAN || (code[read] as TwoRegisterInstruction).registerB != printer.parameterRegister(0)) return null
    val field = code[read].field() ?: return null
    return if (field.definingClass == type && field.type == "Z") field.toString() else null
}

/** The registers Vito's constructor stores isCircular, the corner radius and the corner radii from. */
internal class VitoRounding(val circle: Int, val radius: Int, val radii: Int)

/**
 * The constructor in its proven shape: Object's constructor, then nothing but one store per field from its own
 * parameters, then return. So the three values are still the caller's when the hook rewrites them right after the
 * super call, and nothing else in it can see the originals.
 */
internal fun Method.vitoRounding(circleField: String = vitoCircleField): VitoRounding? {
    if (name != "<init>" || AccessFlags.STATIC.isSet(accessFlags) || returnType != "V" || circleField.isEmpty()) return null
    if (circleField.substringBefore("->") != definingClass) return null
    val code = code()
    if (code.size < 3 || code.last().opcode != Opcode.RETURN_VOID) return null
    val parent = code[0]
    if (parent.opcode != Opcode.INVOKE_DIRECT || parent.invoked()?.toString() != "Ljava/lang/Object;-><init>()V" ||
        parent.reads() != listOf(parameterRegister(0))) return null
    val params = parameterTypes.indices.associate { parameterRegister(it + 1) to parameterTypes[it].toString() }
    if (params.values.any { it == "J" || it == "D" }) return null
    val stores = code.subList(1, code.size - 1)
    val iputs = setOf(Opcode.IPUT, Opcode.IPUT_BOOLEAN, Opcode.IPUT_OBJECT)
    if (stores.any { it.opcode !in iputs }) return null
    val written = stores.map { store ->
        val field = store.field() ?: return null
        val (value, target) = (store as TwoRegisterInstruction).let { it.registerA to it.registerB }
        if (target != parameterRegister(0) || field.definingClass != definingClass || params[value] != field.type) return null
        field to value
    }
    if (written.map { it.first.name }.toSet().size != written.size || written.map { it.second }.toSet().size != written.size) return null
    val circle = written.singleOrNull { it.first.toString() == circleField }?.second ?: return null
    val radius = written.singleOrNull { it.first.type == "F" }?.second ?: return null
    val radii = written.singleOrNull { it.first.type == "[F" }?.second ?: return null
    if (maxOf(circle, radius, radii) > 15) return null
    return VitoRounding(circle, radius, radii)
}

/**
 * The legacy helper's registers: the rounded layer, the params, the flag and radii it reads, and the register the
 * first instruction after them overwrites, free for one more read of the flag.
 */
internal class LegacyRounding(val layer: Int, val params: Int, val flag: Int, val radii: Int, val spare: Int, val circleField: String)

/**
 * A static (layer, params) helper that starts by handing the params' boolean to the layer's one (Z)V method, then the
 * params' float array to its ([F)V method, the way Fresco's RoundingParams reach a Rounded drawable.
 */
internal fun Method.legacyRounding(): LegacyRounding? {
    if (!AccessFlags.STATIC.isSet(accessFlags) || returnType != "V" || parameterTypes.size != 2) return null
    val layerType = parameterTypes[0].toString()
    val paramsType = parameterTypes[1].toString()
    if (!layerType.startsWith("L") || !paramsType.startsWith("L") || layerType == paramsType) return null
    val code = code()
    if (code.size < 6) return null
    val layer = parameterRegister(0)
    val params = parameterRegister(1)
    fun read(at: Int, opcode: Opcode, type: String): Pair<Int, FieldReference>? {
        val get = code[at] as? TwoRegisterInstruction ?: return null
        val field = code[at].field() ?: return null
        if (code[at].opcode != opcode || get.registerB != params || field.definingClass != paramsType || field.type != type) return null
        if (get.registerA == layer || get.registerA == params || get.registerA > 15) return null
        return get.registerA to field
    }
    fun hands(at: Int, value: Int, parameter: String): Boolean {
        val call = code[at].invoked() ?: return false
        return code[at].opcode == Opcode.INVOKE_INTERFACE && call.definingClass == layerType && call.returnType == "V" &&
            call.parameterTypes.map { it.toString() } == listOf(parameter) && code[at].reads() == listOf(layer, value)
    }
    val (flag, circle) = read(0, Opcode.IGET_BOOLEAN, "Z") ?: return null
    if (!hands(1, flag, "Z")) return null
    val (radii, _) = read(2, Opcode.IGET_OBJECT, "[F") ?: return null
    if (!hands(3, radii, "[F")) return null
    val next = code[4] as? TwoRegisterInstruction ?: return null
    if (code[4].opcode !in setOf(Opcode.IGET, Opcode.IGET_BOOLEAN) || next.registerB != params) return null
    val spare = next.registerA
    if (spare in setOf(layer, params, radii) || spare > 15) return null
    if ((0..4).any { it in jumpTargets() }) return null
    return LegacyRounding(layer, params, flag, radii, spare, circle.toString())
}

/**
 * The one RoundingOptions class by its toString(), its one constructor in the proven shape, and the one legacy helper.
 * A second of either, or either in another shape, leaves the switch unapplied rather than rounding only some pictures.
 */
internal fun findAvatarShape(classes: Iterable<ClassDef>): List<Method> {
    vitoCircleField = ""
    val all = classes.toList()
    val vito = all.mapNotNull { cls -> cls.vitoCircle()?.let { cls to it } }.singleOrNull() ?: return emptyList()
    val constructors = vito.first.methods.filter { it.vitoRounding(vito.second) != null }
    val constructor = constructors.singleOrNull() ?: return emptyList()
    val legacy = all.flatMap { cls -> cls.methods.filter { it.legacyRounding() != null } }.singleOrNull() ?: return emptyList()
    vitoCircleField = vito.second
    return listOf(constructor, legacy)
}

internal fun MutableMethod.validateAvatarShape() {
    if (name == "<init>") vitoRounding() ?: avatarShapeChanged("Vito's rounding options in ${hookId()}")
    else legacyRounding() ?: avatarShapeChanged("the rounded layer helper ${hookId()}")
}

/**
 * Vito's options are rewritten as they're built: a circle becomes a rectangle with Settings' corner radius and no
 * radii, so the radius decides. The radius and radii are worked out from the original isCircular before it changes.
 * The legacy helper hands each layer the same answers. Pictures that weren't circles to begin with are left alone.
 */
internal fun MutableMethod.injectAvatarShape() {
    if (name == "<init>") {
        val vito = vitoRounding() ?: avatarShapeChanged("Vito's rounding options in ${hookId()}")
        addInstructions(1, """
            invoke-static {v${vito.circle}, v${vito.radius}}, $SETTINGS->avatarRadius(ZF)F
            move-result v${vito.radius}
            invoke-static {v${vito.circle}, v${vito.radii}}, $SETTINGS->avatarVitoRadii(Z[F)[F
            move-result-object v${vito.radii}
            invoke-static {v${vito.circle}}, $SETTINGS->avatarCircle(Z)Z
            move-result v${vito.circle}
        """.trimIndent())
        return
    }
    val legacy = legacyRounding() ?: avatarShapeChanged("the rounded layer helper ${hookId()}")
    // The radii first, since inserting at 1 moves everything after it.
    addInstructions(3, """
        iget-boolean v${legacy.spare}, v${legacy.params}, ${legacy.circleField}
        invoke-static {v${legacy.radii}, v${legacy.spare}}, $SETTINGS->avatarRadii([FZ)[F
        move-result-object v${legacy.radii}
    """.trimIndent())
    addInstructions(1, """
        invoke-static {v${legacy.flag}}, $SETTINGS->avatarCircle(Z)Z
        move-result v${legacy.flag}
    """.trimIndent())
}
