using System;
using System.Text;

namespace SolarNet.Nearby
{
    public sealed class SolarNearbyRoomAdvertisement
    {
        public SolarNearbyRoomAdvertisement(string roomId, string roomName, string compatibilityKey)
        {
            RoomId = roomId;
            RoomName = roomName;
            CompatibilityKey = compatibilityKey;
        }

        public string RoomId { get; private set; }
        public string RoomName { get; private set; }
        public string CompatibilityKey { get; private set; }
    }

    public static class NearbyRoomAdvertisementCodec
    {
        private const string Marker = " · SN1:";

        public static string Encode(string roomId, string roomName, string compatibilityKey)
        {
            if (string.IsNullOrWhiteSpace(roomId)) throw new ArgumentException("Room ID is required.", nameof(roomId));
            if (string.IsNullOrWhiteSpace(roomName)) throw new ArgumentException("Room name is required.", nameof(roomName));
            if (string.IsNullOrWhiteSpace(compatibilityKey)) throw new ArgumentException("Compatibility key is required.", nameof(compatibilityKey));
            return roomName + Marker + Base64Url(roomId) + ":" + Base64Url(compatibilityKey);
        }

        public static bool TryDecode(string endpointName, out SolarNearbyRoomAdvertisement advertisement)
        {
            advertisement = null;
            if (string.IsNullOrWhiteSpace(endpointName)) return false;
            var markerIndex = endpointName.LastIndexOf(Marker, StringComparison.Ordinal);
            if (markerIndex <= 0) return false;
            var roomName = endpointName.Substring(0, markerIndex);
            var metadata = endpointName.Substring(markerIndex + Marker.Length).Split(':');
            if (metadata.Length != 2) return false;

            try
            {
                var roomId = FromBase64Url(metadata[0]);
                var compatibilityKey = FromBase64Url(metadata[1]);
                if (string.IsNullOrWhiteSpace(roomId) || string.IsNullOrWhiteSpace(compatibilityKey)) return false;
                advertisement = new SolarNearbyRoomAdvertisement(roomId, roomName, compatibilityKey);
                return true;
            }
            catch (FormatException)
            {
                return false;
            }
        }

        private static string Base64Url(string value)
        {
            return Convert.ToBase64String(Encoding.UTF8.GetBytes(value))
                .TrimEnd('=')
                .Replace('+', '-')
                .Replace('/', '_');
        }

        private static string FromBase64Url(string value)
        {
            var normalized = value.Replace('-', '+').Replace('_', '/');
            switch (normalized.Length % 4)
            {
                case 2: normalized += "=="; break;
                case 3: normalized += "="; break;
                case 1: throw new FormatException("Invalid base64url length.");
            }
            return Encoding.UTF8.GetString(Convert.FromBase64String(normalized));
        }
    }
}
