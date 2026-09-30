# Suggestion box

A box for two people. Each person drops one suggestion — text, a photo, or an animated GIF — into the same box. It shows up, then it is gone. There is no thread and no history.

## What happens to a suggestion

- The sender's copy leaves their screen 3 seconds after they drop it, even if the other person never sees it.
- Once it has been delivered and shown, it leaves the other person's screen 3 seconds after it appears.
- The server deletes the suggestion, and any image or GIF file, 3 seconds after it is received. The file is removed from disk. The record is removed from memory. Nothing is kept with a hidden "deleted" flag.
- Each phone drops the bytes it was showing and wipes its cache when the suggestion leaves the screen. The app has no database.
- A person can drop another suggestion only after their current one has vanished.
- A third connection is refused with: "This room already has two people."

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

The first screen asks for the server address and saves it on the phone. A physical phone should use the computer's LAN address, for example `http://192.168.1.20:43123`. An emulator can keep `http://10.0.2.2:43123`, which is the emulator's name for your computer. That value is only the field's starting text. Change it in the app; you do not need to rebuild.

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

Install it on two emulators or two phones. Both use the same server. The third one is turned away.

The server has to be reachable at `http://<host>:43123`. It speaks WebSocket at `/ws` and accepts photo and GIF uploads at `POST /media`. There is no account and no web app.
