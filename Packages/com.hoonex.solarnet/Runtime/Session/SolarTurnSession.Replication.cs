using System;
using System.Collections.Generic;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using SolarNet.Protocol;
using SolarNet.State;
using SolarNet.Turns;

namespace SolarNet.Session
{
    public sealed class SolarReplicationAcknowledgement
    {
        public SolarReplicationAcknowledgement(string peerId, long nextTurnIndex, string stateHash)
        {
            if (string.IsNullOrWhiteSpace(peerId)) throw new ArgumentException("Peer ID is required.", nameof(peerId));
            if (nextTurnIndex < 0) throw new ArgumentOutOfRangeException(nameof(nextTurnIndex));
            if (string.IsNullOrWhiteSpace(stateHash)) throw new ArgumentException("State hash is required.", nameof(stateHash));
            PeerId = peerId;
            NextTurnIndex = nextTurnIndex;
            StateHash = stateHash;
        }

        public string PeerId { get; private set; }
        public long NextTurnIndex { get; private set; }
        public string StateHash { get; private set; }
    }

    public sealed partial class SolarTurnSession
    {
        private readonly object _replicationGate = new object();
        private Dictionary<string, long> _replicationFrontiers;
        private Dictionary<long, string> _authoritativeReplicationPoints;
        private Queue<long> _authoritativeReplicationOrder;
        private int _replicationPointCapacity;

        public bool ReplicationAcknowledgementsEnabled { get { return StateIntegrityEnabled; } }

        public event Action<SolarReplicationAcknowledgement> ReplicationAcknowledged;
        public event Action<Exception> ReplicationAcknowledgementFailed;

        public long GetReplicationFrontier(string peerId)
        {
            if (!IsHost) throw new InvalidOperationException("Only the authoritative host tracks remote replication frontiers.");
            if (string.IsNullOrWhiteSpace(peerId)) throw new ArgumentException("Peer ID is required.", nameof(peerId));
            if (!_hostCoordinator.IsKnownPlayer(peerId)) throw new ArgumentException("Unknown session player: " + peerId, nameof(peerId));
            if (string.Equals(peerId, LocalPeerId, StringComparison.Ordinal)) return KnownNextTurnIndex;

            lock (_replicationGate)
            {
                long frontier;
                return _replicationFrontiers.TryGetValue(peerId, out frontier) ? frontier : 0L;
            }
        }

        public bool IsReplicatedThrough(string peerId, long nextTurnIndex)
        {
            if (nextTurnIndex < 0) throw new ArgumentOutOfRangeException(nameof(nextTurnIndex));
            return GetReplicationFrontier(peerId) >= nextTurnIndex;
        }

        private void InitializeReplicationAcknowledgements(int journalCapacity)
        {
            _replicationPointCapacity = Math.Max(4, journalCapacity + 1);
            _replicationFrontiers = new Dictionary<string, long>(StringComparer.Ordinal);
            _authoritativeReplicationPoints = new Dictionary<long, string>();
            _authoritativeReplicationOrder = new Queue<long>();

            if (!StateIntegrityEnabled) return;
            if (IsHost)
            {
                ActionCommitted += RecordAuthoritativeReplicationPoint;
            }
            else
            {
                ActionCommitted += AcknowledgeCommittedState;
                SnapshotApplied += AcknowledgeSnapshotState;
            }
        }

        private void RecordAuthoritativeReplicationPoint(SolarTurnCommit commit)
        {
            if (commit == null || string.IsNullOrWhiteSpace(commit.StateHash)) return;
            lock (_replicationGate)
            {
                if (_authoritativeReplicationPoints.ContainsKey(commit.NextTurnIndex))
                {
                    _authoritativeReplicationPoints[commit.NextTurnIndex] = commit.StateHash;
                    return;
                }

                _authoritativeReplicationPoints.Add(commit.NextTurnIndex, commit.StateHash);
                _authoritativeReplicationOrder.Enqueue(commit.NextTurnIndex);
                while (_authoritativeReplicationOrder.Count > _replicationPointCapacity)
                {
                    var expired = _authoritativeReplicationOrder.Dequeue();
                    _authoritativeReplicationPoints.Remove(expired);
                }
            }
        }

        private async void AcknowledgeCommittedState(SolarTurnCommit commit)
        {
            if (commit == null || !_started || IsHost || !StateIntegrityEnabled) return;
            if (string.IsNullOrWhiteSpace(commit.StateHash)) return;
            if (commit.NextTurnIndex != KnownNextTurnIndex) return;
            if (!string.Equals(commit.StateHash, LastStateHash, StringComparison.Ordinal)) return;
            await TrySendReplicationAcknowledgementAsync(commit.NextTurnIndex, commit.StateHash).ConfigureAwait(false);
        }

        private async void AcknowledgeSnapshotState(SolarStateSnapshot snapshot)
        {
            if (snapshot == null || !_started || IsHost || !StateIntegrityEnabled) return;
            if (snapshot.NextTurnIndex != KnownNextTurnIndex) return;
            if (!string.Equals(snapshot.StateHash, LastStateHash, StringComparison.Ordinal)) return;
            await TrySendReplicationAcknowledgementAsync(snapshot.NextTurnIndex, snapshot.StateHash).ConfigureAwait(false);
        }

        private async Task TrySendReplicationAcknowledgementAsync(long nextTurnIndex, string stateHash)
        {
            try
            {
                var packet = new SolarPacket(
                    SolarPacketType.ReplicationAck,
                    SessionId,
                    LocalPeerId,
                    NextSequence(),
                    nextTurnIndex,
                    Array.Empty<byte>(),
                    stateHash);
                await _transport.SendAsync(HostPeerId, SolarPacketCodec.Encode(packet), CancellationToken.None).ConfigureAwait(false);
            }
            catch (Exception ex)
            {
                var handler = ReplicationAcknowledgementFailed;
                if (handler != null) handler(ex);
            }
        }

        private void HandleReplicationAcknowledgementAsHost(SolarPacket packet)
        {
            if (packet == null) throw new ArgumentNullException(nameof(packet));
            if (!IsHost) throw new InvalidOperationException("Only the authoritative host accepts replication acknowledgements.");
            if (!StateIntegrityEnabled)
                throw new InvalidDataException("Replication acknowledgement requires state integrity to be enabled.");
            if (!_hostCoordinator.IsKnownPlayer(packet.SenderId))
                throw new InvalidDataException("Replication acknowledgement came from an unknown player.");
            if (string.Equals(packet.SenderId, LocalPeerId, StringComparison.Ordinal))
                throw new InvalidDataException("Remote transport cannot acknowledge as the authoritative host peer.");
            if (packet.Payload.Length != 0)
                throw new InvalidDataException("Replication acknowledgement payload must be empty.");
            if (string.IsNullOrWhiteSpace(packet.StateHash))
                throw new InvalidDataException("Replication acknowledgement is missing a state digest.");
            if (packet.TurnIndex > KnownNextTurnIndex)
                throw new InvalidDataException("Replication acknowledgement is ahead of authoritative state.");

            string expectedHash;
            if (!TryResolveAuthoritativeReplicationHash(packet.TurnIndex, out expectedHash))
                return; // A legitimate but very stale acknowledgement may outlive the bounded proof cache.
            if (!string.Equals(expectedHash, packet.StateHash, StringComparison.Ordinal))
                throw new InvalidDataException("Replication acknowledgement digest does not match authoritative state.");

            SolarReplicationAcknowledgement acknowledgement = null;
            lock (_replicationGate)
            {
                long previous;
                if (_replicationFrontiers.TryGetValue(packet.SenderId, out previous) && previous >= packet.TurnIndex)
                    return; // duplicate or stale acknowledgement is idempotent
                _replicationFrontiers[packet.SenderId] = packet.TurnIndex;
                acknowledgement = new SolarReplicationAcknowledgement(packet.SenderId, packet.TurnIndex, packet.StateHash);
            }

            var handler = ReplicationAcknowledged;
            if (handler != null) handler(acknowledgement);
        }

        private bool TryResolveAuthoritativeReplicationHash(long nextTurnIndex, out string stateHash)
        {
            if (nextTurnIndex == KnownNextTurnIndex)
            {
                stateHash = LastStateHash;
                return !string.IsNullOrWhiteSpace(stateHash);
            }

            lock (_replicationGate)
                return _authoritativeReplicationPoints.TryGetValue(nextTurnIndex, out stateHash);
        }
    }
}
