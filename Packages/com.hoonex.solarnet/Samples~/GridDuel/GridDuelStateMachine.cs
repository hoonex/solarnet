using System;
using System.IO;
using System.Text;
using SolarNet.State;

namespace SolarNet.Samples.GridDuel
{
    public sealed class GridDuelPlayerState
    {
        internal GridDuelPlayerState(string peerId, int x, int y, int health)
        {
            PeerId = peerId;
            X = x;
            Y = y;
            Health = health;
        }

        public string PeerId { get; internal set; }
        public int X { get; internal set; }
        public int Y { get; internal set; }
        public int Health { get; internal set; }
        public bool IsAlive { get { return Health > 0; } }
    }

    public sealed class GridDuelStateMachine : ISolarGameStateMachine
    {
        public const int BoardWidth = 5;
        public const int BoardHeight = 5;
        public const int StartingHealth = 3;
        private const int SnapshotVersion = 1;
        private readonly string[] _playerIds;
        private readonly GridDuelPlayerState[] _players;

        public GridDuelStateMachine(string firstPeerId, string secondPeerId)
        {
            if (string.IsNullOrWhiteSpace(firstPeerId)) throw new ArgumentException("First peer ID is required.", nameof(firstPeerId));
            if (string.IsNullOrWhiteSpace(secondPeerId)) throw new ArgumentException("Second peer ID is required.", nameof(secondPeerId));
            if (string.Equals(firstPeerId, secondPeerId, StringComparison.Ordinal)) throw new ArgumentException("Grid Duel requires two unique peer IDs.");

            _playerIds = new[] { firstPeerId, secondPeerId };
            _players = new[]
            {
                new GridDuelPlayerState(firstPeerId, 0, BoardHeight / 2, StartingHealth),
                new GridDuelPlayerState(secondPeerId, BoardWidth - 1, BoardHeight / 2, StartingHealth)
            };
        }

        public string WinnerPeerId { get; private set; } = string.Empty;
        public bool IsFinished { get { return !string.IsNullOrEmpty(WinnerPeerId); } }

        public GridDuelPlayerState GetPlayer(string peerId)
        {
            var index = FindPlayerIndex(peerId);
            if (index < 0) return null;
            return Clone(_players[index]);
        }

        public GridDuelPlayerState GetPlayerAt(int x, int y)
        {
            for (var i = 0; i < _players.Length; i++)
            {
                var player = _players[i];
                if (player.IsAlive && player.X == x && player.Y == y) return Clone(player);
            }
            return null;
        }

        public bool TryApply(SolarGameAction action)
        {
            if (action == null || IsFinished) return false;
            var actorIndex = FindPlayerIndex(action.ActorId);
            if (actorIndex < 0) return false;
            var actor = _players[actorIndex];
            if (!actor.IsAlive) return false;

            if (string.Equals(action.ActionKind, GridDuelActionCodec.MoveAction, StringComparison.Ordinal))
                return TryMove(actorIndex, action.Payload);
            if (string.Equals(action.ActionKind, GridDuelActionCodec.AttackAction, StringComparison.Ordinal))
                return TryAttack(actorIndex, action.Payload);
            return false;
        }

        public byte[] CaptureSnapshot()
        {
            using (var stream = new MemoryStream())
            using (var writer = new BinaryWriter(stream, Encoding.UTF8))
            {
                writer.Write(SnapshotVersion);
                for (var i = 0; i < _players.Length; i++)
                {
                    WriteString(writer, _players[i].PeerId);
                    writer.Write(_players[i].X);
                    writer.Write(_players[i].Y);
                    writer.Write(_players[i].Health);
                }
                WriteString(writer, WinnerPeerId ?? string.Empty);
                writer.Flush();
                return stream.ToArray();
            }
        }

        public void RestoreSnapshot(byte[] snapshot)
        {
            if (snapshot == null) throw new ArgumentNullException(nameof(snapshot));
            var restored = new GridDuelPlayerState[2];
            string winner;
            using (var stream = new MemoryStream(snapshot, false))
            using (var reader = new BinaryReader(stream, Encoding.UTF8))
            {
                var version = reader.ReadInt32();
                if (version != SnapshotVersion) throw new InvalidDataException("Unsupported Grid Duel snapshot version: " + version + ".");
                for (var i = 0; i < restored.Length; i++)
                {
                    var peerId = ReadString(reader);
                    if (!string.Equals(peerId, _playerIds[i], StringComparison.Ordinal))
                        throw new InvalidDataException("Grid Duel snapshot player order does not match this match.");
                    restored[i] = new GridDuelPlayerState(peerId, reader.ReadInt32(), reader.ReadInt32(), reader.ReadInt32());
                }
                winner = ReadString(reader);
                if (stream.Position != stream.Length) throw new InvalidDataException("Grid Duel snapshot contains trailing bytes.");
            }

            ValidateSnapshot(restored, winner);
            for (var i = 0; i < _players.Length; i++)
            {
                _players[i].X = restored[i].X;
                _players[i].Y = restored[i].Y;
                _players[i].Health = restored[i].Health;
            }
            WinnerPeerId = winner;
        }

        private bool TryMove(int actorIndex, byte[] payload)
        {
            int targetX;
            int targetY;
            if (!GridDuelActionCodec.TryDecodeMove(payload, out targetX, out targetY)) return false;
            if (!IsInsideBoard(targetX, targetY)) return false;

            var actor = _players[actorIndex];
            if (Manhattan(actor.X, actor.Y, targetX, targetY) != 1) return false;
            if (GetLivePlayerIndexAt(targetX, targetY) >= 0) return false;

            actor.X = targetX;
            actor.Y = targetY;
            return true;
        }

        private bool TryAttack(int actorIndex, byte[] payload)
        {
            if (payload != null && payload.Length != 0) return false;
            var opponentIndex = actorIndex == 0 ? 1 : 0;
            var actor = _players[actorIndex];
            var opponent = _players[opponentIndex];
            if (!opponent.IsAlive) return false;
            if (Manhattan(actor.X, actor.Y, opponent.X, opponent.Y) != 1) return false;

            opponent.Health--;
            if (opponent.Health <= 0)
            {
                opponent.Health = 0;
                WinnerPeerId = actor.PeerId;
            }
            return true;
        }

        private int FindPlayerIndex(string peerId)
        {
            for (var i = 0; i < _playerIds.Length; i++)
                if (string.Equals(_playerIds[i], peerId, StringComparison.Ordinal)) return i;
            return -1;
        }

        private int GetLivePlayerIndexAt(int x, int y)
        {
            for (var i = 0; i < _players.Length; i++)
                if (_players[i].IsAlive && _players[i].X == x && _players[i].Y == y) return i;
            return -1;
        }

        private static bool IsInsideBoard(int x, int y)
        {
            return x >= 0 && x < BoardWidth && y >= 0 && y < BoardHeight;
        }

        private static int Manhattan(int ax, int ay, int bx, int by)
        {
            return Math.Abs(ax - bx) + Math.Abs(ay - by);
        }

        private static GridDuelPlayerState Clone(GridDuelPlayerState state)
        {
            return new GridDuelPlayerState(state.PeerId, state.X, state.Y, state.Health);
        }

        private static void ValidateSnapshot(GridDuelPlayerState[] players, string winner)
        {
            for (var i = 0; i < players.Length; i++)
            {
                var player = players[i];
                if (!IsInsideBoard(player.X, player.Y)) throw new InvalidDataException("Grid Duel snapshot position is out of bounds.");
                if (player.Health < 0 || player.Health > StartingHealth) throw new InvalidDataException("Grid Duel snapshot health is invalid.");
            }
            if (players[0].IsAlive && players[1].IsAlive && players[0].X == players[1].X && players[0].Y == players[1].Y)
                throw new InvalidDataException("Grid Duel snapshot places both live players on one cell.");

            var noWinner = string.IsNullOrEmpty(winner);
            if (players[0].IsAlive && players[1].IsAlive)
            {
                if (!noWinner) throw new InvalidDataException("Grid Duel snapshot declares a winner while both players are alive.");
                return;
            }
            if (!players[0].IsAlive && !players[1].IsAlive) throw new InvalidDataException("Grid Duel snapshot cannot contain two defeated players.");
            var expectedWinner = players[0].IsAlive ? players[0].PeerId : players[1].PeerId;
            if (!string.Equals(winner, expectedWinner, StringComparison.Ordinal))
                throw new InvalidDataException("Grid Duel snapshot winner is inconsistent with health state.");
        }

        private static void WriteString(BinaryWriter writer, string value)
        {
            var bytes = Encoding.UTF8.GetBytes(value ?? string.Empty);
            writer.Write(bytes.Length);
            writer.Write(bytes);
        }

        private static string ReadString(BinaryReader reader)
        {
            var length = reader.ReadInt32();
            if (length < 0 || length > 1024) throw new InvalidDataException("Invalid Grid Duel string length.");
            var bytes = reader.ReadBytes(length);
            if (bytes.Length != length) throw new EndOfStreamException("Grid Duel snapshot string was truncated.");
            return Encoding.UTF8.GetString(bytes);
        }
    }
}
