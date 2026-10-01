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

## Build and install the app

You need JDK 17 or newer and the Android SDK (compile SDK 37).

The app opens the box by itself and stays on that screen. If the server is already awake, the phone is in within a second. If the server is waking up, the phone waits for it and keeps trying. It does not open a "Can't reach the server" page.

`server.url` in `android/local.properties` sets that starting text. Copy the example if you don't have the file yet:

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

The installed app uses `https://rajeev-suggestion-box.onrender.com`. That server stays up on its own, on any network. It speaks WebSocket at `/ws` and accepts photo and GIF uploads at `POST /media`. There is no account and no web app.
