from pathlib import Path

path = Path('android-smoke/grid-duel-game/src/main/java/com/hoonex/solarnet/gridduel/RelaySiegeActivity.java')
text = path.read_text()

replacements = {
    'import android.app.Activity;\n':
        'import android.app.Activity;\nimport android.content.Intent;\n',
    '        root.addView(system, matchWrap(dp(10)));\n\n        TextView choose = text("STARTER DECKS", 12, MUTED, true);':
        '''        root.addView(system, matchWrap(dp(10)));

        LinearLayout learning = row();
        Button trainerButton = compactButton("전술 트레이너", GREEN, BG,
                v -> startActivity(new Intent(this, RelaySiegeTacticalTrainerActivity.class)));
        Button labButton = compactButton("덱 연구소", PANEL_2, TEXT,
                v -> startActivity(new Intent(this, RelaySiegeDeckLabActivity.class)));
        learning.addView(trainerButton, new LinearLayout.LayoutParams(0, dp(48), 1f));
        LinearLayout.LayoutParams labParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        labParams.leftMargin = dp(8);
        learning.addView(labButton, labParams);
        root.addView(learning, matchWrap(dp(10)));

        TextView learningNote = text(
                "전술 트레이너는 같은 전장을 선택지만 바꿔 실제 엔진으로 비교하고, 덱 연구소는 8장 순서·순환·상성 커버리지를 분석합니다.",
                11.5f, MUTED, false);
        learningNote.setPadding(dp(3), dp(7), dp(3), dp(4));
        root.addView(learningNote);

        TextView choose = text("STARTER DECKS", 12, MUTED, true);''',
}

for old, new in replacements.items():
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'expected exactly one match, got {count}: {old[:100]!r}')
    text = text.replace(old, new)

path.write_text(text)
