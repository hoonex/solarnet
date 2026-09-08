using System;
using System.Threading;
using System.Threading.Tasks;
using SolarNet.Turns;

namespace SolarNet.Session
{
    public sealed class SolarDurabilityAdvance
    {
        public SolarDurabilityAdvance(string replicaPeerId, long nextTurnIndex, string stateHash)
        {
            if (string.IsNullOrWhiteSpace(replicaPeerId)) throw new ArgumentException("Replica peer ID is required.", nameof(replicaPeerId));
            if (nextTurnIndex < 0) throw new ArgumentOutOfRangeException(nameof(nextTurnIndex));
            if (string.IsNullOrWhiteSpace(stateHash)) throw new ArgumentException("State hash is required.", nameof(stateHash));
            ReplicaPeerId = replicaPeerId;
            NextTurnIndex = nextTurnIndex;
            StateHash = stateHash;
        }

        public string ReplicaPeerId { get; private set; }
        public long NextTurnIndex { get; private set; }
        public string StateHash { get; private set; }
    }

    public sealed partial class SolarTurnSession
    {
        private readonly object _durabilityGate = new object();
        private string _requiredReplicationPeerId = string.Empty;
        private long _durableNextTurnIndex;
        private SolarTurnCommit _pendingDurabilityCommit;
        private TaskCompletionSource<bool> _pendingDurabilityCompletion;

        public bool DurabilityBarrierEnabled { get { return !string.IsNullOrEmpty(_requiredReplicationPeerId); } }
        public string RequiredReplicationPeerId { get { return _requiredReplicationPeerId; } }

        public long DurableNextTurnIndex
        {
            get
            {
                if (!IsHost) throw new InvalidOperationException("Only the authoritative host owns a durability frontier.");
                if (!DurabilityBarrierEnabled) return KnownNextTurnIndex;
                lock (_durabilityGate) return _durableNextTurnIndex;
            }
        }

        public bool DurabilityPending
        {
            get
            {
                if (!IsHost || !DurabilityBarrierEnabled) return false;
                lock (_durabilityGate) return _pendingDurabilityCommit != null;
            }
        }

        public event Action<SolarDurabilityAdvance> DurabilityAdvanced;

        private void InitializeDurabilityBarrier(string requiredReplicationPeerId)
        {
            if (!IsHost)
            {
                if (!string.IsNullOrWhiteSpace(requiredReplicationPeerId))
                    throw new ArgumentException("Only the authoritative host may require a designated replication peer.", nameof(requiredReplicationPeerId));
                return;
            }

            _durableNextTurnIndex = _hostCoordinator.TurnIndex;
            if (string.IsNullOrWhiteSpace(requiredReplicationPeerId)) return;
            if (!StateIntegrityEnabled)
                throw new ArgumentException("A durability barrier requires state integrity to be enabled.", nameof(requiredReplicationPeerId));
            if (string.Equals(requiredReplicationPeerId, LocalPeerId, StringComparison.Ordinal))
                throw new ArgumentException("The authoritative host cannot be its own designated replica.", nameof(requiredReplicationPeerId));
            if (!_hostCoordinator.IsKnownPlayer(requiredReplicationPeerId))
                throw new ArgumentException("The designated replica must be a known session player.", nameof(requiredReplicationPeerId));

            _requiredReplicationPeerId = requiredReplicationPeerId;
            ReplicationAcknowledged += OnReplicationAcknowledgedForDurability;
        }

        private bool HasPendingDurabilityCommit()
        {
            if (!DurabilityBarrierEnabled) return false;
            lock (_durabilityGate) return _pendingDurabilityCommit != null;
        }

        private Task BeginDurabilityBarrier(SolarTurnCommit commit)
        {
            if (commit == null) throw new ArgumentNullException(nameof(commit));
            if (!DurabilityBarrierEnabled) return Task.CompletedTask;
            if (string.IsNullOrWhiteSpace(commit.StateHash))
                throw new InvalidOperationException("A durability-fenced commit requires an authoritative state digest.");

            lock (_durabilityGate)
            {
                if (_pendingDurabilityCommit != null)
                    throw new InvalidOperationException("A previous authoritative commit is still waiting for designated-replica acknowledgement.");

                _pendingDurabilityCommit = commit;
                _pendingDurabilityCompletion = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
                return _pendingDurabilityCompletion.Task;
            }
        }

        private void OnReplicationAcknowledgedForDurability(SolarReplicationAcknowledgement acknowledgement)
        {
            if (acknowledgement == null || !DurabilityBarrierEnabled) return;
            if (!string.Equals(acknowledgement.PeerId, _requiredReplicationPeerId, StringComparison.Ordinal)) return;

            SolarTurnCommit durableCommit = null;
            TaskCompletionSource<bool> completion = null;
            lock (_durabilityGate)
            {
                if (_pendingDurabilityCommit == null) return;
                if (acknowledgement.NextTurnIndex < _pendingDurabilityCommit.NextTurnIndex) return;

                durableCommit = _pendingDurabilityCommit;
                completion = _pendingDurabilityCompletion;
                _durableNextTurnIndex = durableCommit.NextTurnIndex;
                _pendingDurabilityCommit = null;
                _pendingDurabilityCompletion = null;
            }

            // The proof is durable before user callbacks run. Continuations are asynchronous,
            // so the submitting host task cannot re-enter the turn gate from this callback stack.
            if (completion != null) completion.TrySetResult(true);
            RaiseCommitted(durableCommit);

            var handler = DurabilityAdvanced;
            if (handler != null)
                handler(new SolarDurabilityAdvance(_requiredReplicationPeerId, durableCommit.NextTurnIndex, durableCommit.StateHash));
        }

        private async Task WaitForDurabilityAsync(Task durabilityTask, CancellationToken cancellationToken)
        {
            if (durabilityTask == null) throw new ArgumentNullException(nameof(durabilityTask));
            if (durabilityTask.IsCompleted)
            {
                await durabilityTask.ConfigureAwait(false);
                return;
            }

            var lifetime = _lifetimeCts;
            var lifetimeToken = lifetime == null ? CancellationToken.None : lifetime.Token;
            using (var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, lifetimeToken))
            {
                if (!linked.Token.CanBeCanceled)
                {
                    await durabilityTask.ConfigureAwait(false);
                    return;
                }

                var cancellationTask = Task.Delay(Timeout.Infinite, linked.Token);
                var completed = await Task.WhenAny(durabilityTask, cancellationTask).ConfigureAwait(false);
                if (!ReferenceEquals(completed, durabilityTask))
                    await cancellationTask.ConfigureAwait(false); // throws cancellation

                linked.Cancel(); // release the infinite cancellation delay
                await durabilityTask.ConfigureAwait(false);
            }
        }
    }
}
