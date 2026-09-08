using System;

namespace UnityEngine
{
    public class MonoBehaviour { }

    public static class GUI
    {
        public static bool enabled { get; set; }
        public static GUISkin skin { get; } = new GUISkin();
    }

    public sealed class GUISkin
    {
        public object box { get; } = new object();
    }

    public sealed class GUILayoutOption { }

    public static class GUILayout
    {
        public static void BeginVertical(params object[] options) { }
        public static void EndVertical() { }
        public static void BeginHorizontal(params GUILayoutOption[] options) { }
        public static void EndHorizontal() { }
        public static void Label(string text, params GUILayoutOption[] options) { }
        public static void Space(float pixels) { }
        public static bool Button(string text, params GUILayoutOption[] options) { return false; }
        public static GUILayoutOption Width(float value) { return new GUILayoutOption(); }
        public static GUILayoutOption Height(float value) { return new GUILayoutOption(); }
    }
}
