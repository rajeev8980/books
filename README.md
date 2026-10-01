# Suggestion box

A box for up to four people. Anyone can drop as many suggestions as they want — text, a photo, or an animated GIF. Each one shows up, then it is gone. Nothing is saved.

## What happens to a suggestion

- The sender's copy leaves their screen 3 seconds after they drop it, even if nobody else sees it.
- Once it has been delivered and shown, it leaves each other person's screen 3 seconds after it appears.
- The server deletes the suggestion, and any image or GIF file, 3 seconds after it is received. The file is removed from disk. The record is removed from memory. Nothing is kept with a hidden "deleted" flag.
- Each phone drops the bytes it was showing and wipes its cache when the suggestion leaves the screen. The app has no database.
- There is no limit on how many suggestions a person can drop. They do not have to wait for the last one to vanish.
- A fifth connection is refused with: "This room already has four people."

The picture you pick stays in your gallery. The app only reads it. It does not write a second copy, and it does not delete the original.

## Run the server

The server listens on port **43123**.

```bash
cd server
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
python app.py
```

Check it:

```bash
curl http://127.0.0.1:43123/health
```

Tests (they wait out the real 3 second deletion):

```bash
cd server
source .venv/bin/activate
python -m pytest
```

## Open it

The box is a web page: [https://b-k8s4.onrender.com](https://b-k8s4.onrender.com). A phone browser loads that page. The next visit after a deploy is the latest version. You do not install a new app for each change.

On an Android phone, open that address in Chrome. When the browser is ready, Install app appears. One tap installs Suggest as an app: its own icon, portrait, and no browser bar. Suggestions are still not saved.

The Android app is a window onto that same page. The next time it opens, it loads the latest page from the server. If the server has a newer copy of the app itself, the phone downloads that package and hands it to Android to replace the installed app. There is no download page.

While the server is waking up, the phone stays on the box and keeps trying. It does not show a "Can't reach the server" page.

## Build the Android window

You need JDK 17 or newer and the Android SDK (compile SDK 37).

`server.url` in `android/local.properties` is the page the app opens. Copy the example if you don't have the file yet:

```bash
cd android
cp local.properties.example local.properties
```

Point `sdk.dir` in `local.properties` at your Android SDK, or set `ANDROID_HOME`. Then:

```bash
cd android
./gradlew installDebug
```

Install it on up to four phones. They all use the same server. A fifth phone is turned away.

The installed app opens `https://b-k8s4.onrender.com`. That server stays up on its own, on any network. It speaks WebSocket at `/ws` and accepts photo and GIF uploads at `POST /media`. There is no account.
