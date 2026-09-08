using System;
using System.Collections.Generic;
using System.IO;
using System.Text;

namespace SolarNet.Room
{
    internal sealed class SolarRoomJoinRequest
    {
        public SolarRoomJoinRequest(byte protocolVersion, string compatibilityKey, string displayName)
        {
            ProtocolVersion = protocolVersion;
            CompatibilityKey = compatibilityKey;
            DisplayName = displayName;
        }

        public byte ProtocolVersion { get; private set; }
        public string CompatibilityKey { get; private set; }
        public string DisplayName { get; private set; }
    }

    internal static class RoomWireCodec
    {
        private const int MaxStringBytes = 1024;
        private const int MaxPlayers = 16;

        public static byte[] EncodeJoinRequest(byte protocolVersion, string compatibilityKey, string displayName)
        {
            using (var stream = new MemoryStream())
            using (var writer = new BinaryWriter(stream, Encoding.UTF8))
            {
                writer.Write(protocolVersion);
                WriteString(writer, compatibilityKey);
                WriteString(writer, displayName);
                writer.Flush();
                return stream.ToArray();
            }
        }

        public static SolarRoomJoinRequest DecodeJoinRequest(byte[] payload)
        {
            using (var stream = Open(payload))
            using (var reader = new BinaryReader(stream, Encoding.UTF8))
            {
                var protocolVersion = reader.ReadByte();
                var compatibilityKey = ReadString(reader);
                var displayName = ReadString(reader);
                EnsureEnd(stream);
                return new SolarRoomJoinRequest(protocolVersion, compatibilityKey, displayName);
            }
        }

        public static byte[] EncodeState(SolarRoomSnapshot snapshot)
        {
            if (snapshot == null) throw new ArgumentNullException(nameof(snapshot));
            using (var stream = new MemoryStream())
            using (var writer = new BinaryWriter(stream, Encoding.UTF8))
            {
                writer.Write(snapshot.Revision);
                WriteString(writer, snapshot.RoomName);
                WriteString(writer, snapshot.HostPeerId);
                WriteString(writer, snapshot.CompatibilityKey);
                writer.Write(snapshot.MaxPlayers);
                writer.Write((byte)snapshot.Phase);
                WriteString(writer, snapshot.GameSessionId);
                writer.Write(snapshot.Players.Length);
                foreach (var player in snapshot.Players)
                {
                    writer.Write(player.Slot);
                    WriteString(writer, player.PeerId);
                    WriteString(writer, player.DisplayName);
                    writer.Write(player.IsReady);
                    writer.Write(player.IsConnected);
                }
                writer.Flush();
                return stream.ToArray();
            }
        }

        public static SolarRoomSnapshot DecodeState(string roomId, byte[] payload)
        {
            using (var stream = Open(payload))
            using (var reader = new BinaryReader(stream, Encoding.UTF8))
            {
                var revision = reader.ReadInt64();
                var roomName = ReadString(reader);
                var hostPeerId = ReadString(reader);
                var compatibilityKey = ReadString(reader);
                var maxPlayers = reader.ReadInt32();
                var phase = (SolarRoomPhase)reader.ReadByte();
                if (phase != SolarRoomPhase.Lobby && phase != SolarRoomPhase.Playing && phase != SolarRoomPhase.Closed)
                    throw new InvalidDataException("Invalid authoritative room phase: " + phase + ".");
                var gameSessionId = ReadString(reader);
                var playerCount = reader.ReadInt32();
                if (playerCount < 1 || playerCount > MaxPlayers || playerCount > maxPlayers)
                    throw new InvalidDataException("Invalid room player count.");

                var players = new SolarRoomPlayer[playerCount];
                for (var i = 0; i < playerCount; i++)
                {
                    var slot = reader.ReadInt32();
                    var peerId = ReadString(reader);
                    var displayName = ReadString(reader);
                    var isReady = reader.ReadBoolean();
                    var isConnected = reader.ReadBoolean();
                    players[i] = new SolarRoomPlayer(slot, peerId, displayName, isReady, isConnected);
                }
                EnsureEnd(stream);
                return new SolarRoomSnapshot(revision, roomId, roomName, hostPeerId, compatibilityKey, maxPlayers, phase, gameSessionId, players);
            }
        }

        public static byte[] EncodeReady(bool ready)
        {
            return new[] { ready ? (byte)1 : (byte)0 };
        }

        public static bool DecodeReady(byte[] payload)
        {
            if (payload == null || payload.Length != 1 || payload[0] > 1) throw new InvalidDataException("Invalid room-ready payload.");
            return payload[0] == 1;
        }

        public static byte[] EncodeJoinRejected(SolarRoomJoinRejectReason reason)
        {
            return new[] { (byte)reason };
        }

        public static SolarRoomJoinRejectReason DecodeJoinRejected(byte[] payload)
        {
            if (payload == null || payload.Length != 1) throw new InvalidDataException("Invalid room-join-rejected payload.");
            var reason = (SolarRoomJoinRejectReason)payload[0];
            if (reason < SolarRoomJoinRejectReason.ProtocolMismatch || reason > SolarRoomJoinRejectReason.InvalidRequest)
                throw new InvalidDataException("Unknown room join rejection reason.");
            return reason;
        }

        public static byte[] EncodeClose(SolarRoomCloseReason reason)
        {
            return new[] { (byte)reason };
        }

        public static SolarRoomCloseReason DecodeClose(byte[] payload)
        {
            if (payload == null || payload.Length != 1) throw new InvalidDataException("Invalid room-close payload.");
            var reason = (SolarRoomCloseReason)payload[0];
            if (reason < SolarRoomCloseReason.HostDisconnected || reason > SolarRoomCloseReason.LocalLeave)
                throw new InvalidDataException("Unknown room close reason.");
            return reason;
        }

        private static MemoryStream Open(byte[] payload)
        {
            return new MemoryStream(payload ?? Array.Empty<byte>(), false);
        }

        private static void WriteString(BinaryWriter writer, string value)
        {
            var bytes = Encoding.UTF8.GetBytes(value ?? string.Empty);
            if (bytes.Length > MaxStringBytes) throw new InvalidDataException("Room string field is too large.");
            writer.Write(bytes.Length);
            writer.Write(bytes);
        }

        private static string ReadString(BinaryReader reader)
        {
            var length = reader.ReadInt32();
            if (length < 0 || length > MaxStringBytes) throw new InvalidDataException("Invalid room string length.");
            var bytes = reader.ReadBytes(length);
            if (bytes.Length != length) throw new EndOfStreamException("Room string field was truncated.");
            return Encoding.UTF8.GetString(bytes);
        }

        private static void EnsureEnd(Stream stream)
        {
            if (stream.Position != stream.Length) throw new InvalidDataException("Room payload contains trailing bytes.");
        }
    }
}
