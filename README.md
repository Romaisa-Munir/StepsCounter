# StepsCounter

A clean, highly accurate, privacy-first Android step counter app built with Kotlin and Jetpack Compose.

## Features
- **High Accuracy:** Uses a custom hardware sensor algorithm to count steps reliably.
- **100% Offline & Private:** No accounts, no data harvesting. All step data is stored locally on your device.
- **Battery Efficient:** Relies on the low-power `Sensor.TYPE_STEP_COUNTER` to track steps in the background without draining your battery.
- **Beautiful UI:** A modern, premium interface built with Jetpack Compose featuring dynamic gradients, large typography, and motivational goal tracking.

## Requirements
- Android 13 (API 33) or higher recommended (requires `ACTIVITY_RECOGNITION` and `POST_NOTIFICATIONS` permissions).
- A physical Android device with hardware step sensors (cannot be fully tested on an emulator).

## Tech Stack
- **Language:** Kotlin
- **UI:** Jetpack Compose
- **Background Logic:** Android Foreground Services
- **Storage:** SharedPreferences

## Getting Started
1. Clone the repository.
2. Open the project in Android Studio.
3. Build and run the app on a physical Android device.
4. Accept the required physical activity permissions.
5. Set your daily goal and start walking!
