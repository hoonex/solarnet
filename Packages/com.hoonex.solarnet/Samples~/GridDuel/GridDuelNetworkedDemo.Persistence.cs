using System;
using SolarNet.Session;
using UnityEngine;

namespace SolarNet.Samples.GridDuel
{
    public sealed partial class GridDuelNetworkedDemo
    {
        private const string AuthorityEpochKey = "SolarNet.GridDuel.AuthorityEpoch";
        private const string AuthorityEpochTransportKey = "SolarNet.GridDuel.AuthorityEpoch.Transport";

        private SolarAuthorityEpochRecord _persistedAuthorityEpoch;
        private string _persistedAuthorityEpochError = string.Empty;
        private TransportMode _persistedAuthorityTransportMode = TransportMode.BluetoothClassic;
        private bool _authorityResumeAwaitingReplicaProof;
        private long _authorityResumeProofTurn;

        private bool HasPersistedAuthorityBlob
        {
            get { return !string.IsNullOrWhiteSpace(PlayerPrefs.GetString(AuthorityEpochKey, string.Empty)); }
        }

        private void LoadPersistedAuthorityMetadata()
        {
            _persistedAuthorityEpoch = null;
            _persistedAuthorityEpochError = string.Empty;
            var encoded = PlayerPrefs.GetString(AuthorityEpochKey, string.Empty);
            if (string.IsNullOrWhiteSpace(encoded)) return;

            try
            {
                var transportValue = PlayerPrefs.GetString(AuthorityEpochTransportKey, string.Empty);
                TransportMode parsedMode;
                if (!Enum.TryParse(transportValue, true, out parsedMode))
                    throw new InvalidOperationException("Saved authority transport mode is missing or invalid.");

                _persistedAuthorityTransportMode = parsedMode;
                _persistedAuthorityEpoch = SolarAuthorityEpochCodec.Decode(Convert.FromBase64String(encoded));
                if (_persistedAuthorityEpoch.GameCheckpoint.PlayerIds.Length != 2)
                    throw new InvalidOperationException("Grid Duel authority resume requires exactly two persisted players.");
                if (string.IsNullOrWhiteSpace(_persistedAuthorityEpoch.RequiredReplicationPeerId))
                    throw new InvalidOperationException("Grid Duel authority resume requires a designated replica proof peer.");
            }
            catch (Exception ex)
            {
                _persistedAuthorityEpoch = null;
                _persistedAuthorityEpochError = ex.Message;
            }
        }

        private void DrawPersistedAuthorityControls()
        {
            if (!HasPersistedAuthorityBlob) return;

            GUILayout.Label("Saved authority epoch detected");
            if (_persistedAuthorityEpoch != null)
            {
                GUILayout.Label(
                    "Host: " + _persistedAuthorityEpoch.RoomSnapshot.HostPeerId +
                    " | turn " + _persistedAuthorityEpoch.GameCheckpoint.NextTurnIndex +
                    " | " + _persistedAuthorityTransportMode);
                GUILayout.Label("Resume is fenced until the designated replica verifies this exact epoch.");
                GUI.enabled = !_busy;
                if (GUILayout.Button("Resume saved authority", GUILayout.Height(42f))) ResumePersistedAuthority();
            }
            else
            {
                GUILayout.Label("Saved authority record is invalid: " + _persistedAuthorityEpochError);
            }

            GUI.enabled = !_busy;
            if (GUILayout.Button("Discard saved authority"))
            {
                ClearPersistedAuthorityEpoch();
                _status = "Saved authority record discarded.";
            }
            GUI.enabled = true;
            GUILayout.Space(8f);
        }

        private void PersistCurrentAuthorityEpoch(string reason)
        {
            if (_game == null || !_game.IsHost || _room == null || _gameState == null) return;
            try
            {
                var record = SolarAuthorityEpochPersistence.Capture(_room.CurrentSnapshot, _game, _gameState);
                var encoded = Convert.ToBase64String(SolarAuthorityEpochCodec.Encode(record));
                PlayerPrefs.SetString(AuthorityEpochKey, encoded);
                PlayerPrefs.SetString(AuthorityEpochTransportKey, _transportMode.ToString());
                PlayerPrefs.Save();
                _persistedAuthorityEpoch = record;
                _persistedAuthorityTransportMode = _transportMode;
                _persistedAuthorityEpochError = string.Empty;
                AddLog("Persisted durable authority epoch at turn " + record.GameCheckpoint.NextTurnIndex + " (" + reason + ").");
            }
            catch (Exception ex)
            {
                AddLog("Authority persistence skipped: " + ex.Message);
            }
        }

        private void ClearPersistedAuthorityEpoch()
        {
            PlayerPrefs.SetString(AuthorityEpochKey, string.Empty);
            PlayerPrefs.SetString(AuthorityEpochTransportKey, string.Empty);
            PlayerPrefs.Save();
            _persistedAuthorityEpoch = null;
            _persistedAuthorityEpochError = string.Empty;
            _authorityResumeAwaitingReplicaProof = false;
            _authorityResumeProofTurn = 0;
        }

        private void OnDurabilityAdvancedForPersistence(SolarDurabilityAdvance advance)
        {
            if (_game == null || !_game.IsHost || advance == null) return;
            PersistCurrentAuthorityEpoch("replica proof");
        }

        private void OnReplicationAcknowledgedForAuthorityResume(SolarReplicationAcknowledgement acknowledgement)
        {
            if (!_authorityResumeAwaitingReplicaProof || _game == null || acknowledgement == null) return;
            if (!string.Equals(acknowledgement.PeerId, _game.RequiredReplicationPeerId, StringComparison.Ordinal)) return;
            if (acknowledgement.NextTurnIndex < _authorityResumeProofTurn) return;
            if (!string.Equals(acknowledgement.StateHash, _game.LastStateHash, StringComparison.Ordinal)) return;

            _authorityResumeAwaitingReplicaProof = false;
            _status = "Saved authority epoch verified by replica. Match can continue.";
            AddLog("Replica re-verified resumed authority at turn " + acknowledgement.NextTurnIndex + ".");
            PersistCurrentAuthorityEpoch("resume proof");
        }

        private async void ResumePersistedAuthority()
        {
            if (_busy || _started || _persistedAuthorityEpoch == null) return;
            _busy = true;
            try
            {
                var record = _persistedAuthorityEpoch;
                _transportMode = _persistedAuthorityTransportMode;
                _localPeerId = record.RoomSnapshot.HostPeerId;
                _isHost = true;
                _resumeFromProcessRestart = false;
                _sawLobbyBeforePlaying = false;
                _nearbyEndpoints.Clear();
                _verificationRequests.Clear();
                _bondedDevices.Clear();

                var localDisplayName = FindPersistedPlayerDisplayName(record, _localPeerId);
                if (!string.Equals(_localPeerId, HostPeerId, StringComparison.Ordinal))
                {
                    PlayerPrefs.SetString(ClientPeerIdKey, _localPeerId);
                    PlayerPrefs.Save();
                }

                if (_transportMode == TransportMode.Nearby) CreateNearbyTransport(localDisplayName);
                else CreateBluetoothTransport();

                var players = record.GameCheckpoint.PlayerIds;
                _playerAId = players[0];
                _playerBId = players[1];
                _gameState = new GridDuelStateMachine(_playerAId, _playerBId);
                var bootstrap = SolarAuthorityEpochResume.Create(record, _transport, _gameState);
                _room = bootstrap.RoomSession;
                _game = bootstrap.GameSession;
                SubscribeRoom(_room);
                SubscribeGame(_game);
                _room.Attach();

                _authorityResumeProofTurn = record.GameCheckpoint.NextTurnIndex;
                _authorityResumeAwaitingReplicaProof = true;
                await _game.StartAsync();
                _started = true;
                _status = "Authority state restored. Waiting for designated replica verification.";
                AddLog(
                    "Resumed persisted authority epoch " + record.RoomSnapshot.GameSessionId +
                    " at turn " + record.GameCheckpoint.NextTurnIndex +
                    "; replica=" + record.RequiredReplicationPeerId + ".");
            }
            catch (Exception ex)
            {
                _status = "Authority resume failed: " + ex.Message;
                AddLog(_status);
                try
                {
                    if (_room != null) _room.Detach();
                    if (_game != null) await _game.StopAsync();
                    else if (_transport != null) await _transport.StopAsync();
                }
                catch { }
                DisposeNativeAdapter();
                ClearRuntimeState();
                LoadPersistedAuthorityMetadata();
            }
            finally
            {
                _busy = false;
            }
        }

        private static string FindPersistedPlayerDisplayName(SolarAuthorityEpochRecord record, string peerId)
        {
            foreach (var player in record.RoomSnapshot.Players)
                if (string.Equals(player.PeerId, peerId, StringComparison.Ordinal)) return player.DisplayName;
            throw new InvalidOperationException("Persisted authority peer is missing from the room roster.");
        }
    }
}
