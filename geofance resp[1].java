import RPi.GPIO as GPIO
import time
import os
import requests
import datetime
import threading
import firebase_admin
from firebase_admin import credentials, storage, messaging
from picamera2 import Picamera2

# === Config ===
FIREBASE_DB_URL = "https://geofence-ec874-default-rtdb.asia-southeast1.firebasedatabase.app/geofence/isActive.json"
SERVICE_KEY_PATH = "/home/laxshana/Desktop/geofencing/firebase_key.json"
SAVE_DIR = "/home/laxshana/Desktop/geofencing/photos"
BUCKET_NAME = "geofence-ec874.firebasestorage.app"
FCM_TOPIC = "geofenceAlert"

# === Setup ===
PIR_PIN = 29  # GPIO.BOARD pin number

# Initialize Firebase
cred = credentials.Certificate(SERVICE_KEY_PATH)
firebase_admin.initialize_app(cred, {
    'storageBucket': BUCKET_NAME
})
bucket = storage.bucket()

# Setup GPIO and camera
os.makedirs(SAVE_DIR, exist_ok=True)
GPIO.setmode(GPIO.BOARD)
GPIO.setup(PIR_PIN, GPIO.IN)

picam = Picamera2()
picam.configure(picam.create_still_configuration())

def handle_motion_event():
    try:
        timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
        filename = f"motion_{timestamp}.jpg"
        local_path = os.path.join(SAVE_DIR, filename)

        # Capture image
        picam.start()
        time.sleep(1)
        picam.capture_file(local_path)
        picam.stop()
        print("Image captured:", local_path)

        # Upload to Firebase Storage
        blob = bucket.blob(f"photos/{filename}")
        blob.upload_from_filename(local_path)
        blob.make_public()
        photo_url = blob.public_url
        print("Photo uploaded:", photo_url)

        # Send FCM Notification
        message = messaging.Message(
            notification=messaging.Notification(
                title="Motion Detected",
                body="A photo has been captured by the Raspberry Pi.",
            ),
            data={"imageUrl": photo_url},
            topic=FCM_TOPIC
        )
        result = messaging.send(message)
        print("FCM message sent:", result)

    except Exception as e:
        print("Error in motion thread:", e)

# === Main Monitoring Loop ===
print("Geofencing system started. Monitoring...")

motion_cooldown = 10  # seconds
last_motion_time = 0

try:
    while True:
        try:
            # Check Firebase geofence status
            res = requests.get(FIREBASE_DB_URL)
            is_active = res.status_code == 200 and res.json() is True

            if is_active:
                if GPIO.input(PIR_PIN):
                    now = time.time()
                    if now - last_motion_time > motion_cooldown:
                        print("Motion Detected!")
                        last_motion_time = now
                        threading.Thread(target=handle_motion_event).start()
                    else:
                        print("Motion detected, but still in cooldown...")
                else:
                    print("No motion.")
            else:
                print("Geofence is inactive.")

        except Exception as e:
            print("Error:", e)

        time.sleep(1)

except KeyboardInterrupt:
    print("Stopped by user.")

finally:
    GPIO.cleanup()
    picam.close()
