using System;
using System.IO;

namespace SolarNet.Samples.GridDuel
{
    public static class GridDuelActionCodec
    {
        public const string MoveAction = "grid-duel/move";
        public const string AttackAction = "grid-duel/attack";

        public static byte[] EncodeMove(int x, int y)
        {
            using (var stream = new MemoryStream())
            using (var writer = new BinaryWriter(stream))
            {
                writer.Write(x);
                writer.Write(y);
                writer.Flush();
                return stream.ToArray();
            }
        }

        public static bool TryDecodeMove(byte[] payload, out int x, out int y)
        {
            x = 0;
            y = 0;
            if (payload == null || payload.Length != 8) return false;
            try
            {
                using (var stream = new MemoryStream(payload, false))
                using (var reader = new BinaryReader(stream))
                {
                    x = reader.ReadInt32();
                    y = reader.ReadInt32();
                    return stream.Position == stream.Length;
                }
            }
            catch
            {
                return false;
            }
        }
    }
}
