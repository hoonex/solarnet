using System;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using SolarNet.Protocol;
using SolarNet.Transport;
using SolarNet.Turns;

namespace SolarNet.Session
{
    public sealed class SolarTurnRejection
    {
        public SolarTurnRejection(string actorId, long hostTurnIndex, SolarTurnRejectReason reason)
        {
            ActorId = actorId;
            HostTurnIndex = hostTurnIndex;
            Reason = reason;
        }

        public string ActorId { get; private set; }
        public long HostTurnIndex { get; private set; }
        public SolarTurnRejectReason Reason { get; private set; }
    }

    public sealed class SolarTurnSession
    {
        private readonly ISolarTransport _transport;
        private readonly TurnCoordinator _hostCoordinator;
        private long _nextSequence;
        private bool _started;

        public SolarTurnSession(string sessionId, string hostPeerId, ISolarTransport transport, TurnCoordinator hostCoordinator = null)
        {
            if (string.IsNullOrWhiteSpace(sessionId)) throw new ArgumentException("Session ID is required.", nameof(sessionId));
            if (string.IsNullOrWhiteSpace(hostPeerId)) throw new ArgumentException("Host peer ID is required.", nameof(hostPeerId));
            if (transport == null) throw new ArgumentNullException(nameof(transport));

            SessionId = sessionId;
            HostPeerId = hostPeerId;
            _transport = transport;
            IsHost = string.Equals(transport.LocalPeerId, hostPeerId, StringComparison.Ordinal);
            _hostCoordinator = hostCoordinator;

            if (IsHost && _hostCoordinator == null)
                throw new ArgumentException("The host session requires a TurnCoordinator.", nameof(hostCoordinator));
            if (!IsHost && _hostCoordinator != null)
                throw new ArgumentException("Only the host session may own the TurnCoordinator.", nameof(hostCoordinator));
        }

        public string SessionId { get; private set; }
        public string HostPeerId { get; private set; }
        public string LocalPeerId { get { return _transport.LocalPeerId; } }
        public bool IsHost { get; private set; }

        public event Action<SolarTurnCommit> ActionCommitted;
        public event Action<SolarTurnRejection> ActionRejected;
        public event Action<Exception> ProtocolFaulted;

        public async Task StartAsync(CancellationToken cancellationToken = default(CancellationToken))
        {
            if (_started) return;
            _transport.FrameReceived += HandleFrameAsync;
            try
            {
                await _transport.StartAsync(cancellationToken).ConfigureAwait(false);
                _started = true;
            }
            catch
            {
                _transport.FrameReceived -= HandleFrameAsync;
                throw;
            }
        }

        public async Task StopAsync(CancellationToken cancellationToken = default(CancellationToken))
        {
            if (!_started) return;
            _started = false;
            _transport.FrameReceived -= HandleFrameAsync;
            await _transport.StopAsync(cancellationToken).ConfigureAwait(false);
        }

        public async Task SubmitActionAsync(long expectedTurnIndex, string actionKind, byte[] payload, CancellationToken cancellationToken = default(CancellationToken))
        {
            EnsureStarted();
            if (expectedTurnIndex < 0) throw new ArgumentOutOfRangeException(nameof(expectedTurnIndex));
            if (string.IsNullOrWhiteSpace(actionKind)) throw new ArgumentException("Action kind is required.", nameof(actionKind));

            var sequence = _nextSequence++;
            if (IsHost)
            {
                await CommitAsHostAsync(new SolarTurnAction(LocalPeerId, expectedTurnIndex, sequence, actionKind, payload), cancellationToken).ConfigureAwait(false);
                return;
            }

            var packet = new SolarPacket(
                SolarPacketType.TurnAction,
                SessionId,
                LocalPeerId,
                sequence,
                expectedTurnIndex,
                TurnWireCodec.EncodeAction(actionKind, payload));
            await _transport.SendAsync(HostPeerId, SolarPacketCodec.Encode(packet), cancellationToken).ConfigureAwait(false);
        }

        private async Task HandleFrameAsync(SolarFrame frame)
        {
            try
            {
                var packet = SolarPacketCodec.Decode(frame.Data);
                if (!string.Equals(packet.SessionId, SessionId, StringComparison.Ordinal)) return;
                if (!string.Equals(packet.SenderId, frame.RemotePeerId, StringComparison.Ordinal))
                    throw new InvalidDataException("Packet sender identity does not match transport peer identity.");

                if (IsHost)
                {
                    if (packet.Type != SolarPacketType.TurnAction) return;
                    string actionKind;
                    byte[] payload;
                    TurnWireCodec.DecodeAction(packet.Payload, out actionKind, out payload);
                    await CommitAsHostAsync(
                        new SolarTurnAction(packet.SenderId, packet.TurnIndex, packet.Sequence, actionKind, payload),
                        CancellationToken.None).ConfigureAwait(false);
                    return;
                }

                if (!string.Equals(frame.RemotePeerId, HostPeerId, StringComparison.Ordinal))
                    throw new InvalidDataException("Client received authoritative packet from a non-host peer.");

                if (packet.Type == SolarPacketType.TurnCommitted)
                {
                    RaiseCommitted(TurnWireCodec.DecodeCommit(packet.TurnIndex, packet.Payload));
                }
                else if (packet.Type == SolarPacketType.TurnRejected)
                {
                    RaiseRejected(new SolarTurnRejection(LocalPeerId, packet.TurnIndex, TurnWireCodec.DecodeRejection(packet.Payload)));
                }
            }
            catch (Exception ex)
            {
                var handler = ProtocolFaulted;
                if (handler != null) handler(ex);
            }
        }

        private async Task CommitAsHostAsync(SolarTurnAction action, CancellationToken cancellationToken)
        {
            SolarTurnCommit commit;
            SolarTurnRejectReason reason;
            if (!_hostCoordinator.TryCommit(action, out commit, out reason))
            {
                var rejection = new SolarTurnRejection(action.ActorId, _hostCoordinator.TurnIndex, reason);
                if (string.Equals(action.ActorId, LocalPeerId, StringComparison.Ordinal))
                {
                    RaiseRejected(rejection);
                }
                else
                {
                    var packet = new SolarPacket(
                        SolarPacketType.TurnRejected,
                        SessionId,
                        LocalPeerId,
                        _nextSequence++,
                        _hostCoordinator.TurnIndex,
                        TurnWireCodec.EncodeRejection(reason));
                    await _transport.SendAsync(action.ActorId, SolarPacketCodec.Encode(packet), cancellationToken).ConfigureAwait(false);
                }
                return;
            }

            RaiseCommitted(commit);
            var committedPacket = new SolarPacket(
                SolarPacketType.TurnCommitted,
                SessionId,
                LocalPeerId,
                _nextSequence++,
                commit.CommittedTurnIndex,
                TurnWireCodec.EncodeCommit(commit));
            await _transport.BroadcastAsync(SolarPacketCodec.Encode(committedPacket), cancellationToken).ConfigureAwait(false);
        }

        private void RaiseCommitted(SolarTurnCommit commit)
        {
            var handler = ActionCommitted;
            if (handler != null) handler(commit);
        }

        private void RaiseRejected(SolarTurnRejection rejection)
        {
            var handler = ActionRejected;
            if (handler != null) handler(rejection);
        }

        private void EnsureStarted()
        {
            if (!_started) throw new InvalidOperationException("SolarTurnSession has not been started.");
        }
    }
}
