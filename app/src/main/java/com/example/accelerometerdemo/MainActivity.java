package com.example.accelerometerdemo; // Keep your package name

import static android.os.Build.VERSION.SDK_INT;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelUuid;
import android.util.Log;
import android.view.View; // Needed for Button
import android.widget.ArrayAdapter; // Needed for ListView
import android.widget.Button; // Needed for Button
import android.widget.ListView; // Needed for ListView
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    public static final String TAG = "BlueIO_Accelerometer"; // More specific TAG

    // From iothingy app
    public static String targetDeviceName = "BlueIOThingy"; // Name of your BlueIO device
    private static final String BLUEIO_UUID_SERVICE = "ef680400-9b35-4933-9b10-52ffa9740042";
    // Assuming Accelerometer UUID is 0401 based on common patterns
    private static final String ACCELEROMETER_CHAR_UUID_STRING = "ef680406-9b35-4933-9b10-52ffa9740042";
    private static final UUID ACCELEROMETER_CHAR_UUID = UUID.fromString(ACCELEROMETER_CHAR_UUID_STRING);
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"); // Standard CCCD

    private static long SCAN_PERIOD = 50000; // 5 seconds scan
    private Handler mHandler;

    // Permission handling from iothingy app
    private static final int REQUEST_CODE_COARSE_PERMISSION = 1;
    private static final int REQUEST_CODE_BLUETOOTH_PERMISSION = 2;

    private ActivityResultContracts.RequestMultiplePermissions requestMultiplePermissionsContract;
    private ActivityResultLauncher<String[]> multiplePermissionActivityResultLauncher;

    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothManager mBluetoothManager;
    private BluetoothLeScanner mLEScanner;
    private BluetoothGatt mBluetoothGatt;
    private BluetoothGattService mBlueIOService; // Changed from mCustomService
    private BluetoothGattCharacteristic mAccelerometerCharacteristic; // Changed from Characteristic

    private String discoveredDeviceName;
    private String discoveredDeviceAddress;
    private boolean isConnecting = false;
    private boolean notificationEnabled = false; // Flag for notification status

    // UI elements
    private TextView statusTextView; // To show connection status
    private LineChart accelerometerChart;

    // Chart Data
    private ArrayList<Entry> accelXEntries = new ArrayList<>();
    private ArrayList<Entry> accelYEntries = new ArrayList<>();
    private ArrayList<Entry> accelZEntries = new ArrayList<>();
    private int dataPointCount = 0;


    // --- BLE Scan Callback ---
    private ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            // Default behaviour
            super.onScanResult(callbackType, result);
            BluetoothDevice device = result.getDevice();

            if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Check Bluetooth permissions again");
                return;
            }

            String name = device.getName();
            String address = device.getAddress();
            List<ParcelUuid> uuids = result.getScanRecord().getServiceUuids();

            Log.d(TAG, "device: " + name + ", address: " + address + ", UUIDs: " + (uuids != null ? uuids.toString() : "null"));

            // If a device is found with the same name, attempt to establish a GATT connection
            if (name != null && name.equals(targetDeviceName)) {
                // Device found, stop scanning and connect
                if (SDK_INT >= Build.VERSION_CODES.O) {
                    Log.i(TAG, "Found target device: " + name + " - " + address + " - " + (uuids != null ? uuids.toString() : "null") + " - isConnectable : " + result.isConnectable());
                }
                discoveredDeviceName = name;
                discoveredDeviceAddress = address;

                // Stop the scan immediately upon finding the device
                if (mBluetoothAdapter.isEnabled()) {
                    if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                        return; // Should be handled by initial permission request
                    }
                    mLEScanner.stopScan(this);
                    mHandler.removeCallbacksAndMessages(null); // Remove any pending scan stop
                    connectToDevice(device);
                }
            }
            else {
                Log.i(TAG, "Device Name " + device.getName());
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            Log.e(TAG, "Scan Failed: " + errorCode);
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Scan Failed: " + errorCode, Toast.LENGTH_LONG).show());
            isConnecting = false; // Reset connection flag
        }
    };

    // --- GATT Callbacks (runs when the device is connected to the server and sends information such as connection status and further Gatt operations) ---
    private BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            // GATT operation succeeded, now checking if the device is connected to the server
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d(TAG, "Connected to GATT server.");
                    isConnecting = false;

                    // Inform user that the connection is successfuly established
                    runOnUiThread(() -> {
                        if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                            return;
                        }
                        statusTextView.setText("Status: Connected to " + (gatt.getDevice().getName() != null ? gatt.getDevice().getName() : gatt.getDevice().getAddress()));
                        Toast.makeText(MainActivity.this, "Connected.", Toast.LENGTH_SHORT).show();
                    });

                    // Discover services after successful connection
                    if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        Log.w(TAG, "BLUETOOTH_CONNECT permission missing for discoverServices.");
                        return;
                    }
                    gatt.discoverServices();
                }
                else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d(TAG, "Disconnected from GATT server.");
                    isConnecting = false;

                    // Inform user of this disconnection
                    runOnUiThread(() -> {
                        statusTextView.setText("Status: Disconnected");
                        Toast.makeText(MainActivity.this, "Disconnected.", Toast.LENGTH_SHORT).show();

                        // Clear chart data upon disconnection
                        accelerometerChart.clear();
                        dataPointCount = 0;
                        notificationEnabled = false; // Reset notification flag

                        // Restart scan here if auto-reconnect is needed
                        // scanBluetoothDevices(true);
                    });

                    // Close the GATT client
                    gatt.close();
                    mBluetoothGatt = null;
                }
            }

            // GATT operations failed, connection issues
            else {
                Log.w(TAG, "GATT connection failed with status: " + status);
                isConnecting = false;

                // Inform user
                runOnUiThread(() -> {
                    statusTextView.setText("Status: Connection Failed (" + status + ")");
                    Toast.makeText(MainActivity.this, "Connection failed (Status: " + status + ")", Toast.LENGTH_LONG).show();
                });

                // Close the GATT client
                gatt.close();
                mBluetoothGatt = null;
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered.");

                // Finds if a service with the provided UUID above exists in the device
                mBlueIOService = gatt.getService(UUID.fromString(BLUEIO_UUID_SERVICE));
                if (mBlueIOService == null) {
                    Log.w(TAG, "BlueIO Service (UUID: " + BLUEIO_UUID_SERVICE + ") not found!");
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "BlueIO Service not found!", Toast.LENGTH_LONG).show());
                    return;
                }

                // If that service exist, find its characteristics
                mAccelerometerCharacteristic = mBlueIOService.getCharacteristic(ACCELEROMETER_CHAR_UUID);
                if (mAccelerometerCharacteristic != null) {
                    Log.d(TAG, "Found Accelerometer Characteristic!");
                    // Enable notifications
                    setCharacteristicNotification(gatt, mAccelerometerCharacteristic, true);
                }
                else {
                    Log.w(TAG, "Accelerometer Characteristic (UUID: " + ACCELEROMETER_CHAR_UUID_STRING + ") not found.");
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Accelerometer characteristic not found.", Toast.LENGTH_LONG).show());
                }
            }
            // In the case the GATT connection failed, send a warning message in console
            else Log.w(TAG, "onServicesDiscovered received: " + status);
        }

        // Whenever the motion sensor "characteristic" gets updated, update the graph along with it (based on code from IOThingy app demo)
        // ... inside MainActivity class ...

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (ACCELEROMETER_CHAR_UUID.equals(characteristic.getUuid())) {
                byte[] data = characteristic.getValue();

                // Check for a valid data packet.
                if (data != null && data.length > 0) {
                    // Log the entire raw data packet to see its structure.
                    if(Arrays.toString(data).contains("1") || Arrays.toString(data).contains("-1")) {
                        //Log.d(TAG, "Raw Data Packet (" + data.length + " bytes): " + Arrays.toString(data));
                    }

                    // Use a ByteBuffer to parse the data safely with the correct byte order.
                    ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

                    // --- Parsing Logic ---
                    // Try different starting positions based on your logs.
                    // The logs show data at different offsets.
                    // Let's assume the data is at a fixed position, and we need to find it.

                    // The accelerometer data is three 16-bit signed integers (shorts).
                    // A common pattern is for the data to be at a fixed offset.

                    // Let's test a couple of common starting positions.
                    int startPosition = 6; // Based on your earlier code.

                    if (data.length - startPosition >= 6) {
                        buffer.position(startPosition);

                        short yRaw = buffer.getShort();
                        short zRaw = buffer.getShort();
                        short xRaw = buffer.getShort();

                        // Check if the data is all zeros before processing.
                        if (xRaw == 0 && yRaw == 0 && zRaw == 0) {
                            //Log.d(TAG, "Skipping all-zero data packet.");
                            return; // Ignore this packet and wait for the next one.
                        }

                        // Apply the scale factor to convert to G's.
                        float scaleFactor = 16384.0f; // Assumes a +/-2g range.

                        float xAccel = (float) xRaw / scaleFactor;
                        float yAccel = (float) yRaw / scaleFactor;
                        float zAccel = (float) zRaw / scaleFactor;

                        // Log the parsed values for verification.
                        //Log.d(TAG, String.format("Parsed Data @pos %d - X Raw: %d, Y Raw: %d, Z Raw: %d", startPosition, xRaw, yRaw, zRaw));
                        //Log.d(TAG, String.format("Processed Accel: X=%.4f, Y=%.4f, Z=%.4f", xAccel, yAccel, zAccel));

                        if (xAccel != 0 || yAccel != 0 || zAccel != 0) {
                            Log.d(TAG, "AAAAAAAAAAAAAAAAAAAAAAAAAAAA");
                            Log.d(TAG, "Raw Data Packet (" + data.length + " bytes): " + Arrays.toString(data));
                        }

                        // Update the UI.
                        runOnUiThread(() -> addAccelerometerEntry(xAccel, yAccel, zAccel));
                    } else {
                        Log.w(TAG, "Packet too short to contain accelerometer data at position " + startPosition);
                    }
                } else {
                    Log.w(TAG, "Received empty or null data packet.");
                }
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Descriptor written successfully: " + descriptor.getUuid().toString());
                if (CCCD_UUID.equals(descriptor.getUuid())) {
                    notificationEnabled = true;
                    Log.d(TAG, "Notifications enabled for Accelerometer Characteristic.");
                }
            } else {
                Log.e(TAG, "Descriptor write failed: " + status);
            }
        }
    };

    // --- Helper for converting byte array to int32 (Code from IOThingy app demo)---
    public static int convertByteArrayToInt32(byte[] bytes) {
        return ((bytes[3] & 0xFF) << 24) |
                ((bytes[2] & 0xFF) << 16) |
                ((bytes[1] & 0xFF) << 8) |
                ((bytes[0] & 0xFF) << 0);
    }

    // --- Activity Lifecycle and Permissions ---
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main); // Keep your own layout for buttons/list/chart

        // Apply window insets (from your previous code)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        mHandler = new Handler();
        mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = mBluetoothManager.getAdapter();

        // Check for BLE support and initialize BLE Scanner if it does
        if (mBluetoothAdapter == null || !getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, "ble_not_supported", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        mLEScanner = mBluetoothAdapter.getBluetoothLeScanner();

        // Initialize UI elements
        statusTextView = findViewById(R.id.statusTextView); // You'll need to add this to your layout
        accelerometerChart = findViewById(R.id.accelerometerChart);
        setupChart(accelerometerChart, "Accelerometer Data (X, Y, Z)");

        Button btnStartScan = findViewById(R.id.btnStartDiscovery); // Use your existing button
        btnStartScan.setOnClickListener(v -> startScan()); // Call the new startScan method

        // --- Permission handling setup (from iothingy) ---
        requestMultiplePermissionsContract = new ActivityResultContracts.RequestMultiplePermissions();
        multiplePermissionActivityResultLauncher = registerForActivityResult(requestMultiplePermissionsContract, isGranted -> {
            Log.d(TAG, "Permissions Launcher result: " + isGranted.toString());

            // Check if all necessary permissions are granted (Seperated by asking based on build version of device
            boolean allGranted = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (!isGranted.getOrDefault(Manifest.permission.BLUETOOTH_SCAN, false) || !isGranted.getOrDefault(Manifest.permission.BLUETOOTH_CONNECT, false)) {
                    allGranted = false;
                }
            }
            else {
                if (!isGranted.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false)) { // Coarse is usually enough for pre-S scanning
                    allGranted = false;
                }
            }

            // If the device hasn't granted permission, ask user to do so
            if (!allGranted) {
                Toast.makeText(this, "Required Bluetooth/Location permissions not granted. App may not function correctly.", Toast.LENGTH_LONG).show();
            }
            else {
                Log.d(TAG, "All necessary BLE permissions granted.");
                // Permissions are good, proceed with asking to enable Bluetooth if needed
                askBluetoothPermission();
            }
        });

        // Request permissions on startup
        requestBlePermissions(this, REQUEST_CODE_BLUETOOTH_PERMISSION);
        askCoarsePermission();
    }

    // --- Method to scan devices nearby ---
    private void startScan() {
        // Prompt to enable Bluetooth if not enabled
        if (!mBluetoothAdapter.isEnabled()) {
            askBluetoothPermission();
            return;
        }

        if (mLEScanner == null) {
            Toast.makeText(this, "BLE Scanner not initialized. Bluetooth may be off or unsupported.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Check BLUETOOTH_SCAN permission before starting discovery
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "BLUETOOTH_SCAN permission required to start scan.", Toast.LENGTH_SHORT).show();
            requestBlePermissions(this, REQUEST_CODE_BLUETOOTH_PERMISSION);
            return;
        }
        else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Location permission required to start scan.", Toast.LENGTH_SHORT).show();
            askCoarsePermission();
            return;
        }

        // Start discovery
        Log.d(TAG, "Starting BLE scan for device: " + targetDeviceName);
        runOnUiThread(() -> statusTextView.setText("Status: Scanning for " + targetDeviceName + "..."));
        Toast.makeText(this, "Scanning for " + targetDeviceName + "...", Toast.LENGTH_SHORT).show();

        // Reset current discovered device name
        discoveredDeviceName = null;
        discoveredDeviceAddress = null;

        // This will run after scanning for "SCAN_PERIOD" amount of time and not finding anything
        mHandler.postDelayed(() -> {
            if (mBluetoothAdapter.isEnabled() && mLEScanner != null) {
                // Stop mLEScanner from running if no device can be found
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                    return;
                }
                mLEScanner.stopScan(mScanCallback);

                // Inform user of failure
                Log.d(TAG, "Scan stopped by timeout.");
                if (discoveredDeviceAddress == null) {
                    runOnUiThread(() -> {
                        statusTextView.setText("Status: Scan finished, device not found.");
                        Toast.makeText(MainActivity.this, "Device " + targetDeviceName + " not found.", Toast.LENGTH_LONG).show();
                    });
                }
            }
        }, SCAN_PERIOD);

        // Start scan within that "SCAN_PERIOD" interval
        mLEScanner.startScan(mScanCallback);
    }

    private void connectToDevice(BluetoothDevice device) {
        if (isConnecting) {
            Log.d(TAG, "Already attempting to connect. Ignoring new request.");
            return;
        }

        if (mBluetoothAdapter == null || device == null) {
            Log.w(TAG, "BluetoothAdapter not initialized or unspecified device.");
            return;
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "BLUETOOTH_CONNECT permission required to connect.", Toast.LENGTH_SHORT).show();
            requestBlePermissions(this, REQUEST_CODE_BLUETOOTH_PERMISSION); // Re-request
            return;
        }

        isConnecting = true;
        // Connect with autoConnect = false for direct connection
        mBluetoothGatt = device.connectGatt(this, false, mGattCallback, BluetoothDevice.TRANSPORT_LE);
        Log.d(TAG, "Attempting to create a new GATT connection.");
        runOnUiThread(() -> statusTextView.setText("Status: Connecting to " + (device.getName() != null ? device.getName() : device.getAddress()) + "..."));
    }

    /**
     * Enables or disables notification/indication for a given characteristic.
     * @param gatt The GATT client.
     * @param characteristic The characteristic to enable/disable notifications for.
     * @param enable True to enable, false to disable.
     */
    private void setCharacteristicNotification(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, boolean enable) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "BLUETOOTH_CONNECT permission missing for setCharacteristicNotification.");
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Permission missing to enable notifications.", Toast.LENGTH_SHORT).show());
            return;
        }

        gatt.setCharacteristicNotification(characteristic, enable);

        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD_UUID);
        if (descriptor == null) {
            Log.e(TAG, "CCCD descriptor not found for characteristic: " + characteristic.getUuid().toString());
            return;
        }

        byte[] value;
        if (enable) {
            if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
                Log.d(TAG, "Enabling notifications for " + characteristic.getUuid().toString());
            } else if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
                Log.d(TAG, "Enabling indications for " + characteristic.getUuid().toString());
            } else {
                Log.w(TAG, "Characteristic does not support notifications or indications: " + characteristic.getUuid().toString());
                return;
            }
        } else {
            value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
            Log.d(TAG, "Disabling notifications for " + characteristic.getUuid().toString());
        }

        descriptor.setValue(value);
        gatt.writeDescriptor(descriptor);
    }

    // --- Permission Helper Methods (based on IOThingy Code) ---
    private final String[] ANDROID_12_BLE_PERMISSIONS = new String[]{
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
    };
    private final String[] BLE_PERMISSIONS = new String[]{
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
    };

    public void requestBlePermissions(Activity activity, int requestCode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            ActivityCompat.requestPermissions(activity, ANDROID_12_BLE_PERMISSIONS, requestCode);
        else
            ActivityCompat.requestPermissions(activity, BLE_PERMISSIONS, requestCode);
    }

    // For getOrDefault
    private void askCoarsePermission() {
        if (SDK_INT >= Build.VERSION_CODES.Q) { // Android 10 (Q) and above, ACCESS_FINE_LOCATION implies COARSE
            ActivityResultLauncher<String[]> locationPermissionRequest =
                    registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                                Boolean fineLocationGranted = result.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false);
                                Boolean coarseLocationGranted = result.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false);
                                if (fineLocationGranted != null && fineLocationGranted) {
                                    Toast.makeText(MainActivity.this, "Precise location access granted.", Toast.LENGTH_SHORT).show();
                                } else if (coarseLocationGranted != null && coarseLocationGranted) {
                                    Toast.makeText(MainActivity.this, "Only approximate location access granted.", Toast.LENGTH_SHORT).show();
                                } else {
                                    Toast.makeText(MainActivity.this, "No location access granted. BLE scanning may not work.", Toast.LENGTH_SHORT).show();
                                }
                            }
                    );

            locationPermissionRequest.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            });
        } else { // Below Android 10
            if (this.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_CODE_COARSE_PERMISSION);
            }
        }
    }

    private void askBluetoothPermission() {
        if (!mBluetoothAdapter.isEnabled()) {
            // Check BLUETOOTH_CONNECT permission before launching enable BT intent on Android 12+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "BLUETOOTH_CONNECT permission needed to enable Bluetooth.", Toast.LENGTH_SHORT).show();
                requestBlePermissions(this, REQUEST_CODE_BLUETOOTH_PERMISSION);
                return;
            }
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            bluetoothEnableLauncher.launch(enableBtIntent);
        }
    }

    // ActivityResultLauncher for enabling Bluetooth (similar to previous code)
    private ActivityResultLauncher<Intent> bluetoothEnableLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    Toast.makeText(this, "Bluetooth has been enabled.", Toast.LENGTH_SHORT).show();
                    // Bluetooth is now enabled, you can try starting discovery
                    startScan(); // Restart scan after Bluetooth enabled
                } else {
                    Toast.makeText(this, "Bluetooth not enabled.", Toast.LENGTH_SHORT).show();
                    runOnUiThread(() -> statusTextView.setText("Status: Bluetooth Disabled"));
                }
            }
    );

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_CODE_COARSE_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Location Permission Granted!", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Location Permission Denied! BLE scanning requires it.", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQUEST_CODE_BLUETOOTH_PERMISSION) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                Log.d(TAG, "All Bluetooth permissions granted from onRequestPermissionsResult.");
                askBluetoothPermission(); // Now that permissions are granted, ask to enable BT if off
            } else {
                Toast.makeText(this, "Bluetooth permissions denied. Cannot perform BLE operations.", Toast.LENGTH_LONG).show();
            }
        }
    }

    // --- Chart Setup (retained from previous code) ---
    private void setupChart(LineChart chart, String description) {
        chart.getDescription().setText(description);
        chart.setNoDataText("No data yet.");
        chart.setTouchEnabled(true);
        chart.setDragEnabled(true);
        chart.setScaleEnabled(true);
        chart.setPinchZoom(true);
        chart.setDrawGridBackground(false);

        // Set up axes
        chart.getXAxis().setDrawGridLines(false);
        chart.getAxisLeft().setDrawGridLines(false);
        chart.getAxisRight().setEnabled(false); // Disable right axis if not needed

        // Add initial empty data
        chart.setData(new LineData());
        chart.invalidate();
    }

    private void addAccelerometerEntry(float x, float y, float z) {
        LineData data = accelerometerChart.getData();

        if (data != null) {
            ILineDataSet setX = data.getDataSetByIndex(0);
            ILineDataSet setY = data.getDataSetByIndex(1);
            ILineDataSet setZ = data.getDataSetByIndex(2);

            // Create datasets if they don't exist
            if (setX == null) {
                setX = createDataSet("X-Axis", getResources().getColor(android.R.color.holo_red_light));
                data.addDataSet(setX);
            }
            if (setY == null) {
                setY = createDataSet("Y-Axis", getResources().getColor(android.R.color.holo_green_light));
                data.addDataSet(setY);
            }
            if (setZ == null) {
                setZ = createDataSet("Z-Axis", getResources().getColor(android.R.color.holo_blue_light));
                data.addDataSet(setZ);
            }

            data.addEntry(new Entry(dataPointCount, x), 0); // Add to X-axis dataset
            data.addEntry(new Entry(dataPointCount, y), 1); // Add to Y-axis dataset
            data.addEntry(new Entry(dataPointCount, z), 2); // Add to Z-axis dataset

            data.notifyDataChanged();

            // let the chart know it's data has changed
            accelerometerChart.notifyDataSetChanged();

            // limit the number of visible entries
            accelerometerChart.setVisibleXRangeMaximum(50); // Show 50 entries at a time
            // move to the latest entry
            accelerometerChart.moveViewToX(data.getEntryCount());

            dataPointCount++;
        }
    }

    private LineDataSet createDataSet(String label, int color) {
        LineDataSet set = new LineDataSet(null, label);
        set.setAxisDependency(com.github.mikephil.charting.components.YAxis.AxisDependency.LEFT);
        set.setColor(color);
        set.setDrawCircles(false);
        set.setLineWidth(2f);
        set.setMode(LineDataSet.Mode.LINEAR); // Use linear mode for smooth lines
        set.setDrawValues(false); // Do not draw values on the chart
        return set;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Stop scanning if active
        if (mLEScanner != null && mBluetoothAdapter.isEnabled()) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                mLEScanner.stopScan(mScanCallback);
            }
        }
        // Close GATT connection
        if (mBluetoothGatt != null) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                mBluetoothGatt.close();
            }
            mBluetoothGatt = null;
        }
        mHandler.removeCallbacksAndMessages(null); // Clean up any pending handler messages
    }
}