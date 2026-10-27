package com.appgarage.dash;

import android.content.Context;
import android.graphics.Canvas;
import android.util.Log;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

/**
 * Vehicle gauge dashboard for the 800x480 InTouch head-unit screen. Reads RAW
 * VS_ID_* sensor values (set via setValue(type, raw)) and renders calibrated gauges.
 *
 * Calibration (locked from on-car data + the DrivingPerformance reference app):
 *   RPM(13) = rpm, direct.        COOLANT(14) / OILTEMP(15) = degC, direct.
 *   TPMS(36-39) = psi, direct.    TORQUE(12) ~ Nm.
 *   POWER(32) = rpm*Nm  -> kW  = raw * 1.047e-4.
 *   OILPRESS(16) = MPa  -> psi = raw * 145  (confirmed ~22 psi warm idle).
 *   SPEED(17) = km/h    -> mph = raw * 0.621.
 *   GEAR(22) enum: P/R/N/D = 1/2/3/4, manual M1..M7 = 16..22 (confirmed on-car).
 *   DTE(18): raw assumed km -> mi x0.621 (unit unverified on-car).
 *   RPM and SPEED arcs sit side by side. On dead-RPM ECUs (e.g. VQ37: sensor 13
 *   registered but never populated), engine speed is re-derived as
 *   raw32(rpm*Nm)/raw12(Nm); the RPM arc shows -- when no derivation is available.
 *   G lat(20)/long(21): raw, ~1g full-scale assumed (not yet calibrated).
 *
 * v1.1: tap anywhere to flip the whole dash between imperial and metric (persisted);
 * oil pressure hides itself when it reads negative (a wrong-engine signal, e.g. VQ);
 * a loud banner is drawn when the values are the built-in demo, not live CAN.
 */
public class GaugeView extends View {

    // sensor types
    private static final int TORQUE=12, RPM=13, COOLANT=14, OILT=15, OILP=16, SPEED=17, DTE=18,
            GLAT=20, GLONG=21, GEAR=22, THROTTLE=23, POWER=32,
            TP_FR=36, TP_FL=37, TP_RR=38, TP_RL=39;

    // scaling constants (calibrated on-car). Oil-pressure raw is MPa: x145 -> psi, or x10 -> bar.
    public static float OILP_RAW_TO_PSI = 145.0377f;   // MPa -> psi (~22 psi warm idle)
    public static float POWER_RAW_TO_KW = 0.0001047f;  // raw = rpm*Nm -> kW
    public static float SPEED_RAW_TO_MPH = 0.621371f;  // km/h -> mph
    // unit conversions for the imperial/metric toggle
    static final float PSI_TO_BAR  = 0.0689476f;
    static final float KW_TO_HP    = 1.34102f;
    static final float NM_TO_LBFT  = 0.737562f;

    private static final int N = 64;
    private final float[] v = new float[N];
    private final boolean[] have = new boolean[N];
    private final long[] lastMs = new long[N];    // last update time per type (diagnostics)
    private final String[] names = new String[N]; // sensor names from the platform
    private boolean diag = false;                 // long-press -> diagnostics overlay
    private boolean rpmSawLife;                   // RPM ever reported a nonzero value
    private float derivedRpm;                     // raw32(rpm*Nm) / raw12(Nm) -> rpm
    private long derivedMs;                       // 0 = never derived; stale after 3s
    private String status = "";
    private boolean demo = false;                 // true => values are seedDemo(), not live CAN
    private boolean imperial = true;              // tap to flip; default imperial (persisted)
    private static final String PREFS = "agdash", KEY_IMP = "imperial";
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    // colors
    private static final int BG=0xFF0B0E12, PANEL=0xFF151B22, LABEL=0xFF7FB0FF,
            VAL=0xFFFFFFFF, DIM=0xFF6A7684, OK=0xFF37E07A, WARN=0xFFFFB020, DANGER=0xFFFF4040,
            ARC_BG=0xFF243040, ARC_FG=0xFF39C0FF;

    public GaugeView(Context c) {
        super(c);
        setBackgroundColor(BG);
        p.setTypeface(Typeface.MONOSPACE);
        setClickable(true);
        try { imperial = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_IMP, true); }
        catch (Throwable ignored) {}
    }

    /** tap -> flip units (or exit diag); hold 3s -> diagnostics overlay; drag scrolls diag. */
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        // timing measured here: the default View.onTouchEvent that posts the
        // framework long-press check is overridden, so OnLongClickListener never fires
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                lastY = e.getY(); moved = 0;
                break;
            case MotionEvent.ACTION_MOVE:
                if (diag) {
                    float dy = lastY - e.getY();
                    lastY = e.getY(); moved += Math.abs(dy);
                    scroll += dy;
                    invalidate();
                }
                break;
            case MotionEvent.ACTION_UP:
                boolean held = (e.getEventTime() - e.getDownTime()) >= 3000;
                if (diag) {
                    if (moved < 20) diag = false;              // tap exits; a drag just scrolled
                } else if (held) {
                    diag = true; scroll = 0;
                } else {
                    imperial = !imperial;
                    try { getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit().putBoolean(KEY_IMP, imperial).commit(); } catch (Throwable ignored) {}
                }
                invalidate();
                break;
        }
        return true;
    }

    private float scroll, lastY, moved;           // diag overlay: drag scroll state

    public void setName(int t, String n) { if (t>=0 && t<N) names[t] = n; }
    public void setValue(int t, float val) {
        if (t>=0 && t<N) {
            v[t]=val; have[t]=true; lastMs[t]=SystemClock.elapsedRealtime();
            if (t==RPM && val>0) rpmSawLife = true;
            // dead-RPM cars: re-derive engine speed from power(rpm*Nm)/torque(Nm). Both
            // negative (fuel-cut coast) still yields +rpm, so abs(torque) guards; the
            // 200..9000 band rejects div-by-small-torque garbage.
            if ((t==TORQUE || t==POWER) && have[TORQUE] && have[POWER]
                    && Math.abs(v[TORQUE]) >= 20) {
                float d = v[POWER] / v[TORQUE];
                if (d > 200 && d < 9000) { derivedRpm = d; derivedMs = lastMs[t]; }
            }
            if (diag) Log.i("agdash", "type " + t + (names[t]!=null? " ("+names[t]+")" : "")
                    + " = " + val);
        }
    }
    public void setStatus(String s) { status = s; }
    public void setDemo(boolean d) { demo = d; }
    private float g(int t) { return have[t] ? v[t] : 0f; }
    private boolean h(int t) { return have[t]; }

    /** RPM sensor exists but has never read nonzero — dead on this ECU's CAN map (e.g. VQ37). */
    private boolean rpmLive() { return h(RPM) && rpmSawLife; }
    /** engine speed derivable from power/torque, updated within the last 3s */
    private boolean rpmDerivedOk() {
        return !rpmLive() && derivedMs != 0
                && SystemClock.elapsedRealtime() - derivedMs < 3000;
    }
    private static float cToF(float c) { return c*9f/5f + 32f; }

    /** seed representative values so the layout is visible on an emulator (no vehicle bus). */
    public void seedDemo() {
        int[] t = {TORQUE,RPM,COOLANT,OILT,OILP,SPEED,GLAT,GLONG,GEAR,THROTTLE,POWER,TP_FR,TP_FL,TP_RR,TP_RL};
        float[] val = {180f,3120f,92f,105f,0.42f,68f,0.35f,-0.20f,3f,42f,3120f*180f,38.5f,38.5f,37f,36.8f};
        for (int i=0;i<t.length;i++) setValue(t[i], val[i]);
        demo = true;
        status = "DEMO (no vehicle bus) — sample values, not live";
    }

    @Override
    protected void onDraw(Canvas cv) {
        int W=getWidth(), H=getHeight();
        p.setColor(BG); cv.drawRect(0,0,W,H,p);

        drawRpm(cv, 110, 170, 96);                           // RPM arc, left
        drawSpeed(cv, 320, 170, 96);                         // speed arc, always beside it

        // right column: temp/pressure bars
        int bx=432, bw=W-bx-16;
        if (imperial) {
            drawBar(cv, bx, 24,  bw, "OIL TEMP", cToF(g(OILT)),   cToF(40),cToF(150), cToF(120),cToF(140), "°F", h(OILT));
            drawBar(cv, bx, 78,  bw, "COOLANT",  cToF(g(COOLANT)),cToF(40),cToF(130), cToF(110),cToF(120), "°F", h(COOLANT));
        } else {
            drawBar(cv, bx, 24,  bw, "OIL TEMP", g(OILT),   40,150, 120,140, "°C", h(OILT));
            drawBar(cv, bx, 78,  bw, "COOLANT",  g(COOLANT),40,130, 110,120, "°C", h(COOLANT));
        }

        // oil pressure: hide when negative (a wrong-engine signal, e.g. VQ reported -14 psi)
        float oilPsi = g(OILP)*OILP_RAW_TO_PSI;
        boolean oilHas = h(OILP) && oilPsi >= 0f;
        if (imperial)
            drawBar(cv, bx, 132, bw, "OIL PRESS", oilPsi,            0,100,               90,100,               "psi", oilHas);
        else
            drawBar(cv, bx, 132, bw, "OIL PRESS", oilPsi*PSI_TO_BAR, 0,100*PSI_TO_BAR, 90*PSI_TO_BAR,100*PSI_TO_BAR, "bar", oilHas);

        // gear + DTE + throttle row (speed lives in its own arc now)
        float dte = imperial ? g(DTE)*SPEED_RAW_TO_MPH : g(DTE);   // raw assumed km
        drawBigNum(cv, bx,      196, "GEAR",  gearStr(), "",     h(GEAR));
        drawBigNum(cv, bx+125, 196, "DTE",    fmt0(dte), imperial?"mi":"km", h(DTE));
        drawBigNum(cv, bx+250, 196, "THR",    fmt0(g(THROTTLE)), "%",    h(THROTTLE));

        // power + torque
        p.setColor(LABEL); p.setTextSize(14f); cv.drawText("POWER", bx, 300, p);
        p.setColor(VAL);   p.setTextSize(26f);
        String pw = imperial ? fmt0(g(POWER)*POWER_RAW_TO_KW*KW_TO_HP)+" hp" : fmt0(g(POWER)*POWER_RAW_TO_KW)+" kW";
        cv.drawText(pw, bx+70, 302, p);
        p.setColor(LABEL); p.setTextSize(14f); cv.drawText("TORQUE", bx, 328, p);
        p.setColor(VAL);   p.setTextSize(26f);
        String tq = imperial ? fmt0(g(TORQUE)*NM_TO_LBFT)+" lb-ft" : fmt0(g(TORQUE))+" Nm";
        cv.drawText(tq, bx+70, 330, p);

        drawTpms(cv, 16, 350, 300);                          // bottom-left TPMS corners
        drawGball(cv, W-120, 360, 90);                       // bottom-right G-ball

        // status footer + units hint
        p.setColor(DIM); p.setTextSize(11f);
        cv.drawText(status, 16, H-8, p);
        String u = (imperial?"IMPERIAL":"METRIC") + " — tap to switch";
        cv.drawText(u, W - p.measureText(u) - 8, H-8, p);

        // loud demo banner drawn last so it can't be missed
        if (demo) {
            p.setColor(WARN); cv.drawRect(0, 0, W, 22, p);
            p.setColor(0xFF101010); p.setTextSize(14f);
            center(cv, "DEMO — NO LIVE CAN DETECTED (sample values, not your car)", W/2, 16);
        }

        if (diag) drawDiag(cv, W, H);
    }

    /** diagnostics overlay: every sensor type that has reported, with raw value + age. */
    private void drawDiag(Canvas cv, int W, int H) {
        long now = SystemClock.elapsedRealtime();
        p.setColor(0xE0101418); cv.drawRect(0, 0, W, H, p);
        p.setColor(WARN); p.setTextSize(15f);
        cv.drawText("DIAG — live sensors (type, raw value, age)", 16, 26, p);
        p.setColor(DIM); p.setTextSize(12f);
        cv.drawText("hold 3s open · drag = scroll · tap = exit", W - p.measureText("hold 3s open · drag = scroll · tap = exit") - 16, 26, p);

        // clamp scroll so the last page sits flush with the bottom
        int y = 52, cols = 2, colW = (W-32)/cols, rowH = 30, live = 0;
        for (int t = 0; t < N; t++) if (have[t]) live++;
        float maxScroll = Math.max(0, ((live + cols - 1) / cols) * rowH - (H - y));
        if (scroll > maxScroll) scroll = maxScroll;

        cv.save(); cv.translate(0, -scroll);
        int row = 0;
        for (int t = 0; t < N; t++) {
            if (!have[t]) continue;
            long age = (now - lastMs[t]) / 1000;
            p.setTextSize(13f);
            // name gets whatever column space the type prefix + value tail leave; no hard char cap
            String base = t + " ";
            String tail = " = " + fmt1(v[t]) + "  (" + age + "s)";
            String name = names[t] != null ? names[t] : "";
            float avail = colW - 10 - p.measureText(base) - p.measureText(tail);
            while (name.length() > 0 && p.measureText(name) > avail)
                name = name.substring(0, name.length()-1);
            String line = base + name + tail;
            int cx = 16 + (row % cols) * colW, cy = y + (row / cols) * rowH;
            p.setColor(age > 2 ? WARN : OK);
            cv.drawText(line, cx, cy, p);
            row++;
        }
        cv.restore();

        if (row == 0) {
            p.setColor(DIM); p.setTextSize(15f);
            cv.drawText("no sensor events received at all", 16, y, p);
        } else if (scroll < maxScroll - 1) {
            p.setColor(DIM); p.setTextSize(11f);
            cv.drawText("more below…", W/2 - 30, H - 24, p);
        }
    }

    private void drawRpm(Canvas cv, int cx, int cy, int r) {
        // live sensor rpm; on dead-RPM ECUs (e.g. VQ37) fall back to power÷torque derivation
        boolean live = rpmLive(), hasVal = live || rpmDerivedOk();
        float val = live ? g(RPM) : derivedRpm;
        float max=7500, red=6800, start=135, sweep=270;   // VR30: peak power @6400, rev limit ~6800
        oval.set(cx-r, cy-r, cx+r, cy+r);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(18f);
        p.setColor(ARC_BG); cv.drawArc(oval, start, sweep, false, p);
        // redline zone
        p.setColor(0x66FF4040); cv.drawArc(oval, start+sweep*(red/max), sweep*(1-red/max), false, p);
        // value arc
        float frac=Math.max(0,Math.min(1,val/max));
        p.setColor(val>=red?DANGER:ARC_FG); cv.drawArc(oval, start, sweep*frac, false, p);
        p.setStyle(Paint.Style.FILL);
        // digital center
        p.setColor(LABEL); p.setTextSize(16f); center(cv,"RPM",cx,cy-42);
        p.setColor(val>=red?DANGER:VAL); p.setTextSize(64f); center(cv, hasVal?fmt0(val):"--", cx, cy+18);
        p.setColor(DIM); p.setTextSize(live?14f:12f);
        center(cv, live?"redline 6800":"derived · red 6800",cx,cy+r-6);
    }

    private void drawSpeed(Canvas cv, int cx, int cy, int r) {
        float disp = imperial ? g(SPEED)*SPEED_RAW_TO_MPH : g(SPEED);
        float max = imperial ? 160 : 260, start=135, sweep=270;
        oval.set(cx-r, cy-r, cx+r, cy+r);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(18f);
        p.setColor(ARC_BG); cv.drawArc(oval, start, sweep, false, p);
        float frac=Math.max(0,Math.min(1,disp/max));
        p.setColor(ARC_FG); cv.drawArc(oval, start, sweep*frac, false, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(LABEL); p.setTextSize(16f); center(cv,"SPEED",cx,cy-42);
        p.setColor(VAL); p.setTextSize(64f); center(cv, h(SPEED)?fmt0(disp):"--", cx, cy+18);
        p.setColor(DIM); p.setTextSize(14f); center(cv, imperial?"mph":"km/h",cx,cy+r-6);
    }

    private void drawBar(Canvas cv,int x,int y,int w,String lab,float val,float min,float max,
                         float warn,float red,String unit,boolean has){
        int h=26;
        p.setColor(LABEL); p.setTextSize(15f); cv.drawText(lab, x, y-6, p);
        p.setColor(PANEL); cv.drawRect(x, y, x+w, y+h, p);
        float frac=Math.max(0,Math.min(1,(val-min)/(max-min)));
        int c = val>=red?DANGER : val>=warn?WARN : OK;
        if(has){ p.setColor(c); cv.drawRect(x, y, x+w*frac, y+h, p); }
        p.setColor(VAL); p.setTextSize(20f);
        String s = has ? (fmt1(val)+" "+unit) : "--";
        cv.drawText(s, x+w-p.measureText(s)-8, y+20, p);
    }

    private void drawBigNum(Canvas cv,int x,int y,String lab,String val,String unit,boolean has){
        p.setColor(LABEL); p.setTextSize(14f); cv.drawText(lab, x, y-4, p);
        p.setColor(has?VAL:DIM); p.setTextSize(40f); cv.drawText(has?val:"--", x, y+34, p);
        if(has){ p.setColor(DIM); p.setTextSize(14f); cv.drawText(unit, x+2, y+50, p); }
    }

    private void drawTpms(Canvas cv,int x,int y,int w){
        p.setColor(LABEL); p.setTextSize(14f); cv.drawText(imperial?"TPMS (psi)":"TPMS (bar)", x, y-4, p);
        String[] lab={"FL","FR","RL","RR"}; int[] typ={TP_FL,TP_FR,TP_RL,TP_RR};
        int cw=w/2, ch=44;
        for(int i=0;i<4;i++){
            int cx=x+(i%2)*cw, cy=y+(i/2)*ch;
            p.setColor(DIM); p.setTextSize(13f); cv.drawText(lab[i], cx, cy+18, p);
            float raw=g(typ[i]);                                  // raw is psi
            boolean has=have[typ[i]];
            int c = (has && (raw<30||raw>42))?WARN:VAL;           // warn thresholds stay in psi
            float disp = imperial ? raw : raw*PSI_TO_BAR;
            p.setColor(has?c:DIM); p.setTextSize(24f);
            cv.drawText(has?fmt1(disp):"--", cx+34, cy+20, p);
        }
    }

    private void drawGball(Canvas cv,int cx,int cy,int r){
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2f); p.setColor(ARC_BG);
        cv.drawCircle(cx,cy,r,p); cv.drawCircle(cx,cy,r/2,p);
        cv.drawLine(cx-r,cy,cx+r,cy,p); cv.drawLine(cx,cy-r,cx,cy+r,p);
        p.setStyle(Paint.Style.FILL); p.setColor(LABEL); p.setTextSize(12f); center(cv,"G",cx,cy-r-4);
        // long accel = vertical (fwd up), lat = horizontal; assume ~1g full-scale
        float gx=Math.max(-1,Math.min(1,g(GLAT))), gy=Math.max(-1,Math.min(1,g(GLONG)));
        p.setColor(OK); cv.drawCircle(cx+gx*r, cy-gy*r, 6f, p);
    }

    private void center(Canvas cv,String s,int cx,int y){ cv.drawText(s, cx-p.measureText(s)/2, y, p); }
    private String fmt0(float f){ return String.valueOf(Math.round(f)); }
    private String fmt1(float f){ return String.valueOf(Math.round(f*10f)/10f); }
    // GEAR_POSITION enum (confirmed on-car): P/R/N/D = 1/2/3/4; manual mode M1..M7 = 16..22.
    private String gearStr(){
        if(!h(GEAR)) return "--";
        int gg=Math.round(g(GEAR));
        switch(gg){
            case 1: return "P";
            case 2: return "R";
            case 3: return "N";
            case 4: return "D";
            default: return (gg>=16 && gg<=22) ? "M"+(gg-15) : String.valueOf(gg);
        }
    }
}
