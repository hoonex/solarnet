using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using SolarNet.BluetoothClassic;
using SolarNet.Nearby;
using SolarNet.Room;
using SolarNet.Session;

namespace SolarNet.Samples.GridDuel
{
    public sealed partial class GridDuelNetworkedDemo
    {
        private INearbyPeerAdapter _nearbyAdapter;
        private IBluetoothClassicPeerAdapter _bluetoothAdapter;
        private GridDuelMigrationContext _migrationContext;
        private NearbyMigrationSwitchResult _nearbyMigrationSwitch;
        private BluetoothClassicMigrationSwitchResult _bluetoothMigrationSwitch;
        private readonly List<string> _deferredMigrationPeers = new List<string>();
        private bool _migrationInProgress;
        private bool _migrationNearbyConnectRequested;
        private string _migrationNearbyRequestedEndpointId = string.Empty;

        private void SubscribeNearbyTransport(NearbyTransport transport)
        {
            if (transport == null) throw new ArgumentNullException(nameof(transport));
            transport.EndpointDiscovered += OnNearbyEndpointFound;
            transport.EndpointLost += OnNearbyEndpointLost;
            transport.ConnectionVerificationRequired += OnNearbyVerificationRequired;
            transport.PeerConnected += OnPeerConnected;
            transport.PeerDisconnected += OnPeerDisconnected;
            transport.Faulted += OnTransportFault;
        }

        private void SubscribeBluetoothTransport(BluetoothClassicTransport transport)
        {
            if (transport == null) throw new ArgumentNullException(nameof(transport));
            transport.PeerConnected += OnPeerConnected;
            transport.PeerDisconnected += OnPeerDisconnected;
            transport.Faulted += OnTransportFault;
        }

        private void SubscribeGame(SolarTurnSession game)
        {
            if (game == null) throw new ArgumentNullException(nameof(game));
            game.ActionCommitted += OnActionCommitted;
            game.ActionRejected += rejection => AddLog("Turn rejected: " + rejection.Reason);
            game.StateMismatchDetected += mismatch => AddLog("State mismatch: " + mismatch.Reason + ". Automatic resync is active.");
            game.SnapshotApplied += snapshot => AddLog("State snapshot applied at turn " + snapshot.NextTurnIndex + ".");
            game.ResyncFailed += failure => AddLog("Resync failed: " + failure.Reason);
            game.ProtocolFaulted += ex => AddLog("Game fault: " + ex.Message);
        }

        private async Task HandlePeerConnectedAsync(string peerId)
        {
            if (string.IsNullOrWhiteSpace(peerId)) return;
            if (_migrationInProgress)
            {
                if (!_deferredMigrationPeers.Contains(peerId)) _deferredMigrationPeers.Add(peerId);
                AddLog("Migration link ready; deferring peer handshake until the new room/game epoch is attached: " + peerId);
                return;
            }

            _status = "Connected: " + peerId;
            AddLog(_status);
            try
            {
                if (!IsExpectedMigratedAuthority(peerId))
                {
                    AddLog("Rejected migrated link from unexpected authority peer: " + peerId);
                    await DisconnectUnexpectedMigrationPeerAsync(peerId).ConfigureAwait(false);
                    return;
                }

                if (_room != null) await _room.NotifyPeerConnectedAsync(peerId).ConfigureAwait(false);
                if (_game != null && !_game.IsHost && string.Equals(peerId, _game.HostPeerId, StringComparison.Ordinal))
                {
                    await _game.RequestResyncAsync().ConfigureAwait(false);
                    AddLog(_migrationContext == null
                        ? "Requested game-state resync after live-link reconnect."
                        : "Requested authoritative state resync from migrated host " + _game.HostPeerId + ".");
                }
            }
            catch (Exception ex)
            {
                AddLog("Reconnect/join error: " + ex.Message);
            }
        }

        private async Task HandleOrdinaryPeerDisconnectedAsync(string peerId)
        {
            _status = "Opponent disconnected.";
            AddLog(_status);
            try
            {
                if (_room != null) await _room.NotifyPeerDisconnectedAsync(peerId).ConfigureAwait(false);
            }
            catch (Exception ex)
            {
                AddLog("Room disconnect error: " + ex.Message);
            }
        }

        private async Task<bool> TryHandleMigrationDisconnectAsync(string peerId)
        {
            if (_migrationInProgress || _room == null || _game == null || _gameState == null) return false;
            var sourceRoom = _room.CurrentSnapshot;
            if (sourceRoom == null || sourceRoom.Phase != SolarRoomPhase.Playing || sourceRoom.Players.Length != 2) return false;

            GridDuelMigrationContext migration;
            try
            {
                migration = GridDuelMigrationWorkflow.Prepare(sourceRoom, _game, _gameState);
            }
            catch (Exception ex)
            {
                AddLog("Migration safety gate blocked authority rotation: " + ex.Message);
                return false;
            }

            _migrationInProgress = true;
            _busy = true;
            _migrationContext = migration;
            _nearbyMigrationSwitch = null;
            _bluetoothMigrationSwitch = null;
            _migrationNearbyConnectRequested = false;
            _migrationNearbyRequestedEndpointId = string.Empty;
            _deferredMigrationPeers.Clear();

            var sourceGame = _game;
            var sourceRoomSession = _room;
            var successorAddress = string.Empty;
            if (_bluetoothTransport != null && !migration.IsSuccessor(_localPeerId))
            {
                string resolvedAddress;
                if (_bluetoothTransport.TryGetLastKnownDeviceAddress(migration.Plan.SuccessorPeerId, out resolvedAddress))
                {
                    successorAddress = resolvedAddress;
                    AddLog("Resolved elected successor Bluetooth address from the authenticated prior link.");
                }
            }

            _status = "Link lost. Rotating Grid Duel authority epoch...";
            AddLog(
                "Migration plan: oldHost=" + migration.Plan.SourceHostPeerId +
                " successor=" + migration.Plan.SuccessorPeerId +
                " turn=" + migration.Checkpoint.NextTurnIndex + ".");

            var migrationReady = false;
            try
            {
                await sourceRoomSession.NotifyPeerDisconnectedAsync(peerId).ConfigureAwait(false);
                sourceRoomSession.Detach();
                await sourceGame.StopAsync().ConfigureAwait(false);
                _game = null;

                _nearbyEndpoints.Clear();
                _verificationRequests.Clear();
                _bondedDevices.Clear();

                if (_transportMode == TransportMode.Nearby)
                {
                    if (_nearbyTransport == null || _nearbyAdapter == null)
                        throw new InvalidOperationException("Nearby migration requires the live Android Nearby adapter.");
                    _nearbyMigrationSwitch = await NearbyMigrationTransportSwitch.SwitchAsync(
                        _nearbyTransport,
                        _nearbyAdapter,
                        sourceRoom,
                        migration.Plan,
                        NearbyServiceId,
                        configureTransport: SubscribeNearbyTransport).ConfigureAwait(false);
                    _nearbyTransport = _nearbyMigrationSwitch.Transport;
                    _bluetoothTransport = null;
                    _transport = _nearbyTransport;
                }
                else
                {
                    if (_bluetoothTransport == null || _bluetoothAdapter == null)
                        throw new InvalidOperationException("Bluetooth migration requires the live Android Bluetooth adapter.");
                    _bluetoothMigrationSwitch = await BluetoothClassicMigrationTransportSwitch.SwitchAsync(
                        _bluetoothTransport,
                        _bluetoothAdapter,
                        sourceRoom,
                        migration.Plan,
                        successorDeviceAddress: string.IsNullOrWhiteSpace(successorAddress) ? null : successorAddress,
                        configureTransport: SubscribeBluetoothTransport).ConfigureAwait(false);
                    _bluetoothTransport = _bluetoothMigrationSwitch.Transport;
                    _nearbyTransport = null;
                    _transport = _bluetoothTransport;
                }

                _isHost = migration.IsSuccessor(_localPeerId);
                var roomBootstrap = GridDuelMigrationWorkflow.CreateRoom(migration, _transport);
                _room = roomBootstrap.RoomSession;
                SubscribeRoom(_room);
                _room.Attach();

                _game = GridDuelMigrationWorkflow.CreateGame(migration, roomBootstrap, _transport, _gameState);
                SubscribeGame(_game);
                await _game.StartAsync().ConfigureAwait(false);
                _resumeFromProcessRestart = false;
                migrationReady = true;

                if (_isHost)
                {
                    _status = "Authority migrated to this phone. Waiting for the other player to reconnect.";
                }
                else if (_bluetoothMigrationSwitch != null && _bluetoothMigrationSwitch.RequiresSuccessorDeviceAddress)
                {
                    _status = "Authority migrated. Choose the elected successor from paired Bluetooth devices.";
                }
                else
                {
                    _status = "Authority migrated. Reconnecting to " + migration.Plan.SuccessorPeerId + "...";
                }
                AddLog("Migrated game epoch attached: " + migration.Plan.NextGameSessionId + ".");
            }
            catch (Exception ex)
            {
                _status = "Host migration failed: " + ex.Message + " Stop and restart the match.";
                AddLog(_status);
            }
            finally
            {
                _migrationInProgress = false;
                _busy = false;
            }

            if (migrationReady)
            {
                var deferred = _deferredMigrationPeers.ToArray();
                _deferredMigrationPeers.Clear();
                for (var i = 0; i < deferred.Length; i++)
                    await HandlePeerConnectedAsync(deferred[i]).ConfigureAwait(false);

                if (_transportMode == TransportMode.Nearby)
                    TryConnectMigratedNearbyEndpoint();
                else if (!_isHost && _bluetoothMigrationSwitch != null && _bluetoothMigrationSwitch.RequiresSuccessorDeviceAddress)
                    RefreshBondedDevices();
            }

            return true;
        }

        private void HandleNearbyEndpointFoundForMigration(NearbyEndpoint endpoint)
        {
            if (endpoint == null) return;
            _nearbyEndpoints[endpoint.EndpointId] = new NearbyEndpointView { Id = endpoint.EndpointId, Name = endpoint.EndpointName };
            if (_migrationContext != null && !_migrationContext.IsSuccessor(_localPeerId) && !_migrationInProgress)
                TryConnectMigratedNearbyEndpoint();
        }

        private void HandleNearbyEndpointLostForMigration(string endpointId)
        {
            _nearbyEndpoints.Remove(endpointId);
            if (string.Equals(endpointId, _migrationNearbyRequestedEndpointId, StringComparison.Ordinal))
            {
                _migrationNearbyConnectRequested = false;
                _migrationNearbyRequestedEndpointId = string.Empty;
            }
        }

        private async void TryConnectMigratedNearbyEndpoint()
        {
            if (_migrationInProgress || _nearbyMigrationSwitch == null || _migrationContext == null) return;
            if (_nearbyMigrationSwitch.IsSuccessor || _migrationNearbyConnectRequested) return;

            foreach (var view in new List<NearbyEndpointView>(_nearbyEndpoints.Values))
            {
                var endpoint = new NearbyEndpoint(view.Id, view.Name);
                if (!_nearbyMigrationSwitch.IsSuccessorAdvertisement(endpoint)) continue;

                _migrationNearbyConnectRequested = true;
                _migrationNearbyRequestedEndpointId = endpoint.EndpointId;
                try
                {
                    await _nearbyMigrationSwitch.RequestSuccessorConnectionAsync(endpoint).ConfigureAwait(false);
                    AddLog("Requested migrated Nearby authority endpoint: " + endpoint.EndpointId + ". Verify the digits on both phones.");
                }
                catch (Exception ex)
                {
                    _migrationNearbyConnectRequested = false;
                    _migrationNearbyRequestedEndpointId = string.Empty;
                    AddLog("Migrated Nearby reconnect error: " + ex.Message);
                }
                return;
            }
        }

        private bool IsExpectedMigratedAuthority(string peerId)
        {
            if (_migrationContext == null || _migrationContext.IsSuccessor(_localPeerId)) return true;
            return string.Equals(peerId, _migrationContext.Plan.SuccessorPeerId, StringComparison.Ordinal);
        }

        private async Task DisconnectUnexpectedMigrationPeerAsync(string peerId)
        {
            try
            {
                if (_nearbyTransport != null)
                    await _nearbyTransport.DisconnectPeerAsync(peerId).ConfigureAwait(false);
                else if (_bluetoothTransport != null)
                    await _bluetoothTransport.DisconnectPeerAsync(peerId).ConfigureAwait(false);
            }
            catch (Exception ex)
            {
                AddLog("Unexpected-peer disconnect error: " + ex.Message);
            }
        }

        private void ClearMigrationRuntimeState()
        {
            _nearbyAdapter = null;
            _bluetoothAdapter = null;
            _migrationContext = null;
            _nearbyMigrationSwitch = null;
            _bluetoothMigrationSwitch = null;
            _migrationInProgress = false;
            _migrationNearbyConnectRequested = false;
            _migrationNearbyRequestedEndpointId = string.Empty;
            _deferredMigrationPeers.Clear();
        }
    }
}
