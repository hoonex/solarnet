using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;

namespace SolarNet.BluetoothClassic
{
    public sealed class BluetoothClassicDevice
    {
        public BluetoothClassicDevice(string address, string name)
        {
            if (string.IsNullOrWhiteSpace(address)) throw new ArgumentException("Bluetooth device address is required.", nameof(address));
            Address = address;
            Name = string.IsNullOrWhiteSpace(name) ? address : name;
        }

        public string Address { get; private set; }
        public string Name { get; private set; }
    }

    public interface IBluetoothClassicPeerAdapter
    {
        event Action<string, string, string> Connected;
        event Action<string> Disconnected;
        event Action<string, byte[]> BytesReceived;
        event Action<Exception> Faulted;

        IReadOnlyList<BluetoothClassicDevice> GetBondedDevices();
        Task StartServerAsync(string serviceName, string serviceUuid, CancellationToken cancellationToken = default(CancellationToken));
        Task ConnectAsync(string deviceAddress, string serviceUuid, CancellationToken cancellationToken = default(CancellationToken));
        Task SendBytesAsync(string connectionId, byte[] payload, CancellationToken cancellationToken = default(CancellationToken));
        Task BroadcastBytesAsync(byte[] payload, CancellationToken cancellationToken = default(CancellationToken));
        Task DisconnectAsync(string connectionId, CancellationToken cancellationToken = default(CancellationToken));
        Task StopAllAsync(CancellationToken cancellationToken = default(CancellationToken));
    }

    public enum BluetoothClassicRole
    {
        Server = 0,
        Client = 1
    }

    public sealed class BluetoothClassicTransportOptions
    {
        public const string DefaultServiceUuid = "7d8f3a10-7b8a-4f66-9c38-63f04cb61b0e";

        public BluetoothClassicTransportOptions(BluetoothClassicRole role, string serviceName = "SolarNet", string serviceUuid = DefaultServiceUuid)
        {
            if (string.IsNullOrWhiteSpace(serviceName)) throw new ArgumentException("Service name is required.", nameof(serviceName));
            Guid parsed;
            if (!Guid.TryParse(serviceUuid, out parsed)) throw new ArgumentException("A valid RFCOMM service UUID is required.", nameof(serviceUuid));
            Role = role;
            ServiceName = serviceName;
            ServiceUuid = parsed.ToString("D");
        }

        public BluetoothClassicRole Role { get; private set; }
        public string ServiceName { get; private set; }
        public string ServiceUuid { get; private set; }
    }
}
