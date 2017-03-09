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
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.widget.ImageView;

import org.tensorflow.contrib.android.TensorFlowInferenceInterface;

/**
 * Sample activity that stylizes the camera preview according to "A Learned Representation For
 * Artistic Style" (https://arxiv.org/abs/1610.07629)
 */
public class StylizeActivity extends PhotoboothActivity implements TensorflowStyler {

    private static final int INPUT_SIZE = 256;

    public static final String TAG = "StylizeActivity";

    private static final String MODEL_FILE = "file:///android_asset/stylize_quantized.pb";
    private static final String INPUT_NODE = "input";
    private static final String STYLE_NODE = "style_num";
    private static final String OUTPUT_NODE = "transformer/expand/conv3/conv/Sigmoid";
    private static final int NUM_STYLES = 26;

    private final float[] styleVals = new float[NUM_STYLES];
    private int[] intValues;
    private float[] floatValues;

    private TensorFlowInferenceInterface inferenceInterface;

    // For testing the artistic styles, will save the original image, the "stylized" image,
    // and the blended combination of the two to device when this flag is set to true.
    private static final boolean IMAGE_PREVIEW_DEBUG = false;

    // For testing:  Just take one picture and apply all styles, saving images internally.
    private static final boolean PREVIEW_DUMP_DEBUG = false;

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
        initializeTensorFlow();
        initializeStyleButton();
    }

    public void initializeTensorFlow() {
        inferenceInterface = new TensorFlowInferenceInterface();
        inferenceInterface.initializeTensorFlow(getAssets(), MODEL_FILE);

        intValues = new int[INPUT_SIZE * INPUT_SIZE];
        floatValues = new float[INPUT_SIZE * INPUT_SIZE * 3];
    }

    boolean processingButton;
    protected void initializeStyleButton() {
        // Hook up a button to iterate through different styles and save image to style pane.
        setOnButtonPressedListener(
                (button, pressed) -> {
                    if (!processingButton) {
                        processingButton = true;
                        if(PREVIEW_DUMP_DEBUG) {
                            saveStyleExamples();
                            return;
                        } else {
                            setStyle(mSelectedStyle + 1);
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

    private void saveStyleExamples() {
        Bitmap originalBitmap = getCameraFragment().getPreviewBitmap();
        ImageUtils.saveBitmap(originalBitmap, "original.png");
        runInBackground(() -> {
            for (int i = 0; i < NUM_STYLES; i++) {
                setStyle(i);
                Bitmap styledBitmap = Bitmap.createBitmap(originalBitmap);
                stylizeBitmap(styledBitmap);
                Bitmap blended = blendBitmaps(styledBitmap, originalBitmap);
                ImageUtils.saveBitmap(styledBitmap, "preview-" + i + "-styled.png");
                ImageUtils.saveBitmap(blended, "preview-" + i + "-blended.png");
            }
        });
    }

    public void stylizeBitmap(final Bitmap bitmap) {

        bitmap.getPixels(intValues, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());

        for (int i = 0; i < intValues.length; ++i) {
            final int val = intValues[i];
            floatValues[i * 3] = ((val >> 16) & 0xFF) / 255.0f;
            floatValues[i * 3 + 1] = ((val >> 8) & 0xFF) / 255.0f;
            floatValues[i * 3 + 2] = (val & 0xFF) / 255.0f;
        }

        // Copy the input data into TensorFlow.
        inferenceInterface.fillNodeFloat(
                INPUT_NODE, new int[]{1, bitmap.getWidth(), bitmap.getHeight(), 3}, floatValues);
        inferenceInterface.fillNodeFloat(STYLE_NODE, new int[]{NUM_STYLES}, styleVals);

        inferenceInterface.runInference(new String[]{OUTPUT_NODE});
        inferenceInterface.readNodeFloat(OUTPUT_NODE, floatValues);

        for (int i = 0; i < intValues.length; ++i) {
            intValues[i] =
                    0xFF000000
                            | (((int) (floatValues[i * 3] * 255)) << 16)
                            | (((int) (floatValues[i * 3 + 1] * 255)) << 8)
                            | ((int) (floatValues[i * 3 + 2] * 255));
        }

        bitmap.setPixels(intValues, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        Log.d(TAG, "stylizeBitmap() completed.");
    }

    public void updateStylizedView(final Bitmap sourceImage) {
        runInBackground(() -> {
                    Bitmap stylizedImage = Bitmap.createBitmap(sourceImage);
                    stylizeBitmap(stylizedImage);
                    Bitmap blended = blendBitmaps(stylizedImage, sourceImage);
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
                        ImageUtils.saveBitmap(
                                sourceImage, "preview-" + mSelectedStyle + "-orig.png");
                        ImageUtils.saveBitmap(
                                stylizedImage, "preview-" + mSelectedStyle + "-styled.png");
                        ImageUtils.saveBitmap(
                                blended, "preview-" + mSelectedStyle + "-blended.png");
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

    public Bitmap blendBitmaps(Bitmap styled, Bitmap original) {
        Bitmap blended = Bitmap.createBitmap(styled.getWidth(), styled.getHeight(), styled.getConfig());
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        paint.setAlpha(128);
        Canvas canvas = new Canvas(blended);
        canvas.drawBitmap(styled, new Matrix(), null);
        canvas.drawBitmap(original, new Matrix(), paint);
        return blended;
    }


    int mSelectedStyle = 0;
    private void setStyle(int selectedStyle) {
        mSelectedStyle = selectedStyle % NUM_STYLES;
        // Image style is normally selected as an array of intensities from multiple existing source
        // styles.  In this case we're only picking one, and scaling it down so the person
        // in the photograph is recognizable.
        for (int i = 0; i < NUM_STYLES; i++) {
            styleVals[i] = i == mSelectedStyle ? 1.00f : 0.0f;
        }
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
