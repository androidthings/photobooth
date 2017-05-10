// Copyright 2017, Google, Inc.
// Licensed under the Apache License, Version 2.0 (the 'License');
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an 'AS IS' BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
'use strict';

const ResponseFetch = require('./get-response');
const responseFetch = new ResponseFetch();

const functions = require('firebase-functions');
const admin = require('firebase-admin');
admin.initializeApp(functions.config().firebase);

// Photobooth Command Constants
const TOPIC = 'io-photobooth';
const KEY_FOR_COMMAND = 'cmd';
const COMMAND_CAPTURE = 'capture';
const COMMAND_STYLE = 'style';
const GO_WITH_THIS_ONE = 'finish';
const COMMAND_START_OVER = 'startover';

// API.AI Context Constants
const PICTURE_TAKEN = 'picture_taken';
const PICTURE_TAKEN_FOLLOWUP = 'intro_and_take_picture-followup';
const PICTURE_CHOSEN_FOLLOWUP = 'intro_and_take_picture-yes-followup';
const PICTURE_STYLE_DONE = 'picture_styled';

// User ID of the Google account used with the photo booth
const PHOTO_BOOTH_ID = functions.config().assistantApp.photoBoothId;

/**
 * Request handler utility class.
 */
module.exports = class RequestHandler {
  // --------------------------------------------------------------------------
  //                   Welcome intent handler
  // --------------------------------------------------------------------------
  start (app) {
    // Check the ID of the user, if its not coming from the photobooth
    // tell the user to come to the photobooth at I/O
    console.log('User ID: ' + app.getUser().userId);
    if (app.getUser().userId !== PHOTO_BOOTH_ID) {
      return app.tell(responseFetch.getResponse('NOT_IN_PHOTOBOOTH'));
    }

    // Set the context to keep the count for fallback intents
    app.setContext(PICTURE_TAKEN, 3);
    app.setContext(PICTURE_TAKEN_FOLLOWUP, 3);

    // Get response
    let response; // var for response string
    let commandDelay; // var for cmd dealy in milliseconds
    [response, commandDelay] = responseFetch.getWelcomeResponse();
    console.log('Response: ' + response);
    console.log('Command delay: ' + commandDelay);

    // Send a message to Firebase Cloud Messaging to capture at the right moment
    setTimeout(sendFirebaseCommand, commandDelay, COMMAND_CAPTURE);
    return app.ask(response);
  }

  // Retake picture if user doesn't approve
  takePicture (app) {
    // Set the context to keep the count for fallback intents
    app.setContext(PICTURE_TAKEN, 3);
    app.setContext(PICTURE_TAKEN_FOLLOWUP, 3);

    // Get response
    let response; // var for response string
    let commandDelay; // var for cmd dealy in milliseconds
    [response, commandDelay] = responseFetch.getTakePictureResponse();
    console.log('Response: ' + response);
    console.log('Command delay: ' + commandDelay);

    // Send a message to Firebase Cloud Messaging to restart so the screen shows
    // a preview instead of the previous picture
    sendFirebaseCommand(COMMAND_START_OVER);
    // Send a message to Firebase Cloud Messaging to capture at the right moment
    setTimeout(sendFirebaseCommand, commandDelay, COMMAND_CAPTURE);

    return app.ask(response);
  }

  // ---------------------------------------------------------------------------
  //                   Style picture intent handlers
  // ---------------------------------------------------------------------------
  // Ask if the user wants to style their photo
  stylePictureInquiry (app) {
    // Set the context to keep the count for fallback intents
    app.setContext(PICTURE_TAKEN, 3);
    app.setContext(PICTURE_TAKEN_FOLLOWUP, 3);
    app.setContext(PICTURE_CHOSEN_FOLLOWUP, 3);

    let response = responseFetch.getResponse('STYLE_INQUIRY');
    console.log('Style Inquiry');
    console.log('Response: ' + response);
    return app.ask(response);
  }

  // ---------------------------------------------------------------------------
  //                   Select photo intent handlers
  // ---------------------------------------------------------------------------
  // If the user said they wanted style, stall and ask them if they like it
  selectPhotoInquiryStall (app) {
    // Set the context to keep the count for fallback intents
    app.setContext(PICTURE_TAKEN, 3);
    app.setContext(PICTURE_TAKEN_FOLLOWUP, 3);
    app.setContext(PICTURE_CHOSEN_FOLLOWUP, 3);
    app.setContext(PICTURE_STYLE_DONE, 3);

    // Get the response to stall and then ask the user if they like it
    let response = responseFetch.getResponse('SYTLE_STALLING');
    response += responseFetch.getResponse('SELECT_PHOTO_INQUIRY');
    response = response.split('</speak><speak>').join('');
    console.log('Style Inquiry Stall');
    console.log('Response: ' + response);

    // Send FCM message to style the photo
    sendFirebaseCommand(COMMAND_STYLE);

    return app.ask(response);
  }

  // If the user doesn't want style, ask them if they want to print the photo
  selectPhotoInquiry (app) {
    // Set the context to keep the count for fallback intents
    app.setContext(PICTURE_TAKEN, 3);
    app.setContext(PICTURE_TAKEN_FOLLOWUP, 3);
    app.setContext(PICTURE_CHOSEN_FOLLOWUP, 3);
    app.setContext(PICTURE_STYLE_DONE, 3);

    // Get response to ask the user if they want to select the current photo
    let response = responseFetch.getResponse('SELECT_PHOTO_INQUIRY');
    console.log('Select Photo Inquiry');
    console.log('Response: ' + response);

    return app.ask(response);
  }

  // If the user selected the photo print it and tell them what you are doing
  photoSelected (app) {
    // Get response to tell the user they selected the current photo
    let response = responseFetch.getResponse('SELECTING_PHOTO');
    response += responseFetch.getResponse('END');
    response = response.split('</speak><speak>').join('');
    console.log('Photo Selected');
    console.log('Response: ' + response);

    // Send FCM message to print the photo
    sendFirebaseCommand(GO_WITH_THIS_ONE);

    return app.tell(response);
  }

  // If the user doesn't select the photo say goodbye
  end (app) {
    // Get response to ask the user if they want to select the current photo
    let response = responseFetch.getResponse('END');
    console.log('End');
    console.log('Response: ' + response);

    return app.tell(response);
  }

  // ---------------------------------------------------------------------------
  //                   Fallback and other intent handlers
  // ---------------------------------------------------------------------------
  // Fallback intents
  fallbackGeneral (app) {
    let context = app.getContext(PICTURE_TAKEN);
    if (context.lifespan === 0) {
      let response = responseFetch.getResponse('FALLBACK_FINAL');
      console.log('Final Fallback');
      console.log('Response: ' + response);
      return app.tell(response);
    }
    let response = responseFetch.getResponse('FALLBACK_GENERAL');
    console.log('General Fallback');
    console.log('Response: ' + response);
    return app.ask(response);
  }

  // Restart the process, clear contexts and trigger start intent response
  restart (app) {
    // Set the context to keep the count for fallback intents
    app.setContext(PICTURE_TAKEN, 3);
    app.setContext(PICTURE_TAKEN_FOLLOWUP, 3);

    // Get response
    let response; // var for response string
    let commandDelay; // var for cmd dealy in milliseconds
    [response, commandDelay] = responseFetch.getWelcomeResponse();
    console.log('Restart');
    console.log('Response: ' + response);

    // Send a message to Firebase Cloud Messaging to capture at the right moment
    setTimeout(sendFirebaseCommand, commandDelay, COMMAND_CAPTURE);
    return app.ask(response);
  }

  // Return responses for help, about and easter egg intents
  help (app) {
    // Set the context to keep the count for fallback intents
    app.setContext(PICTURE_TAKEN, 3);
    return app.ask(responseFetch.getResponse('HELP'));
  }
  about (app) {
    // Set the context to keep the count for fallback intents
    app.setContext(PICTURE_TAKEN, 3);
    return app.ask(responseFetch.getResponse('ABOUT'));
  }
  easterEgg (app) {
    // Set the context to keep the count for fallback intents
    app.setContext(PICTURE_TAKEN, 3);
    return app.ask(responseFetch.getResponse('EASTER_EGGS'));
  }
};

// ---------------------------------------------------------------------------
//                   Firebase Cloud Messaging Helper Function
// ---------------------------------------------------------------------------
function sendFirebaseCommand (command) {
  // figure out how to delay the set about of time
  // i.e. wait(delay);

  // Define capture command playload for FCM message
  var payload = {
    data: {
      cmd: command
    }
  };
  // Send a message to devices subscribed to the provided topic.
  admin.messaging().sendToTopic(TOPIC, payload)
    .then(function (response) {
      // See the MessagingTopicResponse reference documentation for the
      // contents of response.
      console.log('Successfully sent message:', response);
      console.log('"' + KEY_FOR_COMMAND + ': ' + command + '"' + ' to topic ' + TOPIC);
    })
    .catch(function (error) {
      console.log('Error sending message:', error);
      console.log('"' + KEY_FOR_COMMAND + ': ' + command + '"' + ' to topic ' + TOPIC);
    });
}
