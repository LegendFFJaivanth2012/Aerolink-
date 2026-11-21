# AeroLink v0.31 relay

This server is the network transport layer for the Android app.

Flow:
1. Receiver performs the trained gesture.
2. Receiver calls POST /v1/ready/:recipientId.
3. Sender performs its trained gesture.
4. Sender uploads the selected file to POST /v1/transfers.
5. Receiver polls /v1/inbox/:recipientId.
6. Receiver downloads /v1/transfers/:id/file.
7. The relay deletes the stored file after download.

Deploy this server behind HTTPS. The Android app accepts the HTTPS relay URL in its network dialog.

The ESP32s do not carry file data in v0.31.
