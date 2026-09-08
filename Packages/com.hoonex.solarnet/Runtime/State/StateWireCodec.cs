using System;
using System.IO;
using System.Text;

namespace SolarNet.State
{
    internal static class StateWireCodec
    {
        // Leaves headroom for SolarPacket + Nearby envelope framing under Nearby's BYTES ceiling.
        private const int MaxSnapshotBytes = (1024 * 1024) - 8192;
        private const int MaxStringBytes = 1024;

        public static byte[] EncodeSnapshot(string currentPlayerId, int round, byte[] state)
        {
            if (string.IsNullOrWhiteSpace(currentPlayerId)) throw new ArgumentException("Current player ID is required.", nameof(currentPlayerId));
            if (round < 1) throw new ArgumentOutOfRangeException(nameof(round));
            state = state ?? Array.Empty<byte>();
            if (state.Length > MaxSnapshotBytes) throw new InvalidDataException("SolarNet state snapshot is too large for the portable snapshot envelope.");

            using (var stream = new MemoryStream())
            using (var writer = new BinaryWriter(stream, Encoding.UTF8))
            {
                WriteString(writer, currentPlayerId);
                writer.Write(round);
                writer.Write(state.Length);
                writer.Write(state);
                writer.Flush();
                return stream.ToArray();
            }
        }

        public static SolarStateSnapshot DecodeSnapshot(long nextTurnIndex, string stateHash, byte[] payload)
        {
            using (var stream = new MemoryStream(payload ?? Array.Empty<byte>(), false))
            using (var reader = new BinaryReader(stream, Encoding.UTF8))
            {
                var currentPlayerId = ReadString(reader);
                var round = reader.ReadInt32();
                var length = reader.ReadInt32();
                if (length < 0 || length > MaxSnapshotBytes) throw new InvalidDataException("Invalid SolarNet snapshot length.");
                var state = reader.ReadBytes(length);
                if (state.Length != length) throw new EndOfStreamException("SolarNet snapshot was truncated.");
                if (stream.Position != stream.Length) throw new InvalidDataException("SolarNet snapshot contains trailing bytes.");
                return new SolarStateSnapshot(nextTurnIndex, currentPlayerId, round, stateHash, state);
            }
        }

        public static byte[] EncodeResyncUnavailable(SolarResyncFailureReason reason)
        {
            if (reason == SolarResyncFailureReason.AutomaticRetryLimit)
                throw new ArgumentException("AutomaticRetryLimit is local-only and cannot be sent on the wire.", nameof(reason));
            return new[] { (byte)reason };
        }

        public static SolarResyncFailureReason DecodeResyncUnavailable(byte[] payload)
        {
            if (payload == null || payload.Length != 1) throw new InvalidDataException("Invalid resync-unavailable payload.");
            var reason = (SolarResyncFailureReason)payload[0];
            if (reason < SolarResyncFailureReason.UnknownPlayer || reason > SolarResyncFailureReason.InvalidTurnIndex)
                throw new InvalidDataException("Unknown resync-unavailable reason: " + payload[0] + ".");
            return reason;
        }

        private static void WriteString(BinaryWriter writer, string value)
        {
            var bytes = Encoding.UTF8.GetBytes(value ?? string.Empty);
            if (bytes.Length > MaxStringBytes) throw new InvalidDataException("SolarNet state string field is too large.");
            writer.Write(bytes.Length);
            writer.Write(bytes);
        }

        private static string ReadString(BinaryReader reader)
        {
            var length = reader.ReadInt32();
            if (length < 0 || length > MaxStringBytes) throw new InvalidDataException("Invalid SolarNet state string length.");
            var bytes = reader.ReadBytes(length);
            if (bytes.Length != length) throw new EndOfStreamException("SolarNet state string was truncated.");
            return Encoding.UTF8.GetString(bytes);
        }
    }
}
