/*
 * Copyright 2017 The Android Things Samples Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.androidthings.photobooth;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

import com.google.android.things.contrib.driver.button.Button;
import com.google.android.things.pio.PeripheralManagerService;

import java.io.IOException;
import java.util.List;

public class PhotoboothActivity extends Activity {

    private static final String TAG = "CameraActivity";

    private static final int PERMISSIONS_REQUEST = 1;

    private static final String PERMISSION_CAMERA = Manifest.permission.CAMERA;
    private static final String PERMISSION_STORAGE = Manifest.permission.WRITE_EXTERNAL_STORAGE;

    private boolean debug = false;

    // Fragments are initialized programmatically, so there's no ID's.  Keep references to them.
    CameraConnectionFragment cameraFragment = null;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_camera);

        if (hasPermission()) {
            if (null == savedInstanceState) {
                setFragment();
            }
        } else {
            requestPermission();
        }

        PeripheralManagerService manager = new PeripheralManagerService();
        List<String> portList = manager.getGpioList();
        if (portList.isEmpty()) {
            Log.i(TAG, "No GPIO port available on this device.");
        } else {
            Log.i(TAG, "List of available ports: " + portList);
        }
        initializeButton();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyButton();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case PERMISSIONS_REQUEST: {
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED
                        && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                    setFragment();
                } else {
                    requestPermission();
                }
            }
        }
    }

    private boolean hasPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return checkSelfPermission(PERMISSION_CAMERA) == PackageManager.PERMISSION_GRANTED && checkSelfPermission(PERMISSION_STORAGE) == PackageManager.PERMISSION_GRANTED;
        } else {
            return true;
        }
    }

    private void requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (shouldShowRequestPermissionRationale(PERMISSION_CAMERA) || shouldShowRequestPermissionRationale(PERMISSION_STORAGE)) {
                Toast.makeText(PhotoboothActivity.this, "Camera AND storage permission are required for this demo", Toast.LENGTH_LONG).show();
            }
            requestPermissions(new String[] {PERMISSION_CAMERA, PERMISSION_STORAGE}, PERMISSIONS_REQUEST);
        }
    }

    private void setFragment() {
        cameraFragment = new CameraConnectionFragment();
        getFragmentManager()
                .beginTransaction()
                .replace(R.id.container, cameraFragment)
                .commit();
    }

    private Button mButton;
    private final String BUTTON_GPIO_PIN = "BCM23";
    private void initializeButton() {
        try {
            mButton = new Button(BUTTON_GPIO_PIN,
                    Button.LogicState.PRESSED_WHEN_LOW);

            mButtonCallback = (button, pressed) -> {
                if (pressed) {
                    Log.d(TAG, "Button pressed!");
                }
            };

            mButton.setOnButtonEventListener(mButtonCallback);
            Log.d(TAG, "Hardware button initialized.");
        } catch (IOException e) {
            Log.e(TAG, "button driver error", e);
        }
    }

    private void destroyButton() {
        if (mButton != null) {
            try {
                mButton.close();
            } catch (IOException e) {
                Log.e(TAG, "Button driver error", e);
            }
        }
    }

    private Button.OnButtonEventListener mButtonCallback;

    protected void setOnButtonPressedListener(Button.OnButtonEventListener listener) {
        mButtonCallback = listener;
        if(mButton != null) {
            mButton.setOnButtonEventListener(listener);
        }
    }


    public CameraConnectionFragment getCameraFragment() {
        return cameraFragment;
    }

    public boolean isDebug() {
        return debug;
    }
}
