/*
  AeroLink v0.31 ESP32 bridge
  Purpose: tiny trigger/status channel only.
  File data NEVER passes through the ESP32.

  Board: ESP32 DevKit V1 / ESP32-WROOM
  This sketch uses Serial2 for a simple physical trigger input/output.

  ESP32 #1:
    GPIO17 = TX2
    GPIO18 = RX2

  ESP32 #2:
    GPIO17 = TX2
    GPIO18 = RX2

  Cross-connect:
    #1 GPIO17 (TX2) -> #2 GPIO18 (RX2)
    #1 GPIO18 (RX2) <- #2 GPIO17 (TX2)
    GND -> GND

  Power both boards separately over USB.
*/

#include <Arduino.h>

HardwareSerial AeroSerial(2);

static const int TX2_PIN = 17;
static const int RX2_PIN = 18;
static const int LED_PIN = 2;

void setup() {
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, LOW);

  Serial.begin(115200);
  AeroSerial.begin(115200, SERIAL_8N1, RX2_PIN, TX2_PIN);

  Serial.println("AeroLink v0.31 bridge ready");
  Serial.println("TX2=GPIO17 RX2=GPIO18");
}

void loop() {
  while (AeroSerial.available()) {
    String msg = AeroSerial.readStringUntil('\n');
    msg.trim();

    if (msg == "GESTURE") {
      digitalWrite(LED_PIN, HIGH);
      Serial.println("GESTURE EVENT");
      delay(100);
      digitalWrite(LED_PIN, LOW);
    }
  }

  // Optional USB/serial test:
  // Send the word GESTURE from the serial monitor to create a trigger.
  if (Serial.available()) {
    String msg = Serial.readStringUntil('\n');
    msg.trim();
    if (msg == "GESTURE") {
      AeroSerial.println("GESTURE");
      digitalWrite(LED_PIN, HIGH);
      delay(100);
      digitalWrite(LED_PIN, LOW);
    }
  }
}
