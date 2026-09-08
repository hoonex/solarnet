using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;

namespace SolarNet.Transport
{
    public sealed class LoopbackTransportHub
    {
        private readonly object _gate = new object();
        private readonly Dictionary<string, LoopbackTransport> _endpoints = new Dictionary<string, LoopbackTransport>(StringComparer.Ordinal);

        public LoopbackTransport CreateEndpoint(string peerId)
        {
            return new LoopbackTransport(this, peerId);
        }

        internal void Register(LoopbackTransport endpoint)
        {
            lock (_gate)
            {
                if (_endpoints.ContainsKey(endpoint.LocalPeerId))
                    throw new InvalidOperationException("A loopback endpoint with this peer ID is already started: " + endpoint.LocalPeerId);
                _endpoints.Add(endpoint.LocalPeerId, endpoint);
            }
        }

        internal void Unregister(LoopbackTransport endpoint)
        {
            lock (_gate)
            {
                LoopbackTransport current;
                if (_endpoints.TryGetValue(endpoint.LocalPeerId, out current) && ReferenceEquals(current, endpoint))
                    _endpoints.Remove(endpoint.LocalPeerId);
            }
        }

        internal async Task RouteAsync(string senderId, string remotePeerId, byte[] frame)
        {
            LoopbackTransport target;
            lock (_gate)
            {
                if (!_endpoints.TryGetValue(remotePeerId, out target))
                    throw new InvalidOperationException("Loopback peer is not connected: " + remotePeerId);
            }

            await target.DeliverAsync(new SolarFrame(senderId, Clone(frame))).ConfigureAwait(false);
        }

        internal async Task BroadcastAsync(string senderId, byte[] frame)
        {
            List<LoopbackTransport> targets;
            lock (_gate)
            {
                targets = new List<LoopbackTransport>();
                foreach (var pair in _endpoints)
                    if (!string.Equals(pair.Key, senderId, StringComparison.Ordinal)) targets.Add(pair.Value);
            }

            foreach (var target in targets)
                await target.DeliverAsync(new SolarFrame(senderId, Clone(frame))).ConfigureAwait(false);
        }

        private static byte[] Clone(byte[] frame)
        {
            if (frame == null || frame.Length == 0) return Array.Empty<byte>();
            var copy = new byte[frame.Length];
            Buffer.BlockCopy(frame, 0, copy, 0, frame.Length);
            return copy;
        }
    }

    public sealed class LoopbackTransport : ISolarTransport
    {
        private readonly LoopbackTransportHub _hub;
        private bool _started;

        internal LoopbackTransport(LoopbackTransportHub hub, string peerId)
        {
            if (hub == null) throw new ArgumentNullException(nameof(hub));
            if (string.IsNullOrWhiteSpace(peerId)) throw new ArgumentException("Peer ID is required.", nameof(peerId));
            _hub = hub;
            LocalPeerId = peerId;
        }

        public string LocalPeerId { get; private set; }
        public event Func<SolarFrame, Task> FrameReceived;

        public Task StartAsync(CancellationToken cancellationToken = default(CancellationToken))
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (_started) return Task.CompletedTask;
            _hub.Register(this);
            _started = true;
            return Task.CompletedTask;
        }

        public Task StopAsync(CancellationToken cancellationToken = default(CancellationToken))
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (!_started) return Task.CompletedTask;
            _hub.Unregister(this);
            _started = false;
            return Task.CompletedTask;
        }

        public Task SendAsync(string remotePeerId, byte[] frame, CancellationToken cancellationToken = default(CancellationToken))
        {
            cancellationToken.ThrowIfCancellationRequested();
            EnsureStarted();
            return _hub.RouteAsync(LocalPeerId, remotePeerId, frame ?? Array.Empty<byte>());
        }

        public Task BroadcastAsync(byte[] frame, CancellationToken cancellationToken = default(CancellationToken))
        {
            cancellationToken.ThrowIfCancellationRequested();
            EnsureStarted();
            return _hub.BroadcastAsync(LocalPeerId, frame ?? Array.Empty<byte>());
        }

        internal async Task DeliverAsync(SolarFrame frame)
        {
            var handlers = FrameReceived;
            if (handlers == null) return;
            foreach (var handler in handlers.GetInvocationList())
                await ((Func<SolarFrame, Task>)handler)(frame).ConfigureAwait(false);
        }

        private void EnsureStarted()
        {
            if (!_started) throw new InvalidOperationException("Loopback transport has not been started.");
        }
    }
}
