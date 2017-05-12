/*
 * Copyright 2017 The Android Open Source Project.
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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import com.example.androidthings.photobooth.PhotoStripBuilder.PhotoStripSpec;
import com.google.android.things.contrib.driver.button.Button;
import com.google.android.things.contrib.driver.button.ButtonInputDriver;
import com.google.firebase.messaging.FirebaseMessaging;

import java.io.IOException;

/**
 * Entry point for the Photobooth application.
 */
public class PhotoboothActivity extends Activity {

    private static final String TAG = "PhotoboothActivity";

    private static final boolean DEBUG_IGNORE_MESSAGES = false;
    private static final int PERMISSIONS_REQUEST = 1;

    private static final String PERMISSION_CAMERA = Manifest.permission.CAMERA;
    private static final String PERMISSION_STORAGE = Manifest.permission.WRITE_EXTERNAL_STORAGE;

    public static final String MESSAGE_RECEIVED = "MESSAGE_RECEIVED";

    // Fragments are initialized programmatically, so there's no ID's.  Keep references to them.
    private CameraConnectionFragment cameraFragment = null;

    private ThermalPrinter mThermalPrinter;

    // For testing the artistic styles, will save the original image, the "stylized" image,
    // and the blended combination of the two to device when this flag is set to true.
    public static final boolean IMAGE_PREVIEW_DEBUG = false;

    // For testing:  Just take one picture and apply all styles, saving images internally.
    public static final boolean PREVIEW_DUMP_DEBUG = false;

    public static final boolean USE_THERMAL_PRINTER = false;

    private TensorflowStyler mTensorflowStyler;

    // Background threads specifically for Tensorflow
    /**
     * An additional thread for running inference so as not to block the camera.
     */
    private HandlerThread inferenceThread;

    /**
     * A {@link Handler} for running tasks in the background.
     */
    private Handler inferenceHandler;

    // Physical GPIO-connected buttons.  Not UI buttons.
    private ButtonInputDriver mStyleButton;
    private ButtonInputDriver mSecondaryButton;

    private final String PRIMARY_BUTTON_GPIO_PIN = "BCM23";
    private final String SECONDARY_BUTTON_GPIO_PIN = "BCM24";

    private FirebaseStorageAdapter mFirebaseAdapter;

    private Bitmap mCurrSourceBitmap;
    private Bitmap mCurrStyledBitmap;

    // Depending on the button and how "noisy" the signal is, one physical button press can show
    // up as two.  These locks are simple objects to be used for locking using "synchronized".
    final int[] primaryButtonLock = new int[0];
    final int[] secondaryButtonLock = new int[0];


    boolean processingPrimaryButton = false;
    boolean processingSecondaryButton = false;

    private PhotoStripBuilder mPhotoStripBuilder;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_camera);

        if (hasPermission()) {
            if (null == savedInstanceState) {
                loadCameraFragment();
            }
        } else {
            requestPermission();
        }

        if (USE_THERMAL_PRINTER) {
            mThermalPrinter = new ThermalPrinter(this);
        }

        mTensorflowStyler = new TensorflowStyler(this);
        mTensorflowStyler.initializeTensorFlow();

        initializeButtons();

        mFirebaseAdapter = new FirebaseStorageAdapter();

        mPhotoStripBuilder = new PhotoStripBuilder(this);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[],
            @NonNull int[] grantResults) {
        switch (requestCode) {
            case PERMISSIONS_REQUEST: {
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED
                        && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                    loadCameraFragment();
                } else {
                    requestPermission();
                }
            }
        }
    }

    private boolean hasPermission() {
        return checkSelfPermission(PERMISSION_CAMERA) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(PERMISSION_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermission() {
        String[] permissionsToRequest = {PERMISSION_CAMERA, PERMISSION_STORAGE};
        requestPermissions(permissionsToRequest, PERMISSIONS_REQUEST);
    }

    private void loadCameraFragment() {
        cameraFragment = new CameraConnectionFragment();
        getFragmentManager()
                .beginTransaction()
                .replace(R.id.container, cameraFragment)
                .commit();
    }

    public CameraConnectionFragment getCameraFragment() {
        return cameraFragment;
    }

    /**
     *  Initialize a GPIO button button to iterate through different styles and display image on
     *  screen.
     */
    protected void initializeStyleButton() {
        try {
            mStyleButton = new ButtonInputDriver(PRIMARY_BUTTON_GPIO_PIN,
                    Button.LogicState.PRESSED_WHEN_LOW, KeyEvent.KEYCODE_1);
        } catch (IOException e) {
            Log.e(TAG, "initializeStyleButton error", e);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_1:
                stylizePicture();
                return true;
            case KeyEvent.KEYCODE_2:
                // Locking mechanism until setDebounceDelay bugs are sorted out.
                Log.d(TAG, "Secondary button pressed.");
                synchronized (secondaryButtonLock) {
                    if (processingSecondaryButton) {
                        Log.d(TAG, "Secondary button press registered, locked out.");
                        return true;
                    } else {
                        Log.d(TAG, "Secondary button press registered, entering.");
                        processingSecondaryButton = true;
                    }
                }
                processChosenImage(false);
                return true;
            default:
                return super.onKeyUp(keyCode, event);
        }
    }

    public Bitmap takeSnapshot() {
        mCurrSourceBitmap = getCameraFragment().getCurrentFrameCopy();
        return mCurrSourceBitmap;
    }

    public Bitmap showSnapshot(Bitmap bitmap) {
        ImageView snapshotView = (ImageView) findViewById(R.id.imageView);
        snapshotView.setVisibility(View.VISIBLE);
        snapshotView.setImageBitmap(bitmap);
        return bitmap;
    }
    protected void stylizePicture() {
        // Tensorflow takes a while on these images, and we don't want an itchy trigger-finger
        // (or trigger-voice, or whatever) locking up the CPU with too many requests. If one image
        // is being worked on, ignore requests to process others.
        synchronized (primaryButtonLock) {
            if (processingPrimaryButton) {
                Log.d(TAG, "Primary button press registered, locked out..");
                return;
            } else {
                Log.d(TAG, "Primary button press registered, entering.");
                processingPrimaryButton = true;
            }
        }

        if (PREVIEW_DUMP_DEBUG) {
            runInBackground(() -> {
                Bitmap originalBitmap = takeSnapshot();
                mTensorflowStyler.saveStyleExamples(originalBitmap);
                processingPrimaryButton = false;
            });
        } else {
            cameraFragment.stopPreview();
            Bitmap bitmapToStylize = takeSnapshot();
            showSnapshot(mCurrSourceBitmap);

            if (bitmapToStylize != null) {
                Log.d(TAG, "\tcalling stylize.");
                stylizeAndDisplayBitmap(bitmapToStylize);
            } else {
                Log.d(TAG, "\tbitmapToStylize was null! NULLLL");
            }

            Log.d(TAG, "\tstylizeAndDisplayBitmap called.");
        }
    }

    /**
     * Initialize a GPIO button to take the currently selected image and upload it to firebase.
     */
    private void initializeSecondaryButton() {
        // Hook up a button to upload styled image to Firebase.
        try {
            mSecondaryButton = new ButtonInputDriver(SECONDARY_BUTTON_GPIO_PIN,
                    Button.LogicState.PRESSED_WHEN_LOW, KeyEvent.KEYCODE_2);
        } catch (IOException e) {
            Log.e(TAG, "initializeSecondaryButton error", e);
        }
    }

    private static final int ORIG_LINK_INDEX = 0;
    private static final int STYLED_LINK_INDEX = 1;

    public synchronized boolean gatherLinks(Uri[] links, Uri link, int index) {
        Log.d(TAG, "Got short link for image " + index);
        links[index] = link;
        if (links[0] != null & links[1] != null) {
            Log.d(TAG, "Got all short links.");
            return true;
        }
        return false;
    }

    public void processChosenImage(boolean attendeeRequestingShare) {

        final Uri[] links = new Uri[2];

        runInBackground(() -> {
            if (mCurrSourceBitmap != null) {
                final Bitmap originalBitmap = mCurrSourceBitmap;
                final Bitmap styledBitmap = mCurrStyledBitmap;
                if (mCurrStyledBitmap != null) {
                    // we should use both source and style bitmap
                    FirebaseStorageAdapter.PhotoUploadedListener originalListener =
                            url -> {
                                Log.d(TAG, "Original uploaded successfully, printing shortcode: " +
                                        url);
                                if (gatherLinks(links, url, ORIG_LINK_INDEX)) {
                                    createAndPrintPhotoStrip(originalBitmap, styledBitmap,
                                            links[0].toString(), links[1].toString());
                                }
                            };

                    FirebaseStorageAdapter.PhotoUploadedListener styledListener =
                            url -> {
                                Log.d(TAG, "Styled uploaded successfully, printing shortcode: " +
                                        url);
                                if (gatherLinks(links, url, STYLED_LINK_INDEX)) {
                                    createAndPrintPhotoStrip(originalBitmap, styledBitmap,
                                            links[0].toString(), links[1].toString());
                                }
                            };

                    Log.d(TAG, "Uploading bitmaps");
                    mFirebaseAdapter.uploadBitmaps(originalBitmap, styledBitmap,
                            originalListener, styledListener, attendeeRequestingShare);
                } else {
                    // user requested for no style, so we should only use source bitmap
                    FirebaseStorageAdapter.PhotoUploadedListener uploadListener =
                            url -> {
                                Log.d(TAG, "ONLY original uploaded successfully, printing shortcode: " +
                                        url);
                                createAndPrintPhotoStrip(originalBitmap, styledBitmap,
                                        url.toString(), null);
                            };
                    mFirebaseAdapter.uploadBitmap(originalBitmap, "original", null,
                            uploadListener, attendeeRequestingShare);
                }
            } else {
                Log.d(TAG, "No bitmap to process.");
            }

            if (mCurrSourceBitmap != null) {
                mCurrSourceBitmap = null;
            }
            if (mCurrStyledBitmap != null) {
                mCurrStyledBitmap = null;
            }

            processingSecondaryButton = false;
        });

    }

    public void stylizeAndDisplayBitmap(final Bitmap sourceImage) {
        if (sourceImage == null) {
            return;
        }
        runInBackground(() -> {
            Bitmap stylizedImage = Bitmap.createBitmap(sourceImage);
            mTensorflowStyler.stylizeBitmap(stylizedImage);
            mTensorflowStyler.setNextStyle();
            Bitmap blended = ImageUtils.blendBitmaps(stylizedImage, sourceImage);
            mCurrStyledBitmap = blended;
            runOnUiThread(() -> {
                if (stylizedImage != null) {
                    ImageView snapshotView = (ImageView) findViewById(R.id.imageView);
                    if (snapshotView != null) {
                        snapshotView.setImageBitmap(blended);
                    }
                } else {
                    Log.d(TAG, "Bitmap sent to stylizeAndDisplayBitmap is null.");
                }
            });

            if (IMAGE_PREVIEW_DEBUG) {
                saveBitmapsForDebug(sourceImage, stylizedImage, blended);
            }
            // Allow for another image capture to take place.
            processingPrimaryButton = false;
        });
    }

    void saveBitmapsForDebug(Bitmap original, Bitmap stylized, Bitmap blended) {
        int style = mTensorflowStyler.getSelectedStyle();
        ImageUtils.saveBitmap(
                original, "preview-" + style + "-orig.png");
        ImageUtils.saveBitmap(
                stylized, "preview-" + style + "-styled.png");
        ImageUtils.saveBitmap(
                blended, "preview-" + style + "-blended.png");
    }

    protected void createAndPrintPhotoStrip(Bitmap original, Bitmap styled, String shortLink1, String shortLink2) {
        final PhotoStripSpec spec = new PhotoStripSpec(original, styled, shortLink1, shortLink2);
        if (USE_THERMAL_PRINTER) {
            mThermalPrinter.printQrCode(shortLink1, 200, shortLink1);
            if (shortLink2 != null) {
                mThermalPrinter.printQrCode(shortLink2, 200, shortLink2);
            }
        }
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                Bitmap bitmap = mPhotoStripBuilder.createPhotoStrip(spec);
                ImageUtils.saveBitmap(bitmap, "photostrip_debug.png");
                HttpImagePrint.print(bitmap);
                bitmap.recycle();
                return null;
            }
        }.execute();
    }

    /**
     * Starts a background thread and its {@link Handler}.
     */
    private void startInferenceThread() {
        inferenceThread = new HandlerThread("InferenceThread");
        inferenceThread.start();
        inferenceHandler = new Handler(inferenceThread.getLooper());
    }

    /**
     * Stops the background thread and its {@link Handler}.
     */
    private void stopInferenceThread() {
        inferenceThread.quitSafely();
        try {
            inferenceThread.join();
            inferenceThread = null;
        } catch (final InterruptedException e) {
            Log.e(TAG, "Exception!");
        }
    }

    @Override
    public synchronized void onPause() {
        Log.d(TAG, "onPause " + this);
        if (!isFinishing()) {
            Log.d(TAG, "Requesting finish");
            finish();
        }
        // Unregister since the activity is paused.
        LocalBroadcastManager.getInstance(this).unregisterReceiver(
                mMessageReceiver);
        stopInferenceThread();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Register to receive messages.
        // We are registering an observer (mMessageReceiver) to receive Intents
        // with actions named "custom-event-name".
        LocalBroadcastManager.getInstance(this).registerReceiver(
                mMessageReceiver, new IntentFilter(MESSAGE_RECEIVED));

        if (!DEBUG_IGNORE_MESSAGES) {
            FirebaseMessaging.getInstance().subscribeToTopic("io-photobooth");
        }

        startInferenceThread();
    }

    private BroadcastReceiver mMessageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Get extra data included in the Intent
            String message = intent.getStringExtra(FcmContract.KEY_FOR_COMMAND);
            Log.d("receiver", "Got message: " + message);
            switch (message) {
                case FcmContract.COMMAND_PREVIEW:
                    Log.d(TAG, "Previewing.");
                    cameraFragment.startPreview();
                    break;
                case FcmContract.COMMAND_CAPTURE:
                    Log.d("receiver", "Capturing.");
                    cameraFragment.stopPreview();
                    Bitmap snapshot = takeSnapshot();
                    showSnapshot(snapshot);
                    break;
                case FcmContract.COMMAND_STYLE:
                    stylizeAndDisplayBitmap(mCurrSourceBitmap);
                    break;
                case FcmContract.UPLOAD:
                    processChosenImage(false);
                    break;
                case FcmContract.UPLOAD_AND_SHARE:
                    processChosenImage(true);
                    break;
                case FcmContract.COMMAND_START_OVER:
                    Log.d(TAG, "Starting over, start");
                    mCurrSourceBitmap = null;
                    mCurrStyledBitmap = null;
                    cameraFragment.stopPreview();
                    ((ImageView) findViewById(R.id.imageView))
                            .setImageResource(android.R.color.transparent);
                    break;

            }
        }
    };

    protected synchronized void runInBackground(final Runnable r) {
        if (inferenceHandler != null) {
            inferenceHandler.post(r);
        }
    }

    private void initializeButtons() {
        initializeStyleButton();
        initializeSecondaryButton();
        Log.d(TAG, "Hardware buttons initialized.");
    }

    private void destroyButtons() {
        if (mStyleButton != null) {
            try {
                mStyleButton.close();
            } catch (IOException e) {
                Log.e(TAG, "Button driver error", e);
            }
        }

        if (mSecondaryButton != null) {
            try {
                mSecondaryButton.close();
            } catch (IOException e) {
                Log.e(TAG, "Button driver error", e);
            }
        }
    }

    @Override
    protected void onDestroy() {
        destroyButtons();
        if (mThermalPrinter != null) {
            mThermalPrinter.close();
            mThermalPrinter = null;
        }
        super.onDestroy();
    }

    @Override
    public void onStart() {
        super.onStart();
        mFirebaseAdapter.onStart();
    }

    @Override
    public void onStop() {
        super.onStop();
        mFirebaseAdapter.onStop();
    }
}
