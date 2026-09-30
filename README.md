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

The app opens the box by itself. The address is already set to a public server, so a phone on any network can use it. The phone remembers whatever address you save. If you run the server yourself, change the address on the first screen (tap the X in the box to get there). An emulator on the same computer can use `http://10.0.2.2:43123`.

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

The installed app uses `https://feature-beyond-cyber-seat.trycloudflare.com`, which reaches this server from any network. It speaks WebSocket at `/ws` and accepts photo and GIF uploads at `POST /media`. There is no account and no web app.
