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
import android.graphics.Color;
import android.util.Log;

import com.google.android.things.pio.PeripheralManagerService;
import com.google.android.things.pio.UartDevice;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Hashtable;
import java.util.List;

public class ThermalPrinter {

    private static final byte[] PRINTER_SELECT_BIT_IMAGE_MODE = {0x1B, 0x2A, 33};
    private final String TAG = "ThermalPrinter";
    // Pulled from calling PeripheralManagerService.getUartDeviceList().
    private final String UART_DEVICE_NAME = "USB1-1.2";

    private final byte[] PRINTER_INITIALIZE = {0x1B, 0x40};
    private final byte ESC_CHAR = 0x1B;
    private final byte[] PRINTER_SET_LINE_SPACE_24 = new byte[]{ESC_CHAR, 0x33, 24};
    // Slowing down the printer a little and increasing dot density, in order to make the QR
    // codes darker (they're a little faded at default settings).
    // Bytes represent the following: (first two): Print settings.
    // Max heating dots: Units of 8 dots.  11 means 88 dots.
    // Heating time: Units of 10 uS.  120 means 1.2 milliseconds.
    // Heating interval: Units of 10 uS. 50 means 0.5 milliseconds.
    private final byte[] PRINTER_DARKER_PRINTING = {0x1B, 0x37, 11, 0x7F, 50};
    private final byte[] PRINTER_PRINT_AND_FEED = {0x1B, 0x64};
    private final byte BYTE_LF = 0xA;
    private UartDevice mDevice;

    // Config settings for Ada 597 thermal printer.
    ThermalPrinter(Context c) {
        PeripheralManagerService manager = new PeripheralManagerService();
        try {
            List<String> devices = manager.getUartDeviceList();
            if (devices.contains(UART_DEVICE_NAME)) {
                Log.d(TAG, "Connecting to thermal printer at " + UART_DEVICE_NAME);
                mDevice = manager.openUartDevice(UART_DEVICE_NAME);
                configureUartFrame(mDevice);
                configurePrinter();
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void configurePrinter() {
        ByteArrayOutputStream config = getOutputStream();
        writeToPrinterBuffer(config, PRINTER_INITIALIZE);
        writeToPrinterBuffer(config, PRINTER_DARKER_PRINTING);
        print(config);
    }

    private void writeToPrinterBuffer(ByteArrayOutputStream printerBuffer, byte[] command) {
        try {
            printerBuffer.write(command);
        } catch (IOException e) {
            Log.d(TAG, "IO Exception while writing printer data to buffer.", e);
        }
    }

    private void addLineFeed(ByteArrayOutputStream printerBuffer, int numLines) {
        try {
            if (numLines <= 1) {
                printerBuffer.write(BYTE_LF);
            } else {
                printerBuffer.write(PRINTER_PRINT_AND_FEED);
                printerBuffer.write(numLines);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void print(ByteArrayOutputStream output) {
        try {
            writeUartData(output.toByteArray());
        } catch (IOException e) {
            Log.d(TAG, "IO Exception while printing.", e);
        }
    }

    // Specific settings for the Ada 597 thermal printer.
    private void configureUartFrame(UartDevice uart) throws IOException {
        // Configure the UART port
        uart.setBaudrate(19200);
        uart.setDataSize(8);
        uart.setParity(UartDevice.PARITY_NONE);
        uart.setStopBits(1);
    }

    void printImage(Bitmap bitmap) {
        if (mDevice == null) {
            return;
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        final byte[] controlByte = {(byte) (0x00ff & width), (byte) ((0xff00 & width) >> 8)};
        int[] pixels = new int[width * height];

        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        final int BAND_HEIGHT = 24;

        // Bands of pixels are sent that are 8 pixels high.  Iterate through bitmap
        // 24 rows of pixels at a time, capturing bytes representing vertical slices 1 pixel wide.
        // Each bit indicates if the pixel at that position in the slice should be dark or not.
        for (int row = 0; row < height; row += BAND_HEIGHT) {
            ByteArrayOutputStream imageData = getOutputStream();

            writeToPrinterBuffer(imageData, PRINTER_SET_LINE_SPACE_24);

            // Need to send these two sets of bytes at the beginning of each row.
            writeToPrinterBuffer(imageData, PRINTER_SELECT_BIT_IMAGE_MODE);
            writeToPrinterBuffer(imageData, controlByte);

            // Columns, unlike rows, are one at a time.
            for (int col = 0; col < width; col++) {

                byte[] bandBytes = {0x0, 0x0, 0x0};

                // Ugh, the nesting of forloops.  For each starting row/col position, evaluate
                // each pixel in a column, or "band", 24 pixels high.  Convert into 3 bytes.
                for (int rowOffset = 0; rowOffset < 8; rowOffset++) {

                    // Because the printer only maintains correct height/width ratio
                    // at the highest density, where it takes 24 bit-deep slices, process
                    // a 24-bit-deep slice as 3 bytes.
                    int[] pixelSlice = new int[3];
                    int pixel2Row = row + rowOffset + 8;
                    int pixel3Row = row + rowOffset + 16;

                    // If we go past the bottom of the image, just send white pixels so the printer
                    // doesn't do anything.  Everything still needs to be sent in sets of 3 rows.
                    pixelSlice[0] = bitmap.getPixel(col, row + rowOffset);
                    pixelSlice[1] = (pixel2Row >= bitmap.getHeight()) ?
                            Color.WHITE : bitmap.getPixel(col, pixel2Row);
                    pixelSlice[2] = (pixel3Row >= bitmap.getHeight()) ?
                            Color.WHITE : bitmap.getPixel(col, pixel3Row);

                    boolean[] isDark = {pixelSlice[0] == Color.BLACK,
                            pixelSlice[1] == Color.BLACK,
                            pixelSlice[2] == Color.BLACK};

                    // Towing that fine line between "should I forloop or not".  This will only
                    // ever be 3 elements deep.
                    if (isDark[0]) bandBytes[0] |= 1 << (7 - rowOffset);
                    if (isDark[1]) bandBytes[1] |= 1 << (7 - rowOffset);
                    if (isDark[2]) bandBytes[2] |= 1 << (7 - rowOffset);
                }
                writeToPrinterBuffer(imageData, bandBytes);
            }
            addLineFeed(imageData, 1);
            print(imageData);
        }
    }

    void printEmptyLines(int lines) {
        if (mDevice == null) {
            return;
        }
        ByteArrayOutputStream printerBuffer = getOutputStream();
        addLineFeed(printerBuffer, lines);
        print(printerBuffer);
    }

    void printLn(String text) {
        if (mDevice == null) {
            return;
        }
        // The EscPosBuilder will take our formatted text and convert it to a byte array
        // understood as instructions and data by the printer.
        ByteArrayOutputStream printerBuffer = getOutputStream();
        writeToPrinterBuffer(printerBuffer, text.getBytes());
        addLineFeed(printerBuffer, 1);
        print(printerBuffer);
    }

    private synchronized void writeUartData(byte[] data) throws IOException {
        if (mDevice == null) {
            return;
        }

        // If printer isn't initialized, abort.
        if (mDevice == null) {
            return;
        }

        // In the case of writing images, let's assume we shouldn't send more than 400 bytes
        // at a time to avoid buffer overrun - At which point the thermal printer tends to
        // either lock up or print garbage.
        final int DEFAULT_CHUNK_SIZE = 400;

        byte[] chunk = new byte[DEFAULT_CHUNK_SIZE];
        ByteBuffer byteBuffer = ByteBuffer.wrap(data);
        while (byteBuffer.remaining() > DEFAULT_CHUNK_SIZE) {
            byteBuffer.get(chunk);
            mDevice.write(chunk, chunk.length);
            try {
                this.wait(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        if (byteBuffer.hasRemaining()) {
            byte[] lastChunk = new byte[byteBuffer.remaining()];
            byteBuffer.get(lastChunk);
            mDevice.write(lastChunk, lastChunk.length);
        }
    }

    void close() {
        if (mDevice != null) {
            try {
                mDevice.close();
                mDevice = null;
            } catch (IOException e) {
                Log.w(TAG, "Unable to close UART device", e);
            }
        }
    }

    private ByteArrayOutputStream getOutputStream() {
        return new ByteArrayOutputStream();
    }

    public void printQrCode(String data, int size, String label) {
        if (mDevice == null) {
            return;
        }

        Bitmap qrBitmap;
        try {
            printLn("Here's your photo!");
            printEmptyLines(1);
            qrBitmap = generateQrCode(data, size);

            Log.d(TAG, "Width: " + qrBitmap.getWidth() + ", Height: " + qrBitmap.getHeight());

            printImage(qrBitmap);
            if (label != null && !label.isEmpty()) {
                printLn(label);
                printEmptyLines(3);
            }
        } catch (WriterException e) {
            Log.d(TAG, "Exception: ", e);
        }
    }

    public Bitmap generateQrCode(String myCodeText, int size) throws WriterException {
        Hashtable<EncodeHintType, ErrorCorrectionLevel> hintMap = new Hashtable<EncodeHintType, ErrorCorrectionLevel>();
        hintMap.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H); // H = 30% damage

        QRCodeWriter qrCodeWriter = new QRCodeWriter();

        BitMatrix bitMatrix = qrCodeWriter.encode(myCodeText, BarcodeFormat.QR_CODE, size, size,
                hintMap);
        int width = bitMatrix.getWidth();

        Bitmap bmp = Bitmap.createBitmap(width, width, Bitmap.Config.RGB_565);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < width; y++) {
                bmp.setPixel(y, x, (bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE));
            }
        }
        return Bitmap.createScaledBitmap(bmp, bmp.getWidth(), bmp.getHeight(), false);
    }
}
