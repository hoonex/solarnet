using System;
using System.IO;
using System.Text;

namespace SolarNet.Turns
{
    internal static class TurnWireCodec
    {
        private const int MaxStringBytes = 1024;
        private const int MaxPayloadBytes = 1024 * 1024;

        public static byte[] EncodeAction(string actionKind, byte[] payload)
        {
            using (var stream = new MemoryStream())
            using (var writer = new BinaryWriter(stream, Encoding.UTF8))
            {
                WriteString(writer, actionKind);
                WriteBytes(writer, payload ?? Array.Empty<byte>());
                writer.Flush();
                return stream.ToArray();
            }
        }

        public static void DecodeAction(byte[] bytes, out string actionKind, out byte[] payload)
        {
            using (var stream = new MemoryStream(bytes ?? Array.Empty<byte>(), false))
            using (var reader = new BinaryReader(stream, Encoding.UTF8))
            {
                actionKind = ReadString(reader);
                payload = ReadBytes(reader);
                EnsureEnd(stream);
            }
        }

        public static byte[] EncodeCommit(SolarTurnCommit commit)
        {
            using (var stream = new MemoryStream())
            using (var writer = new BinaryWriter(stream, Encoding.UTF8))
            {
                WriteString(writer, commit.ActorId);
                WriteString(writer, commit.ActionKind);
                WriteBytes(writer, commit.Payload);
                WriteString(writer, commit.NextPlayerId);
                writer.Write(commit.NextTurnIndex);
                writer.Write(commit.Round);
                writer.Flush();
                return stream.ToArray();
            }
        }

        public static SolarTurnCommit DecodeCommit(long committedTurnIndex, byte[] bytes)
        {
            using (var stream = new MemoryStream(bytes ?? Array.Empty<byte>(), false))
            using (var reader = new BinaryReader(stream, Encoding.UTF8))
            {
                var actorId = ReadString(reader);
                var actionKind = ReadString(reader);
                var payload = ReadBytes(reader);
                var nextPlayerId = ReadString(reader);
                var nextTurnIndex = reader.ReadInt64();
                var round = reader.ReadInt32();
                EnsureEnd(stream);
                return new SolarTurnCommit(actorId, committedTurnIndex, actionKind, payload, nextPlayerId, nextTurnIndex, round);
            }
        }

        public static byte[] EncodeRejection(SolarTurnRejectReason reason)
        {
            return new[] { (byte)reason };
        }

        public static SolarTurnRejectReason DecodeRejection(byte[] bytes)
        {
            if (bytes == null || bytes.Length != 1) throw new InvalidDataException("Invalid turn rejection payload.");
            return (SolarTurnRejectReason)bytes[0];
        }

        private static void WriteString(BinaryWriter writer, string value)
        {
            var bytes = Encoding.UTF8.GetBytes(value ?? string.Empty);
            if (bytes.Length > MaxStringBytes) throw new InvalidDataException("Turn string field is too large.");
            writer.Write(bytes.Length);
            writer.Write(bytes);
        }

        private static string ReadString(BinaryReader reader)
        {
            var length = reader.ReadInt32();
            if (length < 0 || length > MaxStringBytes) throw new InvalidDataException("Invalid turn string length.");
            var bytes = reader.ReadBytes(length);
            if (bytes.Length != length) throw new EndOfStreamException("Turn string field was truncated.");
            return Encoding.UTF8.GetString(bytes);
        }

        private static void WriteBytes(BinaryWriter writer, byte[] value)
        {
            if (value.Length > MaxPayloadBytes) throw new InvalidDataException("Turn payload is too large.");
            writer.Write(value.Length);
            writer.Write(value);
        }

        private static byte[] ReadBytes(BinaryReader reader)
        {
            var length = reader.ReadInt32();
            if (length < 0 || length > MaxPayloadBytes) throw new InvalidDataException("Invalid turn payload length.");
            var bytes = reader.ReadBytes(length);
            if (bytes.Length != length) throw new EndOfStreamException("Turn payload was truncated.");
            return bytes;
        }

        private static void EnsureEnd(Stream stream)
        {
            if (stream.Position != stream.Length) throw new InvalidDataException("Turn payload contains trailing bytes.");
        }
    }
}
