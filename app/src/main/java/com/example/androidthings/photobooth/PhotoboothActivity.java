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

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Entry point for the Photobooth application.
 */
public class PhotoboothActivity extends Activity {

    private static final String TAG = "PhotoboothActivity";

    public static final String MESSAGE_RECEIVED = "MESSAGE_RECEIVED";

    private static final String PRIMARY_BUTTON_GPIO_PIN = "BCM23";
    private static final String SECONDARY_BUTTON_GPIO_PIN = "BCM24";

    /**
     * When set to true, printing and remote message handling (FCM) will be disabled.
     */
    private static final boolean DEBUG_DRYRUN = false;

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

    private FirebaseStorageAdapter mFirebaseAdapter;

    private Bitmap mCurrSourceBitmap;
    private Bitmap mCurrStyledBitmap;

    private AtomicBoolean mProcessing = new AtomicBoolean(false);

    private PhotoStripBuilder mPhotoStripBuilder;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_camera);

        loadCameraFragment();

        if (USE_THERMAL_PRINTER) {
            mThermalPrinter = new ThermalPrinter(this);
        }

        startInferenceThread();

        runInBackground(
                () -> {
                    mTensorflowStyler = new TensorflowStyler(this);
                    mTensorflowStyler.initializeTensorFlow();
                    mFirebaseAdapter = new FirebaseStorageAdapter();
                    initializeButtons();

                    mFirebaseAdapter.onStart();
                    // Register to receive messages.
                    // We are registering an observer (mMessageReceiver) to receive Intents
                    // with actions named "custom-event-name".
                    LocalBroadcastManager.getInstance(this).registerReceiver(
                            mMessageReceiver, new IntentFilter(MESSAGE_RECEIVED));

                    if (!DEBUG_DRYRUN) {
                        FirebaseMessaging.getInstance().subscribeToTopic("io-photobooth");
                    }

                }
        );

        mPhotoStripBuilder = new PhotoStripBuilder(this);
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
    protected void initializeButtons() {
        try {
            mStyleButton = new ButtonInputDriver(PRIMARY_BUTTON_GPIO_PIN,
                    Button.LogicState.PRESSED_WHEN_LOW, KeyEvent.KEYCODE_1);
            mSecondaryButton = new ButtonInputDriver(SECONDARY_BUTTON_GPIO_PIN,
                    Button.LogicState.PRESSED_WHEN_LOW, KeyEvent.KEYCODE_2);
        } catch (IOException e) {
            Log.e(TAG, "Could not initialize buttons. Use keyboard events instead", e);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_1:
                if (!mProcessing.compareAndSet(false, true)) {
                    Log.i(TAG, "Ignoring button press, still processing.");
                    return true;
                }
                Log.d(TAG, "Primary button pressed.");
                stylizePicture();
                return true;
            case KeyEvent.KEYCODE_2:
                if (!mProcessing.compareAndSet(false, true)) {
                    Log.i(TAG, "Ignoring button press, still processing.");
                    return true;
                }
                Log.d(TAG, "Secondary button pressed.");
                processChosenImage(false);
                return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    public void takeSnapshot() {
        mCurrSourceBitmap = getCameraFragment().getCurrentFrameCopy();
    }

    public void showSnapshot() {
        ImageView snapshotView = (ImageView) findViewById(R.id.imageView);
        snapshotView.setVisibility(View.VISIBLE);
        snapshotView.setImageBitmap(mCurrSourceBitmap);
    }

    protected void stylizePicture() {
        if (PREVIEW_DUMP_DEBUG) {
            runInBackground(() -> {
                takeSnapshot();
                mTensorflowStyler.saveStyleExamples(mCurrSourceBitmap);
                mProcessing.set(false);
            });
        } else {
            cameraFragment.stopPreview();
            takeSnapshot();
            showSnapshot();

            if (mCurrSourceBitmap != null) {
                Log.d(TAG, "\tcalling stylize.");
                stylizeAndDisplayBitmap(mCurrSourceBitmap);
            } else {
                Log.d(TAG, "\tbitmapToStylize was null! NULLLL");
            }

            Log.d(TAG, "\tstylizeAndDisplayBitmap called.");
        }
    }

    public void processChosenImage(boolean attendeeRequestingShare) {
        runInBackground(() -> {
            if (mCurrSourceBitmap == null) {
                Log.d(TAG, "No bitmap to process.");
                mProcessing.set(false);
                return;
            }

            final Bitmap originalBitmap = mCurrSourceBitmap;
            final Bitmap styledBitmap = mCurrStyledBitmap;
            final String[] links = new String[2];
            final CountDownLatch latchLocker = new CountDownLatch(styledBitmap == null ? 1 : 2);
            FirebaseStorageAdapter.PhotoUploadedListener originalListener =
                    url -> {
                        Log.d(TAG, "Original uploaded successfully, shorturl= " + url);
                        links[0] = url.toString();
                        latchLocker.countDown();
                    };

            FirebaseStorageAdapter.PhotoUploadedListener styledListener =
                    url -> {
                        Log.d(TAG, "Original uploaded successfully, shorturl= " + url);
                        links[1] = url.toString();
                        latchLocker.countDown();
                    };

            Log.d(TAG, "Uploading bitmaps");
            mFirebaseAdapter.uploadBitmaps(originalBitmap, styledBitmap,
                    originalListener, styledListener, attendeeRequestingShare);

            try {
                if (!latchLocker.await(10, TimeUnit.SECONDS)) {
                    Log.w(TAG, "Timeout while waiting for short URLs, will proceed anyway");
                }
            } catch (InterruptedException e) {
                Log.e(TAG, "Interrupted while waiting for short URLs, this should not happen");
            }

            createAndPrintPhotoStrip(originalBitmap, styledBitmap, links[0], links[1], true);

            mCurrSourceBitmap = null;
            mCurrStyledBitmap = null;

            mProcessing.set(false);
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
            mProcessing.set(false);
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

    protected void createAndPrintPhotoStrip(Bitmap original, Bitmap styled, String shortLink1,
                                            String shortLink2, boolean recycleBitmaps) {
        final PhotoStripSpec spec = new PhotoStripSpec(original, styled, shortLink1, shortLink2);
        if (USE_THERMAL_PRINTER) {
            mThermalPrinter.printQrCode(shortLink1, 200, shortLink1);
            if (shortLink2 != null) {
                mThermalPrinter.printQrCode(shortLink2, 200, shortLink2);
            }
        }
        runInBackground(
                () -> {
                    Bitmap bitmap = mPhotoStripBuilder.createPhotoStrip(spec);
                    ImageUtils.saveBitmap(bitmap, "photostrip_debug.png");
                    HttpImagePrint.print(bitmap);
                    bitmap.recycle();
                    if (recycleBitmaps) {
                        if (original != null) {
                            original.recycle();
                        }
                        if (styled != null) {
                            styled.recycle();
                        }
                    }
                }
        );
    }

    /**
     * Starts a background thread and its {@link Handler}.
     */
    private void startInferenceThread() {
        inferenceThread = new HandlerThread("InferenceThread");
        inferenceThread.start();
        inferenceThread.setUncaughtExceptionHandler(
                (thread, throwable) -> {
                    Log.e(TAG, "Background thread exception, recreating activity", throwable);
                    runOnUiThread(PhotoboothActivity.this::recreate);
            });
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
                    takeSnapshot();
                    showSnapshot();
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
                    startOver();
                    break;

            }
        }
    };

    private void startOver() {
        Log.d(TAG, "Starting over, start");
        if (mCurrSourceBitmap != null && !mCurrSourceBitmap.isRecycled()) {
            mCurrSourceBitmap.recycle();
        }
        mCurrSourceBitmap = null;
        if (mCurrStyledBitmap != null && !mCurrStyledBitmap.isRecycled()) {
            mCurrStyledBitmap.recycle();
        }
        mCurrStyledBitmap = null;
        cameraFragment.stopPreview();
        ((ImageView) findViewById(R.id.imageView))
                .setImageResource(android.R.color.transparent);
    }

    protected void runInBackground(final Runnable r) {
        if (inferenceHandler != null) {
            inferenceHandler.post(r);
        }
    }

    private void destroyButtons() {
        try {
            if (mStyleButton != null) mStyleButton.close();
        } catch (IOException e) {
            Log.e(TAG, "Button driver error", e);
        }
        try {
            if (mSecondaryButton != null) mSecondaryButton.close();
        } catch (IOException e) {
            Log.e(TAG, "Button driver error", e);
        }
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy");

        // Unregister since the activity is paused.
        LocalBroadcastManager.getInstance(this).unregisterReceiver(
                mMessageReceiver);
        stopInferenceThread();

        mFirebaseAdapter.onStop();

        destroyButtons();
        if (mThermalPrinter != null) {
            mThermalPrinter.close();
            mThermalPrinter = null;
        }
        super.onDestroy();
    }
}
