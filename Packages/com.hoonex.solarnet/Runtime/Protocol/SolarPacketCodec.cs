using System;
using System.IO;
using System.Text;

namespace SolarNet.Protocol
{
    public static class SolarPacketCodec
    {
        private static readonly byte[] Magic = { (byte)'S', (byte)'N', (byte)'E', (byte)'T' };
        private const int MaxStringBytes = 1024;
        private const int MaxPayloadBytes = 1024 * 1024;

        public static byte[] Encode(SolarPacket packet)
        {
            if (packet == null) throw new ArgumentNullException(nameof(packet));
            if (packet.Payload.Length > MaxPayloadBytes) throw new InvalidDataException("Packet payload is too large.");

            using (var stream = new MemoryStream())
            using (var writer = new BinaryWriter(stream, Encoding.UTF8))
            {
                writer.Write(Magic);
                writer.Write(packet.ProtocolVersion);
                writer.Write((byte)packet.Type);
                WriteString(writer, packet.SessionId);
                WriteString(writer, packet.SenderId);
                writer.Write(packet.Sequence);
                writer.Write(packet.TurnIndex);
                WriteString(writer, packet.StateHash);
                writer.Write(packet.Payload.Length);
                writer.Write(packet.Payload);
                writer.Flush();
                return stream.ToArray();
            }
        }

        public static SolarPacket Decode(byte[] frame)
        {
            if (frame == null) throw new ArgumentNullException(nameof(frame));

            using (var stream = new MemoryStream(frame, false))
            using (var reader = new BinaryReader(stream, Encoding.UTF8))
            {
                var magic = reader.ReadBytes(Magic.Length);
                if (magic.Length != Magic.Length || !MatchesMagic(magic))
                    throw new InvalidDataException("Not a SolarNet frame.");

                var version = reader.ReadByte();
                if (version != SolarPacket.CurrentProtocolVersion)
                    throw new InvalidDataException("Unsupported SolarNet protocol version: " + version + ".");

                var type = (SolarPacketType)reader.ReadByte();
                var sessionId = ReadString(reader);
                var senderId = ReadString(reader);
                var sequence = reader.ReadInt64();
                var turnIndex = reader.ReadInt64();
                var stateHash = ReadString(reader);
                var payloadLength = reader.ReadInt32();
                if (payloadLength < 0 || payloadLength > MaxPayloadBytes)
                    throw new InvalidDataException("Invalid SolarNet payload length.");

                var payload = reader.ReadBytes(payloadLength);
                if (payload.Length != payloadLength)
                    throw new EndOfStreamException("SolarNet payload was truncated.");
                if (stream.Position != stream.Length)
                    throw new InvalidDataException("SolarNet frame contains trailing bytes.");

                return new SolarPacket(type, sessionId, senderId, sequence, turnIndex, payload, stateHash);
            }
        }

        private static bool MatchesMagic(byte[] value)
        {
            for (var i = 0; i < Magic.Length; i++)
                if (value[i] != Magic[i]) return false;
            return true;
        }

        private static void WriteString(BinaryWriter writer, string value)
        {
            var bytes = Encoding.UTF8.GetBytes(value ?? string.Empty);
            if (bytes.Length > MaxStringBytes) throw new InvalidDataException("SolarNet string field is too large.");
            writer.Write(bytes.Length);
            writer.Write(bytes);
        }

        private static string ReadString(BinaryReader reader)
        {
            var length = reader.ReadInt32();
            if (length < 0 || length > MaxStringBytes) throw new InvalidDataException("Invalid SolarNet string length.");
            var bytes = reader.ReadBytes(length);
            if (bytes.Length != length) throw new EndOfStreamException("SolarNet string field was truncated.");
            return Encoding.UTF8.GetString(bytes);
        }
    }
}
