package app.hushmessenger.patches.controls

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The synthetic label for the attach step, named after the first build it was found in. */
internal const val INSTANTS_BUTTON_ATTACH = "LX/1mx;->A0D(LX/1mx;)V"

private const val CHAT_LIST = "LX/1mx;"

/**
 * The attach step cut down to what every build shares: static and void, handed its own fragment, and logging both
 * corner peek events, with locals to spare above its one parameter.
 */
private val ATTACH_BODY = """
    const-string v0, "$CORNER_PEEK_ATTACH"
    const-string v1, "$CORNER_PEEK_ATTACHED"
    return-void
""".trimIndent()

private fun attach(
    id: String = INSTANTS_BUTTON_ATTACH,
    body: String = ATTACH_BODY,
    registers: Int = 4,
    flags: Int = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
): MutableMethod = fixtureMethod(id, body, registers, flags)

/** The layout pass that calls the attach step and logs nothing, which never gets the hook. */
private fun layoutPass() = attach("$CHAT_LIST->A0C($CHAT_LIST)V", body = """
    invoke-static {v3}, $INSTANTS_BUTTON_ATTACH
    return-void
""".trimIndent())

/**
 * The attach step beside the layout pass that calls it. Messenger keeps both on InboxFragment itself, but the community
 * inbox fixture already stands in for that class (its finder wants exactly one), so this one carries a label of its own.
 */
internal fun instantsButtonFixture(attachMethod: MutableMethod = attach()): List<MutableClass> = listOf(
    fixtureClass(CHAT_LIST, listOf(attachMethod, layoutPass()), originalName = "InboxCornerPeek"),
)

private fun Method.code() = implementation!!.instructions.toList()

class InstantsButtonTest {
    @AfterTest fun reset() {
        activeProfile = SYNTHETIC_PROFILE
    }

    private fun found(classes: List<MutableClass>) = mirroredControls(classes).getValue(INSTANTS_BUTTON).map { it.hookId() }

    @Test fun theAttachStepIsFoundByItsShapeAndBothEvents() {
        assertEquals(listOf(INSTANTS_BUTTON_ATTACH), found(instantsButtonFixture()))
        validateControls(mirroredControls(instantsButtonFixture()), setOf(INSTANTS_BUTTON))
    }

    @Test fun lookalikesAreNotTheAttachStep() {
        val onlyAttach = ATTACH_BODY.replace(CORNER_PEEK_ATTACHED, "qs_peek_attach")
        val onlyAttached = ATTACH_BODY.replace("\"$CORNER_PEEK_ATTACH\"", "\"qs_peek_attach\"")
        val cases = mapOf(
            "not static" to instantsButtonFixture(attach(flags = AccessFlags.PUBLIC.value)),
            "another parameter" to instantsButtonFixture(attach(id = "$CHAT_LIST->A0D(Landroid/view/View;)V")),
            "two parameters" to instantsButtonFixture(attach(id = "$CHAT_LIST->A0D(${CHAT_LIST}I)V")),
            "returns a value" to instantsButtonFixture(attach(id = INSTANTS_BUTTON_ATTACH.replace(")V", ")Z"),
                body = ATTACH_BODY.replace("return-void", "const/4 v0, 0x0\nreturn v0"))),
            "only the first event" to instantsButtonFixture(attach(body = onlyAttach)),
            "only the second event" to instantsButtonFixture(attach(body = onlyAttached)),
            "only the layout pass" to listOf(fixtureClass(CHAT_LIST, listOf(layoutPass()))),
        )
        for ((case, classes) in cases) {
            assertTrue(found(classes).isEmpty(), case)
            assertFailsWith<PatchException>(case) { validateControls(mirroredControls(classes), setOf(INSTANTS_BUTTON)) }
        }
    }

    @Test fun theSwitchReturnsBeforeTheButtonIsInflated() {
        val method = attach()
        val before = method.code()
        injectControl(INSTANTS_BUTTON, mapOf(INSTANTS_BUTTON to listOf(method)))
        val code = method.code()
        assertEquals("$SETTINGS->hideInstantsButton()Z", ((code[0] as ReferenceInstruction).reference).toString())
        assertEquals(Opcode.MOVE_RESULT, code[1].opcode)
        assertEquals(0, (code[1] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.IF_EQZ, code[2].opcode)
        assertEquals(4, code.branchTarget(2))
        assertEquals(Opcode.RETURN_VOID, code[3].opcode)
        // Off, Pause and safe mode fall through to Messenger's own attach, unchanged.
        assertEquals(before, code.drop(4))
    }

    @Test fun anUnusableAttachStepRefusesTheSwitchBeforeAnyEdit() {
        val cases = mapOf(
            // One register is exactly the parameter, so v0 would be the fragment.
            "no free register" to attach(registers = 1, body = """
                const-string v0, "$CORNER_PEEK_ATTACH"
                const-string v0, "$CORNER_PEEK_ATTACHED"
                return-void
            """.trimIndent()),
            "the layout pass" to layoutPass(),
            "only the first event" to attach(body = ATTACH_BODY.replace(CORNER_PEEK_ATTACHED, "qs_peek_attach")),
        )
        for ((case, bad) in cases) {
            val badBefore = bad.code()
            assertFailsWith<PatchException>(case) {
                injectControl(INSTANTS_BUTTON, mapOf(INSTANTS_BUTTON to listOf(bad)))
            }
            assertEquals(badBefore, bad.code(), case)
        }
    }

    @Test fun everySupportedBuildHooksItsOneAttachStep() {
        val root = System.getenv("HUSH_NATIVE_FIXTURES")
        assumeTrue(root != null, "Set HUSH_NATIVE_FIXTURES to the exact stock fixture directory")
        val apks = Files.list(Path.of(root!!)).use { it.filter { p -> p.toString().endsWith(".apk") }.sorted().toList() }
        assertEquals(controlProfiles.size, apks.size)
        for (apk in apks) {
            val code = apk.fileName.toString().substringBeforeLast(".apk").substringAfterLast('-')
            activeProfile = controlProfileFor(code)
            val dex = DexFileFactory.loadDexContainer(apk.toFile(), Opcodes.forApi(35))
            val attaches = findInstantsButton(dex.dexEntryNames.flatMap { dex.getEntry(it)!!.dexFile.classes })
            assertEquals(activeProfile.hooks.getValue(INSTANTS_BUTTON), attaches.map { it.hookId() }.toSet(), code)
            validateControls(mapOf(INSTANTS_BUTTON to attaches), setOf(INSTANTS_BUTTON))
            val method = MutableMethod(attaches.single())
            val before = method.code()
            method.injectInstantsButton()
            val after = method.code()
            assertEquals("$SETTINGS->hideInstantsButton()Z", ((after[0] as ReferenceInstruction).reference).toString(), code)
            assertEquals(4, after.branchTarget(2), code)
            assertEquals(Opcode.RETURN_VOID, after[3].opcode, code)
            assertEquals(before.map { it.opcode }.filter { it != Opcode.NOP }, after.drop(4).map { it.opcode }.filter { it != Opcode.NOP }, code)
        }
    }
}
