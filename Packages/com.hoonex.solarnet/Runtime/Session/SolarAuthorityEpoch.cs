using System;
using System.Collections.Generic;
using System.IO;
using System.Text;
using SolarNet.Room;
using SolarNet.State;
using SolarNet.Transport;
using SolarNet.Turns;

namespace SolarNet.Session
{
    public sealed class SolarAuthorityEpochRecord
    {
        public SolarAuthorityEpochRecord(
            SolarRoomSnapshot roomSnapshot,
            SolarAuthorityCheckpoint gameCheckpoint,
            string requiredReplicationPeerId = null)
        {
            if (roomSnapshot == null) throw new ArgumentNullException(nameof(roomSnapshot));
            if (gameCheckpoint == null) throw new ArgumentNullException(nameof(gameCheckpoint));

            var room = CloneRoom(roomSnapshot);
            Validate(room, gameCheckpoint, requiredReplicationPeerId);

            RoomSnapshot = room;
            GameCheckpoint = new SolarAuthorityCheckpoint(
                gameCheckpoint.SourceSessionId,
                gameCheckpoint.PlayerIds,
                gameCheckpoint.NextTurnIndex,
                gameCheckpoint.CurrentPlayerId,
                gameCheckpoint.Round,
                gameCheckpoint.StateHash,
                gameCheckpoint.State);
            RequiredReplicationPeerId = requiredReplicationPeerId ?? string.Empty;
        }

        public SolarRoomSnapshot RoomSnapshot { get; private set; }
        public SolarAuthorityCheckpoint GameCheckpoint { get; private set; }
        public string RequiredReplicationPeerId { get; private set; }

        private static SolarRoomSnapshot CloneRoom(SolarRoomSnapshot value)
        {
            var players = new SolarRoomPlayer[value.Players.Length];
            for (var i = 0; i < players.Length; i++)
            {
                var player = value.Players[i];
                players[i] = new SolarRoomPlayer(
                    player.Slot,
                    player.PeerId,
                    player.DisplayName,
                    player.IsReady,
                    player.IsConnected);
            }

            return new SolarRoomSnapshot(
                value.Revision,
                value.RoomId,
                value.RoomName,
                value.HostPeerId,
                value.CompatibilityKey,
                value.MaxPlayers,
                value.Phase,
                value.GameSessionId,
                players);
        }

        private static void Validate(
            SolarRoomSnapshot room,
            SolarAuthorityCheckpoint checkpoint,
            string requiredReplicationPeerId)
        {
            if (room.Phase != SolarRoomPhase.Playing)
                throw new ArgumentException("Persisted authority epoch requires a Playing room snapshot.", nameof(room));
            if (string.IsNullOrWhiteSpace(room.GameSessionId))
                throw new ArgumentException("Persisted authority epoch requires a game session ID.", nameof(room));
            if (!string.Equals(room.GameSessionId, checkpoint.SourceSessionId, StringComparison.Ordinal))
                throw new ArgumentException("Room game session ID does not match the authority checkpoint epoch.", nameof(checkpoint));
            if (room.Players == null || room.Players.Length < 2)
                throw new ArgumentException("Persisted authority epoch requires at least two room players.", nameof(room));

            var ordered = (SolarRoomPlayer[])room.Players.Clone();
            Array.Sort(ordered, (left, right) => left.Slot.CompareTo(right.Slot));
            if (ordered.Length != checkpoint.PlayerIds.Length)
                throw new ArgumentException("Room roster does not match checkpoint player order.", nameof(checkpoint));

            var peerIds = new HashSet<string>(StringComparer.Ordinal);
            var slots = new HashSet<int>();
            var hostFound = false;
            for (var i = 0; i < ordered.Length; i++)
            {
                var player = ordered[i];
                if (!peerIds.Add(player.PeerId))
                    throw new ArgumentException("Persisted room contains a duplicate peer ID: " + player.PeerId, nameof(room));
                if (!slots.Add(player.Slot))
                    throw new ArgumentException("Persisted room contains a duplicate player slot: " + player.Slot, nameof(room));
                if (!string.Equals(player.PeerId, checkpoint.PlayerIds[i], StringComparison.Ordinal))
                    throw new ArgumentException("Persisted room slot order does not match checkpoint player order.", nameof(checkpoint));
                if (string.Equals(player.PeerId, room.HostPeerId, StringComparison.Ordinal)) hostFound = true;
            }
            if (!hostFound)
                throw new ArgumentException("Persisted room roster does not contain its authority peer.", nameof(room));

            if (!string.IsNullOrWhiteSpace(requiredReplicationPeerId))
            {
                if (string.Equals(requiredReplicationPeerId, room.HostPeerId, StringComparison.Ordinal))
                    throw new ArgumentException("Authority peer cannot be its own required replica.", nameof(requiredReplicationPeerId));
                if (!peerIds.Contains(requiredReplicationPeerId))
                    throw new ArgumentException("Required replica is not part of the persisted room roster.", nameof(requiredReplicationPeerId));
            }
        }
    }

    public static class SolarAuthorityEpochPersistence
    {
        public static SolarAuthorityEpochRecord Capture(
            SolarRoomSnapshot roomSnapshot,
            SolarTurnSession authoritativeSession,
            ISolarGameStateMachine gameStateMachine)
        {
            if (roomSnapshot == null) throw new ArgumentNullException(nameof(roomSnapshot));
            if (authoritativeSession == null) throw new ArgumentNullException(nameof(authoritativeSession));
            if (gameStateMachine == null) throw new ArgumentNullException(nameof(gameStateMachine));
            if (!authoritativeSession.IsHost)
                throw new InvalidOperationException("Only the current authoritative host can persist an authority epoch.");
            if (!authoritativeSession.StateIntegrityEnabled)
                throw new InvalidOperationException("Authority epoch persistence requires deterministic state integrity.");
            if (roomSnapshot.Phase != SolarRoomPhase.Playing)
                throw new InvalidOperationException("Authority epoch persistence requires a Playing room.");
            if (!string.Equals(roomSnapshot.HostPeerId, authoritativeSession.HostPeerId, StringComparison.Ordinal) ||
                !string.Equals(roomSnapshot.HostPeerId, authoritativeSession.LocalPeerId, StringComparison.Ordinal))
                throw new InvalidOperationException("Room authority identity does not match the authoritative session.");
            if (!string.Equals(roomSnapshot.GameSessionId, authoritativeSession.SessionId, StringComparison.Ordinal))
                throw new InvalidOperationException("Room and game session epochs do not match.");

            if (authoritativeSession.DurabilityBarrierEnabled)
            {
                if (authoritativeSession.DurabilityPending)
                    throw new InvalidOperationException("Cannot persist a provisional authority turn while durability proof is pending.");
                if (authoritativeSession.DurableNextTurnIndex != authoritativeSession.KnownNextTurnIndex)
                    throw new InvalidOperationException("Cannot persist authority state beyond the designated-replica durability frontier.");
                if (!authoritativeSession.IsReplicatedThrough(
                    authoritativeSession.RequiredReplicationPeerId,
                    authoritativeSession.KnownNextTurnIndex))
                    throw new InvalidOperationException("Required replica has not proven the authority state being persisted.");
            }

            var players = (SolarRoomPlayer[])roomSnapshot.Players.Clone();
            Array.Sort(players, (left, right) => left.Slot.CompareTo(right.Slot));
            var playerIds = new string[players.Length];
            for (var i = 0; i < players.Length; i++) playerIds[i] = players[i].PeerId;

            var checkpoint = SolarAuthorityPromotion.Capture(
                authoritativeSession,
                playerIds,
                gameStateMachine);
            return new SolarAuthorityEpochRecord(
                roomSnapshot,
                checkpoint,
                authoritativeSession.RequiredReplicationPeerId);
        }
    }

    public sealed class SolarAuthorityEpochBootstrap
    {
        internal SolarAuthorityEpochBootstrap(
            SolarRoomSession roomSession,
            SolarTurnSession gameSession,
            SolarGameStartInfo gameStart)
        {
            RoomSession = roomSession ?? throw new ArgumentNullException(nameof(roomSession));
            GameSession = gameSession ?? throw new ArgumentNullException(nameof(gameSession));
            GameStart = gameStart ?? throw new ArgumentNullException(nameof(gameStart));
        }

        public SolarRoomSession RoomSession { get; private set; }
        public SolarTurnSession GameSession { get; private set; }
        public SolarGameStartInfo GameStart { get; private set; }
    }

    public static class SolarAuthorityEpochResume
    {
        public static SolarAuthorityEpochBootstrap Create(
            SolarAuthorityEpochRecord record,
            ISolarTransport transport,
            ISolarGameStateMachine gameStateMachine,
            SolarHostDisconnectPolicy hostDisconnectPolicy = SolarHostDisconnectPolicy.WaitForReconnect,
            int journalCapacity = 256)
        {
            if (record == null) throw new ArgumentNullException(nameof(record));
            if (transport == null) throw new ArgumentNullException(nameof(transport));
            if (gameStateMachine == null) throw new ArgumentNullException(nameof(gameStateMachine));
            if (journalCapacity < 1) throw new ArgumentOutOfRangeException(nameof(journalCapacity));
            if (!string.Equals(transport.LocalPeerId, record.RoomSnapshot.HostPeerId, StringComparison.Ordinal))
                throw new InvalidOperationException("Only the persisted authority peer can resume this authority epoch.");

            var checkpoint = record.GameCheckpoint;
            gameStateMachine.RestoreSnapshot(SolarAuthorityCheckpoint.Clone(checkpoint.State));
            var restored = gameStateMachine.CaptureSnapshot();
            if (restored == null)
                throw new InvalidOperationException("ISolarGameStateMachine.CaptureSnapshot returned null after authority epoch restore.");
            var restoredHash = SolarStateDigest.Compute(restored);
            if (!string.Equals(restoredHash, checkpoint.StateHash, StringComparison.Ordinal))
                throw new InvalidOperationException("Restored authority epoch state does not reproduce the persisted digest.");

            var room = SolarRoomAuthorityResume.CreateSession(
                record.RoomSnapshot,
                transport,
                hostDisconnectPolicy);
            var coordinator = TurnCoordinator.Restore(
                checkpoint.PlayerIds,
                checkpoint.NextTurnIndex,
                checkpoint.CurrentPlayerId,
                checkpoint.Round);
            var game = new SolarTurnSession(
                record.RoomSnapshot.GameSessionId,
                record.RoomSnapshot.HostPeerId,
                transport,
                coordinator,
                gameStateMachine,
                journalCapacity,
                string.IsNullOrWhiteSpace(record.RequiredReplicationPeerId)
                    ? null
                    : record.RequiredReplicationPeerId);
            var start = new SolarGameStartInfo(
                record.RoomSnapshot.GameSessionId,
                record.RoomSnapshot.HostPeerId,
                checkpoint.PlayerIds);
            return new SolarAuthorityEpochBootstrap(room, game, start);
        }
    }

    public static class SolarAuthorityEpochCodec
    {
        private static readonly byte[] Magic = { (byte)'S', (byte)'N', (byte)'A', (byte)'E' };
        private const byte Version = 1;
        private const int MaxStringBytes = 4096;
        private const int MaxStateBytes = 1024 * 1024;
        private const int MaxBodyBytes = MaxStateBytes + (128 * 1024);

        public static byte[] Encode(SolarAuthorityEpochRecord record)
        {
            if (record == null) throw new ArgumentNullException(nameof(record));
            byte[] body;
            using (var bodyStream = new MemoryStream())
            using (var writer = new BinaryWriter(bodyStream, Encoding.UTF8))
            {
                WriteRoom(writer, record.RoomSnapshot);
                WriteCheckpoint(writer, record.GameCheckpoint);
                WriteString(writer, record.RequiredReplicationPeerId);
                writer.Flush();
                body = bodyStream.ToArray();
            }
            if (body.Length > MaxBodyBytes) throw new InvalidDataException("Authority epoch record is too large.");
            var digest = SolarStateDigest.Compute(body);

            using (var stream = new MemoryStream())
            using (var writer = new BinaryWriter(stream, Encoding.UTF8))
            {
                writer.Write(Magic);
                writer.Write(Version);
                writer.Write(body.Length);
                writer.Write(body);
                WriteString(writer, digest);
                writer.Flush();
                return stream.ToArray();
            }
        }

        public static SolarAuthorityEpochRecord Decode(byte[] bytes)
        {
            if (bytes == null) throw new ArgumentNullException(nameof(bytes));
            using (var stream = new MemoryStream(bytes, false))
            using (var reader = new BinaryReader(stream, Encoding.UTF8))
            {
                var magic = reader.ReadBytes(Magic.Length);
                if (magic.Length != Magic.Length || !MatchesMagic(magic))
                    throw new InvalidDataException("Not a SolarNet authority epoch record.");
                var version = reader.ReadByte();
                if (version != Version)
                    throw new InvalidDataException("Unsupported authority epoch record version: " + version + ".");
                var bodyLength = reader.ReadInt32();
                if (bodyLength < 0 || bodyLength > MaxBodyBytes)
                    throw new InvalidDataException("Invalid authority epoch body length.");
                var body = reader.ReadBytes(bodyLength);
                if (body.Length != bodyLength)
                    throw new EndOfStreamException("Authority epoch body was truncated.");
                var digest = ReadString(reader);
                if (stream.Position != stream.Length)
                    throw new InvalidDataException("Authority epoch record contains trailing bytes.");
                var computed = SolarStateDigest.Compute(body);
                if (!string.Equals(computed, digest, StringComparison.Ordinal))
                    throw new InvalidDataException("Authority epoch record digest does not match its body.");

                using (var bodyStream = new MemoryStream(body, false))
                using (var bodyReader = new BinaryReader(bodyStream, Encoding.UTF8))
                {
                    var room = ReadRoom(bodyReader);
                    var checkpoint = ReadCheckpoint(bodyReader);
                    var requiredReplica = ReadString(bodyReader);
                    if (bodyStream.Position != bodyStream.Length)
                        throw new InvalidDataException("Authority epoch body contains trailing bytes.");
                    return new SolarAuthorityEpochRecord(
                        room,
                        checkpoint,
                        string.IsNullOrWhiteSpace(requiredReplica) ? null : requiredReplica);
                }
            }
        }

        private static void WriteRoom(BinaryWriter writer, SolarRoomSnapshot room)
        {
            writer.Write(room.Revision);
            WriteString(writer, room.RoomId);
            WriteString(writer, room.RoomName);
            WriteString(writer, room.HostPeerId);
            WriteString(writer, room.CompatibilityKey);
            writer.Write(room.MaxPlayers);
            writer.Write((byte)room.Phase);
            WriteString(writer, room.GameSessionId);
            if (room.Players.Length > 16) throw new InvalidDataException("Authority epoch room roster is too large.");
            writer.Write(room.Players.Length);
            for (var i = 0; i < room.Players.Length; i++)
            {
                var player = room.Players[i];
                writer.Write(player.Slot);
                WriteString(writer, player.PeerId);
                WriteString(writer, player.DisplayName);
                writer.Write(player.IsReady);
                writer.Write(player.IsConnected);
            }
        }

        private static SolarRoomSnapshot ReadRoom(BinaryReader reader)
        {
            var revision = reader.ReadInt64();
            var roomId = ReadString(reader);
            var roomName = ReadString(reader);
            var hostPeerId = ReadString(reader);
            var compatibilityKey = ReadString(reader);
            var maxPlayers = reader.ReadInt32();
            var phase = (SolarRoomPhase)reader.ReadByte();
            var gameSessionId = ReadString(reader);
            var count = reader.ReadInt32();
            if (count < 2 || count > 16) throw new InvalidDataException("Invalid authority epoch room roster size.");
            var players = new SolarRoomPlayer[count];
            for (var i = 0; i < count; i++)
            {
                players[i] = new SolarRoomPlayer(
                    reader.ReadInt32(),
                    ReadString(reader),
                    ReadString(reader),
                    reader.ReadBoolean(),
                    reader.ReadBoolean());
            }
            return new SolarRoomSnapshot(
                revision,
                roomId,
                roomName,
                hostPeerId,
                compatibilityKey,
                maxPlayers,
                phase,
                gameSessionId,
                players);
        }

        private static void WriteCheckpoint(BinaryWriter writer, SolarAuthorityCheckpoint checkpoint)
        {
            WriteString(writer, checkpoint.SourceSessionId);
            if (checkpoint.PlayerIds.Length > 16) throw new InvalidDataException("Authority checkpoint player order is too large.");
            writer.Write(checkpoint.PlayerIds.Length);
            for (var i = 0; i < checkpoint.PlayerIds.Length; i++) WriteString(writer, checkpoint.PlayerIds[i]);
            writer.Write(checkpoint.NextTurnIndex);
            WriteString(writer, checkpoint.CurrentPlayerId);
            writer.Write(checkpoint.Round);
            WriteString(writer, checkpoint.StateHash);
            var state = checkpoint.State ?? Array.Empty<byte>();
            if (state.Length > MaxStateBytes) throw new InvalidDataException("Authority checkpoint state is too large.");
            writer.Write(state.Length);
            writer.Write(state);
        }

        private static SolarAuthorityCheckpoint ReadCheckpoint(BinaryReader reader)
        {
            var sessionId = ReadString(reader);
            var count = reader.ReadInt32();
            if (count < 2 || count > 16) throw new InvalidDataException("Invalid authority checkpoint player count.");
            var players = new string[count];
            for (var i = 0; i < count; i++) players[i] = ReadString(reader);
            var nextTurn = reader.ReadInt64();
            var currentPlayer = ReadString(reader);
            var round = reader.ReadInt32();
            var stateHash = ReadString(reader);
            var stateLength = reader.ReadInt32();
            if (stateLength < 0 || stateLength > MaxStateBytes) throw new InvalidDataException("Invalid authority checkpoint state length.");
            var state = reader.ReadBytes(stateLength);
            if (state.Length != stateLength) throw new EndOfStreamException("Authority checkpoint state was truncated.");
            return new SolarAuthorityCheckpoint(
                sessionId,
                players,
                nextTurn,
                currentPlayer,
                round,
                stateHash,
                state);
        }

        private static void WriteString(BinaryWriter writer, string value)
        {
            var data = Encoding.UTF8.GetBytes(value ?? string.Empty);
            if (data.Length > MaxStringBytes) throw new InvalidDataException("Authority epoch string field is too large.");
            writer.Write(data.Length);
            writer.Write(data);
        }

        private static string ReadString(BinaryReader reader)
        {
            var length = reader.ReadInt32();
            if (length < 0 || length > MaxStringBytes) throw new InvalidDataException("Invalid authority epoch string length.");
            var data = reader.ReadBytes(length);
            if (data.Length != length) throw new EndOfStreamException("Authority epoch string field was truncated.");
            return Encoding.UTF8.GetString(data);
        }

        private static bool MatchesMagic(byte[] value)
        {
            for (var i = 0; i < Magic.Length; i++) if (value[i] != Magic[i]) return false;
            return true;
        }
    }
}
