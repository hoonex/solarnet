using System;

namespace UnityEngine
{
    public class AndroidJavaObject : IDisposable
    {
        public AndroidJavaObject(string className, params object[] args) { }
        public virtual void Dispose() { }
        public void Call(string methodName, params object[] args) { }
        public T Call<T>(string methodName, params object[] args) { return default(T); }
        public T Get<T>(string fieldName) { return default(T); }
    }

    public sealed class AndroidJavaClass : AndroidJavaObject
    {
        public AndroidJavaClass(string className) : base(className) { }
        public T GetStatic<T>(string fieldName) { return default(T); }
    }

    public abstract class AndroidJavaProxy
    {
        protected AndroidJavaProxy(string javaInterface) { }
    }
}
