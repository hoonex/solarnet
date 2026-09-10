from pathlib import Path

path = Path('android-smoke/grid-duel-game/src/main/java/com/hoonex/solarnet/gridduel/RelaySiegeTacticalTrainer.java')
text = path.read_text()
replacements = {
    'choice("beacon_pull", "Relay Beacon을 안쪽에 배치", "relay_beacon", RelaySiegeGame.Lane.RIGHT, 33_000,':
        'choice("beacon_pull", "Relay Beacon을 뒤쪽에 배치해 Walker를 되돌리기", "relay_beacon", RelaySiegeGame.Lane.RIGHT, 27_000,',
    'choice("duelist_body", "Duelist로 앞을 막기", "duelist", RelaySiegeGame.Lane.RIGHT, 32_000,':
        'choice("duelist_body", "Duelist로 급히 때려잡기", "duelist", RelaySiegeGame.Lane.RIGHT, 24_000,',
    'game.scenarioSpawn("siege_walker", RelaySiegeGame.Player.MOON, RelaySiegeGame.Lane.RIGHT, 50_000);':
        'game.scenarioSpawn("siege_walker", RelaySiegeGame.Player.MOON, RelaySiegeGame.Lane.RIGHT, 21_000);',
    '"방어를 끝낸 SUN Pulse Guard와 Arc Slinger가 오른쪽 중앙을 넘어 살아 있습니다. 지금 추가 Flux를 어디에 쓰느냐가 역공 크기를 결정합니다.",':
        '"방어를 끝낸 SUN Pulse Guard와 Arc Slinger가 오른쪽 중앙을 넘어 살아 있습니다. 반대쪽은 MOON Coil Turret이 이미 지키고 있어 새 Runner만 던지면 효율이 낮습니다.",',
    'game.scenarioSpawn("arc_slinger", RelaySiegeGame.Player.SUN, RelaySiegeGame.Lane.RIGHT, 51_000);\n            return game;':
        'game.scenarioSpawn("arc_slinger", RelaySiegeGame.Player.SUN, RelaySiegeGame.Lane.RIGHT, 51_000);\n            game.scenarioSpawn("coil_turret", RelaySiegeGame.Player.MOON, RelaySiegeGame.Lane.LEFT, 52_000);\n            return game;',
}
for old, new in replacements.items():
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'expected one match, got {count}: {old}')
    text = text.replace(old, new)
path.write_text(text)
