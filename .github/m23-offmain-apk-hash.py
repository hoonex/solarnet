from pathlib import Path

path = Path('android-smoke/probe-app/src/main/java/com/hoonex/solarnet/probe/MainActivity.java')
text = path.read_text()

replacements = [
    ('    private ProbeEvidenceSnapshot bluetoothStartEvidence;\n    private ProbeEvidenceSnapshot nearbyStartEvidence;\n',
     '    private ProbeEvidenceSnapshot bluetoothStartEvidence;\n    private ProbeEvidenceSnapshot nearbyStartEvidence;\n    private volatile String installedApkSha256;\n'),
    ('        setContentView(buildUi());\n\n        bluetoothBridge = new SolarBluetoothClassicBridge(this, new BluetoothCallback());',
     '        setContentView(buildUi());\n\n        new Thread(() -> {\n            installedApkSha256 = ProbeDeviceEvidence.computeInstalledApkSha256(getApplicationContext());\n            log("Evidence APK SHA-256 ready: " + installedApkSha256);\n        }, "solarnet-probe-apk-hash").start();\n\n        bluetoothBridge = new SolarBluetoothClassicBridge(this, new BluetoothCallback());'),
    ('ProbeDeviceEvidence.capture(MainActivity.this, BuildConfig.SOURCE_SHA)',
     'ProbeDeviceEvidence.capture(MainActivity.this, BuildConfig.SOURCE_SHA, installedApkSha256)'),
    ('        ProbeSoakController controller = controller(activeMode);\n        try {\n            setStartEvidence(activeMode, ProbeDeviceEvidence.capture(this, BuildConfig.SOURCE_SHA));',
     '        if (installedApkSha256 == null) {\n            log("Evidence APK SHA-256 is still preparing; retry start shortly.");\n            return;\n        }\n        ProbeSoakController controller = controller(activeMode);\n        try {\n            setStartEvidence(activeMode, ProbeDeviceEvidence.capture(this, BuildConfig.SOURCE_SHA, installedApkSha256));')
]

for old, new in replacements:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'expected exactly one match, got {count}: {old[:100]!r}')
    text = text.replace(old, new)

path.write_text(text)
