using System;
using SolarNet.Transport;

namespace SolarNet.Room
{
    public static class SolarRoomAuthorityResume
    {
        public static SolarRoomSession CreateSession(
            SolarRoomSnapshot persistedSnapshot,
            ISolarTransport transport,
            SolarHostDisconnectPolicy hostDisconnectPolicy = SolarHostDisconnectPolicy.WaitForReconnect)
        {
            if (persistedSnapshot == null) throw new ArgumentNullException(nameof(persistedSnapshot));
            if (transport == null) throw new ArgumentNullException(nameof(transport));
            if (persistedSnapshot.Phase != SolarRoomPhase.Playing)
                throw new InvalidOperationException("Authority resume requires a persisted Playing room snapshot.");
            if (string.IsNullOrWhiteSpace(persistedSnapshot.GameSessionId))
                throw new InvalidOperationException("Authority resume requires a persisted game session ID.");
            if (!string.Equals(transport.LocalPeerId, persistedSnapshot.HostPeerId, StringComparison.Ordinal))
                throw new InvalidOperationException("Only the persisted room authority peer can resume this room epoch.");

            SolarRoomPlayer local = null;
            foreach (var player in persistedSnapshot.Players)
            {
                if (string.Equals(player.PeerId, transport.LocalPeerId, StringComparison.Ordinal))
                {
                    local = player;
                    break;
                }
            }
            if (local == null)
                throw new InvalidOperationException("Persisted room roster does not contain the local authority peer.");

            var options = new SolarRoomOptions(
                persistedSnapshot.RoomId,
                persistedSnapshot.HostPeerId,
                local.DisplayName,
                persistedSnapshot.CompatibilityKey,
                persistedSnapshot.RoomName,
                persistedSnapshot.MaxPlayers,
                hostDisconnectPolicy);
            return new SolarRoomSession(options, transport, persistedSnapshot, true);
        }
    }

    public sealed partial class SolarRoomSession
    {
        internal SolarRoomSession(
            SolarRoomOptions options,
            ISolarTransport transport,
            SolarRoomSnapshot persistedAuthoritySnapshot,
            bool resumeAuthority)
        {
            _options = options ?? throw new ArgumentNullException(nameof(options));
            _transport = transport ?? throw new ArgumentNullException(nameof(transport));
            if (persistedAuthoritySnapshot == null) throw new ArgumentNullException(nameof(persistedAuthoritySnapshot));
            if (!resumeAuthority) throw new ArgumentException("Authority resume marker must be true.", nameof(resumeAuthority));
            if (persistedAuthoritySnapshot.Phase != SolarRoomPhase.Playing)
                throw new InvalidOperationException("Authority resume requires a Playing room snapshot.");
            if (!string.Equals(_transport.LocalPeerId, persistedAuthoritySnapshot.HostPeerId, StringComparison.Ordinal))
                throw new InvalidOperationException("Only the persisted authority peer can seed resumed room authority.");
            if (!string.Equals(_options.HostPeerId, persistedAuthoritySnapshot.HostPeerId, StringComparison.Ordinal))
                throw new InvalidOperationException("Resumed room options must preserve the persisted authority peer.");

            IsHost = true;
            _localPhase = SolarRoomPhase.Playing;
            _hostRevision = persistedAuthoritySnapshot.Revision + 1;

            foreach (var sourcePlayer in persistedAuthoritySnapshot.Players)
            {
                _hostPlayers.Add(new SolarRoomPlayer(
                    sourcePlayer.Slot,
                    sourcePlayer.PeerId,
                    sourcePlayer.DisplayName,
                    sourcePlayer.IsReady,
                    string.Equals(sourcePlayer.PeerId, LocalPeerId, StringComparison.Ordinal)));
            }
            SortHostPlayers();

            if (FindHostPlayer(LocalPeerId) < 0)
                throw new InvalidOperationException("Resumed room roster does not contain the authority peer.");

            _snapshot = BuildHostSnapshot(SolarRoomPhase.Playing, persistedAuthoritySnapshot.GameSessionId);
            _lastStartedGameSessionId = persistedAuthoritySnapshot.GameSessionId;
        }
    }
}
