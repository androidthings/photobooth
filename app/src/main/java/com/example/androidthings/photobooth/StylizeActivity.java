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
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.widget.ImageView;

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


    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mTensorflowStyler = new TensorflowStyler(this);
        mTensorflowStyler.initializeTensorFlow();
        initializeStyleButton();
    }


    boolean processingButton;
    protected void initializeStyleButton() {
        // Hook up a button to iterate through different styles and save image to style pane.
        setOnButtonPressedListener(
                (button, pressed) -> {
                    if (!processingButton) {
                        processingButton = true;
                        if(PREVIEW_DUMP_DEBUG) {
                            runInBackground(() -> {
                                Bitmap originalBitmap = getCameraFragment().getPreviewBitmap();
                                mTensorflowStyler.saveStyleExamples(originalBitmap);
                                processingButton = false;
                                return;
                            });
                        } else {
                            mTensorflowStyler.setStyle(mTensorflowStyler.getSelectedStyle() + 1);
                            Bitmap bitmapToStylize = getCameraFragment().getPreviewBitmap();
                            if (bitmapToStylize != null) {
                                Log.d(TAG, "Button pressed, calling stylize.");
                                updateStylizedView(bitmapToStylize);
                            } else {
                                Log.d(TAG, "bitmapToStylize was null! NULLLL");
                            }

                            Log.d(TAG, "Button pressed, updateStylizedView called.");
                        }
                        processingButton = false;
                    }
                }
        );
    }
    
    public void updateStylizedView(final Bitmap sourceImage) {
        runInBackground(() -> {
                    Bitmap stylizedImage = Bitmap.createBitmap(sourceImage);
                    mTensorflowStyler.stylizeBitmap(stylizedImage);
                    Bitmap blended = ImageUtils.blendBitmaps(stylizedImage, sourceImage);
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
                    processingButton = false;

                    onStylizedImageCaptureFinished(sourceImage, blended);
                }
        );
    }

    /**
     * Everything we want to do after the photo is taken should go here.
     */
    private void onStylizedImageCaptureFinished(Bitmap original, Bitmap stylized) {

        // Upload image to firebase.
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
}
