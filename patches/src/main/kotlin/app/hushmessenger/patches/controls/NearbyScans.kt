package app.hushmessenger.patches.controls

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/**
 * The location check and the Wi-Fi and Bluetooth scans Messenger runs on a timer while it's open, and its reads of the
 * phone's own list of Wi-Fi networks in range.
 */
internal const val NEARBY_SCANS = "nearby_scans"

/**
 * Messenger's foreground location loop. While the app is in front it queues one task over and over that asks for a
 * location, starts a Wi-Fi scan and starts a Bluetooth scan. It keeps this name on every 582 build.
 */
internal const val FOREGROUND_LOCATION = "Lcom/facebook/location/foreground/ForegroundLocationFrameworkController;"

/** The name the loop's scheduler gives the task it queues. The first run, every repeat and both restarts go through it. */
internal const val NEARBY_SCAN_MARK = "ForegroundLocationFrameworkController_scheduleRequestLocationWithDelay"

internal const val WIFI_MANAGER = "Landroid/net/wifi/WifiManager;"
internal const val BLE_SCANNER = "Landroid/bluetooth/le/BluetoothLeScanner;"
internal const val BLE_OPERATION = "Lcom/facebook/blescan/BleScanOperation;"

/** The system's list of Wi-Fi networks in range. Reading it starts no scan, but it's the same list a scan fills. */
internal const val SCAN_RESULTS = "getScanResults"

private val SCHEDULER_PARAMETERS = listOf("Landroid/content/Context;", FB_USER_SESSION, FOREGROUND_LOCATION, "J")

private fun nearbyScansChanged(detail: String): Nothing =
    throw PatchException("Messenger controls: the foreground location loop changed ($detail)")

/** The loop's scheduler: the controller's static (context, session, controller, delay)V that names the task it queues. */
internal fun Method.isNearbyScanScheduler() =
    definingClass == FOREGROUND_LOCATION && AccessFlags.STATIC.isSet(accessFlags) && returnType == "V" &&
        parameterTypes.map { it.toString() } == SCHEDULER_PARAMETERS &&
        implementation?.instructions?.any { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == NEARBY_SCAN_MARK } == true

private fun MethodReference.readsScanResults() = definingClass == WIFI_MANAGER && name == SCAN_RESULTS

/**
 * Messenger's reader of the Wi-Fi scan results: an instance (Z)ArrayList holding the app's one getScanResults call.
 * It already answers null when Messenger isn't allowed to read the list, so null is an answer every caller has to take.
 */
internal fun Method.isScanResultReader(): Boolean {
    if (AccessFlags.STATIC.isSet(accessFlags) || returnType != "Ljava/util/ArrayList;" || parameterTypes.map { it.toString() } != listOf("Z")) return false
    val code = implementation?.instructions?.toList() ?: return false
    val nulls = code.filter { it.opcode == Opcode.CONST_4 && (it as NarrowLiteralInstruction).narrowLiteral == 0 }
        .map { (it as OneRegisterInstruction).registerA }.toSet()
    return code.count { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.readsScanResults() == true } == 1 &&
        code.any { it.opcode == Opcode.RETURN_OBJECT && (it as OneRegisterInstruction).registerA in nulls }
}

private fun Method.calls(owner: String) = implementation?.instructions?.any {
    ((it as? ReferenceInstruction)?.reference as? MethodReference)?.definingClass == owner
} == true

/**
 * The scheduler and the scan-result reader, but only while every scan Messenger can start still sits behind the
 * scheduler and every read of the results goes through the reader. The app has to hold exactly one Wi-Fi startScan,
 * in the task the scheduler queues, exactly one Bluetooth startScan, in a task of the scan operation that same queued
 * task drives, and exactly one getScanResults, in the reader. A build with a scan or a read anywhere else gets no
 * hook, so the switch never claims more than it stops.
 */
internal fun findNearbyScans(classes: Iterable<ClassDef>): List<Method> {
    val scheduler = classes.firstOrNull { it.type == FOREGROUND_LOCATION }?.methods?.singleOrNull { it.isNearbyScanScheduler() }
        ?: return emptyList()
    val wifi = mutableListOf<ClassDef>()
    val bluetooth = mutableListOf<ClassDef>()
    val readers = mutableListOf<Method>()
    for (cls in classes) for (method in cls.methods) for (instruction in method.implementation?.instructions ?: emptyList()) {
        val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: continue
        if (call.readsScanResults()) readers.add(method)
        if (call.name != "startScan") continue
        if (call.definingClass == WIFI_MANAGER) wifi.add(cls)
        if (call.definingClass == BLE_SCANNER) bluetooth.add(cls)
    }
    val loop = wifi.singleOrNull() ?: return emptyList()
    val bluetoothTask = bluetooth.singleOrNull() ?: return emptyList()
    val reader = readers.singleOrNull()?.takeIf { it.isScanResultReader() } ?: return emptyList()
    val queued = scheduler.implementation!!.instructions.any {
        it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type == loop.type
    }
    val drivesBluetooth = bluetoothTask.fields.any { it.type == BLE_OPERATION } && loop.methods.any { it.calls(BLE_OPERATION) }
    return if (queued && drivesBluetooth) listOf(scheduler, reader) else emptyList()
}

internal fun MutableMethod.validateNearbyScans() {
    if (!isNearbyScanScheduler() && !isScanResultReader()) nearbyScansChanged("unexpected method ${hookId()}")
    validateSwitch()
    // The check goes in front of the lock the scheduler takes and of the reader's own checks, so nothing may jump back
    // to the first instruction.
    if (0 in jumpTargets()) nearbyScansChanged("a jump back to the start of ${hookId()}")
}

/**
 * While the switch is on the scheduler returns before it queues anything, so the loop never starts and a run already
 * queued is the last. The reader answers null before it touches the Wi-Fi service, the answer it gives without the
 * location permission. Off, Pause and safe mode run Messenger's own code, and the loop starts again the next time
 * Messenger comes to the front.
 */
internal fun MutableMethod.injectNearbyScans() {
    validateNearbyScans()
    injectSwitch("$SETTINGS->stopNearbyScans()Z", "0x0")
}
