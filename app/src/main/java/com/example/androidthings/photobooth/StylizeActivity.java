/*
 * Copyright 2017 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.androidthings.photobooth;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.util.Log;
import android.widget.ImageView;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.android.things.contrib.driver.button.Button;
import com.google.firebase.storage.UploadTask;

import java.io.IOException;

/**
 * Sample activity that stylizes the camera preview according to "A Learned Representation For
 * Artistic Style" (https://arxiv.org/abs/1610.07629)
 */
public class StylizeActivity extends PhotoboothActivity {

    public static final String TAG = "StylizeActivity";

    // For testing the artistic styles, will save the original image, the "stylized" image,
    // and the blended combination of the two to device when this flag is set to true.
    public static final boolean IMAGE_PREVIEW_DEBUG = false;

    // For testing:  Just take one picture and apply all styles, saving images internally.
    public static final boolean PREVIEW_DUMP_DEBUG = false;

    TensorflowStyler mTensorflowStyler;

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
    private Button mStyleButton;
    private Button mSecondaryButton;


    private final String PRIMARY_BUTTON_GPIO_PIN = "BCM23";
    private final String SECONDARY_BUTTON_GPIO_PIN = "BCM24";

    private Button.OnButtonEventListener mStyleButtonCallback;
    private Button.OnButtonEventListener mSecondaryButtonCallback;

    private FirebaseStorageAdapter mFirebaseAdapter;

    private Bitmap currStyledBitmap;

    // Depending on the button and how "noisy" the signal is, one physical button press can show
    // up as two.  These locks are simple objects to be used for locking using "synchronized".
    final int[] primaryButtonLock = new int[0];
    final int[] secondaryButtonLock = new int[0];


    boolean processingPrimaryButton = false;
    boolean processingSecondaryButton = false;

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mTensorflowStyler = new TensorflowStyler(this);
        mTensorflowStyler.initializeTensorFlow();

        initializeButtons();

        mFirebaseAdapter = new FirebaseStorageAdapter();
    }

    /**
     *  Initialize a GPIO button button to iterate through different styles and display image on
     *  screen.
     */
    protected void initializeStyleButton() {
        try {
            mStyleButton = new Button(PRIMARY_BUTTON_GPIO_PIN,
                    Button.LogicState.PRESSED_WHEN_LOW);
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (mStyleButton != null) {
            mStyleButtonCallback = (button, pressed) -> {

                if (!pressed) {
                    return;
                }

                // Tensorflow takes a while on these images, and we don't want an itchy trigger-finger
                // (or trigger-voice, or whatever) locking up the UI with too many requests.
                // If one image is being worked on, ignore requests to process others.
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
                        Bitmap originalBitmap = getCameraFragment().getPreviewBitmap();
                        mTensorflowStyler.saveStyleExamples(originalBitmap);
                        processingPrimaryButton = false;
                        return;
                    });
                } else {
                    mTensorflowStyler.setStyle(mTensorflowStyler.getSelectedStyle() + 1);
                    Bitmap bitmapToStylize = getCameraFragment().getPreviewBitmap();
                    if (bitmapToStylize != null) {
                        Log.d(TAG, "\tcalling stylize.");
                        updateStylizedView(bitmapToStylize);
                    } else {
                        Log.d(TAG, "\tbitmapToStylize was null! NULLLL");
                    }

                    Log.d(TAG, "\tupdateStylizedView called.");
                }
            };
            mStyleButton.setOnButtonEventListener(mStyleButtonCallback);
        }
    }

    /**
     * Initialize a GPIO button to take the currently selected image and upload it to firebase.
     */
    private void initializeSecondaryButton() {
        // Hook up a button to upload styled image to Firebase.
        try {
            mSecondaryButton = new Button(SECONDARY_BUTTON_GPIO_PIN,
                    Button.LogicState.PRESSED_WHEN_LOW);
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (mSecondaryButton != null) {
            mSecondaryButtonCallback = (button, pressed) -> {
                if (!pressed) {
                    return;
                }
                // Locking mechanism until setDebounceDelay bugs are sorted out.
                Log.d(TAG, "Secondary button pressed.");
                synchronized (secondaryButtonLock) {
                    if (processingSecondaryButton) {
                        Log.d(TAG, "Secondary button press registered, locked out.");
                        return;
                    } else {
                        Log.d(TAG, "Secondary button press registered, entering.");
                        processingSecondaryButton = true;
                    }
                }

                if (currStyledBitmap != null) {
                    runInBackground(() -> {
                        OnCompleteListener listener = new OnCompleteListener<UploadTask.TaskSnapshot>() {
                            @Override
                            public void onComplete(@NonNull Task<UploadTask.TaskSnapshot> task) {
                                Uri downloadUri = task.getResult().getDownloadUrl();
                                if (task.isSuccessful()) {
                                    if (downloadUri != null) {
                                        Log.d(TAG, "File uploaded!  Download at: " + downloadUri);
                                    } else {
                                        Log.d(TAG, "File was not successfully uploaded.");
                                    }
                                }
                            }
                        };

                        mFirebaseAdapter.uploadBitmap(currStyledBitmap, "styled", null)
                            .addOnCompleteListener(listener);
                        processingSecondaryButton = false;
                    });

                } else {
                    Log.d(TAG, "No bitmap to process.");
                    processingSecondaryButton = false;
                }
            };
            mSecondaryButton.setOnButtonEventListener(mSecondaryButtonCallback);
        }
    }

    public void updateStylizedView(final Bitmap sourceImage) {
        runInBackground(() -> {
            Bitmap stylizedImage = Bitmap.createBitmap(sourceImage);
            mTensorflowStyler.stylizeBitmap(stylizedImage);
            Bitmap blended = ImageUtils.blendBitmaps(stylizedImage, sourceImage);
            currStyledBitmap = blended;
            runOnUiThread(() -> {
                if (stylizedImage != null) {
                    ImageView stylizedView =
                            (ImageView) findViewById(R.id.stylizedView);
                    if (stylizedView != null) {

                        stylizedView.setImageBitmap(blended);
                        Log.d(TAG, "stylizedView updated.");
                    } else {
                        Log.d(TAG, "stylizedView is null.");
                    }
                } else {
                    Log.d(TAG, "Bitmap sent to updateStylizedView is null.");
                }
            });

            if (IMAGE_PREVIEW_DEBUG) {
                int style = mTensorflowStyler.getSelectedStyle();
                ImageUtils.saveBitmap(
                        sourceImage, "preview-" + style + "-orig.png");
                ImageUtils.saveBitmap(
                        stylizedImage, "preview-" + style + "-styled.png");
                ImageUtils.saveBitmap(
                        blended, "preview-" + style + "-blended.png");
            }
            // Allow for another image capture to take place.
            processingPrimaryButton = false;
        });
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
        stopInferenceThread();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startInferenceThread();
    }

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
        super.onDestroy();
        destroyButtons();
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
