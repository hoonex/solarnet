using System;
using System.Collections.Generic;

namespace SolarNet.Nearby.Android
{
    public static class NearbyAndroidPermissions
    {
        public const string AccessCoarseLocation = "android.permission.ACCESS_COARSE_LOCATION";
        public const string AccessFineLocation = "android.permission.ACCESS_FINE_LOCATION";
        public const string BluetoothAdvertise = "android.permission.BLUETOOTH_ADVERTISE";
        public const string BluetoothConnect = "android.permission.BLUETOOTH_CONNECT";
        public const string BluetoothScan = "android.permission.BLUETOOTH_SCAN";
        public const string NearbyWifiDevices = "android.permission.NEARBY_WIFI_DEVICES";
        public const string AccessLocalNetwork = "android.permission.ACCESS_LOCAL_NETWORK";

        public static string[] GetRequiredRuntimePermissions(int deviceSdkInt, int targetSdkInt)
        {
            if (deviceSdkInt < 0) throw new ArgumentOutOfRangeException(nameof(deviceSdkInt));
            if (targetSdkInt < 0) throw new ArgumentOutOfRangeException(nameof(targetSdkInt));

            var permissions = new List<string>();
            if (deviceSdkInt <= 28)
            {
                permissions.Add(AccessCoarseLocation);
            }
            else if (deviceSdkInt <= 32)
            {
                permissions.Add(AccessFineLocation);
            }

            if (deviceSdkInt >= 31)
            {
                permissions.Add(BluetoothAdvertise);
                permissions.Add(BluetoothConnect);
                permissions.Add(BluetoothScan);
            }

            if (deviceSdkInt >= 33)
                permissions.Add(NearbyWifiDevices);

            if (deviceSdkInt >= 37 && targetSdkInt >= 37)
                permissions.Add(AccessLocalNetwork);

            return permissions.ToArray();
        }
    }
}
