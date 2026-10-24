# Workspace Provisioner

Provisions an isolated Android work profile via ACTION_PROVISION_MANAGED_PROFILE
and becomes its profile owner through a DeviceAdminReceiver.

Build:  ./gradlew assembleDebug        (APK in app/build/outputs/apk/debug/)
CI:     push to main -> Actions -> artifact "workprofile-apks"
Install: adb install -r app-debug.apk  (must land in user 0)
Remove:  Settings -> Passwords & accounts -> Work profile -> Remove work profile
