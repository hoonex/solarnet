using System;
using System.Collections.Generic;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using SolarNet.Transport;

namespace SolarNet.BluetoothClassic
{
    public sealed class BluetoothClassicTransport : ISolarTransport
    {
        private readonly object _gate = new object();
        private readonly IBluetoothClassicPeerAdapter _adapter;
        private readonly BluetoothClassicTransportOptions _options;
        private readonly Dictionary<string, string> _connectionToPeer = new Dictionary<string, string>(StringComparer.Ordinal);
        private readonly Dictionary<string, string> _peerToConnection = new Dictionary<string, string>(StringComparer.Ordinal);
        private readonly Dictionary<string, string> _connectionToDeviceAddress = new Dictionary<string, string>(StringComparer.Ordinal);
        private readonly Dictionary<string, string> _peerToLastDeviceAddress = new Dictionary<string, string>(StringComparer.Ordinal);
        private readonly SemaphoreSlim _lifecycle = new SemaphoreSlim(1, 1);
        private bool _started;

        public BluetoothClassicTransport(string localPeerId, IBluetoothClassicPeerAdapter adapter, BluetoothClassicTransportOptions options)
        {
            if (string.IsNullOrWhiteSpace(localPeerId)) throw new ArgumentException("Local peer ID is required.", nameof(localPeerId));
            _adapter = adapter ?? throw new ArgumentNullException(nameof(adapter));
            _options = options ?? throw new ArgumentNullException(nameof(options));
            LocalPeerId = localPeerId;
        }

        public string LocalPeerId { get; private set; }
        public event Func<SolarFrame, Task> FrameReceived;
        public event Action<string> PeerConnected;
        public event Action<string> PeerDisconnected;
        public event Action<Exception> Faulted;

        public IReadOnlyList<BluetoothClassicDevice> GetBondedDevices()
        {
            return _adapter.GetBondedDevices();
        }

        public bool TryGetLastKnownDeviceAddress(string remotePeerId, out string deviceAddress)
        {
            deviceAddress = null;
            if (string.IsNullOrWhiteSpace(remotePeerId)) return false;
            lock (_gate) return _peerToLastDeviceAddress.TryGetValue(remotePeerId, out deviceAddress);
        }

        public async Task StartAsync(CancellationToken cancellationToken = default(CancellationToken))
        {
            await _lifecycle.WaitAsync(cancellationToken).ConfigureAwait(false);
            try
            {
                if (_started) return;
                Subscribe();
                try
                {
                    if (_options.Role == BluetoothClassicRole.Server)
                        await _adapter.StartServerAsync(_options.ServiceName, _options.ServiceUuid, cancellationToken).ConfigureAwait(false);
                    lock (_gate) _started = true;
                }
                catch
                {
                    Unsubscribe();
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
                Unsubscribe();
                await _adapter.StopAllAsync(cancellationToken).ConfigureAwait(false);
                lock (_gate)
                {
                    _connectionToPeer.Clear();
                    _peerToConnection.Clear();
                    _connectionToDeviceAddress.Clear();
                    _peerToLastDeviceAddress.Clear();
                }
            }
            finally
            {
                _lifecycle.Release();
            }
        }

        public Task ConnectAsync(string deviceAddress, CancellationToken cancellationToken = default(CancellationToken))
        {
            EnsureStarted();
            if (_options.Role != BluetoothClassicRole.Client) throw new InvalidOperationException("Only a Bluetooth Classic client initiates a connection.");
            if (string.IsNullOrWhiteSpace(deviceAddress)) throw new ArgumentException("Device address is required.", nameof(deviceAddress));
            return _adapter.ConnectAsync(deviceAddress, _options.ServiceUuid, cancellationToken);
        }

        public Task DisconnectPeerAsync(string remotePeerId, CancellationToken cancellationToken = default(CancellationToken))
        {
            EnsureStarted();
            return _adapter.DisconnectAsync(ResolveConnection(remotePeerId), cancellationToken);
        }

        public Task SendAsync(string remotePeerId, byte[] frame, CancellationToken cancellationToken = default(CancellationToken))
        {
            EnsureStarted();
            var envelope = BluetoothClassicEnvelopeCodec.EncodeData(LocalPeerId, frame ?? Array.Empty<byte>());
            return _adapter.SendBytesAsync(ResolveConnection(remotePeerId), envelope, cancellationToken);
        }

        public Task BroadcastAsync(byte[] frame, CancellationToken cancellationToken = default(CancellationToken))
        {
            EnsureStarted();
            return _adapter.BroadcastBytesAsync(BluetoothClassicEnvelopeCodec.EncodeData(LocalPeerId, frame ?? Array.Empty<byte>()), cancellationToken);
        }

        private string ResolveConnection(string peerId)
        {
            if (string.IsNullOrWhiteSpace(peerId)) throw new ArgumentException("Remote peer ID is required.", nameof(peerId));
            lock (_gate)
            {
                string connectionId;
                if (!_peerToConnection.TryGetValue(peerId, out connectionId))
                    throw new InvalidOperationException("Bluetooth Classic peer has not completed the SolarNet handshake: " + peerId);
                return connectionId;
            }
        }

        private void Subscribe()
        {
            _adapter.Connected += OnConnected;
            _adapter.Disconnected += OnDisconnected;
            _adapter.BytesReceived += OnBytesReceived;
            _adapter.Faulted += OnFaulted;
        }

        private void Unsubscribe()
        {
            _adapter.Connected -= OnConnected;
            _adapter.Disconnected -= OnDisconnected;
            _adapter.BytesReceived -= OnBytesReceived;
            _adapter.Faulted -= OnFaulted;
        }

        private void OnConnected(string connectionId, string deviceAddress, string deviceName)
        {
            if (!string.IsNullOrWhiteSpace(connectionId) && !string.IsNullOrWhiteSpace(deviceAddress))
            {
                lock (_gate)
                {
                    _connectionToDeviceAddress[connectionId] = deviceAddress;
                    string peerId;
                    if (_connectionToPeer.TryGetValue(connectionId, out peerId))
                        _peerToLastDeviceAddress[peerId] = deviceAddress;
                }
            }
            SendHelloAsync(connectionId);
        }

        private async void SendHelloAsync(string connectionId)
        {
            try
            {
                await _adapter.SendBytesAsync(connectionId, BluetoothClassicEnvelopeCodec.EncodeHello(LocalPeerId), CancellationToken.None).ConfigureAwait(false);
            }
            catch (Exception ex)
            {
                RaiseFault(ex);
            }
        }

        private void OnDisconnected(string connectionId)
        {
            string peerId = null;
            lock (_gate)
            {
                _connectionToDeviceAddress.Remove(connectionId);
                if (_connectionToPeer.TryGetValue(connectionId, out peerId))
                {
                    _connectionToPeer.Remove(connectionId);
                    string mapped;
                    if (_peerToConnection.TryGetValue(peerId, out mapped) && string.Equals(mapped, connectionId, StringComparison.Ordinal))
                        _peerToConnection.Remove(peerId);
                }
            }
            if (peerId != null)
            {
                var handler = PeerDisconnected;
                if (handler != null) handler(peerId);
            }
        }

        private void OnBytesReceived(string connectionId, byte[] payload)
        {
            HandleBytesAsync(connectionId, payload);
        }

        private async void HandleBytesAsync(string connectionId, byte[] payload)
        {
            try
            {
                var envelope = BluetoothClassicEnvelopeCodec.Decode(payload);
                bool newlyBound;
                string conflict;
                if (!TryBind(connectionId, envelope.PeerId, out newlyBound, out conflict))
                {
                    RaiseFault(new InvalidDataException(conflict));
                    await _adapter.DisconnectAsync(connectionId, CancellationToken.None).ConfigureAwait(false);
                    return;
                }
                if (newlyBound)
                {
                    var connected = PeerConnected;
                    if (connected != null) connected(envelope.PeerId);
                }
                if (envelope.Type != BluetoothClassicEnvelopeType.Data) return;

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

        private bool TryBind(string connectionId, string peerId, out bool newlyBound, out string conflict)
        {
            newlyBound = false;
            conflict = null;
            if (string.IsNullOrWhiteSpace(connectionId))
            {
                conflict = "Bluetooth Classic adapter supplied an empty connection ID.";
                return false;
            }
            if (string.Equals(peerId, LocalPeerId, StringComparison.Ordinal))
            {
                conflict = "Remote Bluetooth device attempted to claim the local SolarNet peer ID.";
                return false;
            }
            lock (_gate)
            {
                string existingPeer;
                if (_connectionToPeer.TryGetValue(connectionId, out existingPeer))
                {
                    if (!string.Equals(existingPeer, peerId, StringComparison.Ordinal))
                    {
                        conflict = "Bluetooth connection changed SolarNet peer identity.";
                        return false;
                    }
                    return true;
                }
                string existingConnection;
                if (_peerToConnection.TryGetValue(peerId, out existingConnection) && !string.Equals(existingConnection, connectionId, StringComparison.Ordinal))
                {
                    conflict = "SolarNet peer ID '" + peerId + "' is already connected over Bluetooth Classic.";
                    return false;
                }
                _connectionToPeer[connectionId] = peerId;
                _peerToConnection[peerId] = connectionId;
                string deviceAddress;
                if (_connectionToDeviceAddress.TryGetValue(connectionId, out deviceAddress) && !string.IsNullOrWhiteSpace(deviceAddress))
                    _peerToLastDeviceAddress[peerId] = deviceAddress;
                newlyBound = true;
                return true;
            }
        }

        private void OnFaulted(Exception exception)
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
                if (!_started) throw new InvalidOperationException("BluetoothClassicTransport has not been started.");
        }
    }
}
