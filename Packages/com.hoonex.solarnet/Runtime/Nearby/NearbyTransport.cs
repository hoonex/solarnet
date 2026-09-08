using System;
using System.Collections.Generic;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using SolarNet.Transport;

namespace SolarNet.Nearby
{
    public sealed class NearbyTransport : ISolarTransport
    {
        private readonly object _gate = new object();
        private readonly INearbyPeerAdapter _adapter;
        private readonly NearbyTransportOptions _options;
        private readonly Dictionary<string, string> _endpointToPeer = new Dictionary<string, string>(StringComparer.Ordinal);
        private readonly Dictionary<string, string> _peerToEndpoint = new Dictionary<string, string>(StringComparer.Ordinal);
        private readonly SemaphoreSlim _lifecycle = new SemaphoreSlim(1, 1);
        private bool _started;

        public NearbyTransport(string localPeerId, INearbyPeerAdapter adapter, NearbyTransportOptions options)
        {
            if (string.IsNullOrWhiteSpace(localPeerId)) throw new ArgumentException("Local peer ID is required.", nameof(localPeerId));
            _adapter = adapter ?? throw new ArgumentNullException(nameof(adapter));
            _options = options ?? throw new ArgumentNullException(nameof(options));
            LocalPeerId = localPeerId;
        }

        public string LocalPeerId { get; private set; }
        public event Func<SolarFrame, Task> FrameReceived;
        public event Action<NearbyEndpoint> EndpointDiscovered;
        public event Action<string> EndpointLost;
        public event Action<NearbyVerificationRequest> ConnectionVerificationRequired;
        public event Action<string> PeerConnected;
        public event Action<string> PeerDisconnected;
        public event Action<Exception> Faulted;

        public async Task StartAsync(CancellationToken cancellationToken = default(CancellationToken))
        {
            await _lifecycle.WaitAsync(cancellationToken).ConfigureAwait(false);
            try
            {
                if (_started) return;
                SubscribeAdapter();
                try
                {
                    if (_options.Role == NearbyConnectionRole.Advertiser)
                        await _adapter.StartAdvertisingAsync(_options.ServiceId, _options.EndpointName, _options.Strategy, cancellationToken).ConfigureAwait(false);
                    else
                        await _adapter.StartDiscoveryAsync(_options.ServiceId, _options.EndpointName, _options.Strategy, cancellationToken).ConfigureAwait(false);
                    lock (_gate) _started = true;
                }
                catch
                {
                    UnsubscribeAdapter();
                    try { await _adapter.StopAllAsync(CancellationToken.None).ConfigureAwait(false); } catch { }
                    throw;
                }
            }
            finally
            {
                _lifecycle.Release();
            }
        }

        public async Task StopAsync(CancellationToken cancellationToken = default(CancellationToken))
        {
            await _lifecycle.WaitAsync(cancellationToken).ConfigureAwait(false);
            try
            {
                lock (_gate)
                {
                    if (!_started) return;
                    _started = false;
                }

                UnsubscribeAdapter();
                await _adapter.StopAllAsync(cancellationToken).ConfigureAwait(false);
                lock (_gate)
                {
                    _endpointToPeer.Clear();
                    _peerToEndpoint.Clear();
                }
            }
            finally
            {
                _lifecycle.Release();
            }
        }

        public Task RequestConnectionAsync(string endpointId, CancellationToken cancellationToken = default(CancellationToken))
        {
            EnsureStarted();
            return _adapter.RequestConnectionAsync(endpointId, _options.EndpointName, cancellationToken);
        }

        public Task AcceptConnectionAsync(string endpointId, CancellationToken cancellationToken = default(CancellationToken))
        {
            EnsureStarted();
            return _adapter.AcceptConnectionAsync(endpointId, cancellationToken);
        }

        public Task RejectConnectionAsync(string endpointId, CancellationToken cancellationToken = default(CancellationToken))
        {
            EnsureStarted();
            return _adapter.RejectConnectionAsync(endpointId, cancellationToken);
        }

        public Task StopAdvertisingAsync(CancellationToken cancellationToken = default(CancellationToken))
        {
            EnsureStarted();
            return _adapter.StopAdvertisingAsync(cancellationToken);
        }

        public Task StopDiscoveryAsync(CancellationToken cancellationToken = default(CancellationToken))
        {
            EnsureStarted();
            return _adapter.StopDiscoveryAsync(cancellationToken);
        }

        public Task DisconnectPeerAsync(string remotePeerId, CancellationToken cancellationToken = default(CancellationToken))
        {
            EnsureStarted();
            var endpointId = ResolveEndpoint(remotePeerId);
            return _adapter.DisconnectAsync(endpointId, cancellationToken);
        }

        public Task SendAsync(string remotePeerId, byte[] frame, CancellationToken cancellationToken = default(CancellationToken))
        {
            EnsureStarted();
            var endpointId = ResolveEndpoint(remotePeerId);
            var envelope = NearbyEnvelopeCodec.EncodeData(LocalPeerId, frame ?? Array.Empty<byte>());
            return _adapter.SendBytesAsync(endpointId, envelope, cancellationToken);
        }

        public Task BroadcastAsync(byte[] frame, CancellationToken cancellationToken = default(CancellationToken))
        {
            EnsureStarted();
            var envelope = NearbyEnvelopeCodec.EncodeData(LocalPeerId, frame ?? Array.Empty<byte>());
            return _adapter.BroadcastBytesAsync(envelope, cancellationToken);
        }

        private string ResolveEndpoint(string remotePeerId)
        {
            if (string.IsNullOrWhiteSpace(remotePeerId)) throw new ArgumentException("Remote peer ID is required.", nameof(remotePeerId));
            lock (_gate)
            {
                string endpointId;
                if (!_peerToEndpoint.TryGetValue(remotePeerId, out endpointId))
                    throw new InvalidOperationException("Nearby peer is not connected or has not completed the SolarNet peer handshake: " + remotePeerId);
                return endpointId;
            }
        }

        private void SubscribeAdapter()
        {
            _adapter.EndpointFound += OnEndpointFound;
            _adapter.EndpointLost += OnEndpointLost;
            _adapter.VerificationRequired += OnVerificationRequired;
            _adapter.Connected += OnConnected;
            _adapter.Disconnected += OnDisconnected;
            _adapter.BytesReceived += OnBytesReceived;
            _adapter.Faulted += OnAdapterFaulted;
        }

        private void UnsubscribeAdapter()
        {
            _adapter.EndpointFound -= OnEndpointFound;
            _adapter.EndpointLost -= OnEndpointLost;
            _adapter.VerificationRequired -= OnVerificationRequired;
            _adapter.Connected -= OnConnected;
            _adapter.Disconnected -= OnDisconnected;
            _adapter.BytesReceived -= OnBytesReceived;
            _adapter.Faulted -= OnAdapterFaulted;
        }

        private void OnEndpointFound(NearbyEndpoint endpoint)
        {
            var handler = EndpointDiscovered;
            if (handler != null) handler(endpoint);
        }

        private void OnEndpointLost(string endpointId)
        {
            var handler = EndpointLost;
            if (handler != null) handler(endpointId);
        }

        private void OnVerificationRequired(NearbyVerificationRequest request)
        {
            var handler = ConnectionVerificationRequired;
            if (handler != null) handler(request);
        }

        private void OnConnected(string endpointId)
        {
            HandleConnectedAsync(endpointId);
        }

        private async void HandleConnectedAsync(string endpointId)
        {
            try
            {
                await _adapter.SendBytesAsync(endpointId, NearbyEnvelopeCodec.EncodeHello(LocalPeerId), CancellationToken.None).ConfigureAwait(false);
                if (_options.Role == NearbyConnectionRole.Discoverer && _options.StopDiscoveryAfterFirstConnection)
                    await _adapter.StopDiscoveryAsync(CancellationToken.None).ConfigureAwait(false);
            }
            catch (Exception ex)
            {
                RaiseFault(ex);
            }
        }

        private void OnDisconnected(string endpointId)
        {
            string peerId = null;
            lock (_gate)
            {
                if (_endpointToPeer.TryGetValue(endpointId, out peerId))
                {
                    _endpointToPeer.Remove(endpointId);
                    string mappedEndpoint;
                    if (_peerToEndpoint.TryGetValue(peerId, out mappedEndpoint) && string.Equals(mappedEndpoint, endpointId, StringComparison.Ordinal))
                        _peerToEndpoint.Remove(peerId);
                }
            }

            if (peerId != null)
            {
                var handler = PeerDisconnected;
                if (handler != null) handler(peerId);
            }
        }

        private void OnBytesReceived(string endpointId, byte[] payload)
        {
            HandleBytesReceivedAsync(endpointId, payload);
        }

        private async void HandleBytesReceivedAsync(string endpointId, byte[] payload)
        {
            try
            {
                var envelope = NearbyEnvelopeCodec.Decode(payload);
                bool newlyBound;
                string conflict;
                if (!TryBindPeer(endpointId, envelope.PeerId, out newlyBound, out conflict))
                {
                    RaiseFault(new InvalidDataException(conflict));
                    await _adapter.DisconnectAsync(endpointId, CancellationToken.None).ConfigureAwait(false);
                    return;
                }

                if (newlyBound)
                {
                    var peerHandler = PeerConnected;
                    if (peerHandler != null) peerHandler(envelope.PeerId);
                }

                if (envelope.Type != NearbyEnvelopeType.Data) return;

                var handlers = FrameReceived;
                if (handlers == null) return;
                var frame = new SolarFrame(envelope.PeerId, envelope.Data);
                foreach (var callback in handlers.GetInvocationList())
                    await ((Func<SolarFrame, Task>)callback)(frame).ConfigureAwait(false);
            }
            catch (Exception ex)
            {
                RaiseFault(ex);
            }
        }

        private bool TryBindPeer(string endpointId, string peerId, out bool newlyBound, out string conflict)
        {
            newlyBound = false;
            conflict = null;
            if (string.IsNullOrWhiteSpace(endpointId))
            {
                conflict = "Nearby adapter supplied an empty endpoint ID.";
                return false;
            }
            if (string.Equals(peerId, LocalPeerId, StringComparison.Ordinal))
            {
                conflict = "Remote Nearby endpoint attempted to claim the local SolarNet peer ID.";
                return false;
            }

            lock (_gate)
            {
                string mappedPeer;
                if (_endpointToPeer.TryGetValue(endpointId, out mappedPeer))
                {
                    if (!string.Equals(mappedPeer, peerId, StringComparison.Ordinal))
                    {
                        conflict = "Nearby endpoint changed SolarNet peer identity from '" + mappedPeer + "' to '" + peerId + "'.";
                        return false;
                    }
                    return true;
                }

                string mappedEndpoint;
                if (_peerToEndpoint.TryGetValue(peerId, out mappedEndpoint) && !string.Equals(mappedEndpoint, endpointId, StringComparison.Ordinal))
                {
                    conflict = "SolarNet peer ID '" + peerId + "' is already bound to another Nearby endpoint.";
                    return false;
                }

                _endpointToPeer[endpointId] = peerId;
                _peerToEndpoint[peerId] = endpointId;
                newlyBound = true;
                return true;
            }
        }

        private void OnAdapterFaulted(Exception exception)
        {
            RaiseFault(exception);
        }

        private void RaiseFault(Exception exception)
        {
            var handler = Faulted;
            if (handler != null) handler(exception);
        }

        private void EnsureStarted()
        {
            lock (_gate)
            {
                if (!_started) throw new InvalidOperationException("NearbyTransport has not been started.");
            }
        }
    }
}
