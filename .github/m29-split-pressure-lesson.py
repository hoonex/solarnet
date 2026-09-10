from pathlib import Path

path = Path('android-smoke/grid-duel-game/src/main/java/com/hoonex/solarnet/gridduel/RelaySiegeTacticalTrainer.java')
text = path.read_text()

lesson_anchor = '''                    waitChoice("hold_flux", "아무것도 더 쓰지 않기", "생존 유닛만 보내고 다음 수를 위해 Flux를 저장한다."))
    ));'''
lesson_replacement = '''                    waitChoice("hold_flux", "아무것도 더 쓰지 않기", "생존 유닛만 보내고 다음 수를 위해 Flux를 저장한다.")),
            new Lesson(
                    "split_lane_punish",
                    "생존 역공이 있어도 비어 있는 반대 라인은 계산하라",
                    "SPLIT PRESSURE / OPPORTUNITY COST",
                    "오른쪽에는 살아남은 SUN Pulse Guard와 Arc Slinger가 이미 전진 중입니다. 하지만 MOON이 오른쪽에 자원을 몰아 왼쪽 라인은 완전히 비어 있습니다.",
                    "runner_split",
                    330,
                    choice("overclock_right", "오른쪽 생존 유닛에 Overclock", "overclock", RelaySiegeGame.Lane.RIGHT, 57_000,
                            "이미 전진 중인 유닛에 추가 자원을 겹쳐 한 라인의 압박을 극대화한다."),
                    choice("runner_split", "빈 왼쪽 라인에 Flux Runner", "runner", RelaySiegeGame.Lane.LEFT, 38_000,
                            "상대의 오른쪽 과투자를 이용해 2 Flux로 별도 Relay 위협을 만든다."),
                    waitChoice("split_hold", "두 라인 모두 추가 투자 없이 관망", "현재 생존 유닛만 보내고 다음 수를 위해 Flux를 보존한다."))
    ));'''

scenario_anchor = '''            game.scenarioSpawn("coil_turret", RelaySiegeGame.Player.MOON, RelaySiegeGame.Lane.LEFT, 52_000);
            return game;
        }
        throw new IllegalArgumentException("Unknown tactical scenario: " + lessonId);'''
scenario_replacement = '''            game.scenarioSpawn("coil_turret", RelaySiegeGame.Player.MOON, RelaySiegeGame.Lane.LEFT, 52_000);
            return game;
        }
        if ("split_lane_punish".equals(lessonId)) {
            RelaySiegeCards.Deck lessonDeck = RelaySiegeCards.customDeck(
                    "trainer_split",
                    "Trainer Split",
                    Arrays.asList("overclock", "runner", "siege_walker", "relay_beacon",
                            "arc_slinger", "pulse_guard", "duelist", "gravity_well"));
            RelaySiegeGame game = new RelaySiegeGame(
                    lessonDeck, RelaySiegeCards.deck("counterforge"), 28004L);
            game.scenarioSetFlux(RelaySiegeGame.Player.SUN, 10_000);
            game.scenarioSpawn("pulse_guard", RelaySiegeGame.Player.SUN, RelaySiegeGame.Lane.RIGHT, 55_000);
            game.scenarioSpawn("arc_slinger", RelaySiegeGame.Player.SUN, RelaySiegeGame.Lane.RIGHT, 51_000);
            return game;
        }
        throw new IllegalArgumentException("Unknown tactical scenario: " + lessonId);'''

score_anchor = '''        if ("survivor_conversion".equals(lessonId)) {
            return enemyRelayDamage * 9 + friendlyHp / 5 - enemyHp - spentMilli / 24;
        }'''
score_replacement = '''        if ("survivor_conversion".equals(lessonId) || "split_lane_punish".equals(lessonId)) {
            return enemyRelayDamage * 9 + friendlyHp / 5 - enemyHp - spentMilli / 24;
        }'''

for old, new in ((lesson_anchor, lesson_replacement), (scenario_anchor, scenario_replacement), (score_anchor, score_replacement)):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'expected exactly one anchor, got {count}: {old[:100]!r}')
    text = text.replace(old, new)

path.write_text(text)
