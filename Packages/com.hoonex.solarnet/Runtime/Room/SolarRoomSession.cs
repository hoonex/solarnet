using System;
using System.Collections.Generic;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using SolarNet.Protocol;
using SolarNet.Transport;

namespace SolarNet.Room
{
    public sealed partial class SolarRoomSession
    {
        private readonly ISolarTransport _transport;
        private readonly SolarRoomOptions _options;
        private readonly SemaphoreSlim _hostGate = new SemaphoreSlim(1, 1);
        private readonly object _stateGate = new object();
        private readonly List<SolarRoomPlayer> _hostPlayers = new List<SolarRoomPlayer>();
        private long _nextSequence = -1;
        private long _hostRevision;
        private SolarRoomSnapshot _snapshot;
        private SolarRoomPhase _localPhase;
        private bool _attached;
        private string _lastStartedGameSessionId = string.Empty;

        public SolarRoomSession(SolarRoomOptions options, ISolarTransport transport)
        {
            _options = options ?? throw new ArgumentNullException(nameof(options));
            _transport = transport ?? throw new ArgumentNullException(nameof(transport));
            IsHost = string.Equals(_transport.LocalPeerId, _options.HostPeerId, StringComparison.Ordinal);
            _localPhase = IsHost ? SolarRoomPhase.Lobby : SolarRoomPhase.Joining;

            if (IsHost)
            {
                _hostPlayers.Add(new SolarRoomPlayer(0, LocalPeerId, _options.LocalDisplayName, false, true));
                _snapshot = BuildHostSnapshot(SolarRoomPhase.Lobby, string.Empty);
            }
        }

        public bool IsHost { get; private set; }
        public string LocalPeerId { get { return _transport.LocalPeerId; } }
        public string RoomId { get { return _options.RoomId; } }
        public string HostPeerId { get { return _options.HostPeerId; } }

        public SolarRoomPhase Phase { get { lock (_stateGate) return _localPhase; } }
        public SolarRoomSnapshot CurrentSnapshot { get { lock (_stateGate) return _snapshot; } }

        public bool CanStart
        {
            get
            {
                if (!IsHost) return false;
                lock (_stateGate)
                {
                    if (_localPhase != SolarRoomPhase.Lobby || _hostPlayers.Count < 2) return false;
                    foreach (var player in _hostPlayers)
                        if (!player.IsConnected || !player.IsReady) return false;
                    return true;
                }
            }
        }

        public event Action<SolarRoomSnapshot> RoomChanged;
        public event Action<SolarRoomPhase> PhaseChanged;
        public event Action<SolarRoomJoinRejection> JoinRejected;
        public event Action<SolarGameStartInfo> GameStarted;
        public event Action<SolarRoomClosed> RoomClosed;
        public event Action<Exception> ProtocolFaulted;

        public void Attach()
        {
            lock (_stateGate)
            {
                if (_attached) return;
                _attached = true;
            }
            _transport.FrameReceived += HandleFrameAsync;
        }

        public void Detach()
        {
            lock (_stateGate)
            {
                if (!_attached) return;
                _attached = false;
            }
            _transport.FrameReceived -= HandleFrameAsync;
        }

        public async Task NotifyPeerConnectedAsync(string peerId, CancellationToken cancellationToken = default(CancellationToken))
        {
            EnsureAttached();
            if (string.IsNullOrWhiteSpace(peerId)) throw new ArgumentException("Peer ID is required.", nameof(peerId));
            if (IsHost) return;
            if (!string.Equals(peerId, HostPeerId, StringComparison.Ordinal)) return;
            if (Phase == SolarRoomPhase.Closed) return;

            var packet = new SolarPacket(
                SolarPacketType.RoomJoinRequest,
                RoomId,
                LocalPeerId,
                NextSequence(),
                0,
                RoomWireCodec.EncodeJoinRequest(SolarNetVersion.CoreProtocolVersion, _options.CompatibilityKey, _options.LocalDisplayName));
            await _transport.SendAsync(HostPeerId, SolarPacketCodec.Encode(packet), cancellationToken).ConfigureAwait(false);
        }

        public async Task NotifyPeerDisconnectedAsync(string peerId, CancellationToken cancellationToken = default(CancellationToken))
        {
            EnsureAttached();
            if (string.IsNullOrWhiteSpace(peerId)) throw new ArgumentException("Peer ID is required.", nameof(peerId));

            if (!IsHost)
            {
                if (!string.Equals(peerId, HostPeerId, StringComparison.Ordinal)) return;
                if (_options.HostDisconnectPolicy == SolarHostDisconnectPolicy.CloseRoom)
                    CloseLocal(SolarRoomCloseReason.HostDisconnected);
                else
                    SetLocalPhase(SolarRoomPhase.Reconnecting);
                return;
            }

            await _hostGate.WaitAsync(cancellationToken).ConfigureAwait(false);
            try
            {
                var index = FindHostPlayer(peerId);
                if (index < 0 || string.Equals(peerId, LocalPeerId, StringComparison.Ordinal)) return;
                var current = _hostPlayers[index];
                _hostPlayers[index] = current.WithState(false, false);
                await PublishHostStateAsync(CurrentHostPhase(), CurrentGameSessionId(), cancellationToken).ConfigureAwait(false);
            }
            finally
            {
                _hostGate.Release();
            }
        }

        public async Task SetReadyAsync(bool ready, CancellationToken cancellationToken = default(CancellationToken))
        {
            EnsureAttached();
            if (Phase != SolarRoomPhase.Lobby) throw new InvalidOperationException("Ready state can only change in the lobby.");

            if (!IsHost)
            {
                var packet = new SolarPacket(
                    SolarPacketType.RoomReady,
                    RoomId,
                    LocalPeerId,
                    NextSequence(),
                    0,
                    RoomWireCodec.EncodeReady(ready));
                await _transport.SendAsync(HostPeerId, SolarPacketCodec.Encode(packet), cancellationToken).ConfigureAwait(false);
                return;
            }

            await _hostGate.WaitAsync(cancellationToken).ConfigureAwait(false);
            try
            {
                var index = FindHostPlayer(LocalPeerId);
                _hostPlayers[index] = _hostPlayers[index].WithState(ready, true);
                await PublishHostStateAsync(SolarRoomPhase.Lobby, string.Empty, cancellationToken).ConfigureAwait(false);
            }
            finally
            {
                _hostGate.Release();
            }
        }

        public async Task<SolarGameStartInfo> StartGameAsync(string gameSessionId = null, CancellationToken cancellationToken = default(CancellationToken))
        {
            EnsureAttached();
            if (!IsHost) throw new InvalidOperationException("Only the room host can start the game.");

            await _hostGate.WaitAsync(cancellationToken).ConfigureAwait(false);
            try
            {
                if (!CanStart) throw new InvalidOperationException("All connected room players must be ready before the game starts.");
                if (string.IsNullOrWhiteSpace(gameSessionId))
                    gameSessionId = RoomId + "-" + Guid.NewGuid().ToString("N");

                await PublishHostStateAsync(SolarRoomPhase.Playing, gameSessionId, cancellationToken).ConfigureAwait(false);
                var start = BuildGameStartInfo(CurrentSnapshot);
                RaiseGameStartedOnce(start);
                return start;
            }
            finally
            {
                _hostGate.Release();
            }
        }

        public async Task LeaveAsync(CancellationToken cancellationToken = default(CancellationToken))
        {
            EnsureAttached();
            if (IsHost)
            {
                await CloseRoomAsync(SolarRoomCloseReason.HostClosed, cancellationToken).ConfigureAwait(false);
                return;
            }

            if (Phase != SolarRoomPhase.Closed)
            {
                var packet = new SolarPacket(
                    SolarPacketType.RoomLeave,
                    RoomId,
                    LocalPeerId,
                    NextSequence(),
                    0,
                    Array.Empty<byte>());
                try
                {
                    await _transport.SendAsync(HostPeerId, SolarPacketCodec.Encode(packet), cancellationToken).ConfigureAwait(false);
                }
                finally
                {
                    CloseLocal(SolarRoomCloseReason.LocalLeave);
                }
            }
        }

        public async Task CloseRoomAsync(SolarRoomCloseReason reason = SolarRoomCloseReason.HostClosed, CancellationToken cancellationToken = default(CancellationToken))
        {
            EnsureAttached();
            if (!IsHost) throw new InvalidOperationException("Only the room host can close the room for everyone.");

            await _hostGate.WaitAsync(cancellationToken).ConfigureAwait(false);
            try
            {
                var packet = new SolarPacket(
                    SolarPacketType.RoomClose,
                    RoomId,
                    LocalPeerId,
                    NextSequence(),
                    0,
                    RoomWireCodec.EncodeClose(reason));
                var bytes = SolarPacketCodec.Encode(packet);
                await SendToConnectedRoomPeersAsync(bytes, cancellationToken).ConfigureAwait(false);

                _hostRevision++;
                lock (_stateGate)
                {
                    _localPhase = SolarRoomPhase.Closed;
                    _snapshot = BuildHostSnapshot(SolarRoomPhase.Closed, CurrentGameSessionIdUnsafe());
                }
                RaisePhaseChanged(SolarRoomPhase.Closed);
                RaiseRoomClosed(new SolarRoomClosed(reason));
            }
            finally
            {
                _hostGate.Release();
            }
        }

        private async Task HandleFrameAsync(SolarFrame frame)
        {
            try
            {
                var packet = SolarPacketCodec.Decode(frame.Data);
                if (!string.Equals(packet.SessionId, RoomId, StringComparison.Ordinal)) return;
                if (!string.Equals(packet.SenderId, frame.RemotePeerId, StringComparison.Ordinal))
                    throw new InvalidDataException("Room packet sender identity does not match transport peer identity.");

                if (IsHost)
                {
                    if (packet.Type == SolarPacketType.RoomJoinRequest)
                        await HandleJoinAsHostAsync(packet, CancellationToken.None).ConfigureAwait(false);
                    else if (packet.Type == SolarPacketType.RoomReady)
                        await HandleReadyAsHostAsync(packet, CancellationToken.None).ConfigureAwait(false);
                    else if (packet.Type == SolarPacketType.RoomLeave)
                        await HandleLeaveAsHostAsync(packet, CancellationToken.None).ConfigureAwait(false);
                    return;
                }

                if (!string.Equals(frame.RemotePeerId, HostPeerId, StringComparison.Ordinal))
                    throw new InvalidDataException("Client received authoritative room packet from a non-host peer.");

                if (packet.Type == SolarPacketType.RoomState)
                    HandleStateAsClient(packet);
                else if (packet.Type == SolarPacketType.RoomJoinRejected)
                    HandleJoinRejectedAsClient(packet);
                else if (packet.Type == SolarPacketType.RoomClose)
                    CloseLocal(RoomWireCodec.DecodeClose(packet.Payload));
            }
            catch (Exception ex)
            {
                RaiseProtocolFault(ex);
            }
        }

        private async Task HandleJoinAsHostAsync(SolarPacket packet, CancellationToken cancellationToken)
        {
            await _hostGate.WaitAsync(cancellationToken).ConfigureAwait(false);
            try
            {
                var request = RoomWireCodec.DecodeJoinRequest(packet.Payload);
                if (request.ProtocolVersion != SolarNetVersion.CoreProtocolVersion)
                {
                    await RejectJoinAsync(packet.SenderId, SolarRoomJoinRejectReason.ProtocolMismatch, cancellationToken).ConfigureAwait(false);
                    return;
                }
                if (!string.Equals(request.CompatibilityKey, _options.CompatibilityKey, StringComparison.Ordinal))
                {
                    await RejectJoinAsync(packet.SenderId, SolarRoomJoinRejectReason.CompatibilityMismatch, cancellationToken).ConfigureAwait(false);
                    return;
                }
                if (string.IsNullOrWhiteSpace(request.DisplayName))
                {
                    await RejectJoinAsync(packet.SenderId, SolarRoomJoinRejectReason.InvalidRequest, cancellationToken).ConfigureAwait(false);
                    return;
                }

                var existingIndex = FindHostPlayer(packet.SenderId);
                if (existingIndex >= 0)
                {
                    var existing = _hostPlayers[existingIndex];
                    if (!string.Equals(existing.DisplayName, request.DisplayName, StringComparison.Ordinal))
                    {
                        await RejectJoinAsync(packet.SenderId, SolarRoomJoinRejectReason.IdentityMismatch, cancellationToken).ConfigureAwait(false);
                        return;
                    }
                    var ready = CurrentHostPhase() == SolarRoomPhase.Lobby ? false : existing.IsReady;
                    _hostPlayers[existingIndex] = existing.WithState(ready, true);
                    await PublishHostStateAsync(CurrentHostPhase(), CurrentGameSessionId(), cancellationToken).ConfigureAwait(false);
                    return;
                }

                if (CurrentHostPhase() != SolarRoomPhase.Lobby)
                {
                    await RejectJoinAsync(packet.SenderId, SolarRoomJoinRejectReason.AlreadyStarted, cancellationToken).ConfigureAwait(false);
                    return;
                }
                if (_hostPlayers.Count >= _options.MaxPlayers)
                {
                    await RejectJoinAsync(packet.SenderId, SolarRoomJoinRejectReason.RoomFull, cancellationToken).ConfigureAwait(false);
                    return;
                }

                _hostPlayers.Add(new SolarRoomPlayer(NextFreeSlot(), packet.SenderId, request.DisplayName, false, true));
                SortHostPlayers();
                await PublishHostStateAsync(SolarRoomPhase.Lobby, string.Empty, cancellationToken).ConfigureAwait(false);
            }
            finally
            {
                _hostGate.Release();
            }
        }

        private async Task HandleReadyAsHostAsync(SolarPacket packet, CancellationToken cancellationToken)
        {
            await _hostGate.WaitAsync(cancellationToken).ConfigureAwait(false);
            try
            {
                if (CurrentHostPhase() != SolarRoomPhase.Lobby) return;
                var index = FindHostPlayer(packet.SenderId);
                if (index < 0) return;
                var player = _hostPlayers[index];
                if (!player.IsConnected) return;
                _hostPlayers[index] = player.WithState(RoomWireCodec.DecodeReady(packet.Payload), true);
                await PublishHostStateAsync(SolarRoomPhase.Lobby, string.Empty, cancellationToken).ConfigureAwait(false);
            }
            finally
            {
                _hostGate.Release();
            }
        }

        private async Task HandleLeaveAsHostAsync(SolarPacket packet, CancellationToken cancellationToken)
        {
            await _hostGate.WaitAsync(cancellationToken).ConfigureAwait(false);
            try
            {
                var index = FindHostPlayer(packet.SenderId);
                if (index < 0) return;
                if (CurrentHostPhase() == SolarRoomPhase.Lobby)
                    _hostPlayers.RemoveAt(index);
                else
                    _hostPlayers[index] = _hostPlayers[index].WithState(false, false);
                await PublishHostStateAsync(CurrentHostPhase(), CurrentGameSessionId(), cancellationToken).ConfigureAwait(false);
            }
            finally
            {
                _hostGate.Release();
            }
        }

        private async Task RejectJoinAsync(string peerId, SolarRoomJoinRejectReason reason, CancellationToken cancellationToken)
        {
            var packet = new SolarPacket(
                SolarPacketType.RoomJoinRejected,
                RoomId,
                LocalPeerId,
                NextSequence(),
                0,
                RoomWireCodec.EncodeJoinRejected(reason));
            await _transport.SendAsync(peerId, SolarPacketCodec.Encode(packet), cancellationToken).ConfigureAwait(false);
        }

        private async Task PublishHostStateAsync(SolarRoomPhase phase, string gameSessionId, CancellationToken cancellationToken)
        {
            _hostRevision++;
            SolarRoomSnapshot snapshot;
            lock (_stateGate)
            {
                _localPhase = phase;
                _snapshot = BuildHostSnapshot(phase, gameSessionId);
                snapshot = _snapshot;
            }
            RaisePhaseChanged(phase);
            RaiseRoomChanged(snapshot);

            var packet = new SolarPacket(
                SolarPacketType.RoomState,
                RoomId,
                LocalPeerId,
                NextSequence(),
                0,
                RoomWireCodec.EncodeState(snapshot));
            await SendToConnectedRoomPeersAsync(SolarPacketCodec.Encode(packet), cancellationToken).ConfigureAwait(false);
        }

        private async Task SendToConnectedRoomPeersAsync(byte[] packet, CancellationToken cancellationToken)
        {
            var targets = new List<string>();
            foreach (var player in _hostPlayers)
                if (player.IsConnected && !string.Equals(player.PeerId, LocalPeerId, StringComparison.Ordinal)) targets.Add(player.PeerId);
            foreach (var peerId in targets)
                await _transport.SendAsync(peerId, packet, cancellationToken).ConfigureAwait(false);
        }

        private void HandleStateAsClient(SolarPacket packet)
        {
            var snapshot = RoomWireCodec.DecodeState(RoomId, packet.Payload);
            ValidateClientSnapshot(snapshot);

            lock (_stateGate)
            {
                if (_snapshot != null && snapshot.Revision <= _snapshot.Revision) return;
                _snapshot = snapshot;
                _localPhase = snapshot.Phase;
            }

            RaisePhaseChanged(snapshot.Phase);
            RaiseRoomChanged(snapshot);
            if (snapshot.Phase == SolarRoomPhase.Playing)
                RaiseGameStartedOnce(BuildGameStartInfo(snapshot));
            else if (snapshot.Phase == SolarRoomPhase.Closed)
                RaiseRoomClosed(new SolarRoomClosed(SolarRoomCloseReason.HostClosed));
        }

        private void HandleJoinRejectedAsClient(SolarPacket packet)
        {
            var rejection = new SolarRoomJoinRejection(RoomWireCodec.DecodeJoinRejected(packet.Payload));
            lock (_stateGate) _localPhase = SolarRoomPhase.Closed;
            RaisePhaseChanged(SolarRoomPhase.Closed);
            var handler = JoinRejected;
            if (handler != null) handler(rejection);
        }

        private void ValidateClientSnapshot(SolarRoomSnapshot snapshot)
        {
            if (!string.Equals(snapshot.HostPeerId, HostPeerId, StringComparison.Ordinal))
                throw new InvalidDataException("Room snapshot host identity changed.");
            if (!string.Equals(snapshot.CompatibilityKey, _options.CompatibilityKey, StringComparison.Ordinal))
                throw new InvalidDataException("Room snapshot compatibility key changed.");
            if (snapshot.Players.Length > snapshot.MaxPlayers) throw new InvalidDataException("Room snapshot exceeds max players.");

            var peers = new HashSet<string>(StringComparer.Ordinal);
            var slots = new HashSet<int>();
            var includesLocal = false;
            var includesHost = false;
            foreach (var player in snapshot.Players)
            {
                if (!peers.Add(player.PeerId)) throw new InvalidDataException("Room snapshot contains duplicate peer IDs.");
                if (!slots.Add(player.Slot)) throw new InvalidDataException("Room snapshot contains duplicate player slots.");
                if (string.Equals(player.PeerId, LocalPeerId, StringComparison.Ordinal)) includesLocal = true;
                if (string.Equals(player.PeerId, HostPeerId, StringComparison.Ordinal)) includesHost = true;
            }
            if (!includesLocal) throw new InvalidDataException("Room snapshot does not include the local peer.");
            if (!includesHost) throw new InvalidDataException("Room snapshot does not include its declared host.");
            if (snapshot.Phase == SolarRoomPhase.Playing && string.IsNullOrWhiteSpace(snapshot.GameSessionId))
                throw new InvalidDataException("Playing room snapshot is missing a game session ID.");
        }

        private SolarRoomSnapshot BuildHostSnapshot(SolarRoomPhase phase, string gameSessionId)
        {
            return new SolarRoomSnapshot(
                _hostRevision,
                RoomId,
                _options.RoomName,
                HostPeerId,
                _options.CompatibilityKey,
                _options.MaxPlayers,
                phase,
                gameSessionId,
                _hostPlayers.ToArray());
        }

        private SolarGameStartInfo BuildGameStartInfo(SolarRoomSnapshot snapshot)
        {
            var players = (SolarRoomPlayer[])snapshot.Players.Clone();
            Array.Sort(players, (a, b) => a.Slot.CompareTo(b.Slot));
            var ids = new string[players.Length];
            for (var i = 0; i < players.Length; i++) ids[i] = players[i].PeerId;
            return new SolarGameStartInfo(snapshot.GameSessionId, snapshot.HostPeerId, ids);
        }

        private void RaiseGameStartedOnce(SolarGameStartInfo start)
        {
            lock (_stateGate)
            {
                if (string.Equals(_lastStartedGameSessionId, start.GameSessionId, StringComparison.Ordinal)) return;
                _lastStartedGameSessionId = start.GameSessionId;
            }
            var handler = GameStarted;
            if (handler != null) handler(start);
        }

        private int FindHostPlayer(string peerId)
        {
            for (var i = 0; i < _hostPlayers.Count; i++)
                if (string.Equals(_hostPlayers[i].PeerId, peerId, StringComparison.Ordinal)) return i;
            return -1;
        }

        private int NextFreeSlot()
        {
            for (var slot = 1; slot < _options.MaxPlayers; slot++)
            {
                var used = false;
                foreach (var player in _hostPlayers)
                    if (player.Slot == slot) { used = true; break; }
                if (!used) return slot;
            }
            throw new InvalidOperationException("No free room slot exists.");
        }

        private void SortHostPlayers()
        {
            _hostPlayers.Sort((a, b) => a.Slot.CompareTo(b.Slot));
        }

        private SolarRoomPhase CurrentHostPhase()
        {
            lock (_stateGate) return _snapshot == null ? _localPhase : _snapshot.Phase;
        }

        private string CurrentGameSessionId()
        {
            lock (_stateGate) return CurrentGameSessionIdUnsafe();
        }

        private string CurrentGameSessionIdUnsafe()
        {
            return _snapshot == null ? string.Empty : _snapshot.GameSessionId;
        }

        private void SetLocalPhase(SolarRoomPhase phase)
        {
            lock (_stateGate) _localPhase = phase;
            RaisePhaseChanged(phase);
        }

        private void CloseLocal(SolarRoomCloseReason reason)
        {
            var shouldRaise = false;
            lock (_stateGate)
            {
                if (_localPhase != SolarRoomPhase.Closed)
                {
                    _localPhase = SolarRoomPhase.Closed;
                    shouldRaise = true;
                }
            }
            if (!shouldRaise) return;
            RaisePhaseChanged(SolarRoomPhase.Closed);
            RaiseRoomClosed(new SolarRoomClosed(reason));
        }

        private long NextSequence()
        {
            return Interlocked.Increment(ref _nextSequence);
        }

        private void RaiseRoomChanged(SolarRoomSnapshot snapshot)
        {
            var handler = RoomChanged;
            if (handler != null) handler(snapshot);
        }

        private void RaisePhaseChanged(SolarRoomPhase phase)
        {
            var handler = PhaseChanged;
            if (handler != null) handler(phase);
        }

        private void RaiseRoomClosed(SolarRoomClosed closed)
        {
            var handler = RoomClosed;
            if (handler != null) handler(closed);
        }

        private void RaiseProtocolFault(Exception exception)
        {
            var handler = ProtocolFaulted;
            if (handler != null) handler(exception);
        }

        private void EnsureAttached()
        {
            lock (_stateGate)
            {
                if (!_attached) throw new InvalidOperationException("SolarRoomSession is not attached to its transport.");
            }
        }
    }
}
