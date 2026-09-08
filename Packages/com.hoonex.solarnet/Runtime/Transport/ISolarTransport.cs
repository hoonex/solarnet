using System;
using System.Threading;
using System.Threading.Tasks;

namespace SolarNet.Transport
{
    public sealed class SolarFrame
    {
        public SolarFrame(string remotePeerId, byte[] data)
        {
            if (string.IsNullOrWhiteSpace(remotePeerId)) throw new ArgumentException("Remote peer ID is required.", nameof(remotePeerId));
            RemotePeerId = remotePeerId;
            Data = data ?? Array.Empty<byte>();
        }

        public string RemotePeerId { get; private set; }
        public byte[] Data { get; private set; }
    }

    public interface ISolarTransport
    {
        string LocalPeerId { get; }
        event Func<SolarFrame, Task> FrameReceived;

        Task StartAsync(CancellationToken cancellationToken = default(CancellationToken));
        Task StopAsync(CancellationToken cancellationToken = default(CancellationToken));
        Task SendAsync(string remotePeerId, byte[] frame, CancellationToken cancellationToken = default(CancellationToken));
        Task BroadcastAsync(byte[] frame, CancellationToken cancellationToken = default(CancellationToken));
    }
}
