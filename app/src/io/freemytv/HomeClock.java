package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Handler;
import java.util.concurrent.Executor;

/** The screensaver's glass digits, scaled to the home header. Masks are prepared only when the minute changes. */
final class HomeClock {
    private final Executor worker;
    private final Handler main;
    private final Runnable changed;
    private final Paint glassPaint=new Paint(), shadowPaint=new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect ink=new Rect();
    private final RectF bounds=new RectF();
    private RuntimeShader shader;
    private Paint digits;
    private DreamView.Glass glass;
    private Bitmap background;
    private String text="";
    private float width, scale=1, right, top, baseline;
    private int generation;
    private boolean released;

    HomeClock(Executor worker,Handler main,Runnable changed) {
        this.worker=worker;this.main=main;this.changed=changed;
        try {shader=new RuntimeShader(Shaders.GLASS_TEXT);glassPaint.setShader(shader);}
        catch(RuntimeException|LinkageError e){AppLog.w("home clock: using readable text fallback");}
        shadowPaint.setColor(0x66000000);
    }
    void layout(float width,float scale) {
        if(this.width==width && this.scale==scale && digits!=null)return;
        this.width=width;this.scale=scale;
        digits=Ui.numbers(Ui.displaySemibold,112*scale,0xFFFFFFFF);
        digits.setLetterSpacing(-0.02f);
        String current=text;text="";update(current);
    }
    void update(String next) {
        if(released || digits==null || next.equals(text))return;
        text=next;generation++;
        if(next.isEmpty())return;
        digits.getTextBounds(next,0,next.length(),ink);
        right=width/2+ink.width()/2f;top=64*scale;baseline=top-ink.top;
        bounds.set(right-ink.width(),top,right,top+ink.height());
        if(shader==null)return;
        final int gen=generation;
        final Paint paint=new Paint(digits);
        final float end=right,start=top,k=scale*0.56f;
        worker.execute(()->{
            try {
                DreamView.Glass made=DreamView.makeGlass(next,paint,end,start,k);
                main.post(()->{
                    if(released || gen!=generation)return;
                    glass=made;
                    shader.setInputShader("slope",nearest(made.slope));
                    shader.setInputShader("cover",nearest(made.cover));
                    changed.run();
                });
            }catch(RuntimeException|OutOfMemoryError e){AppLog.w("home clock mask: text fallback");}
        });
    }
    private static BitmapShader nearest(Bitmap b) {
        BitmapShader s=new BitmapShader(b,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);
        Frost.filter(s,BitmapShader.FILTER_MODE_NEAREST);return s;
    }
    void draw(Canvas c,Bitmap frost,boolean dark) {
        if(digits==null || text.isEmpty())return;
        DreamView.Glass g=glass;
        if(shader==null || g==null || !g.text.equals(text) || frost==null) {
            digits.setColor(dark?0xFFF5F5F7:0xFF1D1D1F);
            c.drawText(text,bounds.left-ink.left,baseline,digits);return;
        }
        if(background!=frost) {
            background=frost;
            BitmapShader image=new BitmapShader(frost,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);
            Frost.filter(image,BitmapShader.FILTER_MODE_LINEAR);
            shader.setInputShader("imageA",image);shader.setInputShader("imageB",image);
        }
        float zoom=width/frost.getWidth();
        shader.setFloatUniform("placeA",0,0,zoom);shader.setFloatUniform("placeB",0,0,zoom);
        shader.setFloatUniform("mixB",0);shader.setFloatUniform("origin",g.x,g.y);
        shader.setFloatUniform("ink",g.inkTop,g.inkHeight);
        shader.setFloatUniform("refraction",8*scale);shader.setFloatUniform("specular",1);
        shader.setFloatUniform("alpha",1);
        c.drawBitmap(g.shadow,g.x,g.y+3*scale,shadowPaint);
        c.drawRect(g.x,g.y,g.x+g.cover.getWidth(),g.y+g.cover.getHeight(),glassPaint);
    }
    void bounds(RectF out){out.set(bounds);}
    void release(){released=true;generation++;glass=null;background=null;}
}
