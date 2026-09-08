using System;
using System.Collections.Generic;
using System.Text;
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

namespace SolarNet.Samples.Diagnostics
{
    public sealed class SolarNetDiagnostics : MonoBehaviour
    {
        private const string RoomId = "solarnet-diagnostics";
        private const string RoomName = "SolarNet Diagnostics";
        private const string CompatibilityKey = "diagnostics-v1";
        private const string NearbyServiceId = "com.hoonex.solarnet.diagnostics";
        private const string HostPeerId = "host";

        private enum TransportMode
        {
            Nearby,
            BluetoothClassic
        }

        private sealed class NearbyEndpointView
        {
            public string Id;
            public string Name;
        }

        private TransportMode _transportMode = TransportMode.BluetoothClassic;
        private bool _isHost = true;
        private string _displayName = "Player";
        private string _localPeerId = string.Empty;
        private string _status = "Not started";
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
        private string _currentTurnPeerId = string.Empty;
        private long _nextTurnIndex;
        private bool _started;
        private bool _busy;
        private Vector2 _scroll;

        private void Awake()
        {
            _displayName = "Player-" + UnityEngine.Random.Range(100, 999);
            AddLog("SolarNet Diagnostics loaded.");
#if !UNITY_ANDROID || UNITY_EDITOR
            AddLog("Android transports require an Android Player build. Editor is UI-only.");
#endif
        }

        private void OnGUI()
        {
            var width = Mathf.Min(Screen.width - 32f, 760f);
            var height = Screen.height - 32f;
            GUILayout.BeginArea(new Rect(16f, 16f, width, height), GUI.skin.box);
            _scroll = GUILayout.BeginScrollView(_scroll);

            GUILayout.Label("SolarNet Diagnostics", HeaderStyle());
            GUILayout.Label("Nearby + Bluetooth Classic + Room + Turn test harness");
            GUILayout.Space(8f);

            if (!_started)
                DrawSetup();
            else
                DrawRunning();

            GUILayout.Space(12f);
            GUILayout.Label("Event log", SubheaderStyle());
            for (var i = Math.Max(0, _logs.Count - 18); i < _logs.Count; i++)
                GUILayout.Label(_logs[i]);

            GUILayout.EndScrollView();
            GUILayout.EndArea();
        }

        private void DrawSetup()
        {
            GUILayout.Label("1. Device setup", SubheaderStyle());
            GUILayout.BeginHorizontal();
            GUI.enabled = !_busy;
            if (GUILayout.Toggle(_isHost, "Host", GUI.skin.button)) _isHost = true;
            if (GUILayout.Toggle(!_isHost, "Client", GUI.skin.button)) _isHost = false;
            GUILayout.EndHorizontal();

            GUILayout.BeginHorizontal();
            if (GUILayout.Toggle(_transportMode == TransportMode.BluetoothClassic, "Bluetooth Classic", GUI.skin.button))
                _transportMode = TransportMode.BluetoothClassic;
            if (GUILayout.Toggle(_transportMode == TransportMode.Nearby, "Nearby Connections", GUI.skin.button))
                _transportMode = TransportMode.Nearby;
            GUILayout.EndHorizontal();

            GUILayout.Label("Display name");
            _displayName = GUILayout.TextField(_displayName, 32);
            GUILayout.Label("Status: " + _status);

            if (GUILayout.Button("Request Android permissions", GUILayout.Height(40f)))
                RequestAndroidPermissions();
            if (GUILayout.Button(_isHost ? "Start host" : "Start client", GUILayout.Height(48f)))
                StartDiagnostics();
            GUI.enabled = true;
        }

        private void DrawRunning()
        {
            GUILayout.Label("Status: " + _status);
            GUILayout.Label("Peer: " + _localPeerId + "  |  Transport: " + _transportMode);
            GUILayout.Label("Role: " + (_isHost ? "Host" : "Client"));

            if (_transportMode == TransportMode.Nearby)
                DrawNearbyControls();
            else
                DrawBluetoothControls();

            GUILayout.Space(8f);
            DrawRoomControls();

            GUILayout.Space(8f);
            GUI.enabled = !_busy;
            if (GUILayout.Button("Stop diagnostics", GUILayout.Height(42f)))
                StopDiagnostics();
            GUI.enabled = true;
        }

        private void DrawNearbyControls()
        {
            GUILayout.Label("2. Nearby connection", SubheaderStyle());
            if (!_isHost)
            {
                if (_nearbyEndpoints.Count == 0)
                    GUILayout.Label("Searching for Nearby hosts...");

                var endpoints = new List<NearbyEndpointView>(_nearbyEndpoints.Values);
                foreach (var endpoint in endpoints)
                {
                    if (GUILayout.Button("Connect: " + FriendlyEndpointName(endpoint.Name)))
                        RequestNearbyConnection(endpoint.Id);
                }
            }
            else
            {
                GUILayout.Label("Advertising room: " + RoomName);
            }

            if (_verificationRequests.Count > 0)
            {
                GUILayout.Space(4f);
                GUILayout.Label("Verify the same code is shown on both phones:");
                var requests = new List<NearbyVerificationRequest>(_verificationRequests.Values);
                foreach (var request in requests)
                {
                    GUILayout.BeginVertical(GUI.skin.box);
                    GUILayout.Label(request.EndpointName + "  code: " + request.AuthenticationDigits);
                    GUILayout.BeginHorizontal();
                    if (GUILayout.Button("Accept")) AcceptNearby(request.EndpointId);
                    if (GUILayout.Button("Reject")) RejectNearby(request.EndpointId);
                    GUILayout.EndHorizontal();
                    GUILayout.EndVertical();
                }
            }
        }

        private void DrawBluetoothControls()
        {
            GUILayout.Label("2. Bluetooth Classic connection", SubheaderStyle());
            if (_isHost)
            {
                GUILayout.Label("RFCOMM server is listening. Pair the phones in Android settings first.");
                return;
            }

            if (GUILayout.Button("Refresh paired devices")) RefreshBondedDevices();
            if (_bondedDevices.Count == 0)
                GUILayout.Label("No paired devices loaded. Pair both phones in Android settings, then refresh.");

            for (var i = 0; i < _bondedDevices.Count; i++)
            {
                var device = _bondedDevices[i];
                if (GUILayout.Button("Connect: " + device.Name + "  [" + device.Address + "]"))
                    ConnectBluetooth(device.Address);
            }
        }

        private void DrawRoomControls()
        {
            GUILayout.Label("3. Room / turn protocol", SubheaderStyle());
            if (_room == null)
            {
                GUILayout.Label("Room is not initialized.");
                return;
            }

            var snapshot = _room.CurrentSnapshot;
            GUILayout.Label("Room phase: " + _room.Phase);
            if (snapshot != null)
            {
                GUILayout.Label("Roster " + snapshot.Players.Length + "/" + snapshot.MaxPlayers + "  revision " + snapshot.Revision);
                foreach (var player in snapshot.Players)
                {
                    GUILayout.Label(
                        "#" + player.Slot + " " + player.DisplayName +
                        "  peer=" + player.PeerId +
                        "  " + (player.IsReady ? "READY" : "not ready") +
                        "  " + (player.IsConnected ? "online" : "offline"));
                }
            }

            GUI.enabled = !_busy && _room.Phase == SolarRoomPhase.Lobby;
            if (GUILayout.Button("Toggle my Ready", GUILayout.Height(38f)))
                ToggleReady();

            GUI.enabled = !_busy && _isHost && _room.CanStart;
            if (GUILayout.Button("Start game session", GUILayout.Height(42f)))
                StartGame();

            GUI.enabled = !_busy && _game != null && string.Equals(_currentTurnPeerId, _localPeerId, StringComparison.Ordinal);
            if (GUILayout.Button("Send diagnostic turn", GUILayout.Height(42f)))
                SendDiagnosticTurn();
            GUI.enabled = true;

            if (_game != null)
                GUILayout.Label("Turn " + _nextTurnIndex + "  active peer: " + _currentTurnPeerId);
        }

        private async void StartDiagnostics()
        {
            if (_busy || _started) return;
            _busy = true;
            try
            {
                _localPeerId = _isHost ? HostPeerId : "client-" + Guid.NewGuid().ToString("N").Substring(0, 8);
                _nearbyEndpoints.Clear();
                _verificationRequests.Clear();
                _bondedDevices.Clear();

                if (_transportMode == TransportMode.Nearby)
                {
                    var adapter = new AndroidNearbyAdapter();
                    _nativeAdapter = adapter;
                    var endpointName = _isHost
                        ? NearbyRoomAdvertisementCodec.Encode(RoomId, RoomName, CompatibilityKey)
                        : _displayName;
                    _nearbyTransport = new NearbyTransport(
                        _localPeerId,
                        adapter,
                        new NearbyTransportOptions(
                            NearbyServiceId,
                            endpointName,
                            _isHost ? NearbyConnectionRole.Advertiser : NearbyConnectionRole.Discoverer,
                            NearbyConnectionStrategy.Star,
                            false));
                    _nearbyTransport.EndpointDiscovered += OnNearbyEndpointFound;
                    _nearbyTransport.EndpointLost += OnNearbyEndpointLost;
                    _nearbyTransport.ConnectionVerificationRequired += OnNearbyVerificationRequired;
                    _nearbyTransport.PeerConnected += OnPeerConnected;
                    _nearbyTransport.PeerDisconnected += OnPeerDisconnected;
                    _nearbyTransport.Faulted += OnTransportFault;
                    _transport = _nearbyTransport;
                }
                else
                {
                    var adapter = new AndroidBluetoothClassicAdapter();
                    _nativeAdapter = adapter;
                    _bluetoothTransport = new BluetoothClassicTransport(
                        _localPeerId,
                        adapter,
                        new BluetoothClassicTransportOptions(_isHost ? BluetoothClassicRole.Server : BluetoothClassicRole.Client));
                    _bluetoothTransport.PeerConnected += OnPeerConnected;
                    _bluetoothTransport.PeerDisconnected += OnPeerDisconnected;
                    _bluetoothTransport.Faulted += OnTransportFault;
                    _transport = _bluetoothTransport;
                }

                _room = new SolarRoomSession(
                    new SolarRoomOptions(
                        RoomId,
                        HostPeerId,
                        string.IsNullOrWhiteSpace(_displayName) ? _localPeerId : _displayName,
                        CompatibilityKey,
                        RoomName,
                        4,
                        SolarHostDisconnectPolicy.WaitForReconnect),
                    _transport);
                SubscribeRoom(_room);
                _room.Attach();

                await _transport.StartAsync();
                _started = true;
                _status = _isHost ? "Host transport started" : "Client transport started";
                AddLog(_status + " as " + _localPeerId + ".");

                if (_transportMode == TransportMode.BluetoothClassic && !_isHost)
                    RefreshBondedDevices();
            }
            catch (Exception ex)
            {
                _status = "Start failed";
                AddLog("ERROR start: " + ex.Message);
                await CleanupAfterFailedStart();
            }
            finally
            {
                _busy = false;
            }
        }

        private async Task CleanupAfterFailedStart()
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

        private void RequestAndroidPermissions()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            try
            {
                var deviceSdk = GetAndroidSdkInt();
                var targetSdk = GetAndroidTargetSdkInt();
                var required = _transportMode == TransportMode.Nearby
                    ? NearbyAndroidPermissions.GetRequiredRuntimePermissions(deviceSdk, targetSdk)
                    : BluetoothClassicAndroidPermissions.GetRequiredRuntimePermissions(deviceSdk);

                var requested = 0;
                foreach (var permission in required)
                {
                    if (Permission.HasUserAuthorizedPermission(permission)) continue;
                    Permission.RequestUserPermission(permission);
                    requested++;
                }
                AddLog(requested == 0 ? "Required Android permissions are already granted." : "Requested " + requested + " Android permission(s). Re-tap Start after approving them.");
            }
            catch (Exception ex)
            {
                AddLog("ERROR permission request: " + ex.Message);
            }
#else
            AddLog("Permission request is only available in an Android Player build.");
#endif
        }

#if UNITY_ANDROID && !UNITY_EDITOR
        private static int GetAndroidSdkInt()
        {
            using (var version = new AndroidJavaClass("android.os.Build$VERSION"))
                return version.GetStatic<int>("SDK_INT");
        }

        private static int GetAndroidTargetSdkInt()
        {
            using (var unityPlayer = new AndroidJavaClass("com.unity3d.player.UnityPlayer"))
            using (var activity = unityPlayer.GetStatic<AndroidJavaObject>("currentActivity"))
            using (var appInfo = activity.Call<AndroidJavaObject>("getApplicationInfo"))
                return appInfo.Get<int>("targetSdkVersion");
        }
#endif

        private async void RequestNearbyConnection(string endpointId)
        {
            if (_busy || _nearbyTransport == null) return;
            _busy = true;
            try
            {
                await _nearbyTransport.RequestConnectionAsync(endpointId);
                AddLog("Nearby connection requested: " + endpointId);
            }
            catch (Exception ex) { AddLog("ERROR Nearby connect: " + ex.Message); }
            finally { _busy = false; }
        }

        private async void AcceptNearby(string endpointId)
        {
            if (_nearbyTransport == null) return;
            try
            {
                await _nearbyTransport.AcceptConnectionAsync(endpointId);
                _verificationRequests.Remove(endpointId);
                AddLog("Accepted Nearby verification for " + endpointId + ".");
            }
            catch (Exception ex) { AddLog("ERROR Nearby accept: " + ex.Message); }
        }

        private async void RejectNearby(string endpointId)
        {
            if (_nearbyTransport == null) return;
            try
            {
                await _nearbyTransport.RejectConnectionAsync(endpointId);
                _verificationRequests.Remove(endpointId);
                AddLog("Rejected Nearby verification for " + endpointId + ".");
            }
            catch (Exception ex) { AddLog("ERROR Nearby reject: " + ex.Message); }
        }

        private void RefreshBondedDevices()
        {
            if (_bluetoothTransport == null) return;
            try
            {
                _bondedDevices.Clear();
                _bondedDevices.AddRange(_bluetoothTransport.GetBondedDevices());
                AddLog("Loaded " + _bondedDevices.Count + " paired Bluetooth device(s).");
            }
            catch (Exception ex) { AddLog("ERROR paired devices: " + ex.Message); }
        }

        private async void ConnectBluetooth(string address)
        {
            if (_busy || _bluetoothTransport == null) return;
            _busy = true;
            try
            {
                await _bluetoothTransport.ConnectAsync(address);
                AddLog("Bluetooth RFCOMM connection established to " + address + ".");
            }
            catch (Exception ex) { AddLog("ERROR Bluetooth connect: " + ex.Message); }
            finally { _busy = false; }
        }

        private async void OnPeerConnected(string peerId)
        {
            AddLog("SolarNet peer connected: " + peerId);
            _status = "Connected to " + peerId;
            try
            {
                if (_room != null) await _room.NotifyPeerConnectedAsync(peerId);
            }
            catch (Exception ex) { AddLog("ERROR room join notify: " + ex.Message); }
        }

        private async void OnPeerDisconnected(string peerId)
        {
            AddLog("SolarNet peer disconnected: " + peerId);
            _status = "Peer disconnected";
            try
            {
                if (_room != null) await _room.NotifyPeerDisconnectedAsync(peerId);
            }
            catch (Exception ex) { AddLog("ERROR room disconnect notify: " + ex.Message); }
        }

        private void OnNearbyEndpointFound(NearbyEndpoint endpoint)
        {
            _nearbyEndpoints[endpoint.EndpointId] = new NearbyEndpointView { Id = endpoint.EndpointId, Name = endpoint.EndpointName };
            AddLog("Nearby endpoint found: " + FriendlyEndpointName(endpoint.EndpointName));
        }

        private void OnNearbyEndpointLost(string endpointId)
        {
            _nearbyEndpoints.Remove(endpointId);
            AddLog("Nearby endpoint lost: " + endpointId);
        }

        private void OnNearbyVerificationRequired(NearbyVerificationRequest request)
        {
            _verificationRequests[request.EndpointId] = request;
            AddLog("Nearby verification code: " + request.AuthenticationDigits + " for " + request.EndpointName);
        }

        private void OnTransportFault(Exception exception)
        {
            AddLog("TRANSPORT FAULT: " + exception.Message);
        }

        private void SubscribeRoom(SolarRoomSession room)
        {
            room.RoomChanged += snapshot =>
            {
                _status = "Room " + snapshot.Phase + " (" + snapshot.Players.Length + " players)";
                AddLog("Room revision " + snapshot.Revision + ": " + snapshot.Phase + ", players=" + snapshot.Players.Length);
            };
            room.PhaseChanged += phase => AddLog("Room phase -> " + phase);
            room.JoinRejected += rejection => AddLog("Room join rejected: " + rejection.Reason);
            room.RoomClosed += closed => AddLog("Room closed: " + closed.Reason);
            room.GameStarted += OnGameStarted;
            room.ProtocolFaulted += ex => AddLog("ROOM FAULT: " + ex.Message);
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
            catch (Exception ex) { AddLog("ERROR ready: " + ex.Message); }
            finally { _busy = false; }
        }

        private async void StartGame()
        {
            if (_busy || _room == null || !_isHost) return;
            _busy = true;
            try
            {
                await _room.StartGameAsync();
            }
            catch (Exception ex) { AddLog("ERROR game start: " + ex.Message); }
            finally { _busy = false; }
        }

        private async void OnGameStarted(SolarGameStartInfo info)
        {
            if (_game != null) return;
            try
            {
                _currentTurnPeerId = info.PlayerIds[0];
                _nextTurnIndex = 0;
                _game = new SolarTurnSession(
                    info.GameSessionId,
                    info.HostPeerId,
                    _transport,
                    _isHost ? info.CreateHostTurnCoordinator() : null);
                _game.ActionCommitted += OnActionCommitted;
                _game.ActionRejected += rejection => AddLog("Turn rejected: " + rejection.Reason + " at host turn " + rejection.HostTurnIndex);
                _game.ProtocolFaulted += ex => AddLog("GAME FAULT: " + ex.Message);
                await _game.StartAsync();
                _status = "Game started";
                AddLog("Game session " + info.GameSessionId + " started. First turn: " + _currentTurnPeerId);
            }
            catch (Exception ex)
            {
                AddLog("ERROR creating turn session: " + ex.Message);
            }
        }

        private void OnActionCommitted(SolarTurnCommit commit)
        {
            _nextTurnIndex = commit.NextTurnIndex;
            _currentTurnPeerId = commit.NextPlayerId;
            var payload = commit.Payload == null ? string.Empty : Encoding.UTF8.GetString(commit.Payload);
            AddLog("COMMIT turn=" + commit.CommittedTurnIndex + " actor=" + commit.ActorId + " action=" + commit.ActionKind + " payload=" + payload);
        }

        private async void SendDiagnosticTurn()
        {
            if (_busy || _game == null) return;
            _busy = true;
            try
            {
                var payload = Encoding.UTF8.GetBytes(DateTime.UtcNow.ToString("O"));
                await _game.SubmitActionAsync(_nextTurnIndex, "diagnostic-ping", payload);
            }
            catch (Exception ex) { AddLog("ERROR send turn: " + ex.Message); }
            finally { _busy = false; }
        }

        private async void StopDiagnostics()
        {
            if (_busy) return;
            _busy = true;
            try
            {
                await ShutdownAsync();
                _status = "Stopped";
                AddLog("Diagnostics stopped.");
            }
            finally { _busy = false; }
        }

        private async Task ShutdownAsync()
        {
            if (_room != null) _room.Detach();
            try
            {
                if (_game != null)
                    await _game.StopAsync();
                else if (_transport != null)
                    await _transport.StopAsync();
            }
            catch (Exception ex) { AddLog("ERROR shutdown: " + ex.Message); }

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
            _game = null;
            _room = null;
            _transport = null;
            _nearbyTransport = null;
            _bluetoothTransport = null;
            _nearbyEndpoints.Clear();
            _verificationRequests.Clear();
            _bondedDevices.Clear();
            _currentTurnPeerId = string.Empty;
            _nextTurnIndex = 0;
        }

        private async void OnDestroy()
        {
            if (_transport == null) return;
            await ShutdownAsync();
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
            _logs.Add(DateTime.Now.ToString("HH:mm:ss") + "  " + message);
            if (_logs.Count > 100) _logs.RemoveRange(0, _logs.Count - 100);
        }

        private static GUIStyle HeaderStyle()
        {
            var style = new GUIStyle(GUI.skin.label);
            style.fontSize = 24;
            style.fontStyle = FontStyle.Bold;
            return style;
        }

        private static GUIStyle SubheaderStyle()
        {
            var style = new GUIStyle(GUI.skin.label);
            style.fontSize = 17;
            style.fontStyle = FontStyle.Bold;
            return style;
        }
    }
}
