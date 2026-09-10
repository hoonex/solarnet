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
}
for old, new in replacements.items():
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'expected one match, got {count}: {old}')
    text = text.replace(old, new)
path.write_text(text)
