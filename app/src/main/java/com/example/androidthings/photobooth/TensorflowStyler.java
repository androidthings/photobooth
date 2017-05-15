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

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import org.tensorflow.contrib.android.TensorFlowInferenceInterface;

import java.sql.Date;
import java.util.Calendar;
import java.util.Random;

/**
 * Stylizes the camera preview according to "A Learned Representation For Artistic Style"
 * (https://arxiv.org/abs/1610.07629)
 */
public class TensorflowStyler {

    public static final String TAG = "TensorflowStyler";

    Context mContext;

    private static final int INPUT_SIZE = 480;

    private static final String MODEL_FILE = "file:///android_asset/stylize_quantized.pb";
    private static final String INPUT_NODE = "input";
    private static final String STYLE_NODE = "style_num";
    private static final String OUTPUT_NODE = "transformer/expand/conv3/conv/Sigmoid";
    private static final int NUM_RAW_STYLES = 26;

    private final float[] styleVals = new float[NUM_RAW_STYLES];
    private int[] intValues;
    private float[] floatValues;

    private TensorFlowInferenceInterface inferenceInterface;

    // Of the group of stylizations being used, only a subset are good for portraits.
    // This is the subset of styles we should actually use.
    private final int[] PORTRAIT_STYLE_INDEXES = {2,4,5,7,8,10,12,14,19};

    public TensorflowStyler(Context context) {
        mContext = context;
    }

    public void initializeTensorFlow() {
        inferenceInterface = new TensorFlowInferenceInterface();
        inferenceInterface.initializeTensorFlow(mContext.getAssets(), MODEL_FILE);

        intValues = new int[INPUT_SIZE * INPUT_SIZE];
        floatValues = new float[INPUT_SIZE * INPUT_SIZE * 3];

        setNextStyle();
    }

    public void saveStyleExamples(Bitmap originalBitmap) {
        ImageUtils.saveBitmap(originalBitmap, "original.png");
        for (int i = 0; i < NUM_RAW_STYLES; i++) {
            setStyle(i);
            Bitmap styledBitmap = Bitmap.createBitmap(originalBitmap);
            stylizeBitmap(styledBitmap);
            Bitmap blended = ImageUtils.blendBitmaps(styledBitmap, originalBitmap);
            ImageUtils.saveBitmap(styledBitmap, "preview-" + i + "-styled.png");
            ImageUtils.saveBitmap(blended, "preview-" + i + "-blended.png");
        }
    }

    int mSelectedStyleIndex = 0;

    public void setStyle(int selectedStyle) {
        mSelectedStyleIndex = selectedStyle;
        int portraitStyle = PORTRAIT_STYLE_INDEXES[mSelectedStyleIndex];
        // Image style is normally selected as an array of intensities from multiple existing source
        // styles.  In this case we're only picking one, and scaling it down so the person
        // in the photograph is recognizable.
        for (int i = 0; i < NUM_RAW_STYLES; i++) {
            styleVals[i] = i == portraitStyle ? 1.00f : 0.0f;
        }
    }

    public void setNextStyle() {
        Random random = new Random(Calendar.getInstance().getTimeInMillis());
        int newStyleIndex = random.nextInt(PORTRAIT_STYLE_INDEXES.length);
        setStyle(newStyleIndex);
    }

    public int getSelectedStyle() {
        return mSelectedStyleIndex;
    }

    public void stylizeBitmap(final Bitmap bitmap) {
        Log.d(TAG, "Applying style: " + mSelectedStyleIndex);

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
        inferenceInterface.fillNodeFloat(STYLE_NODE, new int[]{NUM_RAW_STYLES}, styleVals);

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
}
