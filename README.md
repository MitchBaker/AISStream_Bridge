# AIS Bridge

Android app that ingests live AIS data from the [AISstream.io](https://aisstream.io)
websocket feed and re-encodes it into standard NMEA AIS sentences, served as UDP
datagrams on `127.0.0.1:10110` — the conventional port expected by most
AIS-consuming applications.

Tested with the **OsmAnd+ AIS Vessel Tracking plugin**: with AIS Bridge running
alongside, ships appear on the map with names, callsigns, and full vessel details,
turning a phone/tablet into a self-contained marine traffic display.

## How it works

1. Enter your AISstream.io API key in settings.
2. The app subscribes to the AISstream websocket for a bounding box centered on
   your GPS position (or a manual lat/lon), receiving live vessel data.
3. Incoming messages are converted to standard NMEA-formatted AIS sentences
   (types 1/2/3, 5, 18, 19, 24, 27) and re-broadcast on local UDP port 10110.
4. Any AIS application listening on that port — such as the OsmAnd+ AIS plugin —
   displays the traffic.

## Features

- **Full AIS message coverage** — position reports (Class A and B, including
  extended type 19 and long-range type 27) plus static/voyage data (type 5 and
  type 24) are subscribed, learned, and relayed.
- **Vessel identity memory** — static vessel data (name, callsign, IMO number,
  dimensions, draught) learned from the feed is persisted on-device and used to
  synthesize the static data for vessels whose only incoming reports are
  positions. Real static reports, when received, are relayed verbatim and take
  precedence. Known vessels reappear immediately with names after a restart.
- **Export / import the vessel store** — take your accumulated vessel identity
  database between devices or restore it after a reinstall.
- **Client-side vessel filters** — skip anchored or moored vessels, or rewrite
  contradictory navigational status before retransmission.
- **Class-aware synthesis** — Class A vessels get synthesized type 5 static data;
  Class B vessels get synthesized type 24 parts, matching what their transponders
  would actually transmit.
- **Debug capture** — record raw websocket frames paired with the encoded NMEA
  output for diagnostics, exportable as JSON.
- All data stays on your device. The only network connections are the
  AISstream.io feed and your chosen AIS application over local loopback.

## Requirements

- An AISstream.io account and API key (free tier available)
- An AIS-capable application to consume the UDP stream, e.g. OsmAnd+ with the
  AIS Vessel Tracking plugin

## Usage

1. Install the APK, or Build and install the app (you may have to allow install from outside app store).
2. Open **Settings**, enter your AISstream.io API key, choose the coverage box
   size (nautical miles from your position), and optionally enable vessel filters.
3. Tap **Start** — the foreground service connects and begins streaming.
4. Configure your AIS application (e.g. OsmAnd's AIS plugin) to listen on
   UDP `127.0.0.1:10110` and enable it.
5. Ships begin appearing on the map as data arrives.

## Screenshots

<screenshots to come >

## Disclaimer
AI was used to assist in this project.  It was still proofed by a human with mediocre programming skills. Take that as you will. 

This is a hobby project. Do not rely on it for collision avoidance or any
safety-of-navigation purpose.  I do not know how complete data from AISStream is, I just wanted to see it in OSMAND.
