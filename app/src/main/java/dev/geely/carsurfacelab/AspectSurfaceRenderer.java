package dev.geely.carsurfacelab;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Aspect-correct "show entire frame" renderer for the fixed host Surface.
 * Only used in FIT mode. FILL goes directly from MediaPlayer to the host Surface,
 * avoiding extra GPU composition and retaining the proven fast output path.
 *
 * Does NOT call Surface.lockCanvas(). The OpenGL output is a single EGL producer
 * to the Android Auto Surface; MediaPlayer decodes to its own SurfaceTexture.
 * No render loop while paused: draws only when a new decoder frame arrives.
 */
public final class AspectSurfaceRenderer implements SurfaceTexture.OnFrameAvailableListener {
    public interface Listener {
        void onReady(Surface decoderSurface);
        void onFailure(String reason);
    }

    private static final String TAG = "CarVideo.Aspect";
    private static final String VS =
            "attribute vec2 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main(){ gl_Position=vec4(aPosition,0.0,1.0);" +
            "vTexCoord=(uTexMatrix*vec4(aTexCoord,0.0,1.0)).xy; }\n";
    private static final String FS =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "uniform samplerExternalOES uTexture;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main(){ gl_FragColor=texture2D(uTexture,vTexCoord); }\n";

    private final Surface host;
    private final int outputWidth, outputHeight;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler gl;
    private SurfaceTexture inputTexture;
    private Surface decoder;
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface window = EGL14.EGL_NO_SURFACE;
    private int program, tex, posLoc, uvLoc, matrixLoc, samplerLoc;
    private int videoWidth, videoHeight;
    private volatile int safeLeft, safeTop, safeRight, safeBottom;
    private final float[] textureMatrix = new float[16];
    private final FloatBuffer quad = ByteBuffer.allocateDirect(16*4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
    private boolean stopped, ready;

    public AspectSurfaceRenderer(Surface host, int width, int height, Listener listener) {
        this.host = host;
        this.outputWidth = Math.max(1, width);
        this.outputHeight = Math.max(1, height);
        this.safeRight = this.outputWidth;
        this.safeBottom = this.outputHeight;
        this.listener = listener;
    }

    public void start() {
        thread = new HandlerThread("CarVideo-aspect-frame");
        thread.start();
        gl = new Handler(thread.getLooper());
        gl.post(() -> {
            try {
                initialize();
                ready = true;
                Surface result = decoder;
                main.post(() -> {
                    if (!stopped) listener.onReady(result);
                });
            } catch (Throwable ex) {
                Log.e(TAG, "EGL decoder bridge initialization failed", ex);
                main.post(() -> listener.onFailure(ex.getClass().getSimpleName()));
                releaseGl();
                if (thread != null) thread.quitSafely();
            }
        });
    }

    public void videoSize(int width, int height) {
        videoWidth = Math.max(1,width);
        videoHeight = Math.max(1,height);
        if (gl != null) gl.post(() -> {
            if (inputTexture != null && !stopped) {
                inputTexture.setDefaultBufferSize(videoWidth,videoHeight);
            }
        });
    }

    public void setSafeArea(int left, int top, int right, int bottom) {
        if (left < 0 || top < 0 || right <= left || bottom <= top ||
                right > outputWidth || bottom > outputHeight) return;
        safeLeft = left; safeTop = top;
        safeRight = right; safeBottom = bottom;
    }

    private static void require(boolean ok, String stage) {
        if (!ok) throw new IllegalStateException("EGL: " + stage);
    }

    private void initialize() {
        require(host.isValid(), "invalid host Surface");
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        require(display != EGL14.EGL_NO_DISPLAY, "getDisplay");
        int[] version = new int[2];
        require(EGL14.eglInitialize(display, version, 0, version, 1), "initialize");
        int[] attributes = { EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE };
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        require(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)
                && count[0] > 0, "chooseConfig");
        int[] ctxAttr = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                ctxAttr, 0);
        require(context != EGL14.EGL_NO_CONTEXT, "createContext");
        int[] surfAttr = { EGL14.EGL_NONE };
        window = EGL14.eglCreateWindowSurface(display, configs[0], host, surfAttr, 0);
        require(window != EGL14.EGL_NO_SURFACE, "createWindowSurface");
        require(EGL14.eglMakeCurrent(display, window, window, context), "makeCurrent");

        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        tex = ids[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        int vs = compile(GLES20.GL_VERTEX_SHADER, VS);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, FS);
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        require(linked[0] == GLES20.GL_TRUE, "link: " +
                GLES20.glGetProgramInfoLog(program));
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        posLoc = GLES20.glGetAttribLocation(program, "aPosition");
        uvLoc = GLES20.glGetAttribLocation(program, "aTexCoord");
        matrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix");
        samplerLoc = GLES20.glGetUniformLocation(program, "uTexture");

        inputTexture = new SurfaceTexture(tex);
        inputTexture.setDefaultBufferSize(outputWidth,outputHeight);
        inputTexture.setOnFrameAvailableListener(this, gl);
        decoder = new Surface(inputTexture);
        GLES20.glViewport(0,0,outputWidth,outputHeight);
        GLES20.glClearColor(0f,0f,0f,1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        EGL14.eglSwapBuffers(display,window);
    }

    private static int compile(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(shader,GLES20.GL_COMPILE_STATUS,ok,0);
        require(ok[0] == GLES20.GL_TRUE, "compile: " + GLES20.glGetShaderInfoLog(shader));
        return shader;
    }

    @Override public void onFrameAvailable(SurfaceTexture source) {
        if (!ready || stopped || window == EGL14.EGL_NO_SURFACE) return;
        try {
            inputTexture.updateTexImage();
            inputTexture.getTransformMatrix(textureMatrix);
            render();
        } catch (RuntimeException ex) {
            Log.e(TAG,"Aspect frame failed",ex);
            main.post(() -> listener.onFailure("render: " + ex.getClass().getSimpleName()));
        }
    }

    private void render() {
        // Fit INSIDE the unobstructed Android Auto host rectangle.
        // The full Surface is larger than the stable/visible region on Geely.
        int l=safeLeft,t=safeTop,r=safeRight,b=safeBottom;
        float usableW = Math.max(1,r-l), usableH = Math.max(1,b-t);
        float sourceRatio = videoWidth > 0 && videoHeight > 0
                ? (float) videoWidth / videoHeight : usableW / usableH;
        float targetRatio = usableW / usableH;
        float centerX = (l+r)/(float)outputWidth - 1f;
        float centerY = 1f - (t+b)/(float)outputHeight;
        float sx = usableW/outputWidth, sy = usableH/outputHeight;
        if (sourceRatio > targetRatio) sy *= targetRatio/sourceRatio;
        else sx *= sourceRatio/targetRatio;
        quad.put(0,centerX-sx); quad.put(1,centerY-sy); quad.put(2,0f); quad.put(3,0f);
        quad.put(4,centerX+sx); quad.put(5,centerY-sy); quad.put(6,1f); quad.put(7,0f);
        quad.put(8,centerX-sx); quad.put(9,centerY+sy); quad.put(10,0f); quad.put(11,1f);
        quad.put(12,centerX+sx); quad.put(13,centerY+sy); quad.put(14,1f); quad.put(15,1f);
        GLES20.glViewport(0,0,outputWidth,outputHeight);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(program);
        quad.position(0);
        GLES20.glEnableVertexAttribArray(posLoc);
        GLES20.glVertexAttribPointer(posLoc,2,GLES20.GL_FLOAT,false,16,quad);
        quad.position(2);
        GLES20.glEnableVertexAttribArray(uvLoc);
        GLES20.glVertexAttribPointer(uvLoc,2,GLES20.GL_FLOAT,false,16,quad);
        GLES20.glUniformMatrix4fv(matrixLoc,1,false,textureMatrix,0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,tex);
        GLES20.glUniform1i(samplerLoc,0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
        GLES20.glDisableVertexAttribArray(posLoc);
        GLES20.glDisableVertexAttribArray(uvLoc);
        if (!EGL14.eglSwapBuffers(display, window)) {
            throw new IllegalStateException("EGL window unavailable");
        }
    }

    public void close() {
        stopped = true;
        if (gl != null) gl.post(() -> {
            releaseGl();
            if (thread != null) thread.quitSafely();
        });
    }

    private void releaseGl() {
        try {
            if (inputTexture != null) {
                inputTexture.setOnFrameAvailableListener(null);
            }
            if (decoder != null) { decoder.release(); decoder = null; }
            if (inputTexture != null) { inputTexture.release(); inputTexture = null; }
            if (program != 0) GLES20.glDeleteProgram(program);
            if (tex != 0) GLES20.glDeleteTextures(1,new int[]{tex},0);
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT);
                if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,window);
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display,context);
                EGL14.eglTerminate(display);
            }
        } catch (RuntimeException e) {
            Log.w(TAG,"Renderer release",e);
        }
        window = EGL14.EGL_NO_SURFACE;
        context = EGL14.EGL_NO_CONTEXT;
        display = EGL14.EGL_NO_DISPLAY;
    }
}
