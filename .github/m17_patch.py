from pathlib import Path


def replace_one(path, old, new):
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}: {old[:140]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


# Migration transport switches must let callers subscribe before Start/Connect emits events.
replace_one(
    "Packages/com.hoonex.solarnet/Runtime/Nearby/NearbyMigrationTransportSwitch.cs",
    "            NearbyConnectionStrategy strategy = NearbyConnectionStrategy.Star,\n            CancellationToken cancellationToken = default(CancellationToken))",
    "            NearbyConnectionStrategy strategy = NearbyConnectionStrategy.Star,\n            CancellationToken cancellationToken = default(CancellationToken),\n            Action<NearbyTransport> configureTransport = null)")
replace_one(
    "Packages/com.hoonex.solarnet/Runtime/Nearby/NearbyMigrationTransportSwitch.cs",
    "            await next.StartAsync(cancellationToken).ConfigureAwait(false);",
    "            if (configureTransport != null) configureTransport(next);\n            await next.StartAsync(cancellationToken).ConfigureAwait(false);")

replace_one(
    "Packages/com.hoonex.solarnet/Runtime/BluetoothClassic/BluetoothClassicMigrationTransportSwitch.cs",
    "            string serviceUuid = BluetoothClassicTransportOptions.DefaultServiceUuid,\n            CancellationToken cancellationToken = default(CancellationToken))",
    "            string serviceUuid = BluetoothClassicTransportOptions.DefaultServiceUuid,\n            CancellationToken cancellationToken = default(CancellationToken),\n            Action<BluetoothClassicTransport> configureTransport = null)")
replace_one(
    "Packages/com.hoonex.solarnet/Runtime/BluetoothClassic/BluetoothClassicMigrationTransportSwitch.cs",
    "            await next.StartAsync(cancellationToken).ConfigureAwait(false);",
    "            if (configureTransport != null) configureTransport(next);\n            await next.StartAsync(cancellationToken).ConfigureAwait(false);")

# Preserve authenticated RFCOMM device identity until callers capture it for topology rebuild.
bt = "Packages/com.hoonex.solarnet/Runtime/BluetoothClassic/BluetoothClassicTransport.cs"
replace_one(
    bt,
    "        private readonly Dictionary<string, string> _connectionToPeer = new Dictionary<string, string>(StringComparer.Ordinal);\n        private readonly Dictionary<string, string> _peerToConnection = new Dictionary<string, string>(StringComparer.Ordinal);",
    "        private readonly Dictionary<string, string> _connectionToPeer = new Dictionary<string, string>(StringComparer.Ordinal);\n        private readonly Dictionary<string, string> _peerToConnection = new Dictionary<string, string>(StringComparer.Ordinal);\n        private readonly Dictionary<string, string> _connectionToDeviceAddress = new Dictionary<string, string>(StringComparer.Ordinal);\n        private readonly Dictionary<string, string> _peerToLastDeviceAddress = new Dictionary<string, string>(StringComparer.Ordinal);")
replace_one(
    bt,
    "                    _connectionToPeer.Clear();\n                    _peerToConnection.Clear();",
    "                    _connectionToPeer.Clear();\n                    _peerToConnection.Clear();\n                    _connectionToDeviceAddress.Clear();\n                    _peerToLastDeviceAddress.Clear();")
replace_one(
    bt,
    "        public IReadOnlyList<BluetoothClassicDevice> GetBondedDevices()\n        {\n            return _adapter.GetBondedDevices();\n        }",
    "        public IReadOnlyList<BluetoothClassicDevice> GetBondedDevices()\n        {\n            return _adapter.GetBondedDevices();\n        }\n\n        public bool TryGetLastKnownDeviceAddress(string remotePeerId, out string deviceAddress)\n        {\n            deviceAddress = null;\n            if (string.IsNullOrWhiteSpace(remotePeerId)) return false;\n            lock (_gate) return _peerToLastDeviceAddress.TryGetValue(remotePeerId, out deviceAddress);\n        }")
replace_one(
    bt,
    "        private void OnConnected(string connectionId, string deviceAddress, string deviceName)\n        {\n            SendHelloAsync(connectionId);\n        }",
    "        private void OnConnected(string connectionId, string deviceAddress, string deviceName)\n        {\n            if (!string.IsNullOrWhiteSpace(connectionId) && !string.IsNullOrWhiteSpace(deviceAddress))\n            {\n                lock (_gate)\n                {\n                    _connectionToDeviceAddress[connectionId] = deviceAddress;\n                    string peerId;\n                    if (_connectionToPeer.TryGetValue(connectionId, out peerId))\n                        _peerToLastDeviceAddress[peerId] = deviceAddress;\n                }\n            }\n            SendHelloAsync(connectionId);\n        }")
replace_one(
    bt,
    "            lock (_gate)\n            {\n                if (_connectionToPeer.TryGetValue(connectionId, out peerId))\n                {\n                    _connectionToPeer.Remove(connectionId);",
    "            lock (_gate)\n            {\n                _connectionToDeviceAddress.Remove(connectionId);\n                if (_connectionToPeer.TryGetValue(connectionId, out peerId))\n                {\n                    _connectionToPeer.Remove(connectionId);")
replace_one(
    bt,
    "                _connectionToPeer[connectionId] = peerId;\n                _peerToConnection[peerId] = connectionId;\n                newlyBound = true;",
    "                _connectionToPeer[connectionId] = peerId;\n                _peerToConnection[peerId] = connectionId;\n                string deviceAddress;\n                if (_connectionToDeviceAddress.TryGetValue(connectionId, out deviceAddress) && !string.IsNullOrWhiteSpace(deviceAddress))\n                    _peerToLastDeviceAddress[peerId] = deviceAddress;\n                newlyBound = true;")

# Wire the existing Grid Duel MonoBehaviour to the migration partial without duplicating lifecycle ownership.
demo = "Packages/com.hoonex.solarnet/Samples~/GridDuel/GridDuelNetworkedDemo.cs"
replace_one(demo, "    public sealed class GridDuelNetworkedDemo : MonoBehaviour", "    public sealed partial class GridDuelNetworkedDemo : MonoBehaviour")
replace_one(
    demo,
    "            var adapter = new AndroidNearbyAdapter();\n            _nativeAdapter = adapter;",
    "            var adapter = new AndroidNearbyAdapter();\n            _nativeAdapter = adapter;\n            _nearbyAdapter = adapter;")
replace_one(
    demo,
    "            _nearbyTransport.EndpointDiscovered += OnNearbyEndpointFound;\n            _nearbyTransport.EndpointLost += OnNearbyEndpointLost;\n            _nearbyTransport.ConnectionVerificationRequired += OnNearbyVerificationRequired;\n            _nearbyTransport.PeerConnected += OnPeerConnected;\n            _nearbyTransport.PeerDisconnected += OnPeerDisconnected;\n            _nearbyTransport.Faulted += OnTransportFault;",
    "            SubscribeNearbyTransport(_nearbyTransport);")
replace_one(
    demo,
    "            var adapter = new AndroidBluetoothClassicAdapter();\n            _nativeAdapter = adapter;",
    "            var adapter = new AndroidBluetoothClassicAdapter();\n            _nativeAdapter = adapter;\n            _bluetoothAdapter = adapter;")
replace_one(
    demo,
    "            _bluetoothTransport.PeerConnected += OnPeerConnected;\n            _bluetoothTransport.PeerDisconnected += OnPeerDisconnected;\n            _bluetoothTransport.Faulted += OnTransportFault;",
    "            SubscribeBluetoothTransport(_bluetoothTransport);")
replace_one(
    demo,
    '''        private async void OnPeerConnected(string peerId)
        {
            _status = "Connected: " + peerId;
            AddLog(_status);
            try
            {
                if (_room != null) await _room.NotifyPeerConnectedAsync(peerId);
                if (!_isHost && _game != null && string.Equals(peerId, HostPeerId, StringComparison.Ordinal))
                {
                    await _game.RequestResyncAsync();
                    AddLog("Requested game-state resync after live-link reconnect.");
                }
            }
            catch (Exception ex)
            {
                AddLog("Reconnect/join error: " + ex.Message);
            }
        }''',
    '''        private async void OnPeerConnected(string peerId)
        {
            await HandlePeerConnectedAsync(peerId);
        }''')
replace_one(
    demo,
    '''        private async void OnPeerDisconnected(string peerId)
        {
            _status = "Opponent disconnected.";
            AddLog(_status);
            try
            {
                if (_room != null) await _room.NotifyPeerDisconnectedAsync(peerId);
            }
            catch (Exception ex)
            {
                AddLog("Room disconnect error: " + ex.Message);
            }
        }''',
    '''        private async void OnPeerDisconnected(string peerId)
        {
            if (await TryHandleMigrationDisconnectAsync(peerId)) return;
            await HandleOrdinaryPeerDisconnectedAsync(peerId);
        }''')
replace_one(
    demo,
    "        private void OnNearbyEndpointFound(NearbyEndpoint endpoint)\n        {\n            _nearbyEndpoints[endpoint.EndpointId] = new NearbyEndpointView { Id = endpoint.EndpointId, Name = endpoint.EndpointName };\n        }",
    "        private void OnNearbyEndpointFound(NearbyEndpoint endpoint)\n        {\n            HandleNearbyEndpointFoundForMigration(endpoint);\n        }")
replace_one(
    demo,
    "        private void OnNearbyEndpointLost(string endpointId)\n        {\n            _nearbyEndpoints.Remove(endpointId);\n        }",
    "        private void OnNearbyEndpointLost(string endpointId)\n        {\n            HandleNearbyEndpointLostForMigration(endpointId);\n        }")
replace_one(
    demo,
    '''                _game.ActionCommitted += OnActionCommitted;
                _game.ActionRejected += rejection => AddLog("Turn rejected: " + rejection.Reason);
                _game.StateMismatchDetected += mismatch => AddLog("State mismatch: " + mismatch.Reason + ". Automatic resync is active.");
                _game.SnapshotApplied += snapshot => AddLog("State snapshot applied at turn " + snapshot.NextTurnIndex + ".");
                _game.ResyncFailed += failure => AddLog("Resync failed: " + failure.Reason);
                _game.ProtocolFaulted += ex => AddLog("Game fault: " + ex.Message);''',
    "                SubscribeGame(_game);")
replace_one(
    demo,
    "            _sawLobbyBeforePlaying = false;\n            _resumeFromProcessRestart = false;\n        }",
    "            _sawLobbyBeforePlaying = false;\n            _resumeFromProcessRestart = false;\n            ClearMigrationRuntimeState();\n        }")
