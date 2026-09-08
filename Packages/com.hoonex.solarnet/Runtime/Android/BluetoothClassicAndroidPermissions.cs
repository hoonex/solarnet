using System;

namespace SolarNet.BluetoothClassic.Android
{
    public static class BluetoothClassicAndroidPermissions
    {
        public const string BluetoothConnect = "android.permission.BLUETOOTH_CONNECT";

        public static string[] GetRequiredRuntimePermissions(int androidApiLevel)
        {
            return androidApiLevel >= 31 ? new[] { BluetoothConnect } : Array.Empty<string>();
        }
    }
}
