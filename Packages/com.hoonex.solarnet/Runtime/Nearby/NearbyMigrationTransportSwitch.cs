using System;
using System.Threading;
using System.Threading.Tasks;
using SolarNet.Room;

namespace SolarNet.Nearby
{
    public sealed class NearbyMigrationSwitchResult
    {
        internal NearbyMigrationSwitchResult(NearbyTransport transport, NearbyConnectionRole role, SolarRoomSnapshot sourceSnapshot, SolarHostMigrationPlan plan)
        {
            Transport = transport ?? throw new ArgumentNullException(nameof(transport));
            Role = role;
            SourceSnapshot = sourceSnapshot ?? throw new ArgumentNullException(nameof(sourceSnapshot));
            Plan = plan ?? throw new ArgumentNullException(nameof(plan));
        }

        public NearbyTransport Transport { get; private set; }
        public NearbyConnectionRole Role { get; private set; }
        public SolarRoomSnapshot SourceSnapshot { get; private set; }
        public SolarHostMigrationPlan Plan { get; private set; }
        public bool IsSuccessor { get { return Plan.IsSuccessor(Transport.LocalPeerId); } }

        public bool IsExpectedAuthority(string peerId)
        {
            return !string.IsNullOrWhiteSpace(peerId) && string.Equals(peerId, Plan.SuccessorPeerId, StringComparison.Ordinal);
        }

        public bool IsSuccessorAdvertisement(NearbyEndpoint endpoint)
        {
            if (endpoint == null) return false;
            SolarNearbyRoomAdvertisement advertisement;
            if (!NearbyRoomAdvertisementCodec.TryDecode(endpoint.EndpointName, out advertisement)) return false;
            return string.Equals(advertisement.RoomId, SourceSnapshot.RoomId, StringComparison.Ordinal)
                && string.Equals(advertisement.RoomName, SourceSnapshot.RoomName, StringComparison.Ordinal)
                && string.Equals(advertisement.CompatibilityKey, SourceSnapshot.CompatibilityKey, StringComparison.Ordinal);
        }

        public Task RequestSuccessorConnectionAsync(NearbyEndpoint endpoint, CancellationToken cancellationToken = default(CancellationToken))
        {
            if (IsSuccessor) throw new InvalidOperationException("The elected successor advertises the migrated room and does not connect to itself.");
            if (!IsSuccessorAdvertisement(endpoint))
                throw new InvalidOperationException("Nearby endpoint does not advertise the expected migrated room.");
            return Transport.RequestConnectionAsync(endpoint.EndpointId, cancellationToken);
        }
    }

    public static class NearbyMigrationTransportSwitch
    {
        public static async Task<NearbyMigrationSwitchResult> SwitchAsync(
            NearbyTransport currentTransport,
            INearbyPeerAdapter adapter,
            SolarRoomSnapshot sourceSnapshot,
            SolarHostMigrationPlan plan,
            string serviceId,
            NearbyConnectionStrategy strategy = NearbyConnectionStrategy.Star,
            CancellationToken cancellationToken = default(CancellationToken),
            Action<NearbyTransport> configureTransport = null)
        {
            if (currentTransport == null) throw new ArgumentNullException(nameof(currentTransport));
            if (adapter == null) throw new ArgumentNullException(nameof(adapter));
            if (sourceSnapshot == null) throw new ArgumentNullException(nameof(sourceSnapshot));
            if (plan == null) throw new ArgumentNullException(nameof(plan));
            if (string.IsNullOrWhiteSpace(serviceId)) throw new ArgumentException("Nearby service ID is required.", nameof(serviceId));

            ValidateMigration(sourceSnapshot, plan, currentTransport.LocalPeerId);
            var localPlayer = FindPlayer(sourceSnapshot, currentTransport.LocalPeerId);
            var isSuccessor = plan.IsSuccessor(currentTransport.LocalPeerId);
            var role = isSuccessor ? NearbyConnectionRole.Advertiser : NearbyConnectionRole.Discoverer;
            var endpointName = isSuccessor
                ? NearbyRoomAdvertisementCodec.Encode(sourceSnapshot.RoomId, sourceSnapshot.RoomName, sourceSnapshot.CompatibilityKey)
                : localPlayer.DisplayName;

            await currentTransport.StopAsync(cancellationToken).ConfigureAwait(false);
            var next = new NearbyTransport(
                currentTransport.LocalPeerId,
                adapter,
                new NearbyTransportOptions(serviceId, endpointName, role, strategy, true));
            if (configureTransport != null) configureTransport(next);
            await next.StartAsync(cancellationToken).ConfigureAwait(false);
            return new NearbyMigrationSwitchResult(next, role, sourceSnapshot, plan);
        }

        private static void ValidateMigration(SolarRoomSnapshot sourceSnapshot, SolarHostMigrationPlan plan, string localPeerId)
        {
            if (!string.Equals(sourceSnapshot.RoomId, plan.RoomId, StringComparison.Ordinal))
                throw new InvalidOperationException("Nearby migration plan room does not match the source room snapshot.");
            if (!string.Equals(sourceSnapshot.GameSessionId, plan.SourceGameSessionId, StringComparison.Ordinal))
                throw new InvalidOperationException("Nearby migration plan source game session does not match the source room snapshot.");
            if (FindPlayer(sourceSnapshot, localPeerId) == null)
                throw new InvalidOperationException("Nearby transport local peer is not part of the migration roster.");
        }

        private static SolarRoomPlayer FindPlayer(SolarRoomSnapshot snapshot, string peerId)
        {
            if (string.IsNullOrWhiteSpace(peerId)) return null;
            foreach (var player in snapshot.Players)
                if (string.Equals(player.PeerId, peerId, StringComparison.Ordinal)) return player;
            return null;
        }
    }
}
