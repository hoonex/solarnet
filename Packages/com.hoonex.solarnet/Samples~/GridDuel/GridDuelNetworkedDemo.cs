using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using SolarNet.BluetoothClassic;
using SolarNet.BluetoothClassic.Android;
using SolarNet.Nearby;
using SolarNet.Nearby.Android;
using SolarNet.Room;
using SolarNet.Session;
using SolarNet.Transport;
using SolarNet.Turns;
using UnityEngine;

#if UNITY_ANDROID && !UNITY_EDITOR
using UnityEngine.Android;
#endif

namespace SolarNet.Samples.GridDuel
{
    public sealed partial class GridDuelNetworkedDemo : MonoBehaviour
    {
        private const string RoomId = "grid-duel-room";
        private const string RoomName = "Grid Duel";
        private const string CompatibilityKey = "grid-duel-v1";
        private const string NearbyServiceId = "com.hoonex.solarnet.gridduel";
        private const string HostPeerId = "host";
        private const string ClientPeerIdKey = "SolarNet.GridDuel.ClientPeerId";

        private enum TransportMode
        {
            BluetoothClassic,
            Nearby
        }

        private sealed class NearbyEndpointView
        {
            public string Id;
            public string Name;
        }

        private TransportMode _transportMode = TransportMode.BluetoothClassic;
        private bool _isHost = true;
        private bool _started;
        private bool _busy;
        private bool _sawLobbyBeforePlaying;
        private bool _resumeFromProcessRestart;
        private string _localPeerId = string.Empty;
        private string _status = "Choose a role and transport.";
        private readonly List<string> _logs = new List<string>();
        private readonly Dictionary<string, NearbyEndpointView> _nearbyEndpoints = new Dictionary<string, NearbyEndpointView>(StringComparer.Ordinal);
        private readonly Dictionary<string, NearbyVerificationRequest> _verificationRequests = new Dictionary<string, NearbyVerificationRequest>(StringComparer.Ordinal);
        private readonly List<BluetoothClassicDevice> _bondedDevices = new List<BluetoothClassicDevice>();

        private ISolarTransport _transport;
        private NearbyTransport _nearbyTransport;
        private BluetoothClassicTransport _bluetoothTransport;
        private IDisposable _nativeAdapter;
        private SolarRoomSession _room;
        private SolarTurnSession _game;
        private GridDuelStateMachine _gameState;
        private string _playerAId = string.Empty;
        private string _playerBId = string.Empty;

        private void Awake()
        {
            LoadPersistedAuthorityMetadata();
            AddLog("Networked Grid Duel loaded.");
#if !UNITY_ANDROID || UNITY_EDITOR
            AddLog("Physical transports need an Android Player build. Use GridDuelLocalDemo in the Editor.");
#endif
        }

        private void OnGUI()
        {
            GUILayout.BeginVertical(GUI.skin.box);
            GUILayout.Label("Grid Duel — Networked SolarNet Sample");
            GUILayout.Label(_status);
            GUILayout.Space(6f);
            if (_started) DrawRunning(); else DrawSetup();
            GUILayout.Space(8f);
            GUILayout.Label("Event log");
            for (var i = Math.Max(0, _logs.Count - 12); i < _logs.Count; i++) GUILayout.Label(_logs[i]);
            GUILayout.EndVertical();
        }

        private void DrawSetup()
        {
            DrawPersistedAuthorityControls();
            GUILayout.Label("1. Role");
            GUILayout.BeginHorizontal();
            GUI.enabled = !_busy && !HasPersistedAuthorityBlob;
            if (GUILayout.Button(_isHost ? "HOST ✓" : "Host")) _isHost = true;
            if (GUILayout.Button(!_isHost ? "CLIENT ✓" : "Client")) _isHost = false;
            GUILayout.EndHorizontal();

            GUILayout.Label("2. Transport");
            GUILayout.BeginHorizontal();
            if (GUILayout.Button(_transportMode == TransportMode.BluetoothClassic ? "Bluetooth Classic ✓" : "Bluetooth Classic"))
                _transportMode = TransportMode.BluetoothClassic;
            if (GUILayout.Button(_transportMode == TransportMode.Nearby ? "Nearby ✓" : "Nearby"))
                _transportMode = TransportMode.Nearby;
            GUILayout.EndHorizontal();

            if (!_isHost) GUILayout.Label("Client peer identity persists across app restarts for active-match resume.");
            GUI.enabled = !_busy;
            if (GUILayout.Button("Request Android permissions", GUILayout.Height(38f))) RequestAndroidPermissions();
            GUI.enabled = !_busy && !HasPersistedAuthorityBlob;
            if (GUILayout.Button(_isHost ? "Start Grid Duel host" : "Start Grid Duel client", GUILayout.Height(46f))) StartNetworkedMatch();
            GUI.enabled = true;
        }

        private void DrawRunning()
        {
            GUILayout.Label("Peer: " + _localPeerId + " | " + (_isHost ? "HOST" : "CLIENT") + " | " + _transportMode);
            if (_transportMode == TransportMode.BluetoothClassic) DrawBluetoothControls(); else DrawNearbyControls();
            GUILayout.Space(6f);
            DrawRoomControls();
            if (_gameState != null)
            {
                GUILayout.Space(6f);
                DrawGame();
            }
            GUILayout.Space(6f);
            GUI.enabled = !_busy;
            if (GUILayout.Button("Stop", GUILayout.Height(38f))) StopNetworkedMatch();
            GUI.enabled = true;
        }

        private void DrawBluetoothControls()
        {
            GUILayout.Label("Connection");
            if (_isHost)
            {
                GUILayout.Label("RFCOMM server listening. Pair both phones in Android Settings first.");
                return;
            }

            if (GUILayout.Button("Refresh paired devices")) RefreshBondedDevices();
            if (_bondedDevices.Count == 0) GUILayout.Label("No paired devices loaded yet.");
            for (var i = 0; i < _bondedDevices.Count; i++)
            {
                var device = _bondedDevices[i];
                if (GUILayout.Button("Connect: " + device.Name + " [" + device.Address + "]")) ConnectBluetooth(device.Address);
            }
        }

        private void DrawNearbyControls()
        {
            GUILayout.Label("Connection");
            if (_isHost)
            {
                GUILayout.Label("Advertising Grid Duel room with Nearby Connections.");
            }
            else
            {
                if (_nearbyEndpoints.Count == 0) GUILayout.Label("Searching for Grid Duel hosts...");
                foreach (var endpoint in new List<NearbyEndpointView>(_nearbyEndpoints.Values))
                    if (GUILayout.Button("Connect: " + FriendlyEndpointName(endpoint.Name))) RequestNearbyConnection(endpoint.Id);
            }

            if (_verificationRequests.Count == 0) return;
            GUILayout.Label("Confirm the same verification digits on both phones:");
            foreach (var request in new List<NearbyVerificationRequest>(_verificationRequests.Values))
            {
                GUILayout.Label(request.EndpointName + " code=" + request.AuthenticationDigits);
                GUILayout.BeginHorizontal();
                if (GUILayout.Button("Accept")) AcceptNearby(request.EndpointId);
                if (GUILayout.Button("Reject")) RejectNearby(request.EndpointId);
                GUILayout.EndHorizontal();
            }
        }

        private void DrawRoomControls()
        {
            GUILayout.Label("Lobby");
            if (_room == null) return;
            var snapshot = _room.CurrentSnapshot;
            GUILayout.Label("Phase: " + _room.Phase);
            if (snapshot != null)
            {
                foreach (var player in snapshot.Players)
                {
                    GUILayout.Label(
                        "#" + player.Slot + " " + player.DisplayName + " " +
                        (player.IsReady ? "READY" : "not ready") + " " +
                        (player.IsConnected ? "online" : "offline"));
                }
            }

            GUI.enabled = !_busy && _room.Phase == SolarRoomPhase.Lobby;
            if (GUILayout.Button("Toggle my Ready")) ToggleReady();
            GUI.enabled = !_busy && _isHost && _room.CanStart;
            if (GUILayout.Button("Start match", GUILayout.Height(42f))) StartGame();
            GUI.enabled = true;
        }

        private void DrawGame()
        {
            var a = _gameState.GetPlayer(_playerAId);
            var b = _gameState.GetPlayer(_playerBId);
            GUILayout.Label("Match");
            GUILayout.Label("SUN " + a.Health + " HP vs MOON " + b.Health + " HP");
            if (_game != null) GUILayout.Label("Turn " + _game.KnownNextTurnIndex + " — active: " + PlayerLabel(_game.KnownCurrentPlayerId));

            for (var y = 0; y < GridDuelStateMachine.BoardHeight; y++)
            {
                GUILayout.BeginHorizontal();
                for (var x = 0; x < GridDuelStateMachine.BoardWidth; x++)
                {
                    var occupant = _gameState.GetPlayerAt(x, y);
                    var label = occupant == null ? "·" : PlayerLabel(occupant.PeerId) + "\n" + occupant.Health;
                    GUI.enabled = CanActLocally();
                    if (GUILayout.Button(label, GUILayout.Width(70f), GUILayout.Height(54f))) HandleCell(x, y);
                }
                GUILayout.EndHorizontal();
            }
            GUI.enabled = true;
            if (_gameState.IsFinished) GUILayout.Label("Winner: " + PlayerLabel(_gameState.WinnerPeerId));
        }

        private bool CanActLocally()
        {
            return !_busy && !_authorityResumeAwaitingReplicaProof && _game != null && _gameState != null && !_gameState.IsFinished &&
                   string.Equals(_game.KnownCurrentPlayerId, _localPeerId, StringComparison.Ordinal);
        }

        private async void HandleCell(int x, int y)
        {
            if (!CanActLocally()) return;
            var actor = _gameState.GetPlayer(_localPeerId);
            var target = _gameState.GetPlayerAt(x, y);
            if (actor == null) return;

            _busy = true;
            try
            {
                if (target == null)
                {
                    if (Math.Abs(actor.X - x) + Math.Abs(actor.Y - y) != 1)
                    {
                        _status = "Move exactly one orthogonal tile.";
                        return;
                    }
                    await _game.SubmitActionAsync(GridDuelActionCodec.MoveAction, GridDuelActionCodec.EncodeMove(x, y));
                }
                else if (!string.Equals(target.PeerId, _localPeerId, StringComparison.Ordinal))
                {
                    if (Math.Abs(actor.X - target.X) + Math.Abs(actor.Y - target.Y) != 1)
                    {
                        _status = "Enemy must be adjacent to attack.";
                        return;
                    }
                    await _game.SubmitActionAsync(GridDuelActionCodec.AttackAction, Array.Empty<byte>());
                }
                else
                {
                    _status = "That is your own unit.";
                }
            }
            catch (Exception ex)
            {
                _status = "Action failed: " + ex.Message;
                AddLog(_status);
            }
            finally
            {
                _busy = false;
            }
        }

        private async void StartNetworkedMatch()
        {
            if (_busy || _started) return;
            if (HasPersistedAuthorityBlob)
            {
                _status = "Resume or discard the saved authority epoch before starting a new match.";
                return;
            }
            _busy = true;
            try
            {
                _localPeerId = _isHost ? HostPeerId : GetOrCreatePersistentClientPeerId();
                _sawLobbyBeforePlaying = false;
                _resumeFromProcessRestart = false;
                var displayName = _isHost ? "SUN" : "MOON";
                _nearbyEndpoints.Clear();
                _verificationRequests.Clear();
                _bondedDevices.Clear();

                if (_transportMode == TransportMode.Nearby) CreateNearbyTransport(displayName);
                else CreateBluetoothTransport();

                _room = new SolarRoomSession(
                    new SolarRoomOptions(RoomId, HostPeerId, displayName, CompatibilityKey, RoomName, 2, SolarHostDisconnectPolicy.WaitForReconnect),
                    _transport);
                SubscribeRoom(_room);
                _room.Attach();
                await _transport.StartAsync();
                _started = true;
                _status = _isHost ? "Host started. Waiting for opponent." : "Client started. Connect to the host.";
                AddLog(_status + " peer=" + _localPeerId);
                if (_transportMode == TransportMode.BluetoothClassic && !_isHost) RefreshBondedDevices();
            }
            catch (Exception ex)
            {
                _status = "Start failed: " + ex.Message;
                AddLog(_status);
                await CleanupFailedStart();
            }
            finally
            {
                _busy = false;
            }
        }

        private void CreateNearbyTransport(string displayName)
        {
            var adapter = new AndroidNearbyAdapter();
            _nativeAdapter = adapter;
            _nearbyAdapter = adapter;
            var endpointName = _isHost
                ? NearbyRoomAdvertisementCodec.Encode(RoomId, RoomName, CompatibilityKey)
                : displayName;
            _nearbyTransport = new NearbyTransport(
                _localPeerId,
                adapter,
                new NearbyTransportOptions(
                    NearbyServiceId,
                    endpointName,
                    _isHost ? NearbyConnectionRole.Advertiser : NearbyConnectionRole.Discoverer,
                    NearbyConnectionStrategy.Star,
                    false));
            SubscribeNearbyTransport(_nearbyTransport);
            _transport = _nearbyTransport;
        }

        private void CreateBluetoothTransport()
        {
            var adapter = new AndroidBluetoothClassicAdapter();
            _nativeAdapter = adapter;
            _bluetoothAdapter = adapter;
            _bluetoothTransport = new BluetoothClassicTransport(
                _localPeerId,
                adapter,
                new BluetoothClassicTransportOptions(_isHost ? BluetoothClassicRole.Server : BluetoothClassicRole.Client));
            SubscribeBluetoothTransport(_bluetoothTransport);
            _transport = _bluetoothTransport;
        }

        private void RequestAndroidPermissions()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            try
            {
                var sdk = GetAndroidSdkInt();
                var required = _transportMode == TransportMode.Nearby
                    ? NearbyAndroidPermissions.GetRequiredRuntimePermissions(sdk, GetAndroidTargetSdkInt())
                    : BluetoothClassicAndroidPermissions.GetRequiredRuntimePermissions(sdk);
                var requested = 0;
                foreach (var permission in required)
                {
                    if (Permission.HasUserAuthorizedPermission(permission)) continue;
                    Permission.RequestUserPermission(permission);
                    requested++;
                }
                AddLog(requested == 0
                    ? "Android permissions already granted."
                    : "Requested " + requested + " permission(s). Approve them, then start again.");
            }
            catch (Exception ex)
            {
                AddLog("Permission error: " + ex.Message);
            }
#else
            AddLog("Permission request is available only in an Android Player build.");
#endif
        }

#if UNITY_ANDROID && !UNITY_EDITOR
        private static int GetAndroidSdkInt()
        {
            using (var version = new AndroidJavaClass("android.os.Build$VERSION")) return version.GetStatic<int>("SDK_INT");
        }

        private static int GetAndroidTargetSdkInt()
        {
            using (var unityPlayer = new AndroidJavaClass("com.unity3d.player.UnityPlayer"))
            using (var activity = unityPlayer.GetStatic<AndroidJavaObject>("currentActivity"))
            using (var appInfo = activity.Call<AndroidJavaObject>("getApplicationInfo")) return appInfo.Get<int>("targetSdkVersion");
        }
#endif

        private async void RequestNearbyConnection(string endpointId)
        {
            if (_busy || _nearbyTransport == null) return;
            _busy = true;
            try
            {
                await _nearbyTransport.RequestConnectionAsync(endpointId);
            }
            catch (Exception ex)
            {
                AddLog("Nearby connect error: " + ex.Message);
            }
            finally
            {
                _busy = false;
            }
        }

        private async void AcceptNearby(string endpointId)
        {
            if (_nearbyTransport == null) return;
            try
            {
                await _nearbyTransport.AcceptConnectionAsync(endpointId);
                _verificationRequests.Remove(endpointId);
            }
            catch (Exception ex)
            {
                AddLog("Nearby accept error: " + ex.Message);
            }
        }

        private async void RejectNearby(string endpointId)
        {
            if (_nearbyTransport == null) return;
            try
            {
                await _nearbyTransport.RejectConnectionAsync(endpointId);
                _verificationRequests.Remove(endpointId);
            }
            catch (Exception ex)
            {
                AddLog("Nearby reject error: " + ex.Message);
            }
        }

        private void RefreshBondedDevices()
        {
            if (_bluetoothTransport == null) return;
            try
            {
                _bondedDevices.Clear();
                _bondedDevices.AddRange(_bluetoothTransport.GetBondedDevices());
                AddLog("Loaded " + _bondedDevices.Count + " paired device(s).");
            }
            catch (Exception ex)
            {
                AddLog("Paired device error: " + ex.Message);
            }
        }

        private async void ConnectBluetooth(string address)
        {
            if (_busy || _bluetoothTransport == null) return;
            _busy = true;
            try
            {
                await _bluetoothTransport.ConnectAsync(address);
            }
            catch (Exception ex)
            {
                AddLog("Bluetooth connect error: " + ex.Message);
            }
            finally
            {
                _busy = false;
            }
        }

        private async void OnPeerConnected(string peerId)
        {
            await HandlePeerConnectedAsync(peerId);
        }

        private async void OnPeerDisconnected(string peerId)
        {
            if (await TryHandleMigrationDisconnectAsync(peerId)) return;
            await HandleOrdinaryPeerDisconnectedAsync(peerId);
        }

        private void OnNearbyEndpointFound(NearbyEndpoint endpoint)
        {
            HandleNearbyEndpointFoundForMigration(endpoint);
        }

        private void OnNearbyEndpointLost(string endpointId)
        {
            HandleNearbyEndpointLostForMigration(endpointId);
        }

        private void OnNearbyVerificationRequired(NearbyVerificationRequest request)
        {
            _verificationRequests[request.EndpointId] = request;
            AddLog("Nearby verification code: " + request.AuthenticationDigits);
        }

        private void OnTransportFault(Exception exception)
        {
            _status = "Transport fault: " + exception.Message;
            AddLog(_status);
        }

        private void SubscribeRoom(SolarRoomSession room)
        {
            room.RoomChanged += OnRoomChanged;
            room.PhaseChanged += phase => AddLog("Room phase -> " + phase);
            room.JoinRejected += rejection => AddLog("Join rejected: " + rejection.Reason);
            room.RoomClosed += closed => AddLog("Room closed: " + closed.Reason);
            room.GameStarted += OnGameStarted;
            room.ProtocolFaulted += ex => AddLog("Room fault: " + ex.Message);
        }

        private void OnRoomChanged(SolarRoomSnapshot snapshot)
        {
            _status = "Lobby " + snapshot.Players.Length + "/2 — " + snapshot.Phase;
            if (_isHost) return;

            if (snapshot.Phase == SolarRoomPhase.Lobby)
            {
                _sawLobbyBeforePlaying = true;
                return;
            }

            if (snapshot.Phase == SolarRoomPhase.Playing && _game == null && !_sawLobbyBeforePlaying)
            {
                _resumeFromProcessRestart = true;
                AddLog("Detected direct rejoin into an active match. A full authoritative resync will run after game-session attach.");
            }
        }

        private async void ToggleReady()
        {
            if (_busy || _room == null) return;
            _busy = true;
            try
            {
                var ready = false;
                var snapshot = _room.CurrentSnapshot;
                if (snapshot != null)
                {
                    foreach (var player in snapshot.Players)
                    {
                        if (string.Equals(player.PeerId, _localPeerId, StringComparison.Ordinal))
                        {
                            ready = player.IsReady;
                            break;
                        }
                    }
                }
                await _room.SetReadyAsync(!ready);
            }
            catch (Exception ex)
            {
                AddLog("Ready error: " + ex.Message);
            }
            finally
            {
                _busy = false;
            }
        }

        private async void StartGame()
        {
            if (_busy || !_isHost || _room == null) return;
            _busy = true;
            try
            {
                await _room.StartGameAsync();
            }
            catch (Exception ex)
            {
                AddLog("Start match error: " + ex.Message);
            }
            finally
            {
                _busy = false;
            }
        }

        private async void OnGameStarted(SolarGameStartInfo info)
        {
            if (_game != null) return;
            if (info.PlayerIds == null || info.PlayerIds.Length != 2)
            {
                AddLog("Grid Duel requires exactly two players.");
                return;
            }

            try
            {
                _playerAId = info.PlayerIds[0];
                _playerBId = info.PlayerIds[1];
                _gameState = new GridDuelStateMachine(_playerAId, _playerBId);
                var requiredReplica = _isHost
                    ? (string.Equals(_playerAId, _localPeerId, StringComparison.Ordinal) ? _playerBId : _playerAId)
                    : null;
                _game = new SolarTurnSession(
                    info.GameSessionId,
                    info.HostPeerId,
                    _transport,
                    _isHost ? info.CreateHostTurnCoordinator() : null,
                    _gameState,
                    256,
                    requiredReplica);
                SubscribeGame(_game);
                await _game.StartAsync();
                if (_isHost) PersistCurrentAuthorityEpoch("game start");

                if (!_isHost && _resumeFromProcessRestart)
                {
                    _status = "Rejoined active match. Restoring authoritative state...";
                    AddLog("Game session restored: " + info.GameSessionId + ". Requesting resync from turn 0.");
                    _resumeFromProcessRestart = false;
                    await _game.RequestResyncAsync(0);
                }
                else
                {
                    _status = "Match started. SUN moves first.";
                    AddLog("Game session started: " + info.GameSessionId);
                }
            }
            catch (Exception ex)
            {
                _status = "Game setup failed: " + ex.Message;
                AddLog(_status);
                _game = null;
                _gameState = null;
            }
        }

        private void OnActionCommitted(SolarTurnCommit commit)
        {
            _status = PlayerLabel(commit.ActorId) + " committed " + commit.ActionKind + ".";
            AddLog("Turn " + commit.CommittedTurnIndex + " committed by " + commit.ActorId + ".");
            if (_gameState != null && _gameState.IsFinished)
                _status = "Winner: " + PlayerLabel(_gameState.WinnerPeerId);
        }

        private async void StopNetworkedMatch()
        {
            if (_busy) return;
            _busy = true;
            try
            {
                await ShutdownAsync();
                ClearPersistedAuthorityEpoch();
                _status = "Stopped.";
            }
            finally
            {
                _busy = false;
            }
        }

        private async Task CleanupFailedStart()
        {
            try
            {
                if (_room != null) _room.Detach();
                if (_transport != null) await _transport.StopAsync();
            }
            catch { }
            DisposeNativeAdapter();
            ClearRuntimeState();
        }

        private async Task ShutdownAsync()
        {
            if (_room != null) _room.Detach();
            try
            {
                if (_game != null) await _game.StopAsync();
                else if (_transport != null) await _transport.StopAsync();
            }
            catch (Exception ex)
            {
                AddLog("Shutdown error: " + ex.Message);
            }
            DisposeNativeAdapter();
            ClearRuntimeState();
        }

        private void DisposeNativeAdapter()
        {
            if (_nativeAdapter == null) return;
            try { _nativeAdapter.Dispose(); } catch { }
            _nativeAdapter = null;
        }

        private void ClearRuntimeState()
        {
            _started = false;
            _room = null;
            _game = null;
            _gameState = null;
            _transport = null;
            _nearbyTransport = null;
            _bluetoothTransport = null;
            _nearbyEndpoints.Clear();
            _verificationRequests.Clear();
            _bondedDevices.Clear();
            _playerAId = string.Empty;
            _playerBId = string.Empty;
            _localPeerId = string.Empty;
            _sawLobbyBeforePlaying = false;
            _resumeFromProcessRestart = false;
            _authorityResumeAwaitingReplicaProof = false;
            _authorityResumeProofTurn = 0;
            ClearMigrationRuntimeState();
        }

        private async void OnDestroy()
        {
            if (_transport != null) await ShutdownAsync();
        }

        private static string GetOrCreatePersistentClientPeerId()
        {
            var existing = PlayerPrefs.GetString(ClientPeerIdKey, string.Empty);
            if (!string.IsNullOrWhiteSpace(existing)) return existing;

            var created = "client-" + Guid.NewGuid().ToString("N").Substring(0, 12);
            PlayerPrefs.SetString(ClientPeerIdKey, created);
            PlayerPrefs.Save();
            return created;
        }

        private string PlayerLabel(string peerId)
        {
            if (string.Equals(peerId, _playerAId, StringComparison.Ordinal)) return "SUN";
            if (string.Equals(peerId, _playerBId, StringComparison.Ordinal)) return "MOON";
            return peerId ?? string.Empty;
        }

        private static string FriendlyEndpointName(string endpointName)
        {
            SolarNearbyRoomAdvertisement advertisement;
            return NearbyRoomAdvertisementCodec.TryDecode(endpointName, out advertisement)
                ? advertisement.RoomName + " [" + advertisement.RoomId + "]"
                : endpointName;
        }

        private void AddLog(string message)
        {
            if (string.IsNullOrWhiteSpace(message)) return;
            _logs.Add(DateTime.Now.ToString("HH:mm:ss") + " " + message);
            if (_logs.Count > 100) _logs.RemoveRange(0, _logs.Count - 100);
        }
    }
}
