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
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.Log;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.HashMap;
import java.util.Map;

public class PhotoStripBuilder {

    private static final String TAG = "PhotoStripBuilder";
    private static final int MARGIN = 16;

    private static final int WIDTH = 480 + MARGIN * 2;
    private static final int WIDTH_HALF = WIDTH / 2;

    private Drawable mIoLogo;

    public PhotoStripBuilder(Context context) {
        mIoLogo = context.getDrawable(R.drawable.ic_googleio17);
    }

    private static Bitmap createQrCode(String data, int size) {
        if (data == null) {
            return null;
        }
        Map<EncodeHintType, ErrorCorrectionLevel> hintMap = new HashMap<>();
        hintMap.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H); // H = 30% damage

        QRCodeWriter writer = new QRCodeWriter();
        BitMatrix bitMatrix;
        try {
            bitMatrix = writer.encode(data, BarcodeFormat.QR_CODE, size, size, hintMap);
        } catch (WriterException e) {
            Log.e(TAG, "createQrCode failed", e);
            return null;
        }
        int width = bitMatrix.getWidth();
        int height = bitMatrix.getHeight();
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            int offset = y * width;
            for (int x = 0; x < width; x++) {
                pixels[offset + x] = bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE;
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Config.RGB_565);
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }

    public Bitmap createPhotoStrip(PhotoStripSpec spec) {
        // This is the photo strip
        Bitmap out = Bitmap.createBitmap(WIDTH * 2, WIDTH * 3, Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint();
        paint.setFlags(paint.getFlags() | Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setColor(0xff212121); // 87% black
        paint.setTextAlign(Align.LEFT);
        canvas.drawColor(Color.WHITE); // TODO: only needed for preview

        // draw the strip
        int saveCount = canvas.save();
        drawSpec(spec, canvas, paint);
        canvas.restoreToCount(saveCount);

        // draw the strip again
        saveCount = canvas.save();
        canvas.translate(WIDTH, 0);
        drawSpec(spec, canvas, paint);
        canvas.restoreToCount(saveCount);

        return out;
    }

    private void drawSpec(PhotoStripSpec spec, Canvas canvas, Paint paint) {
        final int halfMargin = MARGIN / 2;

        // Starting from the top: draw the two images, then the logo
        canvas.save();

        final int imageLeftHorizMargin = MARGIN * 3;
        final int imageRightHorizMargin = MARGIN * 2;
        canvas.drawBitmap(spec.mOriginalImage,
                null,
                new Rect(imageLeftHorizMargin, MARGIN * 3, WIDTH - imageRightHorizMargin, WIDTH - MARGIN * 3),
                paint);
        canvas.translate(0, WIDTH);
        canvas.drawBitmap(
                spec.mStylizedImage == null ? spec.mOriginalImage : spec.mStylizedImage,
                null,
                new Rect(imageLeftHorizMargin, MARGIN, WIDTH - imageRightHorizMargin, WIDTH - MARGIN * 4),
                paint);

        canvas.translate(MARGIN * 2, WIDTH + MARGIN);
        int logoWidth = WIDTH - (MARGIN * 4);

        float gLogoScale = logoWidth / (float) mIoLogo.getIntrinsicWidth();
        int gLogoHeight = (int) (gLogoScale * mIoLogo.getIntrinsicHeight() + 0.5f);
        mIoLogo.setBounds(0, 0, logoWidth, gLogoHeight);
        mIoLogo.draw(canvas);

        canvas.restore();

        // From the bottom: draw the two QR codes, then the text
        canvas.translate(0, WIDTH * 2);

        if (spec.mOriginalQrImage != null) {
            canvas.save();
            canvas.translate(0, WIDTH_HALF);
            canvas.drawBitmap(spec.mOriginalQrImage, 0, 0, paint);
            canvas.restore();
        }
        if (spec.mStylizedQrImage != null) {
            canvas.save();
            canvas.translate(WIDTH_HALF, WIDTH_HALF);
            canvas.drawBitmap(spec.mStylizedQrImage, 0, 0, paint);
            canvas.restore();
        }

        float textY = WIDTH_HALF;
        paint.setTextSize(26);
        paint.setTypeface(Typeface.MONOSPACE);
        if (!TextUtils.isEmpty(spec.mOriginalQrLink)) {
            String text = spec.mOriginalQrLink;
            if (text.startsWith("https://")) {
                text = text.substring(8);
            }
            canvas.drawText(text, MARGIN * 2, textY, paint);
        }
        canvas.save();
        canvas.translate(WIDTH_HALF, 0);

        if (!TextUtils.isEmpty(spec.mStylizedQrLink)) {
            String text = spec.mStylizedQrLink;
            if (text.startsWith("https://")) {
                text = text.substring(8);
            }
            canvas.drawText(text, MARGIN, textY, paint);
        }
        canvas.restore();
    }

    public static class PhotoStripSpec {
        final Bitmap mOriginalImage;
        final Bitmap mStylizedImage;

        final String mOriginalQrLink;
        final String mStylizedQrLink;
        final Bitmap mOriginalQrImage;
        final Bitmap mStylizedQrImage;

        PhotoStripSpec(Bitmap originalImage, Bitmap stylizedImage, String originalQrLink,
                       String stylizedQrLink) {
            mOriginalImage = originalImage;
            mStylizedImage = stylizedImage;
            mOriginalQrLink = originalQrLink;
            mStylizedQrLink = stylizedQrLink;

            int size = mOriginalImage.getWidth() / 2;
            mOriginalQrImage = createQrCode(mOriginalQrLink, size);
            mStylizedQrImage = createQrCode(mStylizedQrLink, size);
        }
    }
}
