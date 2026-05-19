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
repo init -u https://android.googlesource.com/platform/manifest -b android-13.0.0_r75 --depth=1
git clone https://github.com/Luca-Brabender/aaos_local_manifest.git .repo/local_manifestsrepo sync
repo sync
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
├── AndroidManifest.xml      
├── privapp-permissions-knob.xml 
└── src/
    └── com/
        └── example/
            └── knobservice/
                ├── BootReceiver.kt  
                └── KnobService.kt   
```
NOTE: Make sure you change the Android.txt in the repository to Android.bp, otherwise the build system won't recognize the new service.

From your source code directory, go to /devices/brcm/rpi4/aosp_rpi4_car.mk, then add following code:

```text
PRODUCT_PACKAGES += \
    KnobService \
    privapp-permissions-knob.xml
```

## Build Android Automotive
compile using following commands:
```bash
# Compile the AOSP source code
. build/envsetup.sh
lunch aosp_rpi4_car-userdebug
make -j$(nproc) bootimage systemimage vendorimage
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

w # Write the partition table to the SD card and exit
```

Creating the filesystems:
```bash
sudo mkfs.vfat /dev/sdX1
sudo mkfs.ext4 -L userdata /dev/sdX4
```

Now, time to write the image to the SD card. Use the following commands to fill each partition:

```bash
# mount the first partition
sudo mount /dev/sdX1 /mnt
# Copy the boot files to the first partition
sudo cp -r out/target/product/rpi4/rpiboot/* /mnt/
# Put the system image on the second partition
sudo dd if=out/target/product/rpi4_car/system.img of=/dev/sdX2 bs=1M status=progress
# Put the vendor image on the third partition
sudo dd if=out/target/product/rpi4_car/vendor.img of=/dev/sdX3 bs=1M status=progress
```

After the flashing process is complete, safely eject the SD card and insert it into your Raspberry Pi. 
Power on the Raspberry Pi, and it should boot into Android Automotive with the KnobService running in the background, ready to receive input from the rotary knob.

Note: It can occur, that bluetooth is disabled in the first boot. Enable it and pair with the rotary knob to test the functionality of the KnobService.




