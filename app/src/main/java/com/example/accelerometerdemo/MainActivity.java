package com.example.accelerometerdemo;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
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

import java.util.ArrayList;


public class MainActivity extends AppCompatActivity {

    public static final String TAG = "MainActivity";
    BluetoothAdapter mBluetoothAdapter;
    Button btnEnableDisable_Discoverable;
    private LineChart xAccelerationChart;

    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 1;
    private static final int REQUEST_ENABLE_BT = 2;

    // Activity Result Launcher for enabling Bluetooth
    private ActivityResultLauncher<Intent> enableBtLauncher;

    // Variables for BT discovery
    private ArrayList<String> deviceList = new ArrayList<>();
    private ArrayAdapter<String> deviceListAdapter;
    private ListView lvNewDevices;

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
            if (grantResults.length > 0 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED &&
                    grantResults[1] == PackageManager.PERMISSION_GRANTED &&
                    grantResults[2] == PackageManager.PERMISSION_GRANTED &&
                    grantResults[3] == PackageManager.PERMISSION_GRANTED) {
                // Permissions granted, proceed with Bluetooth operations
            } else {
                // Permissions denied, handle accordingly (e.g., show a message, disable Bluetooth features)
                Toast.makeText(this, "Bluetooth permissions denied.", Toast.LENGTH_SHORT).show();
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