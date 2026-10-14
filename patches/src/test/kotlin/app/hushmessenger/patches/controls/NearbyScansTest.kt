package app.hushmessenger.patches.controls

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal const val SYNTHETIC_NEARBY_SCAN_SCHEDULER =
    "$FOREGROUND_LOCATION->A04(Landroid/content/Context;$FB_USER_SESSION${FOREGROUND_LOCATION}J)V"
internal const val SYNTHETIC_SCAN_RESULT_READER = "LX/1fX;->A03(Z)Ljava/util/ArrayList;"
private const val LOOP = "LX/Yxd;"
private const val BLUETOOTH_TASK = "LX/Yoi;"
private val STATIC = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value

/** The scheduler as 582 writes it: under the controller's lock it builds the loop's task, names it and queues it. */
private val SCHEDULER_BODY = """
    monitor-enter p2
    const-string v2, "$NEARBY_SCAN_MARK"
    new-instance v1, $LOOP
    invoke-direct {v1, p0, p1, p2}, $LOOP-><init>(Landroid/content/Context;$FB_USER_SESSION$FOREGROUND_LOCATION)V
    invoke-static {v1, v2}, LX/0zx;->A01(Ljava/lang/Runnable;Ljava/lang/String;)Ljava/lang/Runnable;
    monitor-exit p2
    return-void
""".trimIndent()

/** The loop's task cut down to its three jobs: a location request, a Wi-Fi scan and the Bluetooth scan operation. */
private val LOOP_BODY = """
    const/4 v0, 0x0
    invoke-virtual {v0}, $WIFI_MANAGER->startScan()Z
    invoke-virtual {v0}, $BLE_OPERATION->A04()V
    return-void
""".trimIndent()

private val BLUETOOTH_BODY = """
    const/4 v0, 0x0
    invoke-virtual {v0, v0, v0, v0}, $BLE_SCANNER->startScan(Ljava/util/List;Landroid/bluetooth/le/ScanSettings;Landroid/bluetooth/le/ScanCallback;)V
    return-void
""".trimIndent()

/** The reader as 582 writes it: null unless Messenger may read the list, otherwise a copy of the system's scan results. */
private val READER_BODY = """
    const/4 v2, 0x0
    if-eqz p1, :denied
    const/4 v0, 0x0
    invoke-virtual {v0}, $WIFI_MANAGER->$SCAN_RESULTS()Ljava/util/List;
    move-result-object v1
    new-instance v4, Ljava/util/ArrayList;
    invoke-direct {v4, v1}, Ljava/util/ArrayList;-><init>(Ljava/util/Collection;)V
    return-object v4
    :denied
    return-object v2
""".trimIndent()

private fun scheduler(body: String = SCHEDULER_BODY, id: String = SYNTHETIC_NEARBY_SCAN_SCHEDULER, registers: Int = 9, flags: Int = STATIC) =
    fixtureMethod(id, body, registers, flags)

private fun reader(body: String = READER_BODY, id: String = SYNTHETIC_SCAN_RESULT_READER, registers: Int = 8, flags: Int = AccessFlags.PUBLIC.value) =
    fixtureMethod(id, body, registers, flags)

private fun readerClass(vararg methods: MutableMethod = arrayOf(reader())) = fixtureClass(methods.first().definingClass, methods.toList())

private fun task(type: String, body: String) = fixtureMethod("$type->run()V", body)

private fun bluetoothTask(type: String = BLUETOOTH_TASK, body: String = BLUETOOTH_BODY, operation: String = BLE_OPERATION) =
    fixtureClass(type, listOf(task(type, body)), interfaces = listOf("Ljava/lang/Runnable;"),
        extraFields = listOf(ImmutableField(type, "A00", operation, AccessFlags.PUBLIC.value, null, null, null)))

/** The controller with its scheduler, the task it queues, the Bluetooth scan operation's own task and the scan-result reader. */
internal fun nearbyScansFixture(
    schedulerMethod: MutableMethod? = scheduler(),
    loopBody: String = LOOP_BODY,
    bluetooth: List<MutableClass> = listOf(bluetoothTask()),
    readers: List<MutableClass> = listOf(readerClass()),
): List<MutableClass> = listOf(
    fixtureClass(FOREGROUND_LOCATION, listOfNotNull(schedulerMethod)),
    fixtureClass(LOOP, listOf(task(LOOP, loopBody)), interfaces = listOf("Ljava/lang/Runnable;")),
) + bluetooth + readers

private fun Method.code() = implementation!!.instructions.toList()

/** An odd-length insertion can add or drop the nop that aligns a switch table further down, so nops don't count. */
private fun List<Instruction>.opcodes() = map { it.opcode }.filter { it != Opcode.NOP }

class NearbyScansTest {
    @AfterTest fun reset() {
        activeProfile = SYNTHETIC_PROFILE
    }

    private fun found(classes: List<MutableClass>) = findControls(classes).getValue(NEARBY_SCANS).map { it.hookId() }.toSet()

    @Test fun theSchedulerAndTheReaderAreFoundWhenEveryScanAndReadSitsBehindThem() {
        assertEquals(setOf(SYNTHETIC_NEARBY_SCAN_SCHEDULER, SYNTHETIC_SCAN_RESULT_READER), found(nearbyScansFixture()))
        validateControls(findControls(nearbyScansFixture()), setOf(NEARBY_SCANS))
    }

    @Test fun everyProfileHooksTheSameSchedulerAndItsOwnMappingsReader() {
        for (profile in listOf(SYNTHETIC_PROFILE, BASE_PROFILE, PROFILE_346611351)) {
            val hooks = profile.hooks.getValue(NEARBY_SCANS)
            val readerId = hooks.single { it != SYNTHETIC_NEARBY_SCAN_SCHEDULER }
            assertEquals(setOf(SYNTHETIC_NEARBY_SCAN_SCHEDULER, readerId), hooks)
            assertTrue(readerId.endsWith("->A03(Z)Ljava/util/ArrayList;"), readerId)
            activeProfile = profile
            validateControls(findControls(nearbyScansFixture(readers = listOf(readerClass(reader(id = readerId))))), setOf(NEARBY_SCANS))
        }
    }

    // The switch says it stops Messenger's scans and its reads of the Wi-Fi list. A build that scans from anywhere the
    // scheduler doesn't reach, or reads the list anywhere but the reader, would make that untrue, so it gets no hook at all.
    @Test fun aScanOrReadOutsideTheHooksOrAMissingHookLeavesTheControlUnapplied() {
        val strayWifi = fixtureClass("LX/Zzz;", listOf(task("LX/Zzz;", LOOP_BODY.replace("invoke-virtual {v0}, $BLE_OPERATION->A04()V\n", ""))))
        val strayRead = fixtureClass("LX/Zzy;", listOf(task("LX/Zzy;", "const/4 v0, 0x0\ninvoke-virtual {v0}, $WIFI_MANAGER->$SCAN_RESULTS()Ljava/util/List;\nreturn-void")))
        val readLine = "invoke-virtual {v0}, $WIFI_MANAGER->$SCAN_RESULTS()Ljava/util/List;\n"
        val cases = mapOf(
            "no scheduler" to nearbyScansFixture(schedulerMethod = null),
            "a scheduler that doesn't name its task" to nearbyScansFixture(scheduler(SCHEDULER_BODY.replace(NEARBY_SCAN_MARK, "other"))),
            "an instance scheduler" to nearbyScansFixture(scheduler(flags = AccessFlags.PUBLIC.value, registers = 10)),
            "a scheduler that queues another task" to nearbyScansFixture(scheduler(SCHEDULER_BODY.replace(LOOP, "LX/Yxe;"))),
            "two schedulers" to nearbyScansFixture().let { classes ->
                listOf(fixtureClass(FOREGROUND_LOCATION, listOf(scheduler(), scheduler(id = SYNTHETIC_NEARBY_SCAN_SCHEDULER.replace("->A04(", "->A0P(")))) ) + classes.drop(1)
            },
            "a second Wi-Fi scan elsewhere" to nearbyScansFixture() + strayWifi,
            "a second Bluetooth scan elsewhere" to nearbyScansFixture(bluetooth = listOf(bluetoothTask(), bluetoothTask("LX/Yoj;"))),
            "no Wi-Fi scan in the queued task" to nearbyScansFixture(loopBody = LOOP_BODY.replace("invoke-virtual {v0}, $WIFI_MANAGER->startScan()Z\n", "")),
            "no Bluetooth scan" to nearbyScansFixture(bluetooth = emptyList()),
            "a Bluetooth scan the queued task doesn't drive" to nearbyScansFixture(loopBody = LOOP_BODY.replace("invoke-virtual {v0}, $BLE_OPERATION->A04()V\n", "")),
            "a Bluetooth scan outside the scan operation" to nearbyScansFixture(bluetooth = listOf(bluetoothTask(operation = "LX/Other;"))),
            "no scan-result reader" to nearbyScansFixture(readers = emptyList()),
            "a second read of the scan results elsewhere" to nearbyScansFixture() + strayRead,
            "a reader that reads the list twice" to nearbyScansFixture(readers = listOf(readerClass(reader(READER_BODY.replace(readLine, readLine + readLine))))),
            "a static reader" to nearbyScansFixture(readers = listOf(readerClass(reader(READER_BODY.replace("p1", "p0"), flags = STATIC)))),
            "a reader with other parameters" to nearbyScansFixture(readers = listOf(readerClass(reader(id = "LX/1fX;->A03(I)Ljava/util/ArrayList;")))),
            "a reader that hands back another type" to nearbyScansFixture(readers = listOf(readerClass(reader(id = "LX/1fX;->A03(Z)Ljava/util/List;")))),
            // Without a null answer of its own, nothing says the callers can take one.
            "a reader that never answers null" to nearbyScansFixture(readers = listOf(readerClass(reader(READER_BODY.replace("return-object v2", "return-object v4"))))),
        )
        for ((case, classes) in cases) {
            assertTrue(found(classes).isEmpty(), case)
            assertFailsWith<PatchException>(case) { validateControls(findControls(classes), setOf(NEARBY_SCANS)) }
        }
    }

    @Test fun theSchedulerAsksTheSwitchBeforeItTakesItsLockOrQueuesAnything() {
        val method = scheduler()
        val before = method.code()
        injectControl(NEARBY_SCANS, mapOf(NEARBY_SCANS to listOf(method)))
        val code = method.code()
        assertEquals(Opcode.INVOKE_STATIC, code[0].opcode)
        assertEquals("$SETTINGS->stopNearbyScans()Z", (code[0] as ReferenceInstruction).reference.toString())
        assertEquals(Opcode.MOVE_RESULT, code[1].opcode)
        assertEquals(Opcode.IF_EQZ, code[2].opcode)
        assertEquals(4, code.branchTarget(2))
        assertEquals(Opcode.RETURN_VOID, code[3].opcode)
        // Off, Pause and safe mode fall through to Messenger's own scheduler, lock included, unchanged.
        assertEquals(Opcode.MONITOR_ENTER, code[4].opcode)
        assertEquals(before, code.drop(4))
    }

    @Test fun theReaderAsksTheSwitchBeforeItTouchesTheWifiService() {
        val method = reader()
        val before = method.code()
        injectControl(NEARBY_SCANS, mapOf(NEARBY_SCANS to listOf(method)))
        val code = method.code()
        assertEquals(Opcode.INVOKE_STATIC, code[0].opcode)
        assertEquals("$SETTINGS->stopNearbyScans()Z", (code[0] as ReferenceInstruction).reference.toString())
        assertEquals(Opcode.MOVE_RESULT, code[1].opcode)
        assertEquals(Opcode.IF_EQZ, code[2].opcode)
        assertEquals(5, code.branchTarget(2))
        // On, it answers null, the answer it already gives without the location permission.
        assertEquals(Opcode.CONST_4, code[3].opcode)
        assertEquals(0, (code[3] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals(Opcode.RETURN_OBJECT, code[4].opcode)
        assertEquals((code[3] as OneRegisterInstruction).registerA, (code[4] as OneRegisterInstruction).registerA)
        // Off, Pause and safe mode fall through to Messenger's own reader, unchanged.
        assertEquals(before, code.drop(5))
    }

    @Test fun anUnusableMethodRefusesTheSwitchBeforeAnyEdit() {
        val cases = mapOf(
            // Two registers are exactly the reader's parameter words, so v0 would be the reader itself.
            "a reader with no free register" to reader("const/4 p1, 0x0\ninvoke-virtual {p0}, $WIFI_MANAGER->$SCAN_RESULTS()Ljava/util/List;\nreturn-object p1", registers = 2),
            "a reader that jumps back to its start" to reader(READER_BODY.replace("const/4 v2, 0x0\n", ":again\nconst/4 v2, 0x0\n")
                .replace("move-result-object v1\n", "move-result-object v1\nif-eqz v1, :again\n")),
            // Five registers are exactly the parameter words, so v0 would be the context.
            "no free register" to scheduler(SCHEDULER_BODY.replace("v2", "p0").replace("v1", "p1"), registers = 5),
            "a jump back to the start" to scheduler(SCHEDULER_BODY.replace("monitor-enter p2", ":again\nmonitor-enter p2")
                .replace("monitor-exit p2", "if-eqz v1, :again\nmonitor-exit p2")),
            "another method" to fixtureMethod("$FOREGROUND_LOCATION->A03(Landroid/content/Context;$FB_USER_SESSION$FOREGROUND_LOCATION)V",
                "return-void", registers = 5, flags = STATIC),
        )
        for ((case, bad) in cases) {
            val badBefore = bad.code()
            assertFailsWith<PatchException>(case) { injectControl(NEARBY_SCANS, mapOf(NEARBY_SCANS to listOf(bad))) }
            assertEquals(badBefore, bad.code(), case)
        }
    }

    @Test fun everySupportedBuildKeepsItsScansAndReadsBehindTheTwoHooks() {
        val root = System.getenv("HUSH_NATIVE_FIXTURES")
        assumeTrue(root != null, "Set HUSH_NATIVE_FIXTURES to the exact stock fixture directory")
        val apks = Files.list(Path.of(root!!)).use { it.filter { p -> p.toString().endsWith(".apk") }.sorted().toList() }
        assertEquals(controlProfiles.size, apks.size)
        for (apk in apks) {
            val code = apk.fileName.toString().substringBeforeLast(".apk").substringAfterLast('-')
            activeProfile = controlProfileFor(code)
            val dex = DexFileFactory.loadDexContainer(apk.toFile(), Opcodes.forApi(35))
            val hooks = findNearbyScans(dex.dexEntryNames.flatMap { dex.getEntry(it)!!.dexFile.classes })
            assertEquals(activeProfile.hooks.getValue(NEARBY_SCANS), hooks.map { it.hookId() }.toSet(), code)
            validateControls(mapOf(NEARBY_SCANS to hooks), setOf(NEARBY_SCANS))
            assertEquals(2, hooks.size, code)
            for (hook in hooks) {
                val method = MutableMethod(hook)
                val before = method.code()
                method.injectNearbyScans()
                assertEquals("$SETTINGS->stopNearbyScans()Z", (method.code()[0] as ReferenceInstruction).reference.toString(), code)
                // The scheduler just returns. The reader loads null first and returns that.
                val added = if (hook.isNearbyScanScheduler()) 4 else 5
                assertEquals(if (added == 4) Opcode.RETURN_VOID else Opcode.RETURN_OBJECT, method.code()[added - 1].opcode, code)
                assertEquals(before.opcodes(), method.code().drop(added).opcodes(), code)
            }
        }
    }
}
