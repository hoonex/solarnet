using System;
using System.Security.Cryptography;

namespace SolarNet.State
{
    public static class SolarStateDigest
    {
        private static readonly char[] Hex = "0123456789abcdef".ToCharArray();

        public static string Compute(byte[] snapshot)
        {
            if (snapshot == null) throw new ArgumentNullException(nameof(snapshot));
            byte[] hash;
            using (var sha = SHA256.Create())
                hash = sha.ComputeHash(snapshot);

            var chars = new char[hash.Length * 2];
            for (var i = 0; i < hash.Length; i++)
            {
                chars[i * 2] = Hex[hash[i] >> 4];
                chars[i * 2 + 1] = Hex[hash[i] & 0x0f];
            }
            return new string(chars);
        }
    }
}
