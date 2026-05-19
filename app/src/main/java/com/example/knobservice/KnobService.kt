package com.example.knobservice

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.input.InputManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.annotation.RequiresPermission
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import android.car.Car
import android.car.input.CarInputManager
import android.view.Display
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService

class KnobService : Service() {

    private val KNOB_SERVICE_UUID =
        UUID.fromString("12345678-1234-1234-1234-123456789abc")
    private val TX_CHARACTERISTIC_UUID =
        UUID.fromString("87654321-4321-4321-4321-cba987654321")
    private val RX_CHARACTERISTIC_UUID = UUID.fromString("11111111-2222-3333-4444-555555555555")

    private val CONFIG_DESCRIPTOR = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private lateinit var car: Car
    private lateinit var carInputManager: CarInputManager
    private val inputExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false

    private var lastSnapPoint: Int? = null
    private var lastButtonState: Int = 0

    private val MAC_ADRESS = "64:B7:08:29:37:8E"

    private val carMenus = listOf(
        "com.android.car.carlauncher/.CarLauncher",
        "com.android.car.dialer/com.android.car.dialer.ui.TelecomActivity",
        "com.android.car.carlauncher/.AppGridActivity" ,
        "com.android.car.settings/com.android.car.settings.common.CarSettingActivities\$BluetoothSettingsActivity",
        "com.android.car.settings/com.android.car.settings.common.CarSettingActivities\$NetworkAndInternetActivity",
        "com.android.car.settings/com.android.car.settings.common.CarSettingActivities\$ProfileDetailsActivity"      // AppGrid
    )
    private var currentMenuIndex = 0

    private var lastZapTime = 0L

    private val KEY_SYSTEM_UP = KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP
    private val KEY_SYSTEM_DOWN = KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN


    private val CHANNEL_ID = "KnobServiceChannel"
    private val NOTIFICATION_ID = 1

    private val bondStateReceiver = object : BroadcastReceiver(){
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)

                if (device?.address == MAC_ADRESS) { // Adresse vorher speichern
                    if (bondState == BluetoothDevice.BOND_BONDED) {
                        Log.d("KnobService", "Pairing erfolgreich! Jetzt GATT-Verbindung aufbauen...")
                        myConnectToDevice(device)
                    }
                }
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d("KnobService", "GATT Verbunden! Fordere MTU an...")
                gatt.requestMtu(128)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w("KnobService", "GATT getrennt. Starte Scan für Reconnect...")
                bluetoothGatt?.close()
                bluetoothGatt = null

                Thread.sleep(1000)
                startScan()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("KnobService", "MTU erfolgreich auf $mtu gesetzt. Suche Services...")
            }
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(KNOB_SERVICE_UUID)
                val characteristic = service?.getCharacteristic(TX_CHARACTERISTIC_UUID)

                if (characteristic != null) {
                    Log.d("KnobService", "Service & Charakteristik gefunden. Aktiviere Datenstrom...")
                    enableNotification(gatt, characteristic)
                } else {
                    Log.e("KnobService", "Fehler: Charakteristik nicht gefunden. UUIDs prüfen!")
                }
            }
        }
        
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleKnobData(value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleKnobData(characteristic.value)
        }
    }


    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Knob Service läuft")
            .setContentText("Suche nach BLE-Drehknopf...")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(bondStateReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(bondStateReceiver, filter)
        }

        car = Car.createCar(this)
        carInputManager = car.getCarManager(Car.CAR_INPUT_SERVICE) as CarInputManager
        if (carInputManager == null) {
            Log.e("KnobService", "CarInputManager konnte nicht geladen werden!")
        }

        setupBluetooth()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(bondStateReceiver)
        bluetoothGatt?.close()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Knob Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun setupBluetooth() {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter
        startScan()
    }

    private fun startScan() {
        if (isScanning) return

        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(KNOB_SERVICE_UUID)).build()
        )
        val settings =
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        try {
            bluetoothAdapter.bluetoothLeScanner?.startScan(filters, settings, scanCallback)
            isScanning = true
            Log.d("KnobService", "Scanning gestartet...")
        } catch (e: SecurityException) {
            Log.e("KnobService", "Keine Berechtigung zum Scannen", e)
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val scanRecord = result.scanRecord ?: return
            val advertisedServices: List<ParcelUuid>? = scanRecord.serviceUuids
            val targetParcelUuid = ParcelUuid(KNOB_SERVICE_UUID)


            if (advertisedServices != null && advertisedServices.contains(targetParcelUuid)) {
                val device = result.device
                myStopScan()
                Log.d("KnobService", "Passendes Gerät per UUID gefunden! Name: ${device.name}, Adresse: ${device.address}")

                when (device.bondState) {
                    BluetoothDevice.BOND_NONE -> {
                        Log.d("KnobService", "Starte Bonding...")
                        device.createBond()
                    }
                    BluetoothDevice.BOND_BONDING -> {
                        Log.d("KnobService", "Bonding läuft... Bitte warten.")
                    }
                    BluetoothDevice.BOND_BONDED -> {
                        Log.d("KnobService", "Bereits gebonded. Verbinde...")
                        myConnectToDevice(device)
                    }
                }
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun myStopScan() {
        bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
        isScanning = false
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun myConnectToDevice(device: BluetoothDevice) {
        Log.d("KnobService", "Verbinde mit GATT Server auf ${device.address}")

        if(bluetoothGatt != null)
            return

        bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }


    private fun enableNotification(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        try {
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CONFIG_DESCRIPTOR)
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
                Log.d("KnobService", "Notifications aktiviert.")
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun handleKnobData(value: ByteArray?) {
        if (value == null || value.isEmpty()) return
        if(value.size < 12) return

        val hexString = value.joinToString(" ") { "%02x".format(it) }
        Log.i("KnobService", "INPUT EMPFANGEN: [ $hexString ]")

        if (value[5].toInt() == 0x03) {
            val currentSnapPoint = ByteBuffer.wrap(value, 6, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getInt()

            var buttonState = lastButtonState
            if (value[10].toInt() == 0x04) {
                buttonState = value[11].toInt()
            }

            var fingerCount = 0
            if (value.size > 13 && value[12].toInt() == 0x09) {
                fingerCount = value[13].toInt()
            }

            Log.d(
                "KnobService",
                "SnapPoint: $currentSnapPoint, Finger: $fingerCount, Button: ${if (buttonState == 1) "DOWN" else "UP"}"
            )
            if (buttonState == 1 && lastButtonState == 0) {
                injectCarClick()
            }
            lastButtonState = buttonState

            if (lastSnapPoint != null) {
                val delta = currentSnapPoint - lastSnapPoint!!
                if (delta != 0) {
                    if (delta > 0) {
                        Log.d("KnobService", "Drehung nach RECHTS (Delta: $delta)")
                        when (fingerCount) {
                            // Nutze DPAD_RIGHT (22) statt KEY_NEXT (261)
                            0, 1, 2 -> injectRotaryCommand(true)
                            3 -> injectCarInputKey(KEY_SYSTEM_UP) // DPAD_DOWN
                            4 -> zapToMenu(1)
                            5 -> injectKeyEvent(KeyEvent.KEYCODE_BACK)
                        }
                    } else if (delta < 0) {
                        Log.d("KnobService", "Drehung nach LINKS (Delta: $delta)")

                        when (fingerCount) {
                            0, 1, 2 -> injectRotaryCommand(false)
                            3 -> injectCarInputKey(KEY_SYSTEM_DOWN) // DPAD_UP
                            4 -> zapToMenu(-1)
                            5 -> injectKeyEvent(KeyEvent.KEYCODE_BACK)
                        }
                    }
                }
            }
            lastSnapPoint = currentSnapPoint
        }
    }

    private fun zapToMenu(direction: Int) {
        val currentTime = SystemClock.uptimeMillis()
        if (currentTime - lastZapTime < 800) return
        lastZapTime = currentTime

        currentMenuIndex = if (direction > 0) (currentMenuIndex + 1) % carMenus.size
        else if (currentMenuIndex <= 0) carMenus.size - 1 else currentMenuIndex - 1

        val componentString = carMenus[currentMenuIndex]
        val cn = android.content.ComponentName.unflattenFromString(componentString) ?: return

        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = cn
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }

        Log.i("KnobService", "Zapping zu: $componentString (Index: $currentMenuIndex)")
        val currentUserId = getCurrentForegroundUser()
        val success = startActivityAsSpecificUser(intent, currentUserId)

        if (success) {
            android.widget.Toast.makeText(this, "Wechsel: ${cn.shortClassName}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun getCurrentForegroundUser(): Int {
        return try {
            val amClass = Class.forName("android.app.ActivityManager")
            val method = amClass.getMethod("getCurrentUser")
            val user = method.invoke(null) as Int
            Log.d("KnobService", "Aktiver User erkannt: $user")
            user
        } catch (e: Exception) {
            Log.w("KnobService", "Konnte CurrentUser nicht ermitteln, nutze Fallback 10")
            10
        }
    }

    private fun startActivityAsSpecificUser(intent: Intent, userId: Int): Boolean {
        return try {
            val userHandleClass = Class.forName("android.os.UserHandle")
            val userHandle = userHandleClass.getMethod("of", Int::class.javaPrimitiveType).invoke(null, userId)

            val method = Context::class.java.getMethod("startActivityAsUser", Intent::class.java, userHandleClass)
            method.invoke(this, intent, userHandle)
            true
        } catch (e: Exception) {
            Log.e("KnobService", "startActivityAsUser für User $userId fehlgeschlagen: ${e.message}")
            try {
                // Letzter Rettungsversuch: Normaler Start
                startActivity(intent)
                true
            } catch (inner: Exception) {
                false
            }
        }
    }

    private fun injectCarClick() {
        inputExecutor.execute {
            try {
                val command = "cmd car_service inject-key 23"
                val process = Runtime.getRuntime().exec(command)
                process.waitFor()
                Log.d("KnobService", "System-Leiste Klick (23) via car_service gesendet")
            } catch (e: Exception) {
                Log.e("KnobService", "Shell Click failed: ${e.message}")
            }
        }
    }

    private fun injectRotaryCommand(clockwise: Boolean) {
        inputExecutor.execute {
            try {
                val direction = if (clockwise) "-c true" else ""
                val command = "cmd car_service inject-rotary $direction"

                val process = Runtime.getRuntime().exec(command)
                process.waitFor()
                Log.d("KnobService", "Rotary-Kommando gesendet: clockwise=$clockwise")
            } catch (e: Exception) {
                Log.e("KnobService", "Shell Rotary failed: ${e.message}")
            }
        }
    }


    private fun injectKeyEvent(keyCode: Int, metaState: Int = 0) {
        Log.d("KnobService", ">>> SIMULIERE TASTENDRUCK: KeyCode $keyCode (Meta: $metaState) <<<")
        val SOURCE_ROTARY_ENCODER = 0x00400000
        val DEVICE_ID = 1
        val FULL_SOURCE = SOURCE_ROTARY_ENCODER or 0x00000001

        Thread {
            try {
                val inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
                val eventTime = SystemClock.uptimeMillis()

                val eventDown = KeyEvent(
                    eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0,
                    metaState,
                    DEVICE_ID, 0,
                    KeyEvent.FLAG_FROM_SYSTEM,
                    FULL_SOURCE
                )
                inputManager.javaClass.getMethod("injectInputEvent", android.view.InputEvent::class.java, Int::class.javaPrimitiveType)
                    .invoke(inputManager, eventDown, 0)

                // UP-Event mit metaState
                val eventUp = KeyEvent(
                    eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0,
                    metaState, // <-- Hier ebenfalls
                    DEVICE_ID, 0,
                    KeyEvent.FLAG_FROM_SYSTEM,
                    FULL_SOURCE
                )
                inputManager.javaClass.getMethod("injectInputEvent", android.view.InputEvent::class.java, Int::class.javaPrimitiveType)
                    .invoke(inputManager, eventUp, 0)

                Log.d("KnobService", "Rotary-Event erzwungen: $keyCode (Device $DEVICE_ID)")
            } catch (e: Exception) {
                Log.e("KnobService", "Fehler beim Injizieren des KeyEvents", e)
            }
        }.start()
    }

    private fun injectCarInputKey(keyCode: Int) {
        inputExecutor.execute {
            try {
                val command = "cmd car_service inject-key $keyCode"
                val process = Runtime.getRuntime().exec(command)
                process.waitFor()
                Log.d("KnobService", "Shell-Kommando erfolgreich abgesetzt: $keyCode")
            } catch (e: Exception) {
                Log.e("KnobService", "Fehler beim Ausführen des Shell-Kommandos", e)
            }
        }
    }

    override fun onBind(intent: Intent?) = null
}