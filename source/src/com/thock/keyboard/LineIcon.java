package com.thock.keyboard;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Minimal stroke icons on a 24x24 grid (Lucide-style: 2px stroke, round caps). */
public final class LineIcon extends Drawable {
    public static final int SOUND = 0, HAPTIC = 1, KEYBOARD = 2, THEME = 3, CLIPBOARD = 4, BACK = 5, CHEVRON = 6, PLAY = 7, BKSP = 8, ENTER = 9, SPACEBAR = 10, SMILE = 11, SHIFT = 12, SHIFT_LOCK = 13, PENCIL = 14, MESSAGE = 15, TRI_LEFT = 16, TRI_RIGHT = 17, SWAP = 18, TRI_UP = 19, TRI_DOWN = 20, GEAR = 21, MIC = 22, SEARCH = 23, CLOCK = 24, CLOSE = 25;

    private final int type;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public LineIcon(int type, int color) {
        this.type = type;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(2f);
        p.setColor(color);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(color);
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float s = Math.min(b.width(), b.height()) / 24f;
        c.save();
        c.translate(b.left + (b.width() - 24 * s) / 2f, b.top + (b.height() - 24 * s) / 2f);
        c.scale(s, s);
        path.reset();
        switch (type) {
            case SOUND:
                path.moveTo(11, 5); path.lineTo(6, 9); path.lineTo(2, 9); path.lineTo(2, 15);
                path.lineTo(6, 15); path.lineTo(11, 19); path.close();
                c.drawPath(path, p);
                c.drawArc(new RectF(10.5f, 7f, 18.5f, 17f), -50, 100, false, p);
                c.drawArc(new RectF(9f, 3f, 23f, 21f), -50, 100, false, p);
                break;
            case HAPTIC:
                c.drawRoundRect(new RectF(8, 4, 16, 20), 2, 2, p);
                c.drawLine(4, 9, 4, 15, p);
                c.drawLine(20, 9, 20, 15, p);
                c.drawLine(11, 17, 13, 17, p);
                break;
            case KEYBOARD:
                c.drawRoundRect(new RectF(2, 5, 22, 19), 2.5f, 2.5f, p);
                for (int i = 0; i < 5; i++) c.drawPoint(6 + i * 3, 9.5f, p);
                for (int i = 0; i < 4; i++) c.drawPoint(7.5f + i * 3, 12.5f, p);
                c.drawLine(8, 15.5f, 16, 15.5f, p);
                break;
            case THEME:
                c.drawCircle(12, 12, 9, p);
                path.addArc(new RectF(3, 3, 21, 21), -90, 180);
                path.close();
                c.drawPath(path, fill);
                break;
            case CLIPBOARD:
                c.drawRoundRect(new RectF(8, 2, 16, 6), 1, 1, p);
                path.moveTo(16, 4); path.lineTo(18, 4);
                path.quadTo(20, 4, 20, 6); path.lineTo(20, 20); path.quadTo(20, 22, 18, 22);
                path.lineTo(6, 22); path.quadTo(4, 22, 4, 20); path.lineTo(4, 6); path.quadTo(4, 4, 6, 4); path.lineTo(8, 4);
                c.drawPath(path, p);
                c.drawLine(8, 12, 16, 12, p);
                c.drawLine(8, 16, 13, 16, p);
                break;
            case BACK:
                path.moveTo(15, 18); path.lineTo(9, 12); path.lineTo(15, 6);
                c.drawPath(path, p);
                break;
            case CHEVRON:
                path.moveTo(9, 18); path.lineTo(15, 12); path.lineTo(9, 6);
                c.drawPath(path, p);
                break;
            case BKSP:
                path.moveTo(10, 5); path.lineTo(19, 5); path.quadTo(22, 5, 22, 8); path.lineTo(22, 16);
                path.quadTo(22, 19, 19, 19); path.lineTo(10, 19); path.lineTo(3, 12); path.close();
                c.drawPath(path, p);
                c.drawLine(12.5f, 9.5f, 17.5f, 14.5f, p);
                c.drawLine(17.5f, 9.5f, 12.5f, 14.5f, p);
                break;
            case ENTER:
                path.moveTo(20, 5); path.lineTo(20, 12); path.quadTo(20, 15, 17, 15); path.lineTo(5, 15);
                c.drawPath(path, p);
                path.reset();
                path.moveTo(9, 11); path.lineTo(5, 15); path.lineTo(9, 19);
                c.drawPath(path, p);
                break;
            case SPACEBAR:
                path.moveTo(3, 10); path.lineTo(3, 14); path.lineTo(21, 14); path.lineTo(21, 10);
                c.drawPath(path, p);
                break;
            case SMILE:
                c.drawCircle(12, 12, 9.5f, p);
                c.drawArc(new RectF(7.5f, 8.5f, 16.5f, 16.5f), 20, 140, false, p);
                c.drawCircle(9, 10, 0.6f, fill);
                c.drawCircle(15, 10, 0.6f, fill);
                c.drawPoint(9, 10, p);
                c.drawPoint(15, 10, p);
                break;
            case SHIFT:
            case SHIFT_LOCK:
                path.moveTo(12, 3.5f); path.lineTo(20.5f, 12); path.lineTo(16, 12); path.lineTo(16, 18);
                path.lineTo(8, 18); path.lineTo(8, 12); path.lineTo(3.5f, 12); path.close();
                if (type == SHIFT_LOCK) { c.drawPath(path, fill); c.drawLine(8, 21.5f, 16, 21.5f, p); }
                c.drawPath(path, p);
                break;
            case PENCIL:
                path.moveTo(16.5f, 3.5f); path.lineTo(20.5f, 7.5f); path.lineTo(8f, 20f); path.lineTo(3f, 21f);
                path.lineTo(4f, 16f); path.close();
                c.drawPath(path, p);
                c.drawLine(14.5f, 5.5f, 18.5f, 9.5f, p);
                break;
            case MESSAGE:
                path.moveTo(21, 15); path.quadTo(21, 17, 19, 17); path.lineTo(7, 17); path.lineTo(3, 21);
                path.lineTo(3, 5); path.quadTo(3, 3, 5, 3); path.lineTo(19, 3); path.quadTo(21, 3, 21, 5); path.close();
                c.drawPath(path, p);
                c.drawLine(7.5f, 8, 16.5f, 8, p);
                c.drawLine(7.5f, 12, 13, 12, p);
                break;
            case TRI_LEFT:
                path.moveTo(17, 5); path.lineTo(17, 19); path.lineTo(6, 12); path.close();
                c.drawPath(path, fill); c.drawPath(path, p);
                break;
            case TRI_RIGHT:
                path.moveTo(7, 5); path.lineTo(7, 19); path.lineTo(18, 12); path.close();
                c.drawPath(path, fill); c.drawPath(path, p);
                break;
            case SWAP:
                c.drawLine(4, 8, 19, 8, p);
                path.moveTo(15, 4); path.lineTo(19, 8); path.lineTo(15, 12);
                c.drawPath(path, p);
                path.reset();
                c.drawLine(20, 16, 5, 16, p);
                path.moveTo(9, 12); path.lineTo(5, 16); path.lineTo(9, 20);
                c.drawPath(path, p);
                break;
            case TRI_UP:
                path.moveTo(5, 17); path.lineTo(19, 17); path.lineTo(12, 6); path.close();
                c.drawPath(path, fill); c.drawPath(path, p);
                break;
            case TRI_DOWN:
                path.moveTo(5, 7); path.lineTo(19, 7); path.lineTo(12, 18); path.close();
                c.drawPath(path, fill); c.drawPath(path, p);
                break;
            case GEAR:
                // outline cog (web icon style): 8 flat teeth + hole
                for (int i = 0; i < 8; i++) {
                    double m = Math.PI / 4 * i - Math.PI / 2;
                    double[][] pts = {{7.2, m - 0.30}, {9.6, m - 0.19}, {9.6, m + 0.19}, {7.2, m + 0.30}};
                    for (int k = 0; k < 4; k++) {
                        float x = (float) (12 + pts[k][0] * Math.cos(pts[k][1])), y = (float) (12 + pts[k][0] * Math.sin(pts[k][1]));
                        if (i == 0 && k == 0) path.moveTo(x, y); else path.lineTo(x, y);
                    }
                }
                path.close();
                c.drawPath(path, p);
                c.drawCircle(12, 12, 3, p);
                break;
            case MIC:
                c.drawRoundRect(new RectF(9, 2, 15, 14), 3, 3, p);
                c.drawArc(new RectF(5, 4, 19, 18), 0, 180, false, p);
                c.drawLine(12, 18, 12, 22, p);
                c.drawLine(8.5f, 22, 15.5f, 22, p);
                break;
            case SEARCH:
                c.drawCircle(11, 11, 7, p);
                c.drawLine(16, 16, 21, 21, p);
                break;
            case CLOCK:
                c.drawCircle(12, 12, 9, p);
                c.drawLine(12, 7, 12, 12, p);
                c.drawLine(12, 12, 15.5f, 14, p);
                break;
            case CLOSE:
                c.drawLine(6, 6, 18, 18, p);
                c.drawLine(18, 6, 6, 18, p);
                break;
            case PLAY:
                path.moveTo(8, 5); path.lineTo(19, 12); path.lineTo(8, 19); path.close();
                c.drawPath(path, fill);
                c.drawPath(path, p);
                break;
        }
        c.restore();
    }

    @Override public void setAlpha(int a) { p.setAlpha(a); fill.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); fill.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
