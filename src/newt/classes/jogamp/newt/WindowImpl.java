/*
 * Copyright (c) 2008 Sun Microsystems, Inc. All Rights Reserved.
   Copyright (c) 2010 JogAmp Community. All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 * 
 * - Redistribution of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 * 
 * - Redistribution in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 * 
 * Neither the name of Sun Microsystems, Inc. or the names of
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 * 
 * This software is provided "AS IS," without a warranty of any kind. ALL
 * EXPRESS OR IMPLIED CONDITIONS, REPRESENTATIONS AND WARRANTIES,
 * INCLUDING ANY IMPLIED WARRANTY OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE OR NON-INFRINGEMENT, ARE HEREBY EXCLUDED. SUN
 * MICROSYSTEMS, INC. ("SUN") AND ITS LICENSORS SHALL NOT BE LIABLE FOR
 * ANY DAMAGES SUFFERED BY LICENSEE AS A RESULT OF USING, MODIFYING OR
 * DISTRIBUTING THIS SOFTWARE OR ITS DERIVATIVES. IN NO EVENT WILL SUN OR
 * ITS LICENSORS BE LIABLE FOR ANY LOST REVENUE, PROFIT OR DATA, OR FOR
 * DIRECT, INDIRECT, SPECIAL, CONSEQUENTIAL, INCIDENTAL OR PUNITIVE
 * DAMAGES, HOWEVER CAUSED AND REGARDLESS OF THE THEORY OF LIABILITY,
 * ARISING OUT OF THE USE OF OR INABILITY TO USE THIS SOFTWARE, EVEN IF
 * SUN HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.
 * 
 */

package jogamp.newt;

import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Method;

import com.jogamp.common.util.IntBitfield;
import com.jogamp.common.util.ReflectionUtil;
import com.jogamp.newt.MonitorDevice;
import com.jogamp.newt.NewtFactory;
import com.jogamp.newt.Display;
import com.jogamp.newt.Screen;
import com.jogamp.newt.Window;
import com.jogamp.common.util.locks.LockFactory;
import com.jogamp.common.util.locks.RecursiveLock;
import com.jogamp.newt.event.InputEvent;
import com.jogamp.newt.event.KeyEvent;
import com.jogamp.newt.event.KeyListener;
import com.jogamp.newt.event.MonitorEvent;
import com.jogamp.newt.event.MouseEvent;
import com.jogamp.newt.event.MouseListener;
import com.jogamp.newt.event.NEWTEvent;
import com.jogamp.newt.event.NEWTEventConsumer;
import com.jogamp.newt.event.MonitorModeListener;
import com.jogamp.newt.event.WindowEvent;
import com.jogamp.newt.event.WindowListener;
import com.jogamp.newt.event.WindowUpdateEvent;

import javax.media.nativewindow.AbstractGraphicsConfiguration;
import javax.media.nativewindow.AbstractGraphicsDevice;
import javax.media.nativewindow.CapabilitiesChooser;
import javax.media.nativewindow.CapabilitiesImmutable;
import javax.media.nativewindow.NativeSurface;
import javax.media.nativewindow.NativeWindow;
import javax.media.nativewindow.NativeWindowException;
import javax.media.nativewindow.NativeWindowFactory;
import javax.media.nativewindow.SurfaceUpdatedListener;
import javax.media.nativewindow.WindowClosingProtocol;
import javax.media.nativewindow.util.Insets;
import javax.media.nativewindow.util.InsetsImmutable;
import javax.media.nativewindow.util.Point;
import javax.media.nativewindow.util.Rectangle;
import javax.media.nativewindow.util.RectangleImmutable;

import jogamp.nativewindow.SurfaceUpdatedHelper;

public abstract class WindowImpl implements Window, NEWTEventConsumer
{
    public static final boolean DEBUG_TEST_REPARENT_INCOMPATIBLE = Debug.isPropertyDefined("newt.test.Window.reparent.incompatible", true);
    
    /** Timeout of queued events (repaint and resize) */
    static final long QUEUED_EVENT_TO = 1200; // ms    

    //
    // Volatile: Multithread Mutable Access
    //    
    private volatile long windowHandle = 0; // lifecycle critical
    private volatile boolean visible = false; // lifecycle critical
    private volatile boolean hasFocus = false;    
    private volatile int width = 128, height = 128; // client-area size w/o insets, default: may be overwritten by user
    private volatile int x = 64, y = 64; // client-area pos w/o insets
    private volatile Insets insets = new Insets(); // insets of decoration (if top-level && decorated)
        
    private RecursiveLock windowLock = LockFactory.createRecursiveLock();  // Window instance wide lock
    private int surfaceLockCount = 0; // surface lock recursion count
    
    private ScreenImpl screen; // never null after create - may change reference though (reparent)
    private boolean screenReferenceAdded = false;
    private NativeWindow parentWindow = null;
    private long parentWindowHandle = 0;
    private AbstractGraphicsConfiguration config = null; // control access due to delegation
    protected CapabilitiesImmutable capsRequested = null;
    protected CapabilitiesChooser capabilitiesChooser = null; // default null -> default
    private boolean fullscreen = false, brokenFocusChange = false;
    private List<MonitorDevice> fullscreenMonitors = null;
    private boolean fullscreenUseMainMonitor = true;
    private boolean autoPosition = true; // default: true (allow WM to choose top-level position, if not set by user)
    
    private int nfs_width, nfs_height, nfs_x, nfs_y; // non fullscreen client-area size/pos w/o insets
    private NativeWindow nfs_parent = null;          // non fullscreen parent, in case explicit reparenting is performed (offscreen)
    private String title = "Newt Window";
    private boolean undecorated = false;
    private boolean alwaysOnTop = false;
    private boolean pointerVisible = true;
    private boolean pointerConfined = false;
    private LifecycleHook lifecycleHook = null;

    private Runnable windowDestroyNotifyAction = null;

    private FocusRunnable focusAction = null;
    private KeyListener keyboardFocusHandler = null;

    private SurfaceUpdatedHelper surfaceUpdatedHelper = new SurfaceUpdatedHelper();
    
    private Object childWindowsLock = new Object();
    private ArrayList<NativeWindow> childWindows = new ArrayList<NativeWindow>();

    private ArrayList<MouseListener> mouseListeners = new ArrayList<MouseListener>();
    private short mouseButtonPressed = (short)0;  // current pressed mouse button number
    private int mouseButtonModMask = 0;  // current pressed mouse button modifier mask
    private long lastMousePressed = 0;    // last time when a mouse button was pressed
    private short lastMouseClickCount = (short)0; // last mouse button click count
    private boolean mouseInWindow = false;// mouse entered window - is inside the window (may be synthetic)
    private Point lastMousePosition = new Point();

    private ArrayList<KeyListener> keyListeners = new ArrayList<KeyListener>();

    private ArrayList<WindowListener> windowListeners  = new ArrayList<WindowListener>();
    private boolean repaintQueued = false;

    /**
     * Workaround for initialization order problems on Mac OS X
     * between native Newt and (apparently) Fmod -- if Fmod is
     * initialized first then the connection to the window server
     * breaks, leading to errors from deep within the AppKit
     */
    public static void init(String type) {
        if (NativeWindowFactory.TYPE_MACOSX.equals(type)) {
            try {
                getWindowClass(type);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    //
    // Construction Methods
    //

    private static Class<?> getWindowClass(String type)
        throws ClassNotFoundException
    {
        final Class<?> windowClass = NewtFactory.getCustomClass(type, "WindowDriver");
        if(null==windowClass) {
            throw new ClassNotFoundException("Failed to find NEWT Window Class <"+type+".WindowDriver>");            
        }
        return windowClass;
    }

    public static WindowImpl create(NativeWindow parentWindow, long parentWindowHandle, Screen screen, CapabilitiesImmutable caps) {
        try {
            Class<?> windowClass;
            if(caps.isOnscreen()) {
                windowClass = getWindowClass(screen.getDisplay().getType());
            } else {
                windowClass = OffscreenWindow.class;
            }
            WindowImpl window = (WindowImpl) windowClass.newInstance();
            window.parentWindow = parentWindow;
            window.parentWindowHandle = parentWindowHandle;
            window.screen = (ScreenImpl) screen;
            window.capsRequested = (CapabilitiesImmutable) caps.cloneMutable();
            window.instantiationFinished();
            return window;
        } catch (Throwable t) {
            t.printStackTrace();
            throw new NativeWindowException(t);
        }
    }
    
    public static WindowImpl create(Object[] cstrArguments, Screen screen, CapabilitiesImmutable caps) {
        try {
            Class<?> windowClass = getWindowClass(screen.getDisplay().getType());
            Class<?>[] cstrArgumentTypes = getCustomConstructorArgumentTypes(windowClass);
            if(null==cstrArgumentTypes) {
                throw new NativeWindowException("WindowClass "+windowClass+" doesn't support custom arguments in constructor");
            }
            int argsChecked = verifyConstructorArgumentTypes(cstrArgumentTypes, cstrArguments);
            if ( argsChecked < cstrArguments.length ) {
                throw new NativeWindowException("WindowClass "+windowClass+" constructor mismatch at argument #"+argsChecked+"; Constructor: "+getTypeStrList(cstrArgumentTypes)+", arguments: "+getArgsStrList(cstrArguments));
            }
            WindowImpl window = (WindowImpl) ReflectionUtil.createInstance( windowClass, cstrArgumentTypes, cstrArguments ) ;
            window.screen = (ScreenImpl) screen;
            window.capsRequested = (CapabilitiesImmutable) caps.cloneMutable();
            return window;
        } catch (Throwable t) {
            throw new NativeWindowException(t);
        }
    }

    protected final void setGraphicsConfiguration(AbstractGraphicsConfiguration cfg) {
        config = cfg;
    }
    
    public static interface LifecycleHook {
        /**
         * Reset of internal state counter, ie totalFrames, etc.
         * Called from EDT while window is locked.
         */
        public abstract void resetCounter();

        /** 
         * Invoked after Window setVisible, 
         * allows allocating resources depending on the native Window.
         * Called from EDT while window is locked.
         */
        void setVisibleActionPost(boolean visible, boolean nativeWindowCreated);

        /**
         * Notifies the receiver to preserve resources (GL, ..)
         * for the next destroy*() calls (only).
         */
        void preserveGLStateAtDestroy();
        
        /** 
         * Invoked before Window destroy action, 
         * allows releasing of resources depending on the native Window.<br>
         * Surface not locked yet.<br>
         * Called not necessarily from EDT.
         */
        void destroyActionPreLock();

        /**
         * Invoked before Window destroy action,
         * allows releasing of resources depending on the native Window.<br>
         * Surface locked.<br>
         * Called from EDT while window is locked.
         */
        void destroyActionInLock();

        /**
         * Invoked for expensive modifications, ie while reparenting and MonitorMode change.<br>
         * No lock is hold when invoked.<br>
         *
         * @return true is paused, otherwise false. If true {@link #resumeRenderingAction()} shall be issued.
         *
         * @see #resumeRenderingAction()
         */
        boolean pauseRenderingAction();

        /**
         * Invoked for expensive modifications, ie while reparenting and MonitorMode change.
         * No lock is hold when invoked.<br>
         *
         * @see #pauseRenderingAction()
         */
        void resumeRenderingAction();
    }

    private boolean createNative() {
        long tStart;
        if(DEBUG_IMPLEMENTATION) {
            tStart = System.nanoTime();
            System.err.println("Window.createNative() START ("+getThreadName()+", "+this+")");
        } else {
            tStart = 0;
        }
        
        if( null != parentWindow && 
            NativeSurface.LOCK_SURFACE_NOT_READY >= parentWindow.lockSurface() ) {
            throw new NativeWindowException("Parent surface lock: not ready: "+parentWindow);
        }
        
        // child window: position defaults to 0/0, no auto position, no negative position
        if( null != parentWindow && ( autoPosition || 0>getX() || 0>getY() ) ) {                
            definePosition(0, 0);
        }
        boolean postParentlockFocus = false;
        try {
            if(validateParentWindowHandle()) {
                if( !screenReferenceAdded ) {
                    screen.addReference();
                    screenReferenceAdded = true;
                }
                if(canCreateNativeImpl()) {
                    final int wX, wY;
                    final boolean usePosition;
                    if( autoPosition  ) {
                        wX = 0;
                        wY = 0;
                        usePosition = false;
                    } else {
                        wX = getX();
                        wY = getY();
                        usePosition = true;
                    }
                    final long t0 = System.currentTimeMillis();
                    createNativeImpl();
                    screen.addMonitorModeListener(monitorModeListenerImpl);
                    setTitleImpl(title);
                    setPointerVisibleImpl(pointerVisible);
                    confinePointerImpl(pointerConfined);
                    setKeyboardVisible(keyboardVisible);
                    final long remainingV = waitForVisible(true, false);
                    if( 0 <= remainingV ) {
                        if(isFullscreen()) {
                            synchronized(fullScreenAction) {
                                fullscreen = false; // trigger a state change
                                fullScreenAction.init(true, fullscreenUseMainMonitor, fullscreenMonitors);
                                fullscreenMonitors = null; // release references ASAP
                                fullscreenUseMainMonitor = true;
                                fullScreenAction.run();
                            }
                        } else {
                            // Wait until position is reached within tolerances, either auto-position or custom position.
                            waitForPosition(usePosition, wX, wY, Window.TIMEOUT_NATIVEWINDOW);
                        }
                        if (DEBUG_IMPLEMENTATION) {
                            System.err.println("Window.createNative(): elapsed "+(System.currentTimeMillis()-t0)+" ms");
                        }
                        postParentlockFocus = true;
                    }
                }
            }
        } finally {
            if(null!=parentWindow) {
                parentWindow.unlockSurface();
            }
        }
        if(postParentlockFocus) {
            // harmonize focus behavior for all platforms: focus on creation
            requestFocusInt(isFullscreen() /* skipFocusAction */);
            ((DisplayImpl) screen.getDisplay()).dispatchMessagesNative(); // status up2date
        }
        if(DEBUG_IMPLEMENTATION) {
            System.err.println("Window.createNative() END ("+getThreadName()+", "+this+") total "+ (System.nanoTime()-tStart)/1e6 +"ms");
        }
        return isNativeValid() ;
    }

    private void removeScreenReference() {
        if(screenReferenceAdded) {
            // be nice, probably already called recursive via
            //   closeAndInvalidate() -> closeNativeIml() -> .. -> windowDestroyed() -> closeAndInvalidate() !
            // or via reparentWindow .. etc
            screenReferenceAdded = false;
            screen.removeReference();
        }
    }

    private boolean validateParentWindowHandle() {
        if(null!=parentWindow) {
            parentWindowHandle = getNativeWindowHandle(parentWindow);
            return 0 != parentWindowHandle ;
        }
        return true;
    }

    private static long getNativeWindowHandle(NativeWindow nativeWindow) {
        long handle = 0;
        if(null!=nativeWindow) {
            boolean wasLocked = false;
            if( NativeSurface.LOCK_SURFACE_NOT_READY < nativeWindow.lockSurface() ) {
                wasLocked = true;
                try {
                    handle = nativeWindow.getWindowHandle();
                    if(0==handle) {
                        throw new NativeWindowException("Parent native window handle is NULL, after succesful locking: "+nativeWindow);
                    }
                } catch (NativeWindowException nwe) {
                    if(DEBUG_IMPLEMENTATION) {
                        System.err.println("Window.getNativeWindowHandle: not successful yet: "+nwe);
                    }
                } finally {
                    nativeWindow.unlockSurface();
                }
            }
            if(DEBUG_IMPLEMENTATION) {
                System.err.println("Window.getNativeWindowHandle: locked "+wasLocked+", "+nativeWindow);
            }
        }
        return handle;
    }


    //----------------------------------------------------------------------
    // NativeSurface: Native implementation
    //

    protected int lockSurfaceImpl() { return LOCK_SUCCESS; }

    protected void unlockSurfaceImpl() { }

    //----------------------------------------------------------------------
    // WindowClosingProtocol implementation
    //
    private Object closingListenerLock = new Object();
    private WindowClosingMode defaultCloseOperation = WindowClosingMode.DISPOSE_ON_CLOSE;

    public WindowClosingMode getDefaultCloseOperation() {
        synchronized (closingListenerLock) {
            return defaultCloseOperation;
        }
    }

    public WindowClosingMode setDefaultCloseOperation(WindowClosingMode op) {
        synchronized (closingListenerLock) {
            WindowClosingMode _op = defaultCloseOperation;
            defaultCloseOperation = op;
            return _op;
        }
    }

    //----------------------------------------------------------------------
    // Window: Native implementation
    //

    /**
     * Notifies the driver impl. that the instantiation is finished,
     * ie. instance created and all fields set. 
     */
    protected void instantiationFinished() {
        // nop
    }
    
    protected boolean canCreateNativeImpl() {
        return true; // default: always able to be created
    }
    
    /** 
     * The native implementation must set the native windowHandle.<br>
     *
     * <p>
     * The implementation shall respect the states {@link #isAlwaysOnTop()}/{@link #FLAG_IS_ALWAYSONTOP} and
     * {@link #isUndecorated()}/{@link #FLAG_IS_UNDECORATED}, ie. the created window shall reflect those settings.
     * </p>
     * 
     * <p>
     * The implementation should invoke the referenced java state callbacks
     * to notify this Java object of state changes.</p>
     * 
     * @see #windowDestroyNotify(boolean)
     * @see #focusChanged(boolean, boolean)
     * @see #visibleChanged(boolean, boolean)
     * @see #sizeChanged(int,int)
     * @see #positionChanged(boolean,int, int)
     * @see #windowDestroyNotify(boolean)
     */
    protected abstract void createNativeImpl();

    protected abstract void closeNativeImpl();

    /** 
     * Async request which shall be performed within {@link #TIMEOUT_NATIVEWINDOW}.
     * <p>
     * If if <code>force == false</code> the native implementation 
     * may only request focus if not yet owner.</p>
     * <p>
     * {@link #focusChanged(boolean, boolean)} should be called
     * to notify about the focus traversal. 
     * </p> 
     * 
     * @param force if true, bypass {@link #focusChanged(boolean, boolean)} and force focus request
     */
    protected abstract void requestFocusImpl(boolean force);

    public static final int FLAG_CHANGE_PARENTING       = 1 <<  0;
    public static final int FLAG_CHANGE_DECORATION      = 1 <<  1;
    public static final int FLAG_CHANGE_FULLSCREEN      = 1 <<  2;
    public static final int FLAG_CHANGE_ALWAYSONTOP     = 1 <<  3;
    public static final int FLAG_CHANGE_VISIBILITY      = 1 <<  4;
    
    public static final int FLAG_HAS_PARENT             = 1 <<  8;
    public static final int FLAG_IS_UNDECORATED         = 1 <<  9;
    public static final int FLAG_IS_FULLSCREEN          = 1 << 10;
    public static final int FLAG_IS_FULLSCREEN_SPAN     = 1 << 11;
    public static final int FLAG_IS_ALWAYSONTOP         = 1 << 12;
    public static final int FLAG_IS_VISIBLE             = 1 << 13;

    /**
     * The native implementation should invoke the referenced java state callbacks
     * to notify this Java object of state changes.
     * 
     * <p>
     * Implementations shall set x/y to 0, in case it's negative. This could happen due
     * to insets and positioning a decorated window to 0/0, which would place the frame
     * outside of the screen.</p>
     * 
     * @param x client-area position, or <0 if unchanged
     * @param y client-area position, or <0 if unchanged
     * @param width client-area size, or <=0 if unchanged
     * @param height client-area size, or <=0 if unchanged
     * @param flags bitfield of change and status flags
     *
     * @see #sizeChanged(int,int)
     * @see #positionChanged(boolean,int, int)
     */
    protected abstract boolean reconfigureWindowImpl(int x, int y, int width, int height, int flags);

    protected int getReconfigureFlags(int changeFlags, boolean visible) {
        return changeFlags |= ( ( 0 != getParentWindowHandle() ) ? FLAG_HAS_PARENT : 0 ) |
                              ( isUndecorated() ? FLAG_IS_UNDECORATED : 0 ) |
                              ( isFullscreen() ? FLAG_IS_FULLSCREEN : 0 ) |
                              ( isAlwaysOnTop() ? FLAG_IS_ALWAYSONTOP : 0 ) |
                              ( visible ? FLAG_IS_VISIBLE : 0 ) ;
    }
    protected static String getReconfigureFlagsAsString(StringBuilder sb, int flags) {
        if(null == sb) { sb = new StringBuilder(); }
        sb.append("[");
        
        if( 0 != ( FLAG_CHANGE_PARENTING & flags) ) {
            sb.append("*");
        }
        sb.append("PARENT_");
        sb.append(0 != ( FLAG_HAS_PARENT & flags));
        sb.append(", ");
        
        if( 0 != ( FLAG_CHANGE_FULLSCREEN & flags) ) {
            sb.append("*");
        }
        sb.append("FS_");
        sb.append(0 != ( FLAG_IS_FULLSCREEN & flags));
        sb.append("_span_");
        sb.append(0 != ( FLAG_IS_FULLSCREEN_SPAN & flags));
        sb.append(", ");

        if( 0 != ( FLAG_CHANGE_DECORATION & flags) ) {
            sb.append("*");
        }
        sb.append("UNDECOR_");
        sb.append(0 != ( FLAG_IS_UNDECORATED & flags));
        sb.append(", ");
        
        if( 0 != ( FLAG_CHANGE_ALWAYSONTOP & flags) ) {
            sb.append("*");
        }
        sb.append("ALWAYSONTOP_");
        sb.append(0 != ( FLAG_IS_ALWAYSONTOP & flags));
        sb.append(", ");
        
        if( 0 != ( FLAG_CHANGE_VISIBILITY & flags) ) {
            sb.append("*");
        }
        sb.append("VISIBLE_");
        sb.append(0 != ( FLAG_IS_VISIBLE & flags));
        
        sb.append("]");
        return sb.toString();
    }
    
    protected void setTitleImpl(String title) {}

    /**
     * Translates the given window client-area coordinates with top-left origin
     * to screen coordinates.
     * <p>
     * Since the position reflects the client area, it does not include the insets.
     * </p>
     * <p>
     * May return <code>null</code>, in which case the caller shall traverse through the NativeWindow tree
     * as demonstrated in {@link #getLocationOnScreen(javax.media.nativewindow.util.Point)}.
     * </p>
     *
     * @return if not null, the screen location of the given coordinates
     */
    protected abstract Point getLocationOnScreenImpl(int x, int y);
    
    /** Triggered by user via {@link #getInsets()}.<br>
     * Implementations may implement this hook to update the insets.<br> 
     * However, they may prefer the event driven path via {@link #insetsChanged(boolean, int, int, int, int)}.
     * 
     * @see #getInsets()
     * @see #insetsChanged(boolean, int, int, int, int)
     */
    protected abstract void updateInsetsImpl(Insets insets);

    protected boolean setPointerVisibleImpl(boolean pointerVisible) { return false; }
    protected boolean confinePointerImpl(boolean confine) { return false; }
    protected void warpPointerImpl(int x, int y) { }
    
    //----------------------------------------------------------------------
    // NativeSurface
    //

    @Override
    public final int lockSurface() throws NativeWindowException, RuntimeException {
        final RecursiveLock _wlock = windowLock;
        _wlock.lock();
        surfaceLockCount++;
        int res = ( 1 == surfaceLockCount ) ? LOCK_SURFACE_NOT_READY : LOCK_SUCCESS; // new lock ?

        if ( LOCK_SURFACE_NOT_READY == res ) {
            try {
                if( isNativeValid() ) {
                    final AbstractGraphicsDevice adevice = getGraphicsConfiguration().getScreen().getDevice();
                    adevice.lock();
                    try {
                        res = lockSurfaceImpl();
                    } finally {
                        if (LOCK_SURFACE_NOT_READY >= res) {
                            adevice.unlock();
                        }
                    }
                }
            } finally {
                if (LOCK_SURFACE_NOT_READY >= res) {
                    surfaceLockCount--;
                    _wlock.unlock();
                }
            }
        }
        return res;
    }

    @Override
    public final void unlockSurface() {
        final RecursiveLock _wlock = windowLock;
        _wlock.validateLocked();

        if ( 1 == surfaceLockCount ) {
            final AbstractGraphicsDevice adevice = getGraphicsConfiguration().getScreen().getDevice();
            try {
                unlockSurfaceImpl();
            } finally {
                adevice.unlock();
            }
        }
        surfaceLockCount--;
        _wlock.unlock();
    }

    @Override
    public final boolean isSurfaceLockedByOtherThread() {
        return windowLock.isLockedByOtherThread();
    }

    @Override
    public final Thread getSurfaceLockOwner() {
        return windowLock.getOwner();
    }

    public final RecursiveLock getLock() {
        return windowLock;
    }
    
    @Override
    public long getSurfaceHandle() {
        return windowHandle; // default: return window handle
    }

    @Override
    public boolean surfaceSwap() {
        return false;
    }

    @Override
    public final AbstractGraphicsConfiguration getGraphicsConfiguration() {
        return config.getNativeGraphicsConfiguration();
    }

    @Override
    public final long getDisplayHandle() {
        return config.getNativeGraphicsConfiguration().getScreen().getDevice().getHandle();
    }    

    @Override
    public final int  getScreenIndex() {
        return screen.getIndex();
    }

    //----------------------------------------------------------------------
    // NativeWindow
    //

    // public final void destroy() - see below

    @Override
    public final NativeWindow getParent() {
        return parentWindow;
    }

    @Override
    public final long getWindowHandle() {
        return windowHandle;
    }

    @Override
    public Point getLocationOnScreen(Point storage) {
        if(isNativeValid()) {
            Point d;
            final RecursiveLock _lock = windowLock;
            _lock.lock();
            try {
                d = getLocationOnScreenImpl(0, 0);
            } finally {
                _lock.unlock();
            }
            if(null!=d) {
                if(null!=storage) {
                    storage.translate(d.getX(),d.getY());
                    return storage;
                }
                return d;
            }
            // fall through intended ..
        }

        if(null!=storage) {
            storage.translate(getX(),getY());
        } else {
            storage = new Point(getX(),getY());
        }
        if(null!=parentWindow) {
            // traverse through parent list ..
            parentWindow.getLocationOnScreen(storage);
        }
        return storage;
    }

    //----------------------------------------------------------------------
    // Window
    //

    @Override
    public final boolean isNativeValid() {
        return 0 != windowHandle ;
    }

    @Override
    public final Screen getScreen() {
        return screen;
    }
    
    @Override
    public final MonitorDevice getMainMonitor() {
        return screen.getMainMonitor(new Rectangle(getX(), getY(), getWidth(), getHeight()));
    }
    
    protected final void setVisibleImpl(boolean visible, int x, int y, int width, int height) {
        reconfigureWindowImpl(x, y, width, height, getReconfigureFlags(FLAG_CHANGE_VISIBILITY, visible));           
    }    
    final void setVisibleActionImpl(boolean visible) {
        boolean nativeWindowCreated = false;
        boolean madeVisible = false;
        
        final RecursiveLock _lock = windowLock;
        _lock.lock();
        try {
            if(!visible && null!=childWindows && childWindows.size()>0) {
              synchronized(childWindowsLock) {
                for(int i = 0; i < childWindows.size(); i++ ) {
                    NativeWindow nw = childWindows.get(i);
                    if(nw instanceof WindowImpl) {
                        ((WindowImpl)nw).setVisible(false);
                    }
                }
              }
            }
            if(!isNativeValid() && visible) {
                if( 0<getWidth()*getHeight() ) {
                    nativeWindowCreated = createNative();
                    madeVisible = nativeWindowCreated;
                }
                // always flag visible, allowing a retry ..
                WindowImpl.this.visible = true;      
            } else if(WindowImpl.this.visible != visible) {
                if(isNativeValid()) {
                    setVisibleImpl(visible, getX(), getY(), getWidth(), getHeight());
                    WindowImpl.this.waitForVisible(visible, false);
                    madeVisible = visible;
                } else {
                    WindowImpl.this.visible = true;
                }
            }

            if(null!=lifecycleHook) {
                lifecycleHook.setVisibleActionPost(visible, nativeWindowCreated);
            }

            if(isNativeValid() && visible && null!=childWindows && childWindows.size()>0) {
              synchronized(childWindowsLock) {
                for(int i = 0; i < childWindows.size(); i++ ) {
                    NativeWindow nw = childWindows.get(i);
                    if(nw instanceof WindowImpl) {
                        ((WindowImpl)nw).setVisible(true);
                    }
                }
              }
            }
            if(DEBUG_IMPLEMENTATION) {
                System.err.println("Window setVisible: END ("+getThreadName()+") "+getX()+"/"+getY()+" "+getWidth()+"x"+getHeight()+", fs "+fullscreen+", windowHandle "+toHexString(windowHandle)+", visible: "+WindowImpl.this.visible+", nativeWindowCreated: "+nativeWindowCreated+", madeVisible: "+madeVisible);
            }
        } finally {
            if(null!=lifecycleHook) {
                lifecycleHook.resetCounter();
            }
            _lock.unlock();
        }
        if( nativeWindowCreated || madeVisible ) {
            sendWindowEvent(WindowEvent.EVENT_WINDOW_RESIZED); // trigger a resize/relayout and repaint to listener
        }
    }
    private class VisibleAction implements Runnable {
        boolean visible;

        private VisibleAction(boolean visible) {
            this.visible = visible;
        }

        public final void run() {
            setVisibleActionImpl(visible);
        }
    }

    protected void setVisible(boolean wait, boolean visible) {
        if(DEBUG_IMPLEMENTATION) {
            System.err.println("Window setVisible: START ("+getThreadName()+") "+getX()+"/"+getY()+" "+getWidth()+"x"+getHeight()+", fs "+fullscreen+", windowHandle "+toHexString(windowHandle)+", visible: "+this.visible+" -> "+visible+", parentWindowHandle "+toHexString(parentWindowHandle)+", parentWindow "+(null!=parentWindow));
        }
        runOnEDTIfAvail(wait, new VisibleAction(visible));        
    }

    @Override
    public void setVisible(boolean visible) {
        setVisible(true, visible);
    }
    
    private class SetSizeAction implements Runnable {
        int width, height;

        private SetSizeAction(int w, int h) {
            this.width = w;
            this.height = h;
        }

        public final void run() {
            final RecursiveLock _lock = windowLock;
            _lock.lock();
            try {
                if ( !isFullscreen() && ( getWidth() != width || getHeight() != height ) ) {
                    if(DEBUG_IMPLEMENTATION) {
                        System.err.println("Window setSize: START "+getWidth()+"x"+getHeight()+" -> "+width+"x"+height+", fs "+fullscreen+", windowHandle "+toHexString(windowHandle)+", visible "+visible);
                    }
                    int visibleAction; // 0 nop, 1 invisible, 2 visible (create)
                    if ( visible && isNativeValid() && ( 0 >= width || 0 >= height ) ) {
                        visibleAction=1; // invisible
                        defineSize(0, 0);
                    } else if ( visible && !isNativeValid() && 0 < width && 0 < height ) {
                        visibleAction = 2; // visible (create)
                        defineSize(width, height);
                    } else if ( visible && isNativeValid() ) {
                        visibleAction = 0;
                        // this width/height will be set by windowChanged, called by the native implementation
                        reconfigureWindowImpl(getX(), getY(), width, height, getReconfigureFlags(0, isVisible()));
                        WindowImpl.this.waitForSize(width, height, false, TIMEOUT_NATIVEWINDOW);
                    } else {
                        // invisible or invalid w/ 0 size
                        visibleAction = 0;
                        defineSize(width, height);
                    }
                    if(DEBUG_IMPLEMENTATION) {
                        System.err.println("Window setSize: END "+getWidth()+"x"+getHeight()+", visibleAction "+visibleAction);
                    }
                    switch(visibleAction) {
                        case 1: setVisibleActionImpl(false); break;
                        case 2: setVisibleActionImpl(true); break;
                    }
                }
            } finally {
                _lock.unlock();
            }
        }
    }

    @Override
    public void setSize(int width, int height) {
        runOnEDTIfAvail(true, new SetSizeAction(width, height));
    }    
    @Override
    public void setTopLevelSize(int width, int height) {
        setSize(width - getInsets().getTotalWidth(), height - getInsets().getTotalHeight());
    }

    private class DestroyAction implements Runnable {
        public final void run() {
            boolean animatorPaused = false;
            if(null!=lifecycleHook) {
                animatorPaused = lifecycleHook.pauseRenderingAction();
            }
            if(null!=lifecycleHook) {
                lifecycleHook.destroyActionPreLock();
            }
            final RecursiveLock _lock = windowLock;
            _lock.lock();
            try {
                if(DEBUG_IMPLEMENTATION) {
                    System.err.println("Window DestroyAction() hasScreen "+(null != screen)+", isNativeValid "+isNativeValid()+" - "+getThreadName());
                }
                
                // send synced destroy-notify notification
                sendWindowEvent(WindowEvent.EVENT_WINDOW_DESTROY_NOTIFY);
                
                // Childs first ..
                synchronized(childWindowsLock) {
                  if(childWindows.size()>0) {
                    // avoid ConcurrentModificationException: parent -> child -> parent.removeChild(this)
                    @SuppressWarnings("unchecked")
                    ArrayList<NativeWindow> clonedChildWindows = (ArrayList<NativeWindow>) childWindows.clone();
                    while( clonedChildWindows.size() > 0 ) {
                      NativeWindow nw = clonedChildWindows.remove(0);
                      if(nw instanceof WindowImpl) {
                          ((WindowImpl)nw).windowDestroyNotify(true);
                      } else {
                          nw.destroy();
                      }
                    }
                  }
                }

                if(null!=lifecycleHook) {
                    // send synced destroy notification for proper cleanup, eg GLWindow/OpenGL
                    lifecycleHook.destroyActionInLock();
                }

                if( isNativeValid() ) {
                    screen.removeMonitorModeListener(monitorModeListenerImpl);
                    closeNativeImpl();
                    final AbstractGraphicsDevice cfgADevice = config.getScreen().getDevice();
                    if( cfgADevice != screen.getDisplay().getGraphicsDevice() ) { // don't pull display's device
                        cfgADevice.close(); // ensure a cfg's device is closed
                    }
                    setGraphicsConfiguration(null);
                }
                removeScreenReference();
                Display dpy = screen.getDisplay();
                if(null != dpy) {
                    dpy.validateEDT();
                }

                // send synced destroyed notification
                sendWindowEvent(WindowEvent.EVENT_WINDOW_DESTROYED);

                if(DEBUG_IMPLEMENTATION) {
                    System.err.println("Window.destroy() END "+getThreadName()/*+", "+WindowImpl.this*/);
                }
            } finally {
                // update states before release window lock
                setWindowHandle(0);
                visible = false;
                fullscreen = false;
                fullscreenMonitors = null;
                fullscreenUseMainMonitor = true;
                hasFocus = false;
                parentWindowHandle = 0;

                _lock.unlock();
            }
            if(animatorPaused) {
                lifecycleHook.resumeRenderingAction();
            }
            
            // these refs shall be kept alive - resurrection via setVisible(true)
            /**
            if(null!=parentWindow && parentWindow instanceof Window) {
                ((Window)parentWindow).removeChild(WindowImpl.this);
            }        
            childWindows = null;
            surfaceUpdatedListeners = null;
            mouseListeners = null;
            keyListeners = null;
            capsRequested = null;
            lifecycleHook = null;
            
            screen = null;           
            windowListeners = null;
            parentWindow = null;
            */                        
        }
    }
    private final DestroyAction destroyAction = new DestroyAction();

    @Override
    public void destroy() {
        visible = false; // Immediately mark synchronized visibility flag, avoiding possible recreation 
        runOnEDTIfAvail(true, destroyAction);
    }

    protected void destroy(boolean preserveResources) {
        if( preserveResources && null != WindowImpl.this.lifecycleHook ) {
            WindowImpl.this.lifecycleHook.preserveGLStateAtDestroy();
        }
        destroy();
    }
    
    /**
     * @param cWin child window, must not be null
     * @param pWin parent window, may be null
     * @return true if at least one of both window's configurations is offscreen 
     */
    protected static boolean isOffscreenInstance(NativeWindow cWin, NativeWindow pWin) {
        boolean ofs = false;
        final AbstractGraphicsConfiguration cWinCfg = cWin.getGraphicsConfiguration(); 
        if( null != cWinCfg ) {
            ofs = !cWinCfg.getChosenCapabilities().isOnscreen();
        }
        if( !ofs && null != pWin ) {
            final AbstractGraphicsConfiguration pWinCfg = pWin.getGraphicsConfiguration();
            if( null != pWinCfg ) {
                ofs = !pWinCfg.getChosenCapabilities().isOnscreen();
            }
        }
        return ofs;
    }
    
    private class ReparentAction implements Runnable {
        NativeWindow newParentWindow;
        boolean forceDestroyCreate;
        ReparentOperation operation;

        private ReparentAction(NativeWindow newParentWindow, boolean forceDestroyCreate) {
            this.newParentWindow = newParentWindow;
            this.forceDestroyCreate = forceDestroyCreate | DEBUG_TEST_REPARENT_INCOMPATIBLE;
            this.operation = ReparentOperation.ACTION_INVALID; // ensure it's set
        }

        private ReparentOperation getOp() {
            return operation;
        }

        private void setScreen(ScreenImpl newScreen) { // never null !
            removeScreenReference();
            screen = newScreen;
        }
        
        public final void run() {
            boolean animatorPaused = false;
            if(null!=lifecycleHook) {
                animatorPaused = lifecycleHook.pauseRenderingAction();
            }
            reparent();
            if(animatorPaused) {
                lifecycleHook.resumeRenderingAction();
            }
        }
        
        private void reparent() {
            // mirror pos/size so native change notification can get overwritten
            int x = getX();
            int y = getY();
            int width = getWidth();
            int height = getHeight();
            boolean wasVisible;
            
            final RecursiveLock _lock = windowLock;
            _lock.lock();
            try {
                if(isNativeValid()) {
                    // force recreation if offscreen, since it may become onscreen
                    forceDestroyCreate |= isOffscreenInstance(WindowImpl.this, newParentWindow);
                }
                                
                wasVisible = isVisible();

                Window newParentWindowNEWT = null;
                if(newParentWindow instanceof Window) {
                    newParentWindowNEWT = (Window) newParentWindow;
                }

                long newParentWindowHandle = 0 ;

                if(DEBUG_IMPLEMENTATION) {
                    System.err.println("Window.reparent: START ("+getThreadName()+") valid "+isNativeValid()+", windowHandle "+toHexString(windowHandle)+" parentWindowHandle "+toHexString(parentWindowHandle)+", visible "+wasVisible+", old parentWindow: "+Display.hashCodeNullSafe(parentWindow)+", new parentWindow: "+Display.hashCodeNullSafe(newParentWindow)+", forceDestroyCreate "+forceDestroyCreate+", "+x+"/"+y+" "+width+"x"+height);
                }

                if(null!=newParentWindow) {
                    // reset position to 0/0 within parent space
                    x = 0;
                    y = 0;

                    // refit if size is bigger than parent
                    if( width > newParentWindow.getWidth() ) {
                        width = newParentWindow.getWidth();
                    }
                    if( height > newParentWindow.getHeight() ) {
                        height = newParentWindow.getHeight();
                    }

                    // Case: Child Window
                    newParentWindowHandle = getNativeWindowHandle(newParentWindow);
                    if(0 == newParentWindowHandle) {
                        // Case: Parent's native window not realized yet
                        if(null==newParentWindowNEWT) {
                            throw new NativeWindowException("Reparenting with non NEWT Window type only available after it's realized: "+newParentWindow);
                        }
                        // Destroy this window and use parent's Screen.
                        // It may be created properly when the parent is made visible.
                        destroy(false);
                        setScreen( (ScreenImpl) newParentWindowNEWT.getScreen() );
                        operation = ReparentOperation.ACTION_NATIVE_CREATION_PENDING;
                    } else if(newParentWindow != getParent()) {
                        // Case: Parent's native window realized and changed
                        if( !isNativeValid() ) {
                            // May create a new compatible Screen/Display and
                            // mark it for creation.
                            if(null!=newParentWindowNEWT) {
                                setScreen( (ScreenImpl) newParentWindowNEWT.getScreen() );
                            } else {
                                final Screen newScreen = NewtFactory.createCompatibleScreen(newParentWindow, screen);
                                if( screen != newScreen ) {
                                    // auto destroy on-the-fly created Screen/Display
                                    setScreen( (ScreenImpl) newScreen );
                                }
                            }
                            if( 0 < width && 0 < height ) {
                                operation = ReparentOperation.ACTION_NATIVE_CREATION;
                            } else {
                                operation = ReparentOperation.ACTION_NATIVE_CREATION_PENDING;
                            }
                        } else if ( forceDestroyCreate || !NewtFactory.isScreenCompatible(newParentWindow, screen) ) {
                            // Destroy this window, may create a new compatible Screen/Display, while trying to preserve resources if becoming visible again.                            
                            destroy( wasVisible );
                            if(null!=newParentWindowNEWT) {
                                setScreen( (ScreenImpl) newParentWindowNEWT.getScreen() );
                            } else {
                                setScreen( (ScreenImpl) NewtFactory.createCompatibleScreen(newParentWindow, screen) );
                            }
                            operation = ReparentOperation.ACTION_NATIVE_CREATION;
                        } else {
                            // Mark it for native reparenting
                            operation = ReparentOperation.ACTION_NATIVE_REPARENTING;
                        }
                    } else {
                        // Case: Parent's native window realized and not changed
                        operation = ReparentOperation.ACTION_NOP;
                    }
                } else {
                    if( null != parentWindow ) {
                        // child -> top
                        // put client to current parent+child position
                        final Point p = getLocationOnScreen(null);
                        x = p.getX();
                        y = p.getY();
                    }

                    // Case: Top Window
                    if( 0 == parentWindowHandle ) {
                        // Already Top Window
                        operation = ReparentOperation.ACTION_NOP;
                    } else if( !isNativeValid() || forceDestroyCreate ) {
                        // Destroy this window and mark it for [pending] creation.
                        // If isNativeValid() and becoming visible again - try to preserve resources, i.e. b/c on-/offscreen switch.
                        destroy( isNativeValid() && wasVisible );
                        if( 0 < width && 0 < height ) {
                            operation = ReparentOperation.ACTION_NATIVE_CREATION;
                        } else {
                            operation = ReparentOperation.ACTION_NATIVE_CREATION_PENDING;
                        }
                    } else {
                        // Mark it for native reparenting
                        operation = ReparentOperation.ACTION_NATIVE_REPARENTING;
                    }
                }
                parentWindowHandle = newParentWindowHandle;

                if ( ReparentOperation.ACTION_INVALID == operation ) {
                    throw new NativeWindowException("Internal Error: reparentAction not set");
                }

                if( ReparentOperation.ACTION_NOP == operation ) {
                    if(DEBUG_IMPLEMENTATION) {
                        System.err.println("Window.reparent: NO CHANGE ("+getThreadName()+") windowHandle "+toHexString(windowHandle)+" new parentWindowHandle "+toHexString(newParentWindowHandle)+", visible "+wasVisible);
                    }
                    return;
                }

                if(DEBUG_IMPLEMENTATION) {
                    System.err.println("Window.reparent: ACTION ("+getThreadName()+") windowHandle "+toHexString(windowHandle)+" new parentWindowHandle "+toHexString(newParentWindowHandle)+", reparentAction "+operation+", visible "+wasVisible);
                }

                // rearrange window tree
                if(null!=parentWindow && parentWindow instanceof Window) {
                    ((Window)parentWindow).removeChild(WindowImpl.this);
                }
                parentWindow = newParentWindow;
                if(parentWindow instanceof Window) {
                    ((Window)parentWindow).addChild(WindowImpl.this);
                }

                if( ReparentOperation.ACTION_NATIVE_CREATION_PENDING == operation ) {
                    // make size and position persistent for proper recreation
                    definePosition(x, y);
                    defineSize(width, height);
                    return;
                }

                if( ReparentOperation.ACTION_NATIVE_REPARENTING == operation ) {
                    final DisplayImpl display = (DisplayImpl) screen.getDisplay();
                    display.dispatchMessagesNative(); // status up2date

                    if(wasVisible) {
                        setVisibleImpl(false, x, y, width, height);
                        WindowImpl.this.waitForVisible(false, false);
                        // FIXME: Some composite WM behave slacky .. give 'em chance to change state -> invisible,
                        // even though we do exactly that (KDE+Composite)
                        try { Thread.sleep(100); } catch (InterruptedException e) { }
                        display.dispatchMessagesNative(); // status up2date
                    }

                    // Lock parentWindow only during reparenting (attempt)
                    final NativeWindow parentWindowLocked;
                    if( null != parentWindow ) {
                        parentWindowLocked = parentWindow;
                        if( NativeSurface.LOCK_SURFACE_NOT_READY >= parentWindowLocked.lockSurface() ) {
                            throw new NativeWindowException("Parent surface lock: not ready: "+parentWindowLocked);
                        }
                        // update native handle, locked state
                        parentWindowHandle = parentWindowLocked.getWindowHandle();
                    } else {
                        parentWindowLocked = null;
                    }
                    boolean ok = false;
                    try {
                        ok = reconfigureWindowImpl(x, y, width, height, getReconfigureFlags(FLAG_CHANGE_PARENTING | FLAG_CHANGE_DECORATION, isVisible()));
                    } finally {
                        if(null!=parentWindowLocked) {
                            parentWindowLocked.unlockSurface();
                        }
                    }
                    definePosition(x, y); // position might not get updated by WM events (SWT parent apparently)

                    // set visible again
                    if(ok) {
                        display.dispatchMessagesNative(); // status up2date
                        if(wasVisible) {
                            setVisibleImpl(true, x, y, width, height);
                            ok = 0 <= WindowImpl.this.waitForVisible(true, false);
                            if(ok) {
                                ok = WindowImpl.this.waitForSize(width, height, false, TIMEOUT_NATIVEWINDOW);
                            }
                            if(ok) {
                                requestFocusInt(false /* skipFocusAction */);
                                display.dispatchMessagesNative(); // status up2date                                
                            }
                        }
                    }

                    if(!ok || !wasVisible) {
                        // make size and position persistent manual, 
                        // since we don't have a WM feedback (invisible or recreation)
                        definePosition(x, y);
                        defineSize(width, height);
                    }
                    
                    if(!ok) {
                        // native reparent failed -> try creation, while trying to preserve resources if becoming visible again.
                        if(DEBUG_IMPLEMENTATION) {
                            System.err.println("Window.reparent: native reparenting failed ("+getThreadName()+") windowHandle "+toHexString(windowHandle)+" parentWindowHandle "+toHexString(parentWindowHandle)+" -> "+toHexString(newParentWindowHandle)+" - Trying recreation");
                        }
                        destroy( wasVisible );
                        operation = ReparentOperation.ACTION_NATIVE_CREATION ;
                    }
                }
                
                if(DEBUG_IMPLEMENTATION) {
                    System.err.println("Window.reparentWindow: END-1 ("+getThreadName()+") windowHandle "+toHexString(windowHandle)+", visible: "+visible+", parentWindowHandle "+toHexString(parentWindowHandle)+", parentWindow "+ Display.hashCodeNullSafe(parentWindow)+" "+getX()+"/"+getY()+" "+getWidth()+"x"+getHeight());
                }
            } finally {
                if(null!=lifecycleHook) {
                    lifecycleHook.resetCounter();
                }
                _lock.unlock();
            }
            if(wasVisible) {
                switch (operation) {
                    case ACTION_NATIVE_REPARENTING:
                        // trigger a resize/relayout and repaint to listener
                        sendWindowEvent(WindowEvent.EVENT_WINDOW_RESIZED);
                        break;

                    case ACTION_NATIVE_CREATION:
                        // This may run on the new Display/Screen connection, hence a new EDT task
                        runOnEDTIfAvail(true, reparentActionRecreate);
                        break;
                        
                    default:
                }
            }
            if(DEBUG_IMPLEMENTATION) {
                System.err.println("Window.reparentWindow: END-X ("+getThreadName()+") windowHandle "+toHexString(windowHandle)+", visible: "+visible+", parentWindowHandle "+toHexString(parentWindowHandle)+", parentWindow "+ Display.hashCodeNullSafe(parentWindow)+" "+getX()+"/"+getY()+" "+getWidth()+"x"+getHeight());
            }
        }
    }

    private class ReparentActionRecreate implements Runnable {
        public final void run() {
            final RecursiveLock _lock = windowLock;
            _lock.lock();
            try {
                if(DEBUG_IMPLEMENTATION) {
                    System.err.println("Window.reparentWindow: ReparentActionRecreate ("+getThreadName()+") windowHandle "+toHexString(windowHandle)+", visible: "+visible+", parentWindowHandle "+toHexString(parentWindowHandle)+", parentWindow "+Display.hashCodeNullSafe(parentWindow));
                }
                setVisible(true); // native creation
            } finally {
                _lock.unlock();
            }
        }
    }
    private final ReparentActionRecreate reparentActionRecreate = new ReparentActionRecreate();

    @Override
    public final ReparentOperation reparentWindow(NativeWindow newParent) {
        return reparentWindow(newParent, false);
    }

    public ReparentOperation reparentWindow(NativeWindow newParent, boolean forceDestroyCreate) {
        final ReparentAction reparentAction = new ReparentAction(newParent, forceDestroyCreate);
        runOnEDTIfAvail(true, reparentAction);
        return reparentAction.getOp();
    }

    @Override
    public CapabilitiesChooser setCapabilitiesChooser(CapabilitiesChooser chooser) {
        CapabilitiesChooser old = this.capabilitiesChooser;
        this.capabilitiesChooser = chooser;
        return old;
    }

    @Override
    public final CapabilitiesImmutable getChosenCapabilities() {
        return getGraphicsConfiguration().getChosenCapabilities();
    }

    @Override
    public final CapabilitiesImmutable getRequestedCapabilities() {
        return capsRequested;
    }

    private class DecorationAction implements Runnable {
        boolean undecorated;

        private DecorationAction(boolean undecorated) {
            this.undecorated = undecorated;
        }

        public final void run() {
            final RecursiveLock _lock = windowLock;
            _lock.lock();
            try {
                if(WindowImpl.this.undecorated != undecorated) {
                    // set current state
                    WindowImpl.this.undecorated = undecorated;

                    if( isNativeValid() && !isFullscreen() ) {
                        // Mirror pos/size so native change notification can get overwritten
                        final int x = getX();
                        final int y = getY();
                        final int width = getWidth();
                        final int height = getHeight();

                        DisplayImpl display = (DisplayImpl) screen.getDisplay();
                        display.dispatchMessagesNative(); // status up2date
                        reconfigureWindowImpl(x, y, width, height, getReconfigureFlags(FLAG_CHANGE_DECORATION, isVisible()));
                        display.dispatchMessagesNative(); // status up2date
                    }
                }
            } finally {
                _lock.unlock();
            }
            sendWindowEvent(WindowEvent.EVENT_WINDOW_RESIZED); // trigger a resize/relayout and repaint to listener
        }
    }

    @Override
    public void setUndecorated(boolean value) {
        runOnEDTIfAvail(true, new DecorationAction(value));
    }

    @Override
    public final boolean isUndecorated() {
        return 0 != parentWindowHandle || undecorated || fullscreen ;
    }

    private class AlwaysOnTopAction implements Runnable {
        boolean alwaysOnTop;

        private AlwaysOnTopAction(boolean alwaysOnTop) {
            this.alwaysOnTop = alwaysOnTop;
        }

        public final void run() {
            final RecursiveLock _lock = windowLock;
            _lock.lock();
            try {
                if(WindowImpl.this.alwaysOnTop != alwaysOnTop) {
                    // set current state
                    WindowImpl.this.alwaysOnTop = alwaysOnTop;
                  
                    if( isNativeValid() ) {
                        // Mirror pos/size so native change notification can get overwritten
                        final int x = getX();
                        final int y = getY();
                        final int width = getWidth();
                        final int height = getHeight();

                        DisplayImpl display = (DisplayImpl) screen.getDisplay();
                        display.dispatchMessagesNative(); // status up2date
                        reconfigureWindowImpl(x, y, width, height, getReconfigureFlags(FLAG_CHANGE_ALWAYSONTOP, isVisible()));
                        display.dispatchMessagesNative(); // status up2date
                    }
                }
            } finally {
                _lock.unlock();
            }
            sendWindowEvent(WindowEvent.EVENT_WINDOW_RESIZED); // trigger a resize/relayout and repaint to listener
        }
    }

    @Override
    public final void setAlwaysOnTop(boolean value) {
        runOnEDTIfAvail(true, new AlwaysOnTopAction(value));
    }
    
    @Override
    public final boolean isAlwaysOnTop() {
        return alwaysOnTop;
    }
        
    @Override
    public String getTitle() {
        return title;
    }
    @Override
    public void setTitle(String title) {
        if (title == null) {
            title = "";
        }
        this.title = title;
        if(0 != getWindowHandle()) {
            setTitleImpl(title);
        }
    }

    @Override
    public boolean isPointerVisible() {
        return pointerVisible;
    }
    @Override
    public void setPointerVisible(boolean pointerVisible) {
        if(this.pointerVisible != pointerVisible) {
            boolean setVal = 0 == getWindowHandle();
            if(!setVal) {
                setVal = setPointerVisibleImpl(pointerVisible);
            }
            if(setVal) {
                this.pointerVisible = pointerVisible;                
            }
        }
    }
    @Override
    public boolean isPointerConfined() {
        return pointerConfined;
    }
    
    @Override
    public void confinePointer(boolean confine) {
        if(this.pointerConfined != confine) {
            boolean setVal = 0 == getWindowHandle();
            if(!setVal) {
                if(confine) {
                    requestFocus();
                    warpPointer(getWidth()/2, getHeight()/2);
                }
                setVal = confinePointerImpl(confine);
                if(confine) {
                    // give time to deliver mouse movements w/o confinement,
                    // this allows user listener to sync previous position value to the new centered position
                    try {
                        Thread.sleep(3 * screen.getDisplay().getEDTUtil().getPollPeriod());
                    } catch (InterruptedException e) { }
                }
            }
            if(setVal) {
                this.pointerConfined = confine;       
            }
        }        
    }
    
    @Override
    public void warpPointer(int x, int y) {
        if(0 != getWindowHandle()) {
            warpPointerImpl(x, y);
        }
    }
    
    @Override
    public final InsetsImmutable getInsets() {
        if(isUndecorated()) {
            return Insets.getZero();
        }
        updateInsetsImpl(insets);
        return insets;
    }
    
    @Override
    public final int getWidth() {
        return width;
    }

    @Override
    public final int getHeight() {
        return height;
    }

    @Override
    public final int getX() {
        return x;
    }

    @Override
    public final int getY() {
        return y;
    }

    protected final boolean autoPosition() { return autoPosition; }
    
    /** Sets the position fields {@link #x} and {@link #y} to the given values and {@link #autoPosition} to false. */ 
    protected final void definePosition(int x, int y) {
        if(DEBUG_IMPLEMENTATION) {
            System.err.println("definePosition: "+this.x+"/"+this.y+" -> "+x+"/"+y);
            // Thread.dumpStack();
        }
        autoPosition = false;
        this.x = x; this.y = y;
    }

    /** Sets the size fields {@link #width} and {@link #height} to the given values. */ 
    protected final void defineSize(int width, int height) {
        if(DEBUG_IMPLEMENTATION) {
            System.err.println("defineSize: "+this.width+"x"+this.height+" -> "+width+"x"+height);
            // Thread.dumpStack();
        }
        this.width = width; this.height = height;
    }
    
    @Override
    public final boolean isVisible() {
        return visible;
    }

    @Override
    public final boolean isFullscreen() {
        return fullscreen;
    }

    //----------------------------------------------------------------------
    // Window
    //

    @Override
    public final Window getDelegatedWindow() {
        return this;
    }
    
    //----------------------------------------------------------------------
    // WindowImpl
    //

    /**
     * If the implementation is capable of detecting a device change
     * return true and clear the status/reason of the change.
     */
    public boolean hasDeviceChanged() {
        return false;
    }

    public LifecycleHook getLifecycleHook() {
        return lifecycleHook;
    }

    public LifecycleHook setLifecycleHook(LifecycleHook hook) {
        LifecycleHook old = lifecycleHook;
        lifecycleHook = hook;
        return old;
    }

    /** 
     * If this Window actually wraps a {@link NativeSurface} from another instance or toolkit, 
     * it will return such reference. Otherwise returns null.
     */
    public NativeSurface getWrappedSurface() {
        return null;
    }

    @Override
    public void setWindowDestroyNotifyAction(Runnable r) {
        windowDestroyNotifyAction = r;
    }

    /** 
     * Returns the non delegated {@link AbstractGraphicsConfiguration}, 
     * see {@link #getGraphicsConfiguration()}. */
    public final AbstractGraphicsConfiguration getPrivateGraphicsConfiguration() {
        return config;
    }
    
    protected final long getParentWindowHandle() {
        return isFullscreen() ? 0 : parentWindowHandle;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        sb.append(getClass().getName()+"[Config "+config+
                    "\n, "+screen+
                    "\n, ParentWindow "+parentWindow+
                    "\n, ParentWindowHandle "+toHexString(parentWindowHandle)+" ("+(0!=getParentWindowHandle())+")"+
                    "\n, WindowHandle "+toHexString(getWindowHandle())+
                    "\n, SurfaceHandle "+toHexString(getSurfaceHandle())+ " (lockedExt window "+windowLock.isLockedByOtherThread()+", surface "+isSurfaceLockedByOtherThread()+")"+
                    "\n, Pos "+getX()+"/"+getY()+" (auto "+autoPosition()+"), size "+getWidth()+"x"+getHeight()+
                    "\n, Visible "+isVisible()+", focus "+hasFocus()+
                    "\n, Undecorated "+undecorated+" ("+isUndecorated()+")"+
                    "\n, AlwaysOnTop "+alwaysOnTop+", Fullscreen "+fullscreen+
                    "\n, WrappedSurface "+getWrappedSurface()+
                    "\n, ChildWindows "+childWindows.size());

        sb.append(", SurfaceUpdatedListeners num "+surfaceUpdatedHelper.size()+" [");
        for (int i = 0; i < surfaceUpdatedHelper.size(); i++ ) {
          sb.append(surfaceUpdatedHelper.get(i)+", ");
        }
        sb.append("], WindowListeners num "+windowListeners.size()+" [");
        for (int i = 0; i < windowListeners.size(); i++ ) {
          sb.append(windowListeners.get(i)+", ");
        }
        sb.append("], MouseListeners num "+mouseListeners.size()+" [");
        for (int i = 0; i < mouseListeners.size(); i++ ) {
          sb.append(mouseListeners.get(i)+", ");
        }
        sb.append("], KeyListeners num "+keyListeners.size()+" [");
        for (int i = 0; i < keyListeners.size(); i++ ) {
          sb.append(keyListeners.get(i)+", ");
        }
        sb.append("], windowLock "+windowLock+", surfaceLockCount "+surfaceLockCount+"]");
        return sb.toString();
    }

    protected final void setWindowHandle(long handle) {
        windowHandle = handle;
    }

    @Override
    public void runOnEDTIfAvail(boolean wait, final Runnable task) {
        if( windowLock.isOwner( Thread.currentThread() ) ) {
            task.run();
        } else {
            ( (DisplayImpl) screen.getDisplay() ).runOnEDTIfAvail(wait, task);
        }
    }

    private final Runnable requestFocusAction = new Runnable() {
        public final void run() {
            if(DEBUG_IMPLEMENTATION) {
                System.err.println("Window.RequestFocusAction: force 0 - ("+getThreadName()+"): "+hasFocus+" -> true - windowHandle "+toHexString(windowHandle)+" parentWindowHandle "+toHexString(parentWindowHandle));
            }
            WindowImpl.this.requestFocusImpl(false);
        }
    };
    private final Runnable requestFocusActionForced = new Runnable() {
        public final void run() {
            if(DEBUG_IMPLEMENTATION) {
                System.err.println("Window.RequestFocusAction: force 1 - ("+getThreadName()+"): "+hasFocus+" -> true - windowHandle "+toHexString(windowHandle)+" parentWindowHandle "+toHexString(parentWindowHandle));
            }
            WindowImpl.this.requestFocusImpl(true);
        }
    };

    @Override
    public final boolean hasFocus() {
        return hasFocus;
    }

    @Override
    public void requestFocus() {
        requestFocus(true);
    }

    @Override
    public void requestFocus(boolean wait) {
        requestFocus(wait /* wait */, false /* skipFocusAction */, brokenFocusChange /* force */);
    }
    
    private void requestFocus(boolean wait, boolean skipFocusAction, boolean force) {
        if( isNativeValid() &&
            ( force || !hasFocus() ) &&
            ( skipFocusAction || !focusAction() ) ) {
            runOnEDTIfAvail(wait, force ? requestFocusActionForced : requestFocusAction);
        }
    }
    
    /** Internally forcing request focus on current thread */
    private void requestFocusInt(boolean skipFocusAction) {
        if( skipFocusAction || !focusAction() ) {
            if(DEBUG_IMPLEMENTATION) {
                System.err.println("Window.RequestFocusInt: forcing - ("+getThreadName()+"): "+hasFocus+" -> true - windowHandle "+toHexString(windowHandle)+" parentWindowHandle "+toHexString(parentWindowHandle));
            }
            requestFocusImpl(true);
        }        
    }
    
    @Override
    public void setFocusAction(FocusRunnable focusAction) {
        this.focusAction = focusAction;
    }
    
    private boolean focusAction() {
        if(DEBUG_IMPLEMENTATION) {
            System.err.println("Window.focusAction() START - "+getThreadName()+", focusAction: "+focusAction+" - windowHandle "+toHexString(getWindowHandle()));
        }
        boolean res;
        if(null!=focusAction) {
            res = focusAction.run();
        } else {
            res = false;
        }
        if(DEBUG_IMPLEMENTATION) {
            System.err.println("Window.focusAction() END - "+getThreadName()+", focusAction: "+focusAction+" - windowHandle "+toHexString(getWindowHandle())+", res: "+res);
        }
        return res;
    }
    
    protected void setBrokenFocusChange(boolean v) {
        brokenFocusChange = v;
    }
    
    @Override
    public void setKeyboardFocusHandler(KeyListener l) {
        keyboardFocusHandler = l;
    }
    
    private class SetPositionAction implements Runnable {
        int x, y;

        private SetPositionAction(int x, int y) {
            this.x = x;
            this.y = y;
        }

        public final void run() {
            final RecursiveLock _lock = windowLock;
            _lock.lock();
            try {
                if(DEBUG_IMPLEMENTATION) {
                    System.err.println("Window setPosition: "+getX()+"/"+getY()+" -> "+x+"/"+y+", fs "+fullscreen+", windowHandle "+toHexString(windowHandle));
                }
                if ( !isFullscreen() && ( getX() != x || getY() != y ) ) {
                    if(isNativeValid()) {
                        // this.x/this.y will be set by sizeChanged, triggered by windowing event system
                        reconfigureWindowImpl(x, y, getWidth(), getHeight(), getReconfigureFlags(0, isVisible()));
                        
                        // Wait until custom position is reached within tolerances
                        waitForPosition(true, x, y, Window.TIMEOUT_NATIVEWINDOW);
                    } else {
                        definePosition(x, y); // set pos for createNative(..)
                    }
                }
            } finally {
                _lock.unlock();
            }
        }
    }

    @Override
    public void setPosition(int x, int y) {
        autoPosition = false;
        runOnEDTIfAvail(true, new SetPositionAction(x, y));
    }
    
    @Override
    public void setTopLevelPosition(int x, int y) {
        setPosition(x + getInsets().getLeftWidth(), y + getInsets().getTopHeight());
    }
    
    private class FullScreenAction implements Runnable {
        boolean fullscreen;
        List<MonitorDevice> monitors;
        boolean useMainMonitor;

        private boolean init(boolean fullscreen, boolean useMainMonitor, List<MonitorDevice> monitors) {            
            if(isNativeValid()) {
                this.fullscreen = fullscreen;
                if( isFullscreen() != fullscreen ) {
                    this.monitors = monitors;
                    this.useMainMonitor = useMainMonitor;
                    return true;
                } else {
                    this.monitors = null;
                    this.useMainMonitor = true;
                    return false;
                }
            } else {
                WindowImpl.this.fullscreen = fullscreen; // set current state for createNative(..)
                WindowImpl.this.fullscreenMonitors = monitors;
                WindowImpl.this.fullscreenUseMainMonitor = useMainMonitor;
                return false;
            }
        }                
        public boolean fsOn() { return fullscreen; }

        public final void run() {
            final RecursiveLock _lock = windowLock;
            _lock.lock();
            try {
                // set current state
                WindowImpl.this.fullscreen = fullscreen;

                int x,y,w,h;
                
                final RectangleImmutable viewport; 
                final int fs_span_flag;
                if(fullscreen) {
                    if( null == monitors ) {
                        if( useMainMonitor ) {
                            monitors = new ArrayList<MonitorDevice>();
                            monitors.add( getMainMonitor() );
                        } else {
                            monitors = getScreen().getMonitorDevices();
                        }
                    }
                    fs_span_flag = monitors.size() > 1 ? FLAG_IS_FULLSCREEN_SPAN : 0 ;
                    viewport = MonitorDevice.unionOfViewports(new Rectangle(), monitors);
                    nfs_x = getX();
                    nfs_y = getY();
                    nfs_width = getWidth();
                    nfs_height = getHeight();
                    x = viewport.getX(); 
                    y = viewport.getY();
                    w = viewport.getWidth();
                    h = viewport.getHeight();
                } else {
                    fs_span_flag = 0;
                    viewport = null;
                    x = nfs_x;
                    y = nfs_y;
                    w = nfs_width;
                    h = nfs_height;
                    
                    if(null!=parentWindow) {
                        // reset position to 0/0 within parent space
                        x = 0;
                        y = 0;
    
                        // refit if size is bigger than parent
                        if( w > parentWindow.getWidth() ) {
                            w = parentWindow.getWidth();
                        }
                        if( h > parentWindow.getHeight() ) {
                            h = parentWindow.getHeight();
                        }
                    }
                }
                monitors = null; // clear references ASAP
                useMainMonitor = true;
                if(DEBUG_IMPLEMENTATION) {
                    System.err.println("Window fs: "+fullscreen+" "+x+"/"+y+" "+w+"x"+h+", "+isUndecorated()+
                                       ", virtl-size: "+screen.getWidth()+"x"+screen.getHeight()+", monitorsViewport "+viewport);
                }

                DisplayImpl display = (DisplayImpl) screen.getDisplay();
                display.dispatchMessagesNative(); // status up2date
                boolean wasVisible = isVisible();
                
                // Lock parentWindow only during reparenting (attempt)
                final NativeWindow parentWindowLocked;
                if( null != parentWindow ) {
                    parentWindowLocked = parentWindow;
                    if( NativeSurface.LOCK_SURFACE_NOT_READY >= parentWindowLocked.lockSurface() ) {
                        throw new NativeWindowException("Parent surface lock: not ready: "+parentWindow);
                    }
                } else {
                    parentWindowLocked = null;
                }
                try {
                    reconfigureWindowImpl(x, y, w, h, 
                                          getReconfigureFlags( ( ( null != parentWindowLocked ) ? FLAG_CHANGE_PARENTING : 0 ) | 
                                                               fs_span_flag | FLAG_CHANGE_FULLSCREEN | FLAG_CHANGE_DECORATION, wasVisible) );
                } finally {
                    if(null!=parentWindowLocked) {
                        parentWindowLocked.unlockSurface();
                    }
                }
                display.dispatchMessagesNative(); // status up2date
                
                if(wasVisible) {
                    setVisibleImpl(true, x, y, w, h);
                    WindowImpl.this.waitForVisible(true, false);
                    display.dispatchMessagesNative(); // status up2date                                                        
                    WindowImpl.this.waitForSize(w, h, false, TIMEOUT_NATIVEWINDOW);
                    display.dispatchMessagesNative(); // status up2date                                                        
                    
                    if(DEBUG_IMPLEMENTATION) {
                        System.err.println("Window fs done: " + WindowImpl.this);
                    }
                }
            } finally {
                _lock.unlock();
            }
            sendWindowEvent(WindowEvent.EVENT_WINDOW_RESIZED); // trigger a resize/relayout and repaint to listener
        }
    }
    private final FullScreenAction fullScreenAction = new FullScreenAction();

    @Override
    public boolean setFullscreen(boolean fullscreen) {
        return setFullscreenImpl(fullscreen, true, null);
    }
    
    @Override
    public boolean setFullscreen(List<MonitorDevice> monitors) {
        return setFullscreenImpl(true, false, monitors);
    }
    
    private boolean setFullscreenImpl(boolean fullscreen, boolean useMainMonitor, List<MonitorDevice> monitors) {
        synchronized(fullScreenAction) {
            if( fullScreenAction.init(fullscreen, useMainMonitor, monitors) ) {               
                if(fullScreenAction.fsOn() && isOffscreenInstance(WindowImpl.this, parentWindow)) { 
                    // enable fullscreen on offscreen instance
                    if(null != parentWindow) {
                        nfs_parent = parentWindow;
                        reparentWindow(null, true);
                    } else {
                        throw new InternalError("Offscreen instance w/o parent unhandled");
                    }
                }
                
                runOnEDTIfAvail(true, fullScreenAction);
                
                if(!fullScreenAction.fsOn() && null != nfs_parent) {
                    // disable fullscreen on offscreen instance
                    reparentWindow(nfs_parent, true);
                    nfs_parent = null;
                }
                
                if(isVisible()) {         
                    requestFocus(true /* wait */, this.fullscreen /* skipFocusAction */, true /* force */);
                }
            }
            return this.fullscreen;                
        }
    }
    
    private class MonitorModeListenerImpl implements MonitorModeListener {
        boolean animatorPaused = false;

        public void monitorModeChangeNotify(MonitorEvent me) {
            if(DEBUG_IMPLEMENTATION) {
                System.err.println("Window.monitorModeChangeNotify: "+me);
            }

            if(null!=lifecycleHook) {
                animatorPaused = lifecycleHook.pauseRenderingAction();
            }
        }

        public void monitorModeChanged(MonitorEvent me, boolean success) {
            if(DEBUG_IMPLEMENTATION) {
                System.err.println("Window.monitorModeChanged: "+me+", success: "+success);
            }

            if(success) {
                if(!animatorPaused && null!=lifecycleHook) {
                    // Didn't pass above notify method. probably detected screen change after it happened.
                    animatorPaused = lifecycleHook.pauseRenderingAction();
                }
                if( !fullscreen ) {
                    // FIXME: Need to take all covered monitors into account
                    final MonitorDevice mainMonitor = getMainMonitor();
                    final MonitorDevice eventMonitor = me.getMonitor();
                    if( mainMonitor == eventMonitor ) {
                        final RectangleImmutable rect = new Rectangle(getX(), getY(), getWidth(), getHeight());
                        final RectangleImmutable viewport = mainMonitor.getViewport();
                        final RectangleImmutable isect = viewport.intersection(rect);
                        if ( getHeight() > isect.getHeight()  ||
                             getWidth() > isect.getWidth() ) {
                            setSize(isect.getWidth(), isect.getHeight());
                        }
                    }
                }
            }

            if(animatorPaused) {
                lifecycleHook.resumeRenderingAction();
            }
            sendWindowEvent(WindowEvent.EVENT_WINDOW_RESIZED); // trigger a resize/relayout and repaint to listener
        }
    }
    private final MonitorModeListenerImpl monitorModeListenerImpl = new MonitorModeListenerImpl();



    //----------------------------------------------------------------------
    // Child Window Management
    // 

    @Override
    public final boolean removeChild(NativeWindow win) {
        synchronized(childWindowsLock) {
            return childWindows.remove(win);
        }
    }

    @Override
    public final boolean addChild(NativeWindow win) {
        if (win == null) {
            return false;
        }
        synchronized(childWindowsLock) {
            return childWindows.add(win);
        }
    }

    //----------------------------------------------------------------------
    // Generic Event Support
    //
    private void doEvent(boolean enqueue, boolean wait, com.jogamp.newt.event.NEWTEvent event) {
        boolean done = false;

        if(!enqueue) {
            done = consumeEvent(event);
            wait = done; // don't wait if event can't be consumed now
        }

        if(!done) {
            enqueueEvent(wait, event);
        }
    }

    @Override
    public void enqueueEvent(boolean wait, com.jogamp.newt.event.NEWTEvent event) {
        if(isNativeValid()) {
            ((DisplayImpl)screen.getDisplay()).enqueueEvent(wait, event);
        }
    }

    @Override
    public boolean consumeEvent(NEWTEvent e) {
        switch(e.getEventType()) {
            // special repaint treatment
            case WindowEvent.EVENT_WINDOW_REPAINT:
                // queue repaint event in case window is locked, ie in operation
                if( null != windowLock.getOwner() ) {
                    // make sure only one repaint event is queued
                    if(!repaintQueued) {
                        repaintQueued=true;
                        final boolean discardTO = QUEUED_EVENT_TO <= System.currentTimeMillis()-e.getWhen();
                        if(DEBUG_IMPLEMENTATION) {
                            System.err.println("Window.consumeEvent: REPAINT "+Thread.currentThread().getName()+" - queued "+e+", discard-to "+discardTO);
                            // Thread.dumpStack();
                        }                                                
                        return discardTO; // discardTO:=true -> consumed
                    }
                    return true;
                }
                repaintQueued=false; // no repaint event queued
                break;

            // common treatment
            case WindowEvent.EVENT_WINDOW_RESIZED:
                // queue event in case window is locked, ie in operation
                if( null != windowLock.getOwner() ) {
                    final boolean discardTO = QUEUED_EVENT_TO <= System.currentTimeMillis()-e.getWhen();
                    if(DEBUG_IMPLEMENTATION) {
                        System.err.println("Window.consumeEvent: RESIZED "+Thread.currentThread().getName()+" - queued "+e+", discard-to "+discardTO);
                        // Thread.dumpStack();
                    }
                    return discardTO; // discardTO:=true -> consumed
                }
                break;
            default:
                break;
        }
        if(e instanceof WindowEvent) {
            consumeWindowEvent((WindowEvent)e);
        } else if(e instanceof KeyEvent) {
            consumeKeyEvent((KeyEvent)e);
        } else if(e instanceof MouseEvent) {
            consumeMouseEvent((MouseEvent)e);
        } else {
            throw new NativeWindowException("Unexpected NEWTEvent type " + e);
        }
        return true;
    }

    //
    // SurfaceUpdatedListener Support
    //
    @Override
    public void addSurfaceUpdatedListener(SurfaceUpdatedListener l) {
        surfaceUpdatedHelper.addSurfaceUpdatedListener(l);
    }

    @Override
    public void addSurfaceUpdatedListener(int index, SurfaceUpdatedListener l) throws IndexOutOfBoundsException {
        surfaceUpdatedHelper.addSurfaceUpdatedListener(index, l);
    }

    @Override
    public void removeSurfaceUpdatedListener(SurfaceUpdatedListener l) {
        surfaceUpdatedHelper.removeSurfaceUpdatedListener(l);
    }

    @Override
    public void surfaceUpdated(Object updater, NativeSurface ns, long when) {
        surfaceUpdatedHelper.surfaceUpdated(updater, ns, when);
    }

    //
    // MouseListener/Event Support
    //
    public void sendMouseEvent(short eventType, int modifiers,
                               int x, int y, short button, float rotation) {
        doMouseEvent(false, false, eventType, modifiers, x, y, button, rotation);
    }
    public void enqueueMouseEvent(boolean wait, short eventType, int modifiers,
                                  int x, int y, short button, float rotation) {
        doMouseEvent(true, wait, eventType, modifiers, x, y, button, rotation);
    }
    
    protected void doMouseEvent(boolean enqueue, boolean wait, short eventType, int modifiers,
                                int x, int y, short button, float rotation) {
        if( eventType == MouseEvent.EVENT_MOUSE_ENTERED || eventType == MouseEvent.EVENT_MOUSE_EXITED ) {
            if( eventType == MouseEvent.EVENT_MOUSE_EXITED && x==-1 && y==-1 ) {
                x = lastMousePosition.getX();
                y = lastMousePosition.getY();
            }
            // clip coordinates to window dimension
            x = Math.min(Math.max(x,  0), getWidth()-1);
            y = Math.min(Math.max(y,  0), getHeight()-1);
            mouseInWindow = eventType == MouseEvent.EVENT_MOUSE_ENTERED;
            // clear states
            lastMousePressed = 0;
            lastMouseClickCount = (short)0; 
            mouseButtonPressed = 0;
            mouseButtonModMask = 0;
        }
        if( x < 0 || y < 0 || x >= getWidth() || y >= getHeight() ) {
            return; // .. invalid ..
        }
        if(DEBUG_MOUSE_EVENT) {
            System.err.println("doMouseEvent: enqueue "+enqueue+", wait "+wait+", "+MouseEvent.getEventTypeString(eventType)+
                               ", mod "+modifiers+", pos "+x+"/"+y+", button "+button+", lastMousePosition: "+lastMousePosition);
        }
        final long when = System.currentTimeMillis();
        MouseEvent eEntered = null;
        if(eventType == MouseEvent.EVENT_MOUSE_MOVED) {
            if(!mouseInWindow) {
                mouseInWindow = true;
                eEntered = new MouseEvent(MouseEvent.EVENT_MOUSE_ENTERED, this, when,
                                          modifiers, x, y, (short)0, (short)0, (short)0);
                // clear states
                lastMousePressed = 0;
                lastMouseClickCount = (short)0; 
                mouseButtonPressed = 0;
                mouseButtonModMask = 0;
            } else if( lastMousePosition.getX() == x && lastMousePosition.getY()==y ) { 
                if(DEBUG_MOUSE_EVENT) {
                    System.err.println("doMouseEvent: skip EVENT_MOUSE_MOVED w/ same position: "+lastMousePosition);
                }
                return; // skip same position
            }
            lastMousePosition.setX(x);
            lastMousePosition.setY(y);
        }
        if( 0 > button || button > MouseEvent.BUTTON_NUMBER ) {
            throw new NativeWindowException("Invalid mouse button number" + button);
        }
        modifiers |= InputEvent.getButtonMask(button); // Always add current button to modifier mask (Bug 571)
        modifiers |= mouseButtonModMask; // Always add currently pressed mouse buttons to modifier mask

        MouseEvent eClicked = null;
        MouseEvent e = null;

        if( isPointerConfined() ) {
            modifiers |= InputEvent.CONFINED_MASK;
        }
        if( !isPointerVisible() ) {
            modifiers |= InputEvent.INVISIBLE_MASK;
        }
        
        if( MouseEvent.EVENT_MOUSE_PRESSED == eventType ) {
            if( when - lastMousePressed < MouseEvent.getClickTimeout() ) {
                lastMouseClickCount++;
            } else {
                lastMouseClickCount=(short)1;
            }
            lastMousePressed = when;
            mouseButtonPressed = button;
            mouseButtonModMask |= MouseEvent.getButtonMask(button);
            e = new MouseEvent(eventType, this, when,
                               modifiers, x, y, lastMouseClickCount, button, 0);
        } else if( MouseEvent.EVENT_MOUSE_RELEASED == eventType ) {
            e = new MouseEvent(eventType, this, when,
                               modifiers, x, y, lastMouseClickCount, button, 0);
            if( when - lastMousePressed < MouseEvent.getClickTimeout() ) {
                eClicked = new MouseEvent(MouseEvent.EVENT_MOUSE_CLICKED, this, when,
                                          modifiers, x, y, lastMouseClickCount, button, 0);
            } else {
                lastMouseClickCount = (short)0;
                lastMousePressed = 0;
            }
            mouseButtonPressed = 0;
            mouseButtonModMask &= ~MouseEvent.getButtonMask(button);
        } else if( MouseEvent.EVENT_MOUSE_MOVED == eventType ) {
            if ( mouseButtonPressed > 0 ) {
                e = new MouseEvent(MouseEvent.EVENT_MOUSE_DRAGGED, this, when,
                                   modifiers, x, y, (short)1, mouseButtonPressed, 0);
            } else {
                e = new MouseEvent(eventType, this, when,
                                   modifiers, x, y, (short)0, button, (short)0);
            }
        } else if( MouseEvent.EVENT_MOUSE_WHEEL_MOVED == eventType ) {
            e = new MouseEvent(eventType, this, when, modifiers, x, y, (short)0, button, rotation);
        } else {
            e = new MouseEvent(eventType, this, when, modifiers, x, y, (short)0, button, 0);
        }
        if( null != eEntered ) {
            if(DEBUG_MOUSE_EVENT) {
                System.err.println("doMouseEvent: synthesized MOUSE_ENTERED event: "+eEntered);
            }
            doEvent(enqueue, wait, eEntered);
        }
        doEvent(enqueue, wait, e); // actual mouse event
        if( null != eClicked ) {
            if(DEBUG_MOUSE_EVENT) {
                System.err.println("doMouseEvent: synthesized MOUSE_CLICKED event: "+eClicked);
            }
            doEvent(enqueue, wait, eClicked);
        }
    }

    @Override
    public void addMouseListener(MouseListener l) {
        addMouseListener(-1, l);
    }

    @Override
    public void addMouseListener(int index, MouseListener l) {
        if(l == null) {
            return;
        }
        @SuppressWarnings("unchecked")
        ArrayList<MouseListener> clonedListeners = (ArrayList<MouseListener>) mouseListeners.clone();
        if(0>index) { 
            index = clonedListeners.size(); 
        }
        clonedListeners.add(index, l);
        mouseListeners = clonedListeners;
    }

    @Override
    public void removeMouseListener(MouseListener l) {
        if (l == null) {
            return;
        }
        @SuppressWarnings("unchecked")
        ArrayList<MouseListener> clonedListeners = (ArrayList<MouseListener>) mouseListeners.clone();
        clonedListeners.remove(l);
        mouseListeners = clonedListeners;
    }

    @Override
    public MouseListener getMouseListener(int index) {
        @SuppressWarnings("unchecked")
        ArrayList<MouseListener> clonedListeners = (ArrayList<MouseListener>) mouseListeners.clone();
        if(0>index) { 
            index = clonedListeners.size()-1; 
        }
        return clonedListeners.get(index);
    }

    @Override
    public MouseListener[] getMouseListeners() {
        return mouseListeners.toArray(new MouseListener[mouseListeners.size()]);
    }

    protected void consumeMouseEvent(MouseEvent e) {
        if(DEBUG_MOUSE_EVENT) {
            System.err.println("consumeMouseEvent: event:         "+e);
        }
        for(int i = 0; !e.isConsumed() && i < mouseListeners.size(); i++ ) {
            MouseListener l = mouseListeners.get(i);
            switch(e.getEventType()) {
                case MouseEvent.EVENT_MOUSE_CLICKED:
                    l.mouseClicked(e);
                    break;
                case MouseEvent.EVENT_MOUSE_ENTERED:
                    l.mouseEntered(e);
                    break;
                case MouseEvent.EVENT_MOUSE_EXITED:
                    l.mouseExited(e);
                    break;
                case MouseEvent.EVENT_MOUSE_PRESSED:
                    l.mousePressed(e);
                    break;
                case MouseEvent.EVENT_MOUSE_RELEASED:
                    l.mouseReleased(e);
                    break;
                case MouseEvent.EVENT_MOUSE_MOVED:
                    l.mouseMoved(e);
                    break;
                case MouseEvent.EVENT_MOUSE_DRAGGED:
                    l.mouseDragged(e);
                    break;
                case MouseEvent.EVENT_MOUSE_WHEEL_MOVED:
                    l.mouseWheelMoved(e);
                    break;
                default:
                    throw new NativeWindowException("Unexpected mouse event type " + e.getEventType());
            }
        }
    }

    //
    // KeyListener/Event Support
    //
    private static final int keyTrackingRange = 255;
    private final IntBitfield keyPressedState = new IntBitfield( keyTrackingRange + 1 );
    
    protected final boolean isKeyCodeTracked(final short keyCode) {
        return ( 0xFFFF & (int)keyCode ) <= keyTrackingRange;
    }
    
    /**
     * @param keyCode the keyCode to set pressed state
     * @param pressed true if pressed, otherwise false
     * @return the previus pressed value 
     */
    protected final boolean setKeyPressed(short keyCode, boolean pressed) {
        final int v = 0xFFFF & (int)keyCode;
        if( v <= keyTrackingRange ) {
            return keyPressedState.put(v, pressed);
        }
        return false;
    }
    /**
     * @param keyCode the keyCode to test pressed state
     * @return true if pressed, otherwise false 
     */
    protected final boolean isKeyPressed(short keyCode) {
        final int v = 0xFFFF & (int)keyCode;
        if( v <= keyTrackingRange ) {
            return keyPressedState.get(v);
        }
        return false;
    }
        
    public void sendKeyEvent(short eventType, int modifiers, short keyCode, short keySym, char keyChar) {
        // Always add currently pressed mouse buttons to modifier mask
        consumeKeyEvent( KeyEvent.create(eventType, this, System.currentTimeMillis(), modifiers | mouseButtonModMask, keyCode, keySym, keyChar) );
    }

    public void enqueueKeyEvent(boolean wait, short eventType, int modifiers, short keyCode, short keySym, char keyChar) {
        // Always add currently pressed mouse buttons to modifier mask
        enqueueEvent(wait, KeyEvent.create(eventType, this, System.currentTimeMillis(), modifiers | mouseButtonModMask, keyCode, keySym, keyChar) );
    }
    
    @Override
    public final void setKeyboardVisible(boolean visible) {
        if(isNativeValid()) {
            // We don't skip the impl. if it seems that there is no state change,
            // since we cannot assume the impl. reliably gives us it's current state. 
            final boolean ok = setKeyboardVisibleImpl(visible);
            if(DEBUG_IMPLEMENTATION || DEBUG_KEY_EVENT) {
                System.err.println("setKeyboardVisible(native): visible "+keyboardVisible+" -- op[visible:"+visible +", ok "+ok+"] -> "+(visible && ok));
            }
            keyboardVisibilityChanged( visible && ok );
        } else {
            keyboardVisibilityChanged( visible ); // earmark for creation
        }
    }
    @Override
    public final boolean isKeyboardVisible() {
        return keyboardVisible;
    }
    /** 
     * Returns <code>true</code> if operation was successful, otherwise <code>false</code>.
     * <p>
     * We assume that a failed invisible operation is due to an already invisible keyboard,
     * hence even if an invisible operation failed, the keyboard is considered invisible!  
     * </p> 
     */ 
    protected boolean setKeyboardVisibleImpl(boolean visible) {
        return false; // nop
    }
    /** Triggered by implementation's WM events to update the virtual on-screen keyboard's visibility state. */
    protected void keyboardVisibilityChanged(boolean visible) {
        if(keyboardVisible != visible) {
            if(DEBUG_IMPLEMENTATION || DEBUG_KEY_EVENT) {
                System.err.println("keyboardVisibilityChanged: "+keyboardVisible+" -> "+visible);
            }
            keyboardVisible = visible;
        }
    }
    protected boolean keyboardVisible = false;
    
    @Override
    public void addKeyListener(KeyListener l) {
        addKeyListener(-1, l);
    }

    @Override
    public void addKeyListener(int index, KeyListener l) {
        if(l == null) {
            return;
        }
        @SuppressWarnings("unchecked")
        ArrayList<KeyListener> clonedListeners = (ArrayList<KeyListener>) keyListeners.clone();
        if(0>index) { 
            index = clonedListeners.size();
        }
        clonedListeners.add(index, l);
        keyListeners = clonedListeners;
    }

    @Override
    public void removeKeyListener(KeyListener l) {
        if (l == null) {
            return;
        }
        @SuppressWarnings("unchecked")
        ArrayList<KeyListener> clonedListeners = (ArrayList<KeyListener>) keyListeners.clone();
        clonedListeners.remove(l);
        keyListeners = clonedListeners;
    }

    @Override
    public KeyListener getKeyListener(int index) {
        @SuppressWarnings("unchecked")
        ArrayList<KeyListener> clonedListeners = (ArrayList<KeyListener>) keyListeners.clone();
        if(0>index) { 
            index = clonedListeners.size()-1;
        }
        return clonedListeners.get(index);
    }

    @Override
    public KeyListener[] getKeyListeners() {
        return keyListeners.toArray(new KeyListener[keyListeners.size()]);
    }

    @SuppressWarnings("deprecation")
    private final boolean propagateKeyEvent(KeyEvent e, KeyListener l) {
        switch(e.getEventType()) {
            case KeyEvent.EVENT_KEY_PRESSED:
                l.keyPressed(e);
                break;
            case KeyEvent.EVENT_KEY_RELEASED:
                l.keyReleased(e);
                break;
            case KeyEvent.EVENT_KEY_TYPED:
                l.keyTyped(e);
                break;
            default:
                throw new NativeWindowException("Unexpected key event type " + e.getEventType());
        }
        return e.isConsumed();
    }
    
    @SuppressWarnings("deprecation")
    protected void consumeKeyEvent(KeyEvent e) {
        boolean consumedE = false, consumedTyped = false;
        if( KeyEvent.EVENT_KEY_TYPED == e.getEventType() ) {
            throw new InternalError("Deprecated KeyEvent.EVENT_KEY_TYPED is synthesized - don't send/enqueue it!");
        }
        
        // Synthesize deprecated event KeyEvent.EVENT_KEY_TYPED
        final KeyEvent eTyped;
        if( KeyEvent.EVENT_KEY_RELEASED == e.getEventType() && e.isPrintableKey() && !e.isAutoRepeat() ) {
            eTyped = KeyEvent.create(KeyEvent.EVENT_KEY_TYPED, e.getSource(), e.getWhen(), e.getModifiers(), e.getKeyCode(), e.getKeySymbol(), e.getKeyChar());
        } else {
            eTyped = null;
        }
        if(null != keyboardFocusHandler) {
            consumedE = propagateKeyEvent(e, keyboardFocusHandler);
            if(DEBUG_KEY_EVENT) {
                System.err.println("consumeKeyEvent: "+e+", keyboardFocusHandler consumed: "+consumedE);
            }
            if( null != eTyped ) {
                consumedTyped = propagateKeyEvent(eTyped, keyboardFocusHandler);
                if(DEBUG_KEY_EVENT) {
                    System.err.println("consumeKeyEvent: "+eTyped+", keyboardFocusHandler consumed: "+consumedTyped);
                }                
            }
        }
        if(DEBUG_KEY_EVENT) {
            if( !consumedE ) {
                System.err.println("consumeKeyEvent: "+e);
            }
        }
        for(int i = 0; !consumedE && i < keyListeners.size(); i++ ) {
            consumedE = propagateKeyEvent(e, keyListeners.get(i));
        }
        if( null != eTyped ) {
            if(DEBUG_KEY_EVENT) {
                if( !consumedTyped ) {
                    System.err.println("consumeKeyEvent: "+eTyped);
                }
            }
            for(int i = 0; !consumedTyped && i < keyListeners.size(); i++ ) {
                consumedTyped = propagateKeyEvent(eTyped, keyListeners.get(i));
            }            
        }
    }

    //
    // WindowListener/Event Support
    //
    @Override
    public void sendWindowEvent(int eventType) {
        consumeWindowEvent( new WindowEvent((short)eventType, this, System.currentTimeMillis()) ); // FIXME
    }

    public void enqueueWindowEvent(boolean wait, int eventType) {
        enqueueEvent( wait, new WindowEvent((short)eventType, this, System.currentTimeMillis()) ); // FIXME
    }

    @Override
    public void addWindowListener(WindowListener l) {
        addWindowListener(-1, l);
    }

    @Override
    public void addWindowListener(int index, WindowListener l) 
        throws IndexOutOfBoundsException
    {
        if(l == null) {
            return;
        }
        @SuppressWarnings("unchecked")
        ArrayList<WindowListener> clonedListeners = (ArrayList<WindowListener>) windowListeners.clone();
        if(0>index) { 
            index = clonedListeners.size(); 
        }
        clonedListeners.add(index, l);
        windowListeners = clonedListeners;
    }

    @Override
    public final void removeWindowListener(WindowListener l) {
        if (l == null) {
            return;
        }
        @SuppressWarnings("unchecked")
        ArrayList<WindowListener> clonedListeners = (ArrayList<WindowListener>) windowListeners.clone();
        clonedListeners.remove(l);
        windowListeners = clonedListeners;
    }

    @Override
    public WindowListener getWindowListener(int index) {
        @SuppressWarnings("unchecked")
        ArrayList<WindowListener> clonedListeners = (ArrayList<WindowListener>) windowListeners.clone();
        if(0>index) { 
            index = clonedListeners.size()-1; 
        }
        return clonedListeners.get(index);
    }

    @Override
    public WindowListener[] getWindowListeners() {
        return windowListeners.toArray(new WindowListener[windowListeners.size()]);
    }

    protected void consumeWindowEvent(WindowEvent e) {
        if(DEBUG_IMPLEMENTATION) {
            System.err.println("consumeWindowEvent: "+e+", visible "+isVisible()+" "+getX()+"/"+getY()+" "+getWidth()+"x"+getHeight());
        }
        for(int i = 0; !e.isConsumed() && i < windowListeners.size(); i++ ) {
            WindowListener l = windowListeners.get(i);
            switch(e.getEventType()) {
                case WindowEvent.EVENT_WINDOW_RESIZED:
                    l.windowResized(e);
                    break;
                case WindowEvent.EVENT_WINDOW_MOVED:
                    l.windowMoved(e);
                    break;
                case WindowEvent.EVENT_WINDOW_DESTROY_NOTIFY:
                    l.windowDestroyNotify(e);
                    break;
                case WindowEvent.EVENT_WINDOW_DESTROYED:
                    l.windowDestroyed(e);
                    break;
                case WindowEvent.EVENT_WINDOW_GAINED_FOCUS:
                    l.windowGainedFocus(e);
                    break;
                case WindowEvent.EVENT_WINDOW_LOST_FOCUS:
                    l.windowLostFocus(e);
                    break;
                case WindowEvent.EVENT_WINDOW_REPAINT:
                    l.windowRepaint((WindowUpdateEvent)e);
                    break;
                default:
                    throw 
                        new NativeWindowException("Unexpected window event type "
                                                  + e.getEventType());
            }
        }
    }

    /** Triggered by implementation's WM events to update the focus state. */
    protected void focusChanged(boolean defer, boolean focusGained) {
        if(brokenFocusChange || hasFocus != focusGained) {
            if(DEBUG_IMPLEMENTATION) {
                System.err.println("Window.focusChanged: ("+getThreadName()+"): (defer: "+defer+") "+this.hasFocus+" -> "+focusGained+" - windowHandle "+toHexString(windowHandle)+" parentWindowHandle "+toHexString(parentWindowHandle));
            }
            hasFocus = focusGained;
            final int evt = focusGained ? WindowEvent.EVENT_WINDOW_GAINED_FOCUS : WindowEvent.EVENT_WINDOW_LOST_FOCUS ; 
            if(!defer) {
                sendWindowEvent(evt);
            } else {
                enqueueWindowEvent(false, evt);
            }
        }
    }
    
    /** Triggered by implementation's WM events to update the visibility state. */
    protected void visibleChanged(boolean defer, boolean visible) {
        if(this.visible != visible) {
            if(DEBUG_IMPLEMENTATION) {
                System.err.println("Window.visibleChanged ("+getThreadName()+"): (defer: "+defer+") "+this.visible+" -> "+visible+" - windowHandle "+toHexString(windowHandle)+" parentWindowHandle "+toHexString(parentWindowHandle));
            }
            this.visible = visible ;
        }
    }

    /** Returns -1 if failed, otherwise remaining time until {@link #TIMEOUT_NATIVEWINDOW}, maybe zero. */
    private long waitForVisible(boolean visible, boolean failFast) {
        return waitForVisible(visible, failFast, TIMEOUT_NATIVEWINDOW);
    }

    /** Returns -1 if failed, otherwise remaining time until <code>timeOut</code>, maybe zero. */
    private long waitForVisible(boolean visible, boolean failFast, long timeOut) {
        final DisplayImpl display = (DisplayImpl) screen.getDisplay();
        display.dispatchMessagesNative(); // status up2date
        long remaining;
        for(remaining = timeOut; 0<remaining && this.visible != visible; remaining-=10 ) {
            try { Thread.sleep(10); } catch (InterruptedException ie) {}
            display.dispatchMessagesNative(); // status up2date
        }
        if(this.visible != visible) {
            final String msg = "Visibility not reached as requested within "+timeOut+"ms : requested "+visible+", is "+this.visible; 
            if(failFast) {
                throw new NativeWindowException(msg);
            } else if (DEBUG_IMPLEMENTATION) {
                System.err.println(msg);
                Thread.dumpStack();
            }
            return -1;
        } else if( 0 < remaining ){
            return remaining;
        } else {
            return 0;
        }
    }

    /** Triggered by implementation's WM events to update the client-area size w/o insets/decorations. */ 
    protected void sizeChanged(boolean defer, int newWidth, int newHeight, boolean force) {
        if(force || getWidth() != newWidth || getHeight() != newHeight) {
            if(DEBUG_IMPLEMENTATION) {
                System.err.println("Window.sizeChanged: ("+getThreadName()+"): (defer: "+defer+") force "+force+", "+getWidth()+"x"+getHeight()+" -> "+newWidth+"x"+newHeight+" - windowHandle "+toHexString(windowHandle)+" parentWindowHandle "+toHexString(parentWindowHandle));
            }
            if(0>newWidth || 0>newHeight) {
                throw new NativeWindowException("Illegal width or height "+newWidth+"x"+newHeight+" (must be >= 0)");
            }
            defineSize(newWidth, newHeight);
            if(isNativeValid()) {
                if(!defer) {
                    sendWindowEvent(WindowEvent.EVENT_WINDOW_RESIZED);
                } else {
                    enqueueWindowEvent(false, WindowEvent.EVENT_WINDOW_RESIZED);
                }
            }
        }
    }
    
    private boolean waitForSize(int w, int h, boolean failFast, long timeOut) {
        final DisplayImpl display = (DisplayImpl) screen.getDisplay();
        display.dispatchMessagesNative(); // status up2date
        long sleep;
        for(sleep = timeOut; 0<sleep && w!=getWidth() && h!=getHeight(); sleep-=10 ) {
            try { Thread.sleep(10); } catch (InterruptedException ie) {}
            display.dispatchMessagesNative(); // status up2date
        }
        if(0 >= sleep) {
            final String msg = "Size/Pos not reached as requested within "+timeOut+"ms : requested "+w+"x"+h+", is "+getWidth()+"x"+getHeight();
            if(failFast) {
                throw new NativeWindowException(msg);
            } else if (DEBUG_IMPLEMENTATION) {
                System.err.println(msg);
                Thread.dumpStack();
            }
            return false;
        } else {
            return true;
        }
    }
    
    /** Triggered by implementation's WM events to update the position. */ 
    protected void positionChanged(boolean defer, int newX, int newY) {
        if ( getX() != newX || getY() != newY ) {
            if(DEBUG_IMPLEMENTATION) {
                System.err.println("Window.positionChanged: ("+getThreadName()+"): (defer: "+defer+") "+getX()+"/"+getY()+" -> "+newX+"/"+newY+" - windowHandle "+toHexString(windowHandle)+" parentWindowHandle "+toHexString(parentWindowHandle));
            }
            definePosition(newX, newY);
            if(!defer) {
                sendWindowEvent(WindowEvent.EVENT_WINDOW_MOVED);
            } else {
                enqueueWindowEvent(false, WindowEvent.EVENT_WINDOW_MOVED);
            }
        } else {
            autoPosition = false; // ensure it's off even w/ same position            
        }
    }

    /**
     * Wait until position is reached within tolerances, either auto-position or custom position.
     * <p>
     * Since WM may not obey our positional request exactly, we allow a tolerance of 2 times insets[left/top], or 64 pixels, whatever is greater.
     * </p>
     */
    private boolean waitForPosition(boolean useCustomPosition, int x, int y, long timeOut) {
        final DisplayImpl display = (DisplayImpl) screen.getDisplay();
        final int maxDX, maxDY;
        {
            final InsetsImmutable insets = getInsets();
            maxDX = Math.max(64, insets.getLeftWidth() * 2);
            maxDY = Math.max(64, insets.getTopHeight() * 2);
        }
        long remaining = timeOut;
        boolean ok;
        do {
            if( useCustomPosition ) {
                ok = Math.abs(x - getX()) <= maxDX && Math.abs(y - getY()) <= maxDY ;
            } else {
                ok = !autoPosition;
            }
            if( !ok ) {
                try { Thread.sleep(10); } catch (InterruptedException ie) {}
                display.dispatchMessagesNative(); // status up2date
                remaining-=10;
            }
        } while ( 0<remaining && !ok );
        if (DEBUG_IMPLEMENTATION) {
            if( !ok ) {
                if( useCustomPosition ) {
                    System.err.println("Custom position "+x+"/"+y+" not reached within timeout, has "+getX()+"/"+getY()+", remaining "+remaining);
                } else {
                    System.err.println("Auto position not reached within timeout, has "+getX()+"/"+getY()+", autoPosition "+autoPosition+", remaining "+remaining);
                }
                Thread.dumpStack();
            }
        }
        return ok;
    }
    
    /**
     * Triggered by implementation's WM events to update the insets. 
     * 
     * @see #getInsets()
     * @see #updateInsetsImpl(Insets)
     */
    protected void insetsChanged(boolean defer, int left, int right, int top, int bottom) {
        if ( left >= 0 && right >= 0 && top >= 0 && bottom >= 0 ) {
            if(isUndecorated()) {
                if(DEBUG_IMPLEMENTATION) {
                    System.err.println("Window.insetsChanged: skip insets change for undecoration mode");
                }
            } else if ( (left != insets.getLeftWidth() || right != insets.getRightWidth() || 
                         top != insets.getTopHeight() || bottom != insets.getBottomHeight() )
                       ) {
                insets.setLeftWidth(left);
                insets.setRightWidth(right);            
                insets.setTopHeight(top);
                insets.setBottomHeight(bottom);            
                if(DEBUG_IMPLEMENTATION) {
                    System.err.println("Window.insetsChanged: (defer: "+defer+") "+insets);
                }
            }
        }
    }
    
    /**
     * Triggered by implementation's WM events or programmatic while respecting {@link #getDefaultCloseOperation()}.
     * 
     * @param force if true, overrides {@link #setDefaultCloseOperation(WindowClosingMode)} with {@link WindowClosingProtocol#DISPOSE_ON_CLOSE}
     *              and hence force destruction. Otherwise is follows the user settings.
     * @return true if this window is no more valid and hence has been destroyed, otherwise false.
     */
    public boolean windowDestroyNotify(boolean force) {
        final WindowClosingMode defMode = getDefaultCloseOperation();
        final WindowClosingMode mode = force ? WindowClosingMode.DISPOSE_ON_CLOSE : defMode;
        if(DEBUG_IMPLEMENTATION) {
            System.err.println("Window.windowDestroyNotify(isNativeValid: "+isNativeValid()+", force: "+force+", mode "+defMode+" -> "+mode+") "+getThreadName()+": "+this);
            // Thread.dumpStack();
        }
        
        final boolean destroyed;
        
        if( isNativeValid() ) {
            if( WindowClosingMode.DISPOSE_ON_CLOSE == mode ) {
                if(force) {
                    setDefaultCloseOperation(mode);
                }
                try {
                    if( null == windowDestroyNotifyAction ) {
                        destroy();
                    } else {
                        windowDestroyNotifyAction.run();
                    }
                } finally {
                    if(force) {
                        setDefaultCloseOperation(defMode);
                    }
                }
            } else {
                // send synced destroy notifications
                sendWindowEvent(WindowEvent.EVENT_WINDOW_DESTROY_NOTIFY);
            }
            
            destroyed = !isNativeValid();
        } else {
            destroyed = true;
        }

        if(DEBUG_IMPLEMENTATION) {
            System.err.println("Window.windowDestroyNotify(isNativeValid: "+isNativeValid()+", force: "+force+", mode "+mode+") END "+getThreadName()+": destroyed "+destroyed+", "+this);
        }        
        
        return destroyed;
    }

    @Override
    public void windowRepaint(int x, int y, int width, int height) {
        windowRepaint(false, x, y, width, height); 
    }
    
    /**
     * Triggered by implementation's WM events to update the content
     */ 
    protected void windowRepaint(boolean defer, int x, int y, int width, int height) {
        width = ( 0 >= width ) ? getWidth() : width;
        height = ( 0 >= height ) ? getHeight() : height;
        if(DEBUG_IMPLEMENTATION) {
            System.err.println("Window.windowRepaint "+getThreadName()+" (defer: "+defer+") "+x+"/"+y+" "+width+"x"+height);
        }

        if(isNativeValid()) {
            NEWTEvent e = new WindowUpdateEvent(WindowEvent.EVENT_WINDOW_REPAINT, this, System.currentTimeMillis(),
                                                new Rectangle(x, y, width, height));
            doEvent(defer, false, e);
        }
    }

    //
    // Reflection helper ..
    //

    private static Class<?>[] getCustomConstructorArgumentTypes(Class<?> windowClass) {
        Class<?>[] argTypes = null;
        try {
            Method m = windowClass.getDeclaredMethod("getCustomConstructorArgumentTypes");
            argTypes = (Class[]) m.invoke(null, (Object[])null);
        } catch (Throwable t) {}
        return argTypes;
    }

    private static int verifyConstructorArgumentTypes(Class<?>[] types, Object[] args) {
        if(types.length != args.length) {
            return -1;
        }
        for(int i=0; i<args.length; i++) {
            if(!types[i].isInstance(args[i])) {
                return i;
            }
        }
        return args.length;
    }

    private static String getArgsStrList(Object[] args) {
        StringBuilder sb = new StringBuilder();
        for(int i=0; i<args.length; i++) {
            sb.append(args[i].getClass());
            if(i<args.length) {
                sb.append(", ");
            }
        }
        return sb.toString();
    }

    private static String getTypeStrList(Class<?>[] types) {
        StringBuilder sb = new StringBuilder();
        for(int i=0; i<types.length; i++) {
            sb.append(types[i]);
            if(i<types.length) {
                sb.append(", ");
            }
        }
        return sb.toString();
    }

    protected final void shouldNotCallThis() {
        throw new NativeWindowException("Should not call this");
    }
    
    public static String getThreadName() {
        return Display.getThreadName();
    }

    public static String toHexString(int hex) {
        return Display.toHexString(hex);
    }

    public static String toHexString(long hex) {
        return Display.toHexString(hex);
    }
}

