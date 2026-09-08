using System;
using System.Threading;
using System.Threading.Tasks;

namespace SolarNet.Nearby
{
    public enum NearbyConnectionRole
    {
        Advertiser = 0,
        Discoverer = 1
    }

    public enum NearbyConnectionStrategy
    {
        Star = 0,
        Cluster = 1,
        PointToPoint = 2
    }

    public sealed class NearbyEndpoint
    {
        public NearbyEndpoint(string endpointId, string endpointName)
        {
            if (string.IsNullOrWhiteSpace(endpointId)) throw new ArgumentException("Endpoint ID is required.", nameof(endpointId));
            EndpointId = endpointId;
            EndpointName = endpointName ?? string.Empty;
        }

        public string EndpointId { get; private set; }
        public string EndpointName { get; private set; }
    }

    public sealed class NearbyVerificationRequest
    {
        public NearbyVerificationRequest(string endpointId, string endpointName, string authenticationDigits, bool isIncomingConnection)
        {
            if (string.IsNullOrWhiteSpace(endpointId)) throw new ArgumentException("Endpoint ID is required.", nameof(endpointId));
            EndpointId = endpointId;
            EndpointName = endpointName ?? string.Empty;
            AuthenticationDigits = authenticationDigits ?? string.Empty;
            IsIncomingConnection = isIncomingConnection;
        }

        public string EndpointId { get; private set; }
        public string EndpointName { get; private set; }
        public string AuthenticationDigits { get; private set; }
        public bool IsIncomingConnection { get; private set; }
    }

    public interface INearbyPeerAdapter
    {
        event Action<NearbyEndpoint> EndpointFound;
        event Action<string> EndpointLost;
        event Action<NearbyVerificationRequest> VerificationRequired;
        event Action<string> Connected;
        event Action<string> Disconnected;
        event Action<string, byte[]> BytesReceived;
        event Action<Exception> Faulted;

        Task StartAdvertisingAsync(string serviceId, string endpointName, NearbyConnectionStrategy strategy, CancellationToken cancellationToken = default(CancellationToken));
        Task StartDiscoveryAsync(string serviceId, string endpointName, NearbyConnectionStrategy strategy, CancellationToken cancellationToken = default(CancellationToken));
        Task StopAdvertisingAsync(CancellationToken cancellationToken = default(CancellationToken));
        Task StopDiscoveryAsync(CancellationToken cancellationToken = default(CancellationToken));
        Task RequestConnectionAsync(string endpointId, string endpointName, CancellationToken cancellationToken = default(CancellationToken));
        Task AcceptConnectionAsync(string endpointId, CancellationToken cancellationToken = default(CancellationToken));
        Task RejectConnectionAsync(string endpointId, CancellationToken cancellationToken = default(CancellationToken));
        Task DisconnectAsync(string endpointId, CancellationToken cancellationToken = default(CancellationToken));
        Task SendBytesAsync(string endpointId, byte[] payload, CancellationToken cancellationToken = default(CancellationToken));
        Task BroadcastBytesAsync(byte[] payload, CancellationToken cancellationToken = default(CancellationToken));
        Task StopAllAsync(CancellationToken cancellationToken = default(CancellationToken));
    }

    public sealed class NearbyTransportOptions
    {
        public NearbyTransportOptions(
            string serviceId,
            string endpointName,
            NearbyConnectionRole role,
            NearbyConnectionStrategy strategy = NearbyConnectionStrategy.Star,
            bool stopDiscoveryAfterFirstConnection = true)
        {
            if (string.IsNullOrWhiteSpace(serviceId)) throw new ArgumentException("Service ID is required.", nameof(serviceId));
            if (string.IsNullOrWhiteSpace(endpointName)) throw new ArgumentException("Endpoint name is required.", nameof(endpointName));

            ServiceId = serviceId;
            EndpointName = endpointName;
            Role = role;
            Strategy = strategy;
            StopDiscoveryAfterFirstConnection = stopDiscoveryAfterFirstConnection;
        }

        public string ServiceId { get; private set; }
        public string EndpointName { get; private set; }
        public NearbyConnectionRole Role { get; private set; }
        public NearbyConnectionStrategy Strategy { get; private set; }
        public bool StopDiscoveryAfterFirstConnection { get; private set; }
    }
}
