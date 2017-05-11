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
admin.initializeApp(functions.config().firebase, 'photoProcess');

/**
 * Twitter Info
 * https://apps.twitter.com/app/13660027
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

/**
 * Constructor for new Photo Handler.
 */
function PhotoHandler() {}

/**
 * Function executes on every storage object change.
 */
PhotoHandler.prototype.storageObjectChanged = function(event) {
    // The storage object.
    var object = event.data;
    console.log('Object:', object);

    // Ignore deleted files
    if (object.resourceState != 'exists') {
        console.log('File deleted at path:', object.name);
        return;
    }

    // Determine if this picture should be tweeted
    var tweetMe = (object.metadata && object.metadata.tweetme);

    // Construct a public URL to the file
    var publicUrl = this.getPublicUrl(object);
    console.log('Public URL: ', publicUrl);

    // Get a short URL (based on the public URL) to the file
    var that = this;
    var shortenAndWrite = this.shortenUrl(publicUrl)
        .then(function(shortUrl) {
            console.log('Short URL:', shortUrl);

            // Write to Firebase RTDB
            var firebasePromise = that.writeShortUrlToDatabase(object, shortUrl);

            // Tweet the image (if required)
            var tweetPromise = tweetMe ? that.tweetImage(object, shortUrl) : Promise.resolve();

            return Promise.all([firebasePromise, tweetPromise]);
        });

    // Wait for all operations to complete.
    return shortenAndWrite;
};

/**
 * Get a public download URL for a Firebase Storage object.
 * @param {object} object Firebase Storage object.
 */
PhotoHandler.prototype.getPublicUrl = function(object) {
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
PhotoHandler.prototype.getImageData = function(object) {
    // Choose temp file path
    var fileName = object.name.replace(/\//g,"_");
    var path = `/tmp/${fileName}`;

    // Download file from bucket.
    var bucket = gcs.bucket(object.bucket);
    return bucket.file(object.name).download({ destination: path })
        .then(function() {
            // Read file data from system
            console.log('Image downloaded locally to', path);
            return fs.readFileSync(path);
        });
}

/**
 * Tweet about a photo booth image.
 * @param {object} object Firebse Storage object.
 */
PhotoHandler.prototype.tweetImage = function(object, shortUrl) {
    // TODO: this function should make a tweet with an attached image
    // that has the text 'Thanks for visiting #io17 @user! Here's your
    // picture' or similar.
    var msg = "Hey! Here's your photo: " + shortUrl;

    var that = this;
    return this.getImageData(object)
        .then(function(data) {
            return that.uploadToTwitter(data)
        })
        .then(function(media_id) {
            return that.postToTwitter(msg, media_id)
        });
}

/**
 * Write the short URL for an obejct to the approprate RTDB path.
 * @param {object} object a Firebase Storage object.
 * @param {string} shortUrl a shortened URL.
 */
PhotoHandler.prototype.writeShortUrlToDatabase = function(object, shortUrl) {
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
PhotoHandler.prototype.shortenUrl = function(longUrl) {
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
    return new Promise(function(resolve, reject) {
        // POST path, options, and data
        var path = '/urlshortener/v1/url?key=' + URL_SHORTENER_API_KEY;
        var options = {
            hostname: 'www.googleapis.com',
            port: 443,
            path: path,
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            }
        };
        var data = {
            longUrl: longUrl
        };

        var req = https.request(options, function(res) {
            // Make sure we got a 200 status code
            if (res.statusCode != 200) {
                reject('Status Code: ' + res.statusCode);
                return;
            }

            // Success, return the URL
            res.on('data', function(d) {
                // Parse as JSON, return just the URL
                var obj = JSON.parse(d);
                resolve(obj.id);
            });
        });

        // Error, return the error
        req.on('error', function(e) {
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
PhotoHandler.prototype.getTwitterClient = function() {
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
PhotoHandler.prototype.uploadToTwitter = function(data) {
    var that = this;
    return new Promise(function(resolve, reject) {
        var params = {
            media: data
        };

        that.getTwitterClient().post('media/upload', params, function(error, media, response) {
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
PhotoHandler.prototype.postToTwitter = function(status, media_id) {
    var that = this;
    return new Promise(function(resolve, reject) {
        var params = {
            status: status,
            media_ids: media_id
        };

        that.getTwitterClient().post('statuses/update', params, function(error, tweet, response) {
            if (error) {
                reject(error);
                return;
            }

            resolve(tweet);
        });
    });
}

// Exports
exports.PhotoHandler = PhotoHandler;