package com.example.accelerometerdemo;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Adapter;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
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

    public static final String TAG = "MainActivity";
    BluetoothAdapter mBluetoothAdapter;
    Button btnEnableDisable_Discoverable;
    private LineChart accelerometerChart;

    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 1;

    // Activity Result Launcher for enabling Bluetooth
    private ActivityResultLauncher<Intent> enableBtLauncher;

    // Variables for BT discovery
    private ArrayList<String> deviceList = new ArrayList<>();
    private ArrayAdapter<String> deviceListAdapter;
    private ListView lvNewDevices;

    // Map to store BluetoothDevice objects corresponding to the names in the list
    private Map<String, BluetoothDevice> discoveredDevicesMap = new HashMap<>();

    // BLE specific variables
    private BluetoothGatt mGatt; // Holds the GATT client connection
    private boolean isConnecting = false;

    // Custom BLE UUIDS for the devices
    private static final UUID BLUEIO_SERVICE_UUID = UUID.fromString("ef680400-9b35-4933-9b10-52ffa9740042");
    // ****** CHANGE THIS UUID to your ACTUAL Accelerometer Characteristic UUID ******
    private static final UUID ACCELEROMETER_CHAR_UUID = UUID.fromString("ef680401-9b35-4933-9b10-52ffa9740042"); // HYPOTHETICAL ACCELEROMETER UUID
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"); // Standard CCCD UUID


    // Data for chart
    private ArrayList<Entry> accelXEntries = new ArrayList<>();
    private ArrayList<Entry> accelYEntries = new ArrayList<>();
    private ArrayList<Entry> accelZEntries = new ArrayList<>();
    private int dataPointCount = 0;

    @RequiresApi(api = Build.VERSION_CODES.S)
    private void requestBluetoothPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED
        ) {

            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.BLUETOOTH,
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_ADVERTISE,
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.ACCESS_FINE_LOCATION
                    },
                    REQUEST_BLUETOOTH_PERMISSIONS);
        } else {
            // Permissions already granted, proceed with Bluetooth operations

        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            boolean allPermissionsGranted = true;
            for (int i = 0; i < 4; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Bluetooth permissions " + grantResults[i] + " denied", Toast.LENGTH_SHORT).show();
                }
            }

            if (allPermissionsGranted) {
                // Permissions granted, proceed with Bluetooth operations

            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // Initialize ActivityResultLauncher for enabling Bluetooth
        enableBtLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK) {
                        Toast.makeText(this, "Bluetooth has been enabled.", Toast.LENGTH_SHORT).show();
                        // Bluetooth is now enabled, you can try starting discovery
                        startDiscovery();
                    } else {
                        Toast.makeText(this, "Bluetooth not enabled.", Toast.LENGTH_SHORT).show();
                    }
                }
        );

        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported on this device.", Toast.LENGTH_LONG).show();
            finish(); // Close the app if Bluetooth is not supported
            return;
        }

        // Initialize ListView and Adapter
        lvNewDevices = findViewById(R.id.lvNewDevices);
        deviceListAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, deviceList);
        lvNewDevices.setAdapter(deviceListAdapter);

        // Setup item click listener for the discovered devices list
        lvNewDevices.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                String deviceInfo = deviceList.get(position);
                String deviceAddress = deviceInfo.substring(deviceInfo.lastIndexOf("\n") + 1);
                BluetoothDevice device = discoveredDevicesMap.get(deviceInfo); // Get the actual BluetoothDevice object

                if (device != null) {
                    if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                        return;
                    }
                    if (mBluetoothAdapter.isDiscovering()) {
                        // Check BLUETOOTH_SCAN permission before cancelling discovery (if on S+)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                            Toast.makeText(MainActivity.this, "BLUETOOTH_SCAN permission needed to cancel discovery.", Toast.LENGTH_SHORT).show();
                            requestBluetoothPermissions();
                            return;
                        }
                        mBluetoothAdapter.cancelDiscovery();
                        Log.d(TAG, "Cancelled discovery to connect to " + (device.getName() != null ? device.getName() : "Unknown Device"));
                    }
                    connectToDevice(device);
                } else {
                    Log.e(TAG, "BluetoothDevice object not found for: " + deviceInfo);
                }
            }
        });

        // Request permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestBluetoothPermissions();
        }
        else {
            Log.d(TAG,"Asked bluetooth here");
            // For older versions of android
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.BLUETOOTH},
                        REQUEST_BLUETOOTH_PERMISSIONS); // Reusing the same request code for simplicity
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                REQUEST_BLUETOOTH_PERMISSIONS); // Reusing the same request code for simplicity
            }
        }

        // Register for broadcasts when a device is discovered
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        registerReceiver(mReceiver, filter);

        // Add a button to start discovery
        Button btnStartDiscovery = (Button)findViewById(R.id.btnStartDiscovery);
        btnStartDiscovery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startDiscovery();
            }
        });

        // Initialize LineChart
        accelerometerChart = findViewById(R.id.accelerometerChart); // Make sure this ID matches your XML
        setupChart(accelerometerChart, "Accelerometer Data (X, Y, Z)");
    }

    // Chart Setup
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

    // New method to add accelerometer data entries
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


    // Create a BroadcastReceiver for ACTION_FOUND.
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                // Discovery has found a device. Get the BluetoothDevice object and its info from the Intent.
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null) {
                    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        return;
                    }
                    String deviceName = device.getName() != null ? device.getName() : "Unknown Device";
                    String deviceHardwareAddress = device.getAddress(); // MAC address
                    Log.d(TAG, "Device Found: " + deviceName + " - " + deviceHardwareAddress);

                    // Add the device to your list if not already present
                    String deviceInfo = deviceName + "\n" + deviceHardwareAddress;
                    if (!deviceList.contains(deviceInfo)) {
                        deviceList.add(deviceInfo);
                        deviceListAdapter.notifyDataSetChanged();
                    }
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)) {
                Log.d(TAG, "Bluetooth Discovery Started.");
                Toast.makeText(context, "Scanning for devices...", Toast.LENGTH_SHORT).show();
                deviceList.clear(); // Clear previous list
                deviceListAdapter.notifyDataSetChanged();
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                Log.d(TAG, "Bluetooth Discovery Finished.");
                Toast.makeText(context, "Scan finished.", Toast.LENGTH_SHORT).show();
            }
        }
    };

    private void startDiscovery() {
        // Check BLUETOOTH_SCAN permission before starting discovery
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "BLUETOOTH_SCAN permission required to start discovery.", Toast.LENGTH_SHORT).show();
            requestBluetoothPermissions(); // Request permissions again if missing
            return;
        }
        // For older APIs, ACCESS_FINE_LOCATION is often required for discovery
        else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Location permission required to start discovery.", Toast.LENGTH_SHORT).show();
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQUEST_BLUETOOTH_PERMISSIONS);
            return;
        }

        if (mBluetoothAdapter.isDiscovering()) {
            // Check BLUETOOTH_SCAN permission again before cancelling discovery (if on S+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "BLUETOOTH_SCAN permission needed to cancel discovery.", Toast.LENGTH_SHORT).show();
                requestBluetoothPermissions();
                return;
            }
            mBluetoothAdapter.cancelDiscovery();
            Log.d(TAG, "Cancelling discovery.");
        }

        if (mBluetoothAdapter.isEnabled()) {
            Log.d(TAG, "Starting discovery.");
            mBluetoothAdapter.startDiscovery();
        } else {
            // If Bluetooth is not enabled, request to enable it
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            // Explicitly check BLUETOOTH_CONNECT permission before launching the activity for result
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "BLUETOOTH_CONNECT permission needed to enable Bluetooth.", Toast.LENGTH_SHORT).show();
                requestBluetoothPermissions(); // Request permissions again if missing
                return;
            }
            // Use the new Activity Result API
            enableBtLauncher.launch(enableBtIntent); // <--- HERE IS THE CHANGE
        }
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

        // Check BLUETOOTH_CONNECT permission
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "BLUETOOTH_CONNECT permission required to connect.", Toast.LENGTH_SHORT).show();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requestBluetoothPermissions();
            }
            return;
        }

        // We want to directly connect to the device, so we pass in false as the autoConnect parameter.
        // Also, use TRANSPORT_LE for BLE devices.
        isConnecting = true;
        mGatt = device.connectGatt(this, false, mGattCallback, BluetoothDevice.TRANSPORT_LE);
        Log.d(TAG, "Attempting to create a new GATT connection.");
        Toast.makeText(this, "Connecting to " + (device.getName() != null ? device.getName() : device.getAddress()) + "...", Toast.LENGTH_SHORT).show();
    }

    // GATT Callbacks for BLE communication
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d(TAG, "Connected to GATT server.");
                    isConnecting = false;
                    if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        return;
                    }
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Connected to " + (gatt.getDevice().getName() != null ? gatt.getDevice().getName() : gatt.getDevice().getAddress()), Toast.LENGTH_SHORT).show());

                    // Discover services after successful connection
                    // Requires BLUETOOTH_CONNECT permission
                    if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        Log.w(TAG, "BLUETOOTH_CONNECT permission missing for discoverServices.");
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "Permission missing for service discovery.", Toast.LENGTH_SHORT).show());
                        return;
                    }
                    gatt.discoverServices();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d(TAG, "Disconnected from GATT server.");
                    isConnecting = false;
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "Disconnected.", Toast.LENGTH_SHORT).show();
                        // Clear chart data upon disconnection
                        accelerometerChart.clear();
                        dataPointCount = 0;
                    });
                    gatt.close(); // Close the GATT client
                    mGatt = null;
                }
            } else {
                Log.w(TAG, "GATT connection failed with status: " + status);
                isConnecting = false;
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Connection failed (Status: " + status + ")", Toast.LENGTH_LONG).show());
                gatt.close();
                mGatt = null;
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered.");
                List<BluetoothGattService> services = gatt.getServices();
                for (BluetoothGattService service : services) {
                    Log.d(TAG, "Service UUID: " + service.getUuid().toString());
                    if (BLUEIO_SERVICE_UUID.equals(service.getUuid())) {
                        Log.d(TAG, "Found BlueIO Service!");
                        // ****** Look for Accelerometer Characteristic here ******
                        BluetoothGattCharacteristic accelerometerCharacteristic = service.getCharacteristic(ACCELEROMETER_CHAR_UUID);
                        if (accelerometerCharacteristic != null) {
                            Log.d(TAG, "Found Accelerometer Characteristic!");
                            // Enable notifications for this characteristic
                            setCharacteristicNotification(gatt, accelerometerCharacteristic, true);
                        } else {
                            Log.w(TAG, "Accelerometer Characteristic (UUID: " + ACCELEROMETER_CHAR_UUID.toString() + ") not found for BlueIO Service.");
                        }
                    }
                }
            } else {
                Log.w(TAG, "onServicesDiscovered received: " + status);
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // This callback is for direct reads, not notifications
                Log.d(TAG, "Characteristic read: " + characteristic.getUuid().toString() + " Value: " + Arrays.toString(characteristic.getValue()));
            } else {
                Log.e(TAG, "Characteristic read failed: " + status);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            // This is where you receive data updates from the characteristic
            // ****** Check for Accelerometer Characteristic UUID ******
            if (ACCELEROMETER_CHAR_UUID.equals(characteristic.getUuid())) {
                byte[] data = characteristic.getValue();
                if (data != null && data.length >= 6) { // Expecting 3 shorts (2 bytes each) for X, Y, Z
                    // Parse the 3 short values (X, Y, Z)
                    // Assuming the data is little-endian as is common in BLE
                    ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
                    short xRaw = buffer.getShort();
                    short yRaw = buffer.getShort();
                    short zRaw = buffer.getShort();

                    // Convert raw short values to float (e.g., if chip sends mg, divide by 1000 for Gs)
                    // You might need a scaling factor from your chip's documentation.
                    // For now, let's just cast to float.
                    float xAccel = (float) xRaw;
                    float yAccel = (float) yRaw;
                    float zAccel = (float) zRaw;

                    Log.d(TAG, String.format("Accelerometer: X=%.2f, Y=%.2f, Z=%.2f", xAccel, yAccel, zAccel));

                    // Update UI on the main thread
                    runOnUiThread(() -> addAccelerometerEntry(xAccel, yAccel, zAccel));
                } else {
                    Log.w(TAG, "Received incomplete or null accelerometer data: " + (data != null ? Arrays.toString(data) : "null"));
                }
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Descriptor written successfully: " + descriptor.getUuid().toString());
            } else {
                Log.e(TAG, "Descriptor write failed: " + status);
            }
        }
    };

    /**
     * Enables or disables notification/indication for a given characteristic.
     *
     * @param gatt The GATT client.
     * @param characteristic The characteristic to enable/disable notifications for.
     * @param enable True to enable, false to disable.
     */
    private void setCharacteristicNotification(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, boolean enable) {
        // Requires BLUETOOTH_CONNECT permission
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "BLUETOOTH_CONNECT permission missing for setCharacteristicNotification.");
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Permission missing to enable notifications.", Toast.LENGTH_SHORT).show());
            return;
        }

        gatt.setCharacteristicNotification(characteristic, enable);

        // This is essential for notifications/indications to work on most BLE devices.
        // You need to write to the Client Characteristic Configuration Descriptor (CCCD).
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mReceiver);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        if (mBluetoothAdapter != null && mBluetoothAdapter.isDiscovering()) {
            mBluetoothAdapter.cancelDiscovery();
        }
    }
}