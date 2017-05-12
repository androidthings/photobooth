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

import android.graphics.Bitmap;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.util.Log;

import com.google.android.gms.tasks.Continuation;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageMetadata;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;

import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;

public class FirebaseStorageAdapter {

    public final String TAG = "FirebaseStorageAdapter";
    private FirebaseAuth.AuthStateListener mAuthListener;
    private FirebaseAuth mAuth;

    FirebaseStorage mStorage = FirebaseStorage.getInstance();

    public interface PhotoUploadedListener {
        void onPhotoUploaded(Uri url);
    }

    public FirebaseStorageAdapter() {
        mAuth = FirebaseAuth.getInstance();
        initializeAuth();
    }

    /**
     * Most of the auth code pulled from
     * https://firebase.google.com/docs/auth/android/anonymous-auth
     */
    public void initializeAuth() {
        mAuthListener = firebaseAuth -> {
            FirebaseUser user = firebaseAuth.getCurrentUser();
            if (user != null) {
                Log.d(TAG, "onAuthStateChanged:signed_in:" + user.getUid());
            } else {
                // User is signed out
                Log.d(TAG, "onAuthStateChanged:signed_out");
            }
            // ...
        };
    }

    /**
     * Get a reference to the "images" directory on Firebase Storage.
     *
     * @return reference to "images" upload directory.
     */
    public StorageReference getImagesStorageRef() {
        if (mStorage == null) {
            mStorage = FirebaseStorage.getInstance();
        }
        return mStorage.getReference("images");
    }

    /**
     * Helper method to generate a filename, which will be the timestamp (for easy searching
     * and sorting) with optional prefix and suffixes.
     *
     * @param prefix Will go in front of the timestamp in the filename.
     * @param suffix Will go after the timestamp in the filename.
     * @return image filename of form "prefix-timestamp-suffix.png".
     */
    public String getTimestampedFileName(String prefix, String suffix) {
        SimpleDateFormat s = new SimpleDateFormat("yyyymmddhhmmssSSS");
        String timestamp = s.format(new Date());

        StringBuilder stringBuilder = new StringBuilder();
        if (prefix != null) {
            stringBuilder.append(prefix).append("-");
        }
        stringBuilder.append(timestamp);
        if (suffix != null) {
            stringBuilder.append("-").append(suffix);
        }

        stringBuilder.append(".png");
        return stringBuilder.toString();
    }

    UploadTask uploadBitmap(Bitmap bitmap, String prefix, String suffix,
                            PhotoUploadedListener listener, boolean share) {
        String filename = getTimestampedFileName(prefix, suffix);
        StorageReference fileRef = getImagesStorageRef().child(filename);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
        byte[] data = baos.toByteArray();

        FirebaseDatabase database = FirebaseDatabase.getInstance();
        DatabaseReference myRef = database.getReference("links")
                .child("images")
                .child(filename.substring(0, filename.length() - 4));

        // Attach metadata

        myRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                if (dataSnapshot != null) {
                    if (dataSnapshot.getValue() != null) {
                        Uri shortUri = Uri.parse(dataSnapshot.getValue(String.class));
                        Log.i(TAG, "Short URL generated! " + shortUri.toString());
                        listener.onPhotoUploaded(shortUri);
                        myRef.removeEventListener(this);
                    }
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
            }
        });

        Log.d(TAG, "Uploading file: " + filename);
        UploadTask uploadTask;
        if (share) {
            StorageMetadata metadata = new StorageMetadata.Builder()
                    .setCustomMetadata("tweetme", "true")
                    .build();

            fileRef.updateMetadata(metadata);
            uploadTask = fileRef.putBytes(data, metadata);
        } else {
            uploadTask = fileRef.putBytes(data);
        }

        uploadTask.addOnFailureListener(e -> Log.d(TAG, "Upload Task Failed."));
        return uploadTask;
    }

    void uploadBitmaps(Bitmap original, Bitmap styled,
                       PhotoUploadedListener origListener, PhotoUploadedListener styledListener,
                       boolean shareOnSocialMedia) {

        // Even if user elects for sharing on social media, we don't want to spam the feed
        // with two images for each person.  When there's a styled image, never share the original.
        uploadBitmap(original, "original", null, origListener, false);
        uploadBitmap(styled, "styled", null, styledListener, shareOnSocialMedia);
    }


    UploadTask signInAndUploadBitmap(Bitmap bitmap, String prefix, String suffix, boolean share) {
        UploadTask uploadTask = (UploadTask) mAuth.signInAnonymously()
                .continueWithTask(task -> {
                    // If task failed, this will throw an Exception and cause the whole task
                    // chain to fail (which is good)
                    AuthResult result = task.getResult();
                    // Otherwise, do the storage thing
                    return uploadBitmap(bitmap, prefix, suffix, null, share);
                }).addOnSuccessListener(result -> {
                    // Auth and upload succeeded
                    Log.d(TAG, "Both Auth and upload succeeded, hooray!");
                }).addOnFailureListener(e -> {
                    // Either auth or upload failed, can check exception subclass if we care which one
                    Log.d(TAG, "There was an error uploading the image!", e);
                }).getResult().getTask();
        return uploadTask;
    }


    void onStart() {
        mAuth.addAuthStateListener(mAuthListener);
        if (!isUserSignedIn()) {
            mAuth.signInAnonymously().addOnCompleteListener(task -> {
                Log.d(TAG, "signInAnonymously:onComplete:" + task.isSuccessful());

                // If sign in fails, display a message to the user. If sign in succeeds
                // the auth state listener will be notified and logic to handle the
                // signed in user can be handled in the listener.
                if (!task.isSuccessful()) {
                    Log.w(TAG, "signInAnonymously: Task failed. ", task.getException());
                } else {
                    Log.w(TAG, "signInAnonymously: Task succeeded. ", task.getException());
                }
            });
        } else {
            Log.d(TAG, "User already signed in.");
        }
    }

    void onStop() {
        if (mAuthListener != null) {
            mAuth.removeAuthStateListener(mAuthListener);
        }
    }

    private boolean isUserSignedIn() {
        return mAuth.getCurrentUser() != null;
    }
}