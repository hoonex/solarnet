using System;
using System.Threading;
using System.Threading.Tasks;
using SolarNet.Room;

namespace SolarNet.BluetoothClassic
{
    public sealed class BluetoothClassicMigrationSwitchResult
    {
        internal BluetoothClassicMigrationSwitchResult(
            BluetoothClassicTransport transport,
            BluetoothClassicRole role,
            SolarHostMigrationPlan plan,
            bool requiresSuccessorDeviceAddress)
        {
            Transport = transport ?? throw new ArgumentNullException(nameof(transport));
            Role = role;
            Plan = plan ?? throw new ArgumentNullException(nameof(plan));
            RequiresSuccessorDeviceAddress = requiresSuccessorDeviceAddress;
        }

        public BluetoothClassicTransport Transport { get; private set; }
        public BluetoothClassicRole Role { get; private set; }
        public SolarHostMigrationPlan Plan { get; private set; }
        public bool IsSuccessor { get { return Plan.IsSuccessor(Transport.LocalPeerId); } }
        public bool RequiresSuccessorDeviceAddress { get; private set; }
    }

    public static class BluetoothClassicMigrationTransportSwitch
    {
        public static async Task<BluetoothClassicMigrationSwitchResult> SwitchAsync(
            BluetoothClassicTransport currentTransport,
            IBluetoothClassicPeerAdapter adapter,
            SolarRoomSnapshot sourceSnapshot,
            SolarHostMigrationPlan plan,
            string successorDeviceAddress = null,
            string serviceName = "SolarNet",
            string serviceUuid = BluetoothClassicTransportOptions.DefaultServiceUuid,
            CancellationToken cancellationToken = default(CancellationToken),
            Action<BluetoothClassicTransport> configureTransport = null)
        {
            if (currentTransport == null) throw new ArgumentNullException(nameof(currentTransport));
            if (adapter == null) throw new ArgumentNullException(nameof(adapter));
            if (sourceSnapshot == null) throw new ArgumentNullException(nameof(sourceSnapshot));
            if (plan == null) throw new ArgumentNullException(nameof(plan));

            ValidateMigration(sourceSnapshot, plan, currentTransport.LocalPeerId);
            var isSuccessor = plan.IsSuccessor(currentTransport.LocalPeerId);
            var role = isSuccessor ? BluetoothClassicRole.Server : BluetoothClassicRole.Client;

            await currentTransport.StopAsync(cancellationToken).ConfigureAwait(false);
            var next = new BluetoothClassicTransport(
                currentTransport.LocalPeerId,
                adapter,
                new BluetoothClassicTransportOptions(role, serviceName, serviceUuid));
            if (configureTransport != null) configureTransport(next);
            await next.StartAsync(cancellationToken).ConfigureAwait(false);

            var requiresAddress = !isSuccessor && string.IsNullOrWhiteSpace(successorDeviceAddress);
            if (!isSuccessor && !requiresAddress)
                await next.ConnectAsync(successorDeviceAddress, cancellationToken).ConfigureAwait(false);

            return new BluetoothClassicMigrationSwitchResult(next, role, plan, requiresAddress);
        }

        private static void ValidateMigration(SolarRoomSnapshot sourceSnapshot, SolarHostMigrationPlan plan, string localPeerId)
        {
            if (!string.Equals(sourceSnapshot.RoomId, plan.RoomId, StringComparison.Ordinal))
                throw new InvalidOperationException("Bluetooth migration plan room does not match the source room snapshot.");
            if (!string.Equals(sourceSnapshot.GameSessionId, plan.SourceGameSessionId, StringComparison.Ordinal))
                throw new InvalidOperationException("Bluetooth migration plan source game session does not match the source room snapshot.");

            var foundLocal = false;
            foreach (var player in sourceSnapshot.Players)
                if (string.Equals(player.PeerId, localPeerId, StringComparison.Ordinal)) { foundLocal = true; break; }
            if (!foundLocal)
                throw new InvalidOperationException("Bluetooth transport local peer is not part of the migration roster.");
        }
    }
}
