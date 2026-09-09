from pathlib import Path

path = Path('android-smoke/grid-duel-game/src/main/java/com/hoonex/solarnet/gridduel/MainActivity.java')
text = path.read_text()

replacements = [
    (
        '        if (networkHost) sendAuthoritativeState();\n        else refreshGameUi("HOST의 게임 상태를 기다리는 중…");',
        '        if (networkHost) sendAuthoritativeState();\n        else {\n            refreshGameUi("HOST의 게임 상태를 기다리는 중…");\n            sendNetworkPayload(GameProtocol.encodeReady());\n        }'
    ),
    (
        '            GameProtocol.Frame frame = GameProtocol.decode(payload);\n            if (frame.type == GameProtocol.Frame.Type.STATE) {',
        '            GameProtocol.Frame frame = GameProtocol.decode(payload);\n            if (frame.type == GameProtocol.Frame.Type.READY) {\n                if (networkHost) sendAuthoritativeState();\n                return;\n            }\n            if (frame.type == GameProtocol.Frame.Type.STATE) {'
    )
]

for old, new in replacements:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'expected one match, got {count}: {old[:100]!r}')
    text = text.replace(old, new)

path.write_text(text)
