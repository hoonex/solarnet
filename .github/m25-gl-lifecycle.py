from pathlib import Path

path = Path('android-smoke/grid-duel-game/src/main/java/com/hoonex/solarnet/gridduel/PulseHockey3DView.java')
text = path.read_text()

field_old = '''    private AimListener aimListener;\n    private boolean aimEnabled;\n    private float downX;'''
field_new = '''    private AimListener aimListener;\n    private boolean aimEnabled;\n    private boolean rendererPaused;\n    private float downX;'''

methods_old = '''    public void clearAim() {\n        queueEvent(arenaRenderer::clearAim);\n    }\n\n    @Override\n    public boolean onTouchEvent(MotionEvent event) {'''
methods_new = '''    public void clearAim() {\n        queueEvent(arenaRenderer::clearAim);\n    }\n\n    @Override\n    protected void onDetachedFromWindow() {\n        onPause();\n        super.onDetachedFromWindow();\n    }\n\n    @Override\n    public void onPause() {\n        if (rendererPaused) return;\n        rendererPaused = true;\n        super.onPause();\n    }\n\n    @Override\n    public void onResume() {\n        if (!rendererPaused || !isAttachedToWindow()) return;\n        rendererPaused = false;\n        super.onResume();\n    }\n\n    @Override\n    public boolean onTouchEvent(MotionEvent event) {'''

for old, new in ((field_old, field_new), (methods_old, methods_new)):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'expected exactly one patch target, got {count}')
    text = text.replace(old, new)

path.write_text(text)
