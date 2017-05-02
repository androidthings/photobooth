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
var functions = require('firebase-functions');
var gcs = require('@google-cloud/storage')();
var admin = require('firebase-admin');

var Twitter = require('twitter');

var https = require('https');
var fs = require('fs');

/**
 * Initialize Firebase Admin SDK.
 */
admin.initializeApp(functions.config().firebase);

/**
 * Twitter Info
 * https://apps.twitter.com/app/13660027
 */
 var TWTR_CONSUMER_KEY = 'INSERT KEY HERE';
 var TWTR_CONSUMER_SECRET = 'INSERT CONSUMER SECRET HERE';
 var TWTR_ACCESS_TOKEN_KEY = 'INSERT ACCESS TOKEN HERE';
 var TWTR_ACCESS_TOKEN_SECRET = 'INSERT TOKEN SECRET HERE';

/**
 * API key for the Google URL shortener API
 * https://developers.google.com/url-shortener/v1/getting_started#APIKey
 */
var URL_SHORTENER_API_KEY = 'INSERT API KEY HERE';

/**
 * Function executes on every storage object change.
 */
exports.storageObjectChanged = functions.storage.object().onChange(event => {
    // The storage object.
    const object = event.data;
    console.log('Object:', object);

    // Ignore deleted files
    if (object.resourceState != 'exists') {
        console.log('File deleted at path:', object.name);
        return;
    }

    // Construct a public URL to the file
    var publicUrl = getPublicUrl(object);
    console.log('Public URL: ', publicUrl);

    // Get a short URL (based on the public URL) to the file
    var shortenAndWrite = shortenUrl(publicUrl)
        .then(shortUrl => {
            console.log('Short URL:', shortUrl);

            // Write to Firebase RTDB
            var firebasePromise = writeShortUrlToDatabase(object, shortUrl);

            // Tweet the image
            var tweetPromise = tweetImage(object, shortUrl);

            return Promise.all([firebasePromise, tweetPromise]);
        });

    // Wait for all operations to complete.
    return shortenAndWrite;
});

/**
 * Get a public download URL for a Firebase Storage object.
 * @param {object} object Firebase Storage object.
 */
function getPublicUrl(object) {
    var baseUrl = 'https://firebasestorage.googleapis.com/v0';
    var bucket = object.bucket;
    var encodedPath = encodeURIComponent(object.name);
    var params = '?alt=media&token=' + object.metadata.firebaseStorageDownloadTokens;

    var url = baseUrl + '/b/' + bucket + '/o/' + encodedPath + params;
    return url;
}

/**
 * Download image from Firebase Storage.
 * @param {object} object Firebase storage object.
 */
function getImageData(object) {
    // Choose temp file path
    var fileName = object.name.replace(/\//g,"_");
    var path = `/tmp/${fileName}`;

    // Download file from bucket.
    var bucket = gcs.bucket(object.bucket);
    return bucket.file(object.name).download({ destination: path })
        .then(() => {
            // Read file data from system
            console.log('Image downloaded locally to', path);
            return fs.readFileSync(path);
        });
}

/**
 * Tweet about a photo booth image.
 * @param {object} object Firebse Storage object.
 */
function tweetImage(object, shortUrl) {
    // TODO: this function should make a tweet with an attached image
    // that has the text 'Thanks for visiting #io17 @user! Here's your
    // picture' or similar.
    var msg = "Hey! Here's your photo: " + shortUrl;

    return getImageData(object)
        .then(data => {
            return uploadToTwitter(data)
        })
        .then(media_id => {
            return postToTwitter(msg, media_id)
        });
}

/**
 * Write the short URL for an obejct to the approprate RTDB path.
 * @param {object} object a Firebase Storage object.
 * @param {string} shortUrl a shortened URL.
 */
function writeShortUrlToDatabase(object, shortUrl) {
    // Convert the object path to a path in RTDB, for example:
    // /images/foo.jpg --> /links/images/foo
    var name = object.name;
    var withoutExtension = name.substr(0, name.lastIndexOf('.'));
    var dbPath = '/links/' + withoutExtension;

    // Write the short URL to that path
    return admin.database().ref(dbPath).set(shortUrl);
}

/**
 * Shorten a long URL using the Google URL shortener. Returns a promise
 * that resolves to the short URL, as a string.
 * @param {string} longUrl the URL to shorten.
 */
function shortenUrl(longUrl) {
    // REQUEST
    // POST https://www.googleapis.com/urlshortener/v1/url?key=<key>
    // Content-Type: application/json
    // {"longUrl": "http://www.google.com/"}

    // RESPONSE
    // {
    //  "kind": "urlshortener#url",
    //  "id": "http://goo.gl/fbsS",
    //  "longUrl": "http://www.google.com/"
    // }

    // Return a promise
    return new Promise((resolve, reject) => {
        // POST path, options, and data
        const path = '/urlshortener/v1/url?key=' + URL_SHORTENER_API_KEY;
        const options = {
            hostname: 'www.googleapis.com',
            port: 443,
            path: path,
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            }
        };
        const data = {
            longUrl: longUrl
        };

        const req = https.request(options, (res) => {
            // Make sure we got a 200 status code
            if (res.statusCode != 200) {
                reject('Status Code: ' + res.statusCode);
                return;
            }

            // Success, return the URL
            res.on('data', (d) => {
                // Parse as JSON, return just the URL
                var obj = JSON.parse(d);
                resolve(obj.id);
            });
        });

        // Error, return the error
        req.on('error', (e) => {
            reject(e);
        });

        // Write the POST data and end
        req.write(JSON.stringify(data));
        req.end();
    });
}

/**
 * Get a Twitter client with the proper keys.
 */
function getTwitterClient() {
    var twitter = new Twitter({
        consumer_key: TWTR_CONSUMER_KEY,
        consumer_secret: TWTR_CONSUMER_SECRET,
        access_token_key: TWTR_ACCESS_TOKEN_KEY,
        access_token_secret: TWTR_ACCESS_TOKEN_SECRET
    });

    return twitter;
}

/**
 * Upload an image to Twitter. Returns a Promise that resolves
 * to a media id (string).
 * @param {object} data image data.
 */
function uploadToTwitter(data) {
    return new Promise((resolve, reject) => {
        var params = {
            media: data
        };

        getTwitterClient().post('media/upload', params, (error, media, response) => {
            if (error) {
                reject(error);
                return;
            }

            resolve(media.media_id_string);
        });
    })

}

/**
 * Post a status update to Twitter with an optional image.
 * @param {string} status text of status update.
 * @param {string} media_id media id to attach (optional).
 */
function postToTwitter(status, media_id) {
    return new Promise((resolve, reject) => {
        var params = {
            status: status,
            media_ids: media_id
        };

        getTwitterClient().post('statuses/update', params, (error, tweet, response) => {
            if (error) {
                reject(error);
                return;
            }

            resolve(tweet);
        });
    });
}
