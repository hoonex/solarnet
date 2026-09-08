using System;
using System.Threading.Tasks;
using SolarNet.Room;

namespace SolarNet.Nearby
{
    public sealed class SolarNearbyRoomBridge
    {
        private readonly NearbyTransport _transport;
        private readonly SolarRoomSession _room;
        private bool _attached;

        public SolarNearbyRoomBridge(NearbyTransport transport, SolarRoomSession room)
        {
            _transport = transport ?? throw new ArgumentNullException(nameof(transport));
            _room = room ?? throw new ArgumentNullException(nameof(room));
        }

        public event Action<Exception> Faulted;

        public void Attach()
        {
            if (_attached) return;
            _attached = true;
            _transport.PeerConnected += OnPeerConnected;
            _transport.PeerDisconnected += OnPeerDisconnected;
        }

        public void Detach()
        {
            if (!_attached) return;
            _attached = false;
            _transport.PeerConnected -= OnPeerConnected;
            _transport.PeerDisconnected -= OnPeerDisconnected;
        }

        private async void OnPeerConnected(string peerId)
        {
            try
            {
                await _room.NotifyPeerConnectedAsync(peerId).ConfigureAwait(false);
            }
            catch (Exception ex)
            {
                RaiseFault(ex);
            }
        }

        private async void OnPeerDisconnected(string peerId)
        {
            try
            {
                await _room.NotifyPeerDisconnectedAsync(peerId).ConfigureAwait(false);
            }
            catch (Exception ex)
            {
                RaiseFault(ex);
            }
        }

        private void RaiseFault(Exception exception)
        {
            var handler = Faulted;
            if (handler != null) handler(exception);
        }
    }
}
