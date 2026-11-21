# AeroLink v0.31 ESP32 wiring

ESP32 #1 (TX node)       ESP32 #2 (RX node)

GPIO17 / TX2  ---------->  GPIO18 / RX2
GPIO18 / RX2  <----------  GPIO17 / TX2
GND           ------------  GND

Power:
- ESP32 #1: USB
- ESP32 #2: USB
- Do NOT connect the 5V/VIN or 3.3V rails together.

Important:
The ESP32 UART link carries only tiny control messages such as GESTURE.
The actual file travels phone -> AeroLink relay -> phone.
