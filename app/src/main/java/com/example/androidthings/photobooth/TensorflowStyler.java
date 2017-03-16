package com.example.androidthings.photobooth;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import org.tensorflow.contrib.android.TensorFlowInferenceInterface;

public class TensorflowStyler {

    public static final String TAG = "TensorflowStyler";

    Context mContext;

    private static final int INPUT_SIZE = 256;

    private static final String MODEL_FILE = "file:///android_asset/stylize_quantized.pb";
    private static final String INPUT_NODE = "input";
    private static final String STYLE_NODE = "style_num";
    private static final String OUTPUT_NODE = "transformer/expand/conv3/conv/Sigmoid";
    private static final int NUM_STYLES = 26;

    private final float[] styleVals = new float[NUM_STYLES];
    private int[] intValues;
    private float[] floatValues;

    private TensorFlowInferenceInterface inferenceInterface;


    public TensorflowStyler(Context context) {
        mContext = context;
    }

    public void initializeTensorFlow() {
        inferenceInterface = new TensorFlowInferenceInterface();
        inferenceInterface.initializeTensorFlow(mContext.getAssets(), MODEL_FILE);

        intValues = new int[INPUT_SIZE * INPUT_SIZE];
        floatValues = new float[INPUT_SIZE * INPUT_SIZE * 3];
    }

    public void saveStyleExamples(Bitmap originalBitmap) {
        ImageUtils.saveBitmap(originalBitmap, "original.png");
        for (int i = 0; i < NUM_STYLES; i++) {
            setStyle(i);
            Bitmap styledBitmap = Bitmap.createBitmap(originalBitmap);
            stylizeBitmap(styledBitmap);
            Bitmap blended = ImageUtils.blendBitmaps(styledBitmap, originalBitmap);
            ImageUtils.saveBitmap(styledBitmap, "preview-" + i + "-styled.png");
            ImageUtils.saveBitmap(blended, "preview-" + i + "-blended.png");
        }
    }

    int mSelectedStyle = 0;

    public void setStyle(int selectedStyle) {
        mSelectedStyle = selectedStyle % NUM_STYLES;
        // Image style is normally selected as an array of intensities from multiple existing source
        // styles.  In this case we're only picking one, and scaling it down so the person
        // in the photograph is recognizable.
        for (int i = 0; i < NUM_STYLES; i++) {
            styleVals[i] = i == mSelectedStyle ? 1.00f : 0.0f;
        }
    }

    public int getSelectedStyle() {
        return mSelectedStyle;
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


}
