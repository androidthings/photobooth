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
'use strict';

var functions = require('firebase-functions');
var admin = require('firebase-admin');

// Initialize Firebase Admin SDK.
admin.initializeApp(functions.config().firebase);

// Import for photo processing (Twitter and Share URL)
var photos = require('./photos.js');

// Assistant App Imports
process.env.DEBUG = 'actions-on-google:*';
const Assistant = require('actions-on-google').ApiAiAssistant;
const RequestHandlers = require('./assistant-app/request-handlers'); // setup request handler
const APIAI = require('./assistant-app/apiai-consts.json'); // API.AI constants

// [START Photo processing (twitter and share URL)]
/**
 * Object to handle new photo upload events.
 */
var photo_handler = new photos.PhotoHandler();

/**
 * Function executes on every storage object change.
 */
exports.storageObjectChanged = functions.storage.object().onChange(function(event) {
    return photo_handler.storageObjectChanged(event);
});
// [END Photo processing (twitter and share URL)]

// [START Assistant app]
/**
 * Object to handle Assistant app requests
 */
const handle = new RequestHandlers(); // setup request handlers

/**
 * Function executes on every Assistant app interaction
 */
exports.photoboothAssistantApp = functions.https.onRequest((req, res) => {
  const app = new Assistant({request: req, response: res});
  console.log('Request headers: ' + JSON.stringify(req.headers));
  console.log('Request body: ' + JSON.stringify(req.body));

  const actionMap = new Map([
    // Starting the interaction, asking if the user likes it, picture retakes
    [APIAI.action.START, handle.start],
    [APIAI.action.PICTURE_DENIED, handle.takePicture],
    // asking if the user wants style to their selected photo
    [APIAI.action.PICTURE_APPROVED, handle.stylePictureInquiry],
    // asking the user if they want to upload/print their photo
    [APIAI.action.STYLE_PICTURE_APPROVED, handle.sharePhotoInquiryStall],
    [APIAI.action.STYLE_PICTURE_DENIED, handle.sharePhotoInquiry],
    // Uploading the picture
    [APIAI.action.PICTURE_UPLOAD_APPROVED, handle.photoSelected],
    [APIAI.action.PICTURE_UPLOAD_DENIED, handle.end],
    // Fallback and other actions
    [APIAI.action.FALLBACK_GENERAL, handle.fallbackGeneral],
    [APIAI.action.START_OVER, handle.restart],
    [APIAI.action.CANCEL, handle.end],
    [APIAI.action.HELP, handle.help],
    [APIAI.action.ABOUT, handle.about],
    [APIAI.action.EASTER_EGG, handle.easterEgg]
  ]);

  app.handleRequest(actionMap);
});
// [END Assisstant app]
