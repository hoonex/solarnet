using System;
using System.IO;
using System.Text;

namespace SolarNet.BluetoothClassic
{
    internal enum BluetoothClassicEnvelopeType : byte
    {
        Hello = 1,
        Data = 2
    }

    internal sealed class BluetoothClassicEnvelope
    {
        public BluetoothClassicEnvelope(BluetoothClassicEnvelopeType type, string peerId, byte[] data)
        {
            Type = type;
            PeerId = peerId;
            Data = data ?? Array.Empty<byte>();
        }

        public BluetoothClassicEnvelopeType Type { get; private set; }
        public string PeerId { get; private set; }
        public byte[] Data { get; private set; }
    }

    internal static class BluetoothClassicEnvelopeCodec
    {
        private static readonly byte[] Magic = { (byte)'S', (byte)'B', (byte)'T', (byte)'C' };
        private const int MaxPeerIdBytes = 512;
        private const int MaxDataBytes = 1024 * 1024;

        public static byte[] EncodeHello(string peerId)
        {
            return Encode(BluetoothClassicEnvelopeType.Hello, peerId, Array.Empty<byte>());
        }

        public static byte[] EncodeData(string peerId, byte[] data)
        {
            return Encode(BluetoothClassicEnvelopeType.Data, peerId, data ?? Array.Empty<byte>());
        }

        public static BluetoothClassicEnvelope Decode(byte[] bytes)
        {
            if (bytes == null) throw new ArgumentNullException(nameof(bytes));
            using (var stream = new MemoryStream(bytes, false))
            using (var reader = new BinaryReader(stream, Encoding.UTF8))
            {
                var magic = reader.ReadBytes(4);
                if (magic.Length != 4 || magic[0] != Magic[0] || magic[1] != Magic[1] || magic[2] != Magic[2] || magic[3] != Magic[3])
                    throw new InvalidDataException("Not a SolarNet Bluetooth Classic envelope.");
                var type = (BluetoothClassicEnvelopeType)reader.ReadByte();
                if (type != BluetoothClassicEnvelopeType.Hello && type != BluetoothClassicEnvelopeType.Data)
                    throw new InvalidDataException("Unknown Bluetooth Classic envelope type.");
                var peerId = ReadString(reader);
                if (string.IsNullOrWhiteSpace(peerId)) throw new InvalidDataException("Bluetooth Classic envelope peer ID is empty.");
                var length = reader.ReadInt32();
                if (length < 0 || length > MaxDataBytes) throw new InvalidDataException("Invalid Bluetooth Classic envelope length.");
                var data = reader.ReadBytes(length);
                if (data.Length != length) throw new EndOfStreamException("Bluetooth Classic envelope was truncated.");
                if (stream.Position != stream.Length) throw new InvalidDataException("Bluetooth Classic envelope contains trailing bytes.");
                if (type == BluetoothClassicEnvelopeType.Hello && data.Length != 0) throw new InvalidDataException("Bluetooth Classic hello contains data.");
                return new BluetoothClassicEnvelope(type, peerId, data);
            }
        }

        private static byte[] Encode(BluetoothClassicEnvelopeType type, string peerId, byte[] data)
        {
            if (string.IsNullOrWhiteSpace(peerId)) throw new ArgumentException("Peer ID is required.", nameof(peerId));
            if (data.Length > MaxDataBytes) throw new InvalidDataException("Bluetooth Classic envelope is too large.");
            using (var stream = new MemoryStream())
            using (var writer = new BinaryWriter(stream, Encoding.UTF8))
            {
                writer.Write(Magic);
                writer.Write((byte)type);
                WriteString(writer, peerId);
                writer.Write(data.Length);
                writer.Write(data);
                writer.Flush();
                return stream.ToArray();
            }
        }

        private static void WriteString(BinaryWriter writer, string value)
        {
            var bytes = Encoding.UTF8.GetBytes(value);
            if (bytes.Length > MaxPeerIdBytes) throw new InvalidDataException("Peer ID is too large.");
            writer.Write(bytes.Length);
            writer.Write(bytes);
        }

        private static string ReadString(BinaryReader reader)
        {
            var length = reader.ReadInt32();
            if (length < 0 || length > MaxPeerIdBytes) throw new InvalidDataException("Invalid peer ID length.");
            var bytes = reader.ReadBytes(length);
            if (bytes.Length != length) throw new EndOfStreamException("Peer ID was truncated.");
            return Encoding.UTF8.GetString(bytes);
        }
    }
}
