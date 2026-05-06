# Knobservice for Android Automotive 13

This Service enables the custom-made rotary knob to control the Android Automotive operating system. The Rotary Knob was provided by moxdlab as part of this study.

## Prerequisites
- Raspberry Pi 4B (4GB RAM)
- Micro SD Card (32GB or more)
- Linux OS with least 300GB of free space

note: multiple 64 GB SD cards were used for this project

## Setting up Android Automotive
To get the AOSP Sourcecode and the specific libraries for Raspberry Pi, use following commands:
```bash
# Get the AOSP source code
repo init -u https://android.googlesource.com/platform/manifest -b android-13.0.0_r71 --depth=1
git clone https://github.com/grapeup/aaos_local_manifest.git .repo/local_manifests
repo sync -j8
````

Depending on your internet connection, the download process may take several hours.

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

## Flahsing the Image

insert the micro SD card into your computer and run the following commands:
```bash
# Check the device name of your SD card (e.g., /dev/sdX)
lsblk
# Unmount the SD card if it is automatically mounted (sdX is just a placeholder)
sudo umount /dev/sdX*
# Flash the image to the SD Card
sudo fdisk /dev/sdX
```

the ```fdisk``` command opens a  terminal-based interface. Use the following commands to flash the image:
```bash
# Inside fdisk, use the following commands:
o # Create a new empty DOS partition table

# CREATE PARTITION 1:
n # Add a new partition
p # Primary partition
1 # Partition number
Enter # First sector (accept default)
+128M # Last sector (size of the partition)
t # Change partition type
c # Set partition type to W95 FAT32 (LBA)
a # Toggle the bootable flag on the partition

# CREATE PARTITION 2:
n # Add a new partition
p # Primary partition
2 # Partition number
Enter # First sector (accept default)
+2048M # Last sector (size of the partition)

# CREATE PARTITION 3:
n # Add a new partition
p # Primary partition
3 # Partition number
Enter # First sector (accept default)
+1024M # Last sector (size of the partition)

# CREATE PARTITION 4:
n # Add a new partition
p # Primary partition
4 # Partition number
Enter # First sector (accept default)
Enter # Last sector (accept default) 
```


