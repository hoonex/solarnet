using System;
using System.Collections.Generic;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using SolarNet.Protocol;
using SolarNet.State;
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
        private readonly ISolarGameStateMachine _gameStateMachine;
        private readonly SolarActionJournal _journal;
        private readonly SemaphoreSlim _hostGate = new SemaphoreSlim(1, 1);
        private readonly object _clientStateGate = new object();
        private readonly object _metadataGate = new object();
        private readonly object _resyncGate = new object();
        private long _nextSequence = -1;
        private bool _started;
        private CancellationTokenSource _lifetimeCts;
        private long _knownNextTurnIndex;
        private string _knownCurrentPlayerId = string.Empty;
        private int _knownRound = 1;
        private string _lastStateHash = string.Empty;
        private bool _automaticResyncRunning;
        private bool _automaticResyncRetryRequested;
        private long _automaticResyncRetryTurn;

        public SolarTurnSession(
            string sessionId,
            string hostPeerId,
            ISolarTransport transport,
            TurnCoordinator hostCoordinator = null,
            ISolarGameStateMachine gameStateMachine = null,
            int journalCapacity = 256)
        {
            if (string.IsNullOrWhiteSpace(sessionId)) throw new ArgumentException("Session ID is required.", nameof(sessionId));
            if (string.IsNullOrWhiteSpace(hostPeerId)) throw new ArgumentException("Host peer ID is required.", nameof(hostPeerId));
            if (transport == null) throw new ArgumentNullException(nameof(transport));
            if (journalCapacity < 1) throw new ArgumentOutOfRangeException(nameof(journalCapacity));

            SessionId = sessionId;
            HostPeerId = hostPeerId;
            _transport = transport;
            IsHost = string.Equals(transport.LocalPeerId, hostPeerId, StringComparison.Ordinal);
            _hostCoordinator = hostCoordinator;
            _gameStateMachine = gameStateMachine;

            if (IsHost && _hostCoordinator == null)
                throw new ArgumentException("The host session requires a TurnCoordinator.", nameof(hostCoordinator));
            if (!IsHost && _hostCoordinator != null)
                throw new ArgumentException("Only the host session may own the TurnCoordinator.", nameof(hostCoordinator));

            if (IsHost)
            {
                _journal = new SolarActionJournal(journalCapacity);
                _knownNextTurnIndex = _hostCoordinator.TurnIndex;
                _knownCurrentPlayerId = _hostCoordinator.CurrentPlayerId;
                _knownRound = _hostCoordinator.Round;
            }
        }

        public string SessionId { get; private set; }
        public string HostPeerId { get; private set; }
        public string LocalPeerId { get { return _transport.LocalPeerId; } }
        public bool IsHost { get; private set; }
        public bool StateIntegrityEnabled { get { return _gameStateMachine != null; } }

        public long KnownNextTurnIndex { get { lock (_metadataGate) return _knownNextTurnIndex; } }
        public string KnownCurrentPlayerId { get { lock (_metadataGate) return _knownCurrentPlayerId; } }
        public int KnownRound { get { lock (_metadataGate) return _knownRound; } }
        public string LastStateHash { get { lock (_metadataGate) return _lastStateHash; } }

        public event Action<SolarTurnCommit> ActionCommitted;
        public event Action<SolarTurnRejection> ActionRejected;
        public event Action<SolarStateMismatch> StateMismatchDetected;
        public event Action<SolarStateSnapshot> SnapshotApplied;
        public event Action<SolarResyncFailure> ResyncFailed;
        public event Action<Exception> ProtocolFaulted;

        public async Task StartAsync(CancellationToken cancellationToken = default(CancellationToken))
        {
            if (_started) return;

            if (_gameStateMachine != null)
                SetLastStateHash(CaptureStateHash());

            var lifetime = new CancellationTokenSource();
            _lifetimeCts = lifetime;
            _transport.FrameReceived += HandleFrameAsync;
            try
            {
                await _transport.StartAsync(cancellationToken).ConfigureAwait(false);
                _started = true;
            }
            catch
            {
                _transport.FrameReceived -= HandleFrameAsync;
                if (ReferenceEquals(_lifetimeCts, lifetime)) _lifetimeCts = null;
                lifetime.Dispose();
                throw;
            }
        }

        public async Task StopAsync(CancellationToken cancellationToken = default(CancellationToken))
        {
            if (!_started) return;
            _started = false;
            var lifetime = _lifetimeCts;
            _lifetimeCts = null;
            if (lifetime != null) lifetime.Cancel();
            _transport.FrameReceived -= HandleFrameAsync;
            try
            {
                await _transport.StopAsync(cancellationToken).ConfigureAwait(false);
            }
            finally
            {
                if (lifetime != null) lifetime.Dispose();
            }
        }

        public Task SubmitActionAsync(string actionKind, byte[] payload, CancellationToken cancellationToken = default(CancellationToken))
        {
            return SubmitActionAsync(KnownNextTurnIndex, actionKind, payload, cancellationToken);
        }

        public async Task SubmitActionAsync(long expectedTurnIndex, string actionKind, byte[] payload, CancellationToken cancellationToken = default(CancellationToken))
        {
            EnsureStarted();
            if (expectedTurnIndex < 0) throw new ArgumentOutOfRangeException(nameof(expectedTurnIndex));
            if (string.IsNullOrWhiteSpace(actionKind)) throw new ArgumentException("Action kind is required.", nameof(actionKind));

            var sequence = NextSequence();
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

        public async Task RequestResyncAsync(CancellationToken cancellationToken = default(CancellationToken))
        {
            await RequestResyncAsync(KnownNextTurnIndex, cancellationToken).ConfigureAwait(false);
        }

        public async Task RequestResyncAsync(long knownNextTurnIndex, CancellationToken cancellationToken = default(CancellationToken))
        {
            EnsureStarted();
            if (IsHost) throw new InvalidOperationException("The authoritative host does not request resync from itself.");
            if (knownNextTurnIndex < 0) throw new ArgumentOutOfRangeException(nameof(knownNextTurnIndex));

            var packet = new SolarPacket(
                SolarPacketType.ResyncRequest,
                SessionId,
                LocalPeerId,
                NextSequence(),
                knownNextTurnIndex,
                Array.Empty<byte>());
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
                    if (packet.Type == SolarPacketType.TurnAction)
                    {
                        string actionKind;
                        byte[] payload;
                        TurnWireCodec.DecodeAction(packet.Payload, out actionKind, out payload);
                        await CommitAsHostAsync(
                            new SolarTurnAction(packet.SenderId, packet.TurnIndex, packet.Sequence, actionKind, payload),
                            CancellationToken.None).ConfigureAwait(false);
                    }
                    else if (packet.Type == SolarPacketType.ResyncRequest)
                    {
                        await HandleResyncRequestAsHostAsync(packet, CancellationToken.None).ConfigureAwait(false);
                    }
                    return;
                }

                if (!string.Equals(frame.RemotePeerId, HostPeerId, StringComparison.Ordinal))
                    throw new InvalidDataException("Client received authoritative packet from a non-host peer.");

                if (packet.Type == SolarPacketType.TurnCommitted)
                {
                    HandleCommittedAsClient(packet);
                }
                else if (packet.Type == SolarPacketType.TurnRejected)
                {
                    RaiseRejected(new SolarTurnRejection(LocalPeerId, packet.TurnIndex, TurnWireCodec.DecodeRejection(packet.Payload)));
                }
                else if (packet.Type == SolarPacketType.Snapshot)
                {
                    HandleSnapshotAsClient(packet);
                }
                else if (packet.Type == SolarPacketType.ResyncUnavailable)
                {
                    RaiseResyncFailed(new SolarResyncFailure(packet.TurnIndex, StateWireCodec.DecodeResyncUnavailable(packet.Payload)));
                }
            }
            catch (Exception ex)
            {
                RaiseProtocolFault(ex);
            }
        }

        private async Task CommitAsHostAsync(SolarTurnAction action, CancellationToken cancellationToken)
        {
            await _hostGate.WaitAsync(cancellationToken).ConfigureAwait(false);
            try
            {
                SolarTurnCommit commit;
                SolarTurnRejectReason reason;
                Func<SolarTurnAction, bool> validator = null;
                if (_gameStateMachine != null)
                {
                    validator = candidate => _gameStateMachine.TryApply(new SolarGameAction(
                        candidate.ActorId,
                        candidate.ExpectedTurnIndex,
                        candidate.ActionKind,
                        candidate.Payload));
                }

                if (!_hostCoordinator.TryCommit(action, validator, out commit, out reason))
                {
                    await RejectAsHostAsync(action.ActorId, reason, cancellationToken).ConfigureAwait(false);
                    return;
                }

                var stateHash = _gameStateMachine == null ? string.Empty : CaptureStateHash();
                commit = commit.WithStateHash(stateHash);
                _journal.Add(commit);
                SetKnownState(commit.NextTurnIndex, commit.NextPlayerId, commit.Round, stateHash);
                RaiseCommitted(commit);

                var committedPacket = CreateCommittedPacket(commit);
                await _transport.BroadcastAsync(SolarPacketCodec.Encode(committedPacket), cancellationToken).ConfigureAwait(false);
            }
            finally
            {
                _hostGate.Release();
            }
        }

        private async Task RejectAsHostAsync(string actorId, SolarTurnRejectReason reason, CancellationToken cancellationToken)
        {
            var rejection = new SolarTurnRejection(actorId, _hostCoordinator.TurnIndex, reason);
            if (string.Equals(actorId, LocalPeerId, StringComparison.Ordinal))
            {
                RaiseRejected(rejection);
                return;
            }

            var packet = new SolarPacket(
                SolarPacketType.TurnRejected,
                SessionId,
                LocalPeerId,
                NextSequence(),
                _hostCoordinator.TurnIndex,
                TurnWireCodec.EncodeRejection(reason));
            await _transport.SendAsync(actorId, SolarPacketCodec.Encode(packet), cancellationToken).ConfigureAwait(false);
        }

        private SolarPacket CreateCommittedPacket(SolarTurnCommit commit)
        {
            return new SolarPacket(
                SolarPacketType.TurnCommitted,
                SessionId,
                LocalPeerId,
                NextSequence(),
                commit.CommittedTurnIndex,
                TurnWireCodec.EncodeCommit(commit),
                commit.StateHash);
        }

        private void HandleCommittedAsClient(SolarPacket packet)
        {
            var commit = TurnWireCodec.DecodeCommit(packet.TurnIndex, packet.Payload, packet.StateHash);
            if (commit.NextTurnIndex != commit.CommittedTurnIndex + 1)
                throw new InvalidDataException("Committed turn does not advance exactly one turn.");

            SolarStateMismatch mismatch = null;
            long resyncFrom = -1;

            lock (_clientStateGate)
            {
                var known = KnownNextTurnIndex;
                if (commit.CommittedTurnIndex < known)
                    return; // replay/duplicate already applied

                if (commit.CommittedTurnIndex > known)
                {
                    mismatch = new SolarStateMismatch(
                        SolarStateMismatchReason.TurnGap,
                        known,
                        commit.CommittedTurnIndex,
                        string.Empty,
                        LastStateHash);
                    resyncFrom = known;
                }
                else
                {
                    string actualHash = string.Empty;
                    if (_gameStateMachine != null)
                    {
                        var applied = _gameStateMachine.TryApply(new SolarGameAction(
                            commit.ActorId,
                            commit.CommittedTurnIndex,
                            commit.ActionKind,
                            commit.Payload));
                        if (!applied)
                        {
                            mismatch = new SolarStateMismatch(
                                SolarStateMismatchReason.ReducerRejectedCommittedAction,
                                known,
                                commit.CommittedTurnIndex,
                                commit.StateHash,
                                LastStateHash);
                            resyncFrom = known;
                        }
                        else
                        {
                            actualHash = CaptureStateHash();
                        }
                    }

                    if (mismatch == null)
                    {
                        SetKnownState(commit.NextTurnIndex, commit.NextPlayerId, commit.Round, actualHash);
                        RaiseCommitted(commit);

                        if (_gameStateMachine != null && !string.IsNullOrEmpty(commit.StateHash) &&
                            !string.Equals(commit.StateHash, actualHash, StringComparison.Ordinal))
                        {
                            mismatch = new SolarStateMismatch(
                                SolarStateMismatchReason.DigestMismatch,
                                commit.NextTurnIndex,
                                commit.CommittedTurnIndex,
                                commit.StateHash,
                                actualHash);
                            resyncFrom = commit.NextTurnIndex;
                        }
                    }
                }
            }

            if (mismatch != null)
            {
                RaiseStateMismatch(mismatch);
                ScheduleAutomaticResync(resyncFrom);
            }
        }

        private async Task HandleResyncRequestAsHostAsync(SolarPacket request, CancellationToken cancellationToken)
        {
            await _hostGate.WaitAsync(cancellationToken).ConfigureAwait(false);
            try
            {
                if (!_hostCoordinator.IsKnownPlayer(request.SenderId))
                {
                    await SendResyncUnavailableAsync(request.SenderId, request.TurnIndex, SolarResyncFailureReason.UnknownPlayer, cancellationToken).ConfigureAwait(false);
                    return;
                }

                var authoritativeNextTurn = _hostCoordinator.TurnIndex;
                if (request.TurnIndex > authoritativeNextTurn)
                {
                    await SendResyncUnavailableAsync(request.SenderId, request.TurnIndex, SolarResyncFailureReason.InvalidTurnIndex, cancellationToken).ConfigureAwait(false);
                    return;
                }

                if (request.TurnIndex < authoritativeNextTurn)
                {
                    List<SolarTurnCommit> replay;
                    if (_journal.TryGetRange(request.TurnIndex, authoritativeNextTurn, out replay))
                    {
                        foreach (var commit in replay)
                            await _transport.SendAsync(request.SenderId, SolarPacketCodec.Encode(CreateCommittedPacket(commit)), cancellationToken).ConfigureAwait(false);
                        return;
                    }
                }

                if (_gameStateMachine == null)
                {
                    await SendResyncUnavailableAsync(request.SenderId, request.TurnIndex, SolarResyncFailureReason.SnapshotUnavailable, cancellationToken).ConfigureAwait(false);
                    return;
                }

                var state = CaptureStateBytes();
                var stateHash = SolarStateDigest.Compute(state);
                var snapshotPacket = new SolarPacket(
                    SolarPacketType.Snapshot,
                    SessionId,
                    LocalPeerId,
                    NextSequence(),
                    authoritativeNextTurn,
                    StateWireCodec.EncodeSnapshot(_hostCoordinator.CurrentPlayerId, _hostCoordinator.Round, state),
                    stateHash);
                await _transport.SendAsync(request.SenderId, SolarPacketCodec.Encode(snapshotPacket), cancellationToken).ConfigureAwait(false);
            }
            finally
            {
                _hostGate.Release();
            }
        }

        private async Task SendResyncUnavailableAsync(
            string peerId,
            long requestedTurnIndex,
            SolarResyncFailureReason reason,
            CancellationToken cancellationToken)
        {
            var packet = new SolarPacket(
                SolarPacketType.ResyncUnavailable,
                SessionId,
                LocalPeerId,
                NextSequence(),
                requestedTurnIndex,
                StateWireCodec.EncodeResyncUnavailable(reason));
            await _transport.SendAsync(peerId, SolarPacketCodec.Encode(packet), cancellationToken).ConfigureAwait(false);
        }

        private void HandleSnapshotAsClient(SolarPacket packet)
        {
            if (_gameStateMachine == null)
            {
                RaiseResyncFailed(new SolarResyncFailure(packet.TurnIndex, SolarResyncFailureReason.SnapshotUnavailable));
                return;
            }
            if (string.IsNullOrWhiteSpace(packet.StateHash))
                throw new InvalidDataException("Authoritative snapshot is missing its state digest.");

            var snapshot = StateWireCodec.DecodeSnapshot(packet.TurnIndex, packet.StateHash, packet.Payload);
            lock (_clientStateGate)
            {
                if (snapshot.NextTurnIndex < KnownNextTurnIndex)
                    return; // stale response must never rewind newer state

                var wireHash = SolarStateDigest.Compute(snapshot.State);
                if (!string.Equals(wireHash, snapshot.StateHash, StringComparison.Ordinal))
                    throw new InvalidDataException("Authoritative snapshot digest does not match snapshot bytes.");

                _gameStateMachine.RestoreSnapshot(snapshot.State);
                var restoredHash = CaptureStateHash();
                if (!string.Equals(restoredHash, snapshot.StateHash, StringComparison.Ordinal))
                    throw new InvalidDataException("Game state did not round-trip to the authoritative snapshot digest.");

                SetKnownState(snapshot.NextTurnIndex, snapshot.CurrentPlayerId, snapshot.Round, restoredHash);
                RaiseSnapshotApplied(snapshot);
            }
        }

        private byte[] CaptureStateBytes()
        {
            var state = _gameStateMachine.CaptureSnapshot();
            if (state == null) throw new InvalidOperationException("ISolarGameStateMachine.CaptureSnapshot returned null.");
            return state;
        }

        private string CaptureStateHash()
        {
            return SolarStateDigest.Compute(CaptureStateBytes());
        }

        private void ScheduleAutomaticResync(long knownNextTurnIndex)
        {
            if (knownNextTurnIndex < 0 || IsHost || !_started) return;

            CancellationToken token;
            lock (_resyncGate)
            {
                if (_automaticResyncRunning)
                {
                    _automaticResyncRetryRequested = true;
                    _automaticResyncRetryTurn = knownNextTurnIndex;
                    return;
                }
                _automaticResyncRunning = true;
                _automaticResyncRetryRequested = false;
                token = _lifetimeCts == null ? CancellationToken.None : _lifetimeCts.Token;
            }

            RunAutomaticResyncAsync(knownNextTurnIndex, token);
        }

        private async void RunAutomaticResyncAsync(long knownNextTurnIndex, CancellationToken cancellationToken)
        {
            var attempts = 0;
            var nextRequest = knownNextTurnIndex;
            try
            {
                while (attempts < 2 && !cancellationToken.IsCancellationRequested)
                {
                    attempts++;
                    try
                    {
                        await RequestResyncAsync(nextRequest, cancellationToken).ConfigureAwait(false);
                    }
                    catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
                    {
                        return;
                    }
                    catch (Exception ex)
                    {
                        RaiseProtocolFault(ex);
                        return;
                    }

                    lock (_resyncGate)
                    {
                        if (!_automaticResyncRetryRequested)
                            return;
                        nextRequest = _automaticResyncRetryTurn;
                        _automaticResyncRetryRequested = false;
                    }
                }

                if (!cancellationToken.IsCancellationRequested)
                    RaiseResyncFailed(new SolarResyncFailure(nextRequest, SolarResyncFailureReason.AutomaticRetryLimit));
            }
            finally
            {
                lock (_resyncGate)
                {
                    _automaticResyncRunning = false;
                    _automaticResyncRetryRequested = false;
                }
            }
        }

        private long NextSequence()
        {
            return Interlocked.Increment(ref _nextSequence);
        }

        private void SetKnownState(long nextTurnIndex, string currentPlayerId, int round, string stateHash)
        {
            lock (_metadataGate)
            {
                _knownNextTurnIndex = nextTurnIndex;
                _knownCurrentPlayerId = currentPlayerId ?? string.Empty;
                _knownRound = round;
                _lastStateHash = stateHash ?? string.Empty;
            }
        }

        private void SetLastStateHash(string stateHash)
        {
            lock (_metadataGate) _lastStateHash = stateHash ?? string.Empty;
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

        private void RaiseStateMismatch(SolarStateMismatch mismatch)
        {
            var handler = StateMismatchDetected;
            if (handler != null) handler(mismatch);
        }

        private void RaiseSnapshotApplied(SolarStateSnapshot snapshot)
        {
            var handler = SnapshotApplied;
            if (handler != null) handler(snapshot);
        }

        private void RaiseResyncFailed(SolarResyncFailure failure)
        {
            var handler = ResyncFailed;
            if (handler != null) handler(failure);
        }

        private void RaiseProtocolFault(Exception exception)
        {
            var handler = ProtocolFaulted;
            if (handler != null) handler(exception);
        }

        private void EnsureStarted()
        {
            if (!_started) throw new InvalidOperationException("SolarTurnSession has not been started.");
        }
    }
}
