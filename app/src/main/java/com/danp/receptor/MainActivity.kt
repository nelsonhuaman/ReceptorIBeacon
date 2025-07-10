package com.danp.receptor

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService

class MainActivity : ComponentActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bleScanner: BluetoothLeScanner? = null
    private var onDeviceDiscovered: ((ScannedDevice) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            var selectedDevice by remember { mutableStateOf<ScannedDevice?>(null) }
            val discoveredDevices = remember { mutableStateListOf<ScannedDevice>() }

            onDeviceDiscovered = { device ->
                if (discoveredDevices.none { it.address == device.address }) {
                    discoveredDevices.add(device)
                }
            }

            BLEScannerUI(
                devices = discoveredDevices,
                selectedDevice = selectedDevice,
                onDeviceClick = { selectedDevice = it }
            )

            LaunchedEffect(Unit) {
                requestPermissionsIfNeeded()
            }
        }
    }

    private fun requestPermissionsIfNeeded() {
        val permissionsNeeded = listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        val permissionsToRequest = permissionsNeeded.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            initBluetoothAndStartScan()
        }
    }

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.values.all { it }
            if (allGranted) {
                initBluetoothAndStartScan()
            } else {
                Log.e("BLEReceiver", "Permisos denegados.")
            }
        }

    private fun initBluetoothAndStartScan() {
        val bluetoothManager = getSystemService<BluetoothManager>()
        bluetoothAdapter = bluetoothManager?.adapter ?: throw IllegalStateException("No Bluetooth adapter")

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            bleScanner = bluetoothAdapter.bluetoothLeScanner
            startBLEScan()
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            result?.let { scanResult ->
                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    Log.e("BLEReceiver", "Falta permiso BLUETOOTH_CONNECT")
                    return
                }

                val name = scanResult.device.name ?: "Desconocido"
                val address = scanResult.device.address
                val rssi = scanResult.rssi

                val data = scanResult.scanRecord?.manufacturerSpecificData?.get(76)
                if (data != null && data.size >= 23) {
                    val temp = ((data[18].toInt() and 0xFF) shl 8) or (data[19].toInt() and 0xFF)
                    val hum  = ((data[20].toInt() and 0xFF) shl 8) or (data[21].toInt() and 0xFF)

                    val device = ScannedDevice(name, address, rssi, temp, hum)
                    onDeviceDiscovered?.invoke(device)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("BLEReceiver", "Scan falló con error: $errorCode")
        }
    }

    private fun startBLEScan() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Log.e("BLEReceiver", "Permiso BLUETOOTH_SCAN no concedido.")
            return
        }

        try {
            val scanFilter = ScanFilter.Builder().build()
            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            bleScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
        } catch (e: SecurityException) {
            Log.e("BLEReceiver", "SecurityException al iniciar el escaneo: ${e.message}")
        }
    }

    data class ScannedDevice(
        val name: String,
        val address: String,
        val rssi: Int,
        val temperature: Int,
        val humidity: Int
    )

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun BLEScannerUI(
        devices: List<ScannedDevice>,
        selectedDevice: ScannedDevice?,
        onDeviceClick: (ScannedDevice) -> Unit
    ) {
        Scaffold(
            topBar = {
                TopAppBar(title = { Text("Dispositivos BLE") })
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp)
            ) {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(devices) { device ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp)
                                .clickable { onDeviceClick(device) }
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Nombre: ${device.name}")
                                Text("Dirección: ${device.address}")
                                Text("RSSI: ${device.rssi} dBm")
                                Text("Temp: ${device.temperature}°C")
                                Text("Humedad: ${device.humidity}%")
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                selectedDevice?.let {
                    Divider()
                    Text("Seleccionado:", style = MaterialTheme.typography.titleMedium)
                    Text("Nombre: ${it.name}")
                    Text("Temp: ${it.temperature}°C")
                    Text("Humedad: ${it.humidity}%")
                }
            }
        }
    }
}
