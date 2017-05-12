package com.example.androidthings.photobooth;

import android.graphics.Bitmap;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class HttpImagePrint {
    private static final String TAG = "HttpImagePrint";

    public static void print(Bitmap bitmap) {
//        String urlStr = "http://172.16.243.1:8081/upload";
        String urlStr = "http://192.168.1.136:8081/upload";
        String attachmentName = "image";
        String attachmentFileName = "myImage.png";
        String crlf = "\r\n";
        String twoHyphens = "--";
        String boundary =  "*****";

        Log.d(TAG, "On my way to send bitmap to " + urlStr);
        HttpURLConnection urlConnection = null;
        try {
            URL url = new URL(urlStr);
            urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setUseCaches(false);
            urlConnection.setDoInput(true);
            urlConnection.setDoOutput(true);

            urlConnection.setRequestMethod("POST");
            urlConnection.setRequestProperty("Connection", "Keep-Alive");
            urlConnection.setRequestProperty("Cache-Control", "no-cache");
            urlConnection.setRequestProperty(
                    "Content-Type", "multipart/form-data;boundary=" + boundary);

            DataOutputStream request = new DataOutputStream(
                    urlConnection.getOutputStream());

            request.writeBytes(twoHyphens + boundary + crlf);
            request.writeBytes("Content-Disposition: form-data; name=\"" +
                    attachmentName + "\";filename=\"" +
                    attachmentFileName + "\"" + crlf);
            request.writeBytes(crlf);

            bitmap.compress(Bitmap.CompressFormat.PNG, 70, request);

            request.writeBytes(crlf);
            request.writeBytes(twoHyphens + boundary +
                    twoHyphens + crlf);



            request.flush();
            request.close();

            Log.d(TAG, "Content sent, let's check the response");

            InputStream responseStream = new
                    BufferedInputStream(urlConnection.getInputStream());

            BufferedReader responseStreamReader =
                    new BufferedReader(new InputStreamReader(responseStream));

            String line = "";
            StringBuilder stringBuilder = new StringBuilder();

            while ((line = responseStreamReader.readLine()) != null) {
                stringBuilder.append(line).append("\n");
            }
            responseStreamReader.close();

            String response = stringBuilder.toString();

            if (response.startsWith("Thanks!")) {
                Log.d(TAG, "Printing OK");
            } else {
                Log.e(TAG, "Possible error printing. " +
                        "Was expecting 'Thanks!' from the print server, " +
                        "got: [" + response + "]");
            }
        } catch (IOException e) {
            Log.e(TAG, "Could not send the bitmap.", e);
        } finally {
            if (urlConnection != null) urlConnection.disconnect();
        }
    }
}