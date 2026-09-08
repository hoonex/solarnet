using System;
using System.Threading.Tasks;
using SolarNet.Session;
using SolarNet.Transport;
using SolarNet.Turns;
using UnityEngine;

namespace SolarNet.Samples.GridDuel
{
    public sealed class GridDuelLocalDemo : MonoBehaviour
    {
        private const string PlayerA = "sun";
        private const string PlayerB = "moon";
        private LoopbackTransport _transportA;
        private LoopbackTransport _transportB;
        private SolarTurnSession _sessionA;
        private SolarTurnSession _sessionB;
        private GridDuelStateMachine _stateA;
        private GridDuelStateMachine _stateB;
        private string _status = "Starting network simulation...";
        private bool _busy;

        private async void Start()
        {
            await StartMatchAsync();
        }

        private void OnGUI()
        {
            GUILayout.BeginVertical(GUI.skin.box);
            GUILayout.Label("Grid Duel — SolarNet 2D turn sample");
            GUILayout.Label("Two SolarTurnSession instances are communicating through LoopbackTransport.");
            GUILayout.Label(_status);
            GUILayout.Space(8f);

            if (_stateA != null)
            {
                var a = _stateA.GetPlayer(PlayerA);
                var b = _stateA.GetPlayer(PlayerB);
                GUILayout.Label("SUN HP " + a.Health + "   vs   MOON HP " + b.Health);
                GUILayout.Label("Turn " + (_sessionA == null ? 0 : _sessionA.KnownNextTurnIndex) + " — active: " + CurrentPeerId());
                DrawBoard();

                if (_stateA.IsFinished)
                    GUILayout.Label("Winner: " + _stateA.WinnerPeerId);
            }

            GUI.enabled = !_busy;
            if (GUILayout.Button("Reset match")) ResetMatch();
            GUI.enabled = true;
            GUILayout.EndVertical();
        }

        private void DrawBoard()
        {
            for (var y = 0; y < GridDuelStateMachine.BoardHeight; y++)
            {
                GUILayout.BeginHorizontal();
                for (var x = 0; x < GridDuelStateMachine.BoardWidth; x++)
                {
                    var occupant = _stateA.GetPlayerAt(x, y);
                    var label = occupant == null ? "·" : (occupant.PeerId == PlayerA ? "SUN\n" : "MOON\n") + occupant.Health;
                    GUI.enabled = !_busy && !_stateA.IsFinished;
                    if (GUILayout.Button(label, GUILayout.Width(72f), GUILayout.Height(58f)))
                        HandleCell(x, y);
                }
                GUILayout.EndHorizontal();
            }
            GUI.enabled = true;
        }

        private async void HandleCell(int x, int y)
        {
            if (_busy || _stateA == null || _stateA.IsFinished) return;
            var activePeer = CurrentPeerId();
            if (string.IsNullOrEmpty(activePeer)) return;
            var actor = _stateA.GetPlayer(activePeer);
            var target = _stateA.GetPlayerAt(x, y);

            _busy = true;
            try
            {
                var session = activePeer == PlayerA ? _sessionA : _sessionB;
                if (target != null && !string.Equals(target.PeerId, activePeer, StringComparison.Ordinal))
                {
                    if (Math.Abs(actor.X - target.X) + Math.Abs(actor.Y - target.Y) != 1)
                    {
                        _status = "Enemy is not adjacent.";
                        return;
                    }
                    await session.SubmitActionAsync(GridDuelActionCodec.AttackAction, Array.Empty<byte>());
                }
                else if (target == null)
                {
                    if (Math.Abs(actor.X - x) + Math.Abs(actor.Y - y) != 1)
                    {
                        _status = "Move exactly one orthogonal cell.";
                        return;
                    }
                    await session.SubmitActionAsync(GridDuelActionCodec.MoveAction, GridDuelActionCodec.EncodeMove(x, y));
                }
                else
                {
                    _status = "That cell contains your own unit.";
                }
            }
            catch (Exception ex)
            {
                _status = "Action error: " + ex.Message;
            }
            finally
            {
                _busy = false;
            }
        }

        private async Task StartMatchAsync()
        {
            _busy = true;
            try
            {
                var hub = new LoopbackTransportHub();
                _transportA = hub.CreateEndpoint(PlayerA);
                _transportB = hub.CreateEndpoint(PlayerB);
                _stateA = new GridDuelStateMachine(PlayerA, PlayerB);
                _stateB = new GridDuelStateMachine(PlayerA, PlayerB);
                _sessionA = new SolarTurnSession("grid-duel-local", PlayerA, _transportA, new TurnCoordinator(new[] { PlayerA, PlayerB }), _stateA);
                _sessionB = new SolarTurnSession("grid-duel-local", PlayerA, _transportB, null, _stateB);
                _sessionA.ActionCommitted += OnCommit;
                _sessionB.ActionCommitted += OnRemoteCommit;
                _sessionA.ActionRejected += rejection => _status = "Rejected: " + rejection.Reason;
                _sessionB.ActionRejected += rejection => _status = "Rejected: " + rejection.Reason;
                _sessionA.ProtocolFaulted += ex => _status = "Host fault: " + ex.Message;
                _sessionB.ProtocolFaulted += ex => _status = "Client fault: " + ex.Message;
                await _sessionA.StartAsync();
                await _sessionB.StartAsync();
                _status = "Ready. SUN moves first. Tap an adjacent cell.";
            }
            catch (Exception ex)
            {
                _status = "Start failed: " + ex.Message;
            }
            finally
            {
                _busy = false;
            }
        }

        private void OnCommit(SolarTurnCommit commit)
        {
            _status = commit.ActorId + " committed " + commit.ActionKind + ". Next: " + commit.NextPlayerId;
        }

        private void OnRemoteCommit(SolarTurnCommit commit)
        {
            if (!SnapshotsEqual()) _status = "ERROR: host/client game states diverged.";
        }

        private bool SnapshotsEqual()
        {
            var a = _stateA.CaptureSnapshot();
            var b = _stateB.CaptureSnapshot();
            if (a.Length != b.Length) return false;
            for (var i = 0; i < a.Length; i++) if (a[i] != b[i]) return false;
            return true;
        }

        private string CurrentPeerId()
        {
            return _sessionA == null ? string.Empty : _sessionA.KnownCurrentPlayerId;
        }

        private async void ResetMatch()
        {
            if (_busy) return;
            _busy = true;
            try
            {
                await StopMatchAsync();
            }
            finally
            {
                _busy = false;
            }
            await StartMatchAsync();
        }

        private async Task StopMatchAsync()
        {
            try
            {
                if (_sessionB != null) await _sessionB.StopAsync();
                if (_sessionA != null) await _sessionA.StopAsync();
            }
            catch { }
            _sessionA = null;
            _sessionB = null;
            _transportA = null;
            _transportB = null;
            _stateA = null;
            _stateB = null;
        }

        private async void OnDestroy()
        {
            await StopMatchAsync();
        }
    }
}
