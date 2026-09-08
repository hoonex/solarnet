using System;
using System.IO;
using System.Text;

namespace SolarNet.Nearby
{
    internal enum NearbyEnvelopeType : byte
    {
        Hello = 1,
        Data = 2
    }

    internal sealed class NearbyEnvelope
    {
        public NearbyEnvelope(NearbyEnvelopeType type, string peerId, byte[] data)
        {
            Type = type;
            PeerId = peerId;
            Data = data ?? Array.Empty<byte>();
        }

        public NearbyEnvelopeType Type { get; private set; }
        public string PeerId { get; private set; }
        public byte[] Data { get; private set; }
    }

    internal static class NearbyEnvelopeCodec
    {
        public const int MaxNearbyBytesPayload = 1047552;
        private const byte Version = 1;
        private const int MaxPeerIdBytes = 512;
        private static readonly byte[] Magic = { (byte)'S', (byte)'N', (byte)'B', (byte)'Y' };

        public static byte[] EncodeHello(string peerId)
        {
            return Encode(NearbyEnvelopeType.Hello, peerId, Array.Empty<byte>());
        }

        public static byte[] EncodeData(string peerId, byte[] frame)
        {
            return Encode(NearbyEnvelopeType.Data, peerId, frame ?? Array.Empty<byte>());
        }

        public static NearbyEnvelope Decode(byte[] payload)
        {
            if (payload == null) throw new ArgumentNullException(nameof(payload));
            if (payload.Length > MaxNearbyBytesPayload) throw new InvalidDataException("Nearby payload exceeds the Google Nearby BYTES limit.");

            using (var stream = new MemoryStream(payload, false))
            {
                var magic = ReadExact(stream, Magic.Length);
                for (var i = 0; i < Magic.Length; i++)
                    if (magic[i] != Magic[i]) throw new InvalidDataException("Not a SolarNet Nearby envelope.");

                var version = ReadByte(stream);
                if (version != Version) throw new InvalidDataException("Unsupported SolarNet Nearby envelope version: " + version + ".");

                var rawType = ReadByte(stream);
                if (rawType != (byte)NearbyEnvelopeType.Hello && rawType != (byte)NearbyEnvelopeType.Data)
                    throw new InvalidDataException("Unsupported SolarNet Nearby envelope type: " + rawType + ".");

                var peerIdLength = ReadUInt16(stream);
                if (peerIdLength <= 0 || peerIdLength > MaxPeerIdBytes) throw new InvalidDataException("Invalid Nearby peer ID length.");
                var peerId = Encoding.UTF8.GetString(ReadExact(stream, peerIdLength));
                if (string.IsNullOrWhiteSpace(peerId)) throw new InvalidDataException("Nearby envelope peer ID is empty.");

                var type = (NearbyEnvelopeType)rawType;
                if (type == NearbyEnvelopeType.Hello)
                {
                    if (stream.Position != stream.Length) throw new InvalidDataException("Nearby HELLO envelope contains trailing bytes.");
                    return new NearbyEnvelope(type, peerId, Array.Empty<byte>());
                }

                var frameLength = ReadInt32(stream);
                if (frameLength < 0 || frameLength > MaxNearbyBytesPayload) throw new InvalidDataException("Invalid Nearby frame length.");
                var frame = ReadExact(stream, frameLength);
                if (stream.Position != stream.Length) throw new InvalidDataException("Nearby DATA envelope contains trailing bytes.");
                return new NearbyEnvelope(type, peerId, frame);
            }
        }

        private static byte[] Encode(NearbyEnvelopeType type, string peerId, byte[] data)
        {
            if (string.IsNullOrWhiteSpace(peerId)) throw new ArgumentException("Peer ID is required.", nameof(peerId));
            var peerBytes = Encoding.UTF8.GetBytes(peerId);
            if (peerBytes.Length > MaxPeerIdBytes) throw new InvalidDataException("Nearby peer ID is too large.");

            using (var stream = new MemoryStream())
            {
                stream.Write(Magic, 0, Magic.Length);
                stream.WriteByte(Version);
                stream.WriteByte((byte)type);
                WriteUInt16(stream, peerBytes.Length);
                stream.Write(peerBytes, 0, peerBytes.Length);
                if (type == NearbyEnvelopeType.Data)
                {
                    WriteInt32(stream, data.Length);
                    stream.Write(data, 0, data.Length);
                }

                if (stream.Length > MaxNearbyBytesPayload)
                    throw new InvalidDataException("SolarNet frame is too large for a Nearby BYTES payload.");
                return stream.ToArray();
            }
        }

        private static int ReadByte(Stream stream)
        {
            var value = stream.ReadByte();
            if (value < 0) throw new EndOfStreamException("Nearby envelope was truncated.");
            return value;
        }

        private static int ReadUInt16(Stream stream)
        {
            var a = ReadByte(stream);
            var b = ReadByte(stream);
            return (a << 8) | b;
        }

        private static int ReadInt32(Stream stream)
        {
            var a = ReadByte(stream);
            var b = ReadByte(stream);
            var c = ReadByte(stream);
            var d = ReadByte(stream);
            return (a << 24) | (b << 16) | (c << 8) | d;
        }

        private static void WriteUInt16(Stream stream, int value)
        {
            if (value < 0 || value > ushort.MaxValue) throw new ArgumentOutOfRangeException(nameof(value));
            stream.WriteByte((byte)((value >> 8) & 0xff));
            stream.WriteByte((byte)(value & 0xff));
        }

        private static void WriteInt32(Stream stream, int value)
        {
            if (value < 0) throw new ArgumentOutOfRangeException(nameof(value));
            stream.WriteByte((byte)((value >> 24) & 0xff));
            stream.WriteByte((byte)((value >> 16) & 0xff));
            stream.WriteByte((byte)((value >> 8) & 0xff));
            stream.WriteByte((byte)(value & 0xff));
        }

        private static byte[] ReadExact(Stream stream, int length)
        {
            var buffer = new byte[length];
            var offset = 0;
            while (offset < length)
            {
                var read = stream.Read(buffer, offset, length - offset);
                if (read <= 0) throw new EndOfStreamException("Nearby envelope was truncated.");
                offset += read;
            }
            return buffer;
        }
    }
}
