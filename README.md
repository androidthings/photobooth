Android Things Photobooth
============

Introduction
------------
This project contains all the code required to build a cloud-connected, talking photobooth powered by
[Android Things](https://developer.android.com/things/index.html).  This is the
codebase that was used in the Photobooth demo at Google I/O 2017.  It will take your photo, style it with Tensorflow, upload it to the cloud, and print the photo out.  Upon request, it can also share your photo to its twitter account.

This demo uses the following Google platforms and APIs:

- [Android Things](https://developer.android.com/things/index.html) - The photobooth's operating system.
- [Tensorflow](https://www.tensorflow.org/) - ML is used for [artistic style transfer](https://arxiv.org/abs/1610.07629) to add style to the photos.
- [Firebase](https://firebase.google.com/) - To manage social media sharing, storing photos in the cloud, and creating short URLs to link to those photos.
- [Google Assistant & Actions On Google](https://developers.google.com/actions/) - This photobooth talks.  To use it, you talk back.  No, really, it's a talking photobooth!  Also, it's kind of cheeky.

Pre-requisites
--------------
- One [Android-Things powered device](https://developer.android.com/things/hardware/developer-kits.html) with a connected camera.  This demo ran on a Raspberry Pi 3, but is in no way limited to that device.

- One Google Assistant device.  It's a talking photobooth, so you need something to handle the VUI.  You can use a Google Home, or [build your own](http://aiyprojects.withgoogle.com/voice).

(Optional)
- To print out QR codes, you can use a [thermal printer](https://www.adafruit.com/product/597).  Modify the code to use the ThermalPrinter class instead of HttpImagePrint.

(Optional)
- A regular printer to print out photos.  This will need to be attached to a print server.  Setting up the server is beyond the scope of this document.

Getting Started
---------------
Most of the steps here are best described by existing documentation, which is better to link to than to paraphrase.  In order, do the following.

- [Set up your Android Things device](https://developer.android.com/things/preview/index.html).

- Import this project in Android Studio

- [Add Firebase to your Android project](https://firebase.google.com/docs/android/setup)
  - Follow the instructions for setting up [Google Cloud Storage for Firebase](https://firebase.google.com/docs/storage/android/start).  Be sure to set up [storage security rules](https://firebase.google.com/docs/storage/security/start).  You'll want the rules set so that anybody can download the photos, but only authenticated "users" of the application (only the signed APK for your application) can upload photos.  Here is the rule to copy/paste:
  ```
  service firebase.storage {
      match /b/{bucket}/o {
        match /{allPaths=**} {
          allow read: if true;
          allow write: if request.auth != null;
        }
      }
  }
  ```

- [Deploy the Google Cloud Functions](https://firebase.google.com/docs/functions/get-started) which are located in _project-root_/firebase
  - These interact with multiple services you will also need to set up:
    - To share on Twitter, you'll need a [Twitter API key](https://dev.twitter.com/).
    - To generate shortened URLs you'll need an [API key](https://developers.google.com/url-shortener/v1/getting_started#APIKey) for Google's URL Shortener.
    - Once you have the keys, add them to your [Environment Configuration] so they'll be retrieved from the server at runtime.  Modify firebase/functions/photos.js to point to the correct environment variables.  The code block to modify will look like this:

    ```
    /**
     * Twitter Info
     */
     var TWTR_CONSUMER_KEY = functions.config().twitter.consumer_key;
     var TWTR_CONSUMER_SECRET = functions.config().twitter.consumer_secret;
     var TWTR_ACCESS_TOKEN_KEY = functions.config().twitter.access_token_key;
     var TWTR_ACCESS_TOKEN_SECRET = functions.config().twitter.access_token_secret;

    /**
     * API key for the Google URL shortener API
     * https://developers.google.com/url-shortener/v1/getting_started#APIKey
     */
    var URL_SHORTENER_API_KEY = functions.config().shorturl.key;
    ```

- Code for the Google Assistant app (the voice UI) is in the _agent_/ directory.
To deploy it, follow the [Getting Started Guide for Actions On Google](https://developers.google.com/actions/get-started/).

- Printing strategies will vary - For the photobooth demo we set up [CUPS](https://www.cups.org/) on a second Raspberry Pi and attached it to a Photobooth printer (the setup of CUPS and various printer models is wildly beyond the scope of this document).  It's also possible (and likely easier) to get a printer that supports Google Cloud Print, and use the [sendJobs](https://developers.google.com/cloud-print/docs/sendJobs) API call to activate a print job over the internet.

Support and Discussion
-------

- Google+ IoT Developer Community Community: https://plus.google.com/communities/107507328426910012281
- Stack Overflow: https://stackoverflow.com/tags/android-things/

If you've found an error in this sample, please file an issue:
https://github.com/androidthings/photobooth/issues

Patches are encouraged, and may be submitted by forking this project and
submitting a pull request through GitHub.

License
-------

Copyright 2017 Google, Inc.

Licensed to the Apache Software Foundation (ASF) under one or more contributor
license agreements.  See the NOTICE file distributed with this work for
additional information regarding copyright ownership.  The ASF licenses this
file to you under the Apache License, Version 2.0 (the "License"); you may not
use this file except in compliance with the License.  You may obtain a copy of
the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
License for the specific language governing permissions and limitations under
the License.
