package com.example.androidthings.photobooth;

import android.graphics.Bitmap;

public interface TensorflowStyler {
    public void stylizeBitmap (final Bitmap bitmap);
    public void updateStylizedView(final Bitmap bitmap);

}
