# Knobservice for Android Automotive 13

This Service enables the custom-made rotary knob to control the Android Automotive operating system. The Rotary Knob was provided by moxdlab as part of this study.

## Prerequisites
- Raspberry Pi 4B (4GB RAM)
- Micro SD Card (32GB or more)
- Linux OS with least 300GB of free space

## Setting up Android Automotive
To get the AOSP Sourcecode and the specific libraries for Raspberry Pi follow the instructions from Grapeup: https://github.com/grapeup/aaos_local_manifest

## Setting up The Knobservice
go to packages/apps and create a new folder called "Knobservice". Inside this folder, extract the following files:
- Android.bp
- AndroidManifest.xml
- privapp-permissions.xml

Then create subfolder src/com/example/knobservice and extract the following files:

- KnobService.kt
- BootReceiver.kt


This is how the folder structure should look like:
```text
KnobService/
├── Android.bp                 
├── AndroidManifest_8.xml      # System-Berechtigungen & Service-Definition
├── privapp-permissions-knob.xml # Whitelist für privilegierte Berechtigungen
└── src/
    └── com/
        └── example/
            └── knobservice/
                ├── BootReceiver.kt  # Startet den Service automatisch nach dem Booten
                └── KnobService.kt   # Hauptlogik (Bluetooth & Event-Injektion)
```

From your source code directory, go to /devices/brcmrpi4-car/device.mk and find the following code sample inside:
```text
PRODUCT_PACKAGES +=
```
Then add the following line to include the Knobservice in the build:
```text
PRODUCT_PACKAGES += Knobservice
```

##Creating the Image

