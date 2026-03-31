package io.github.lostmymind.ncm.car.notify;

import android.annotation.SuppressLint;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.Rating;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public class ModuleMain extends XposedModule {

    private static final String TAG = "NeteaseMediaNotify";
    private static final String TARGET_PACKAGE = "com.netease.cloudmusic.iot";
    private static final String CHANNEL_ID = "netease_media_playback";
    private static final int NOTIFICATION_ID = 10086;
    private static final String CUSTOM_ACTION_LIKE = "io.github.lostmymind.ncm.car.notify.TOGGLE_LIKE";
    private static final String CUSTOM_ACTION_CLOSE = "io.github.lostmymind.ncm.car.notify.CLOSE";
    private static final String ACTION_MEDIA_CONTROL = "io.github.lostmymind.ncm.car.notify.MEDIA_CONTROL";
    private static final String EXTRA_CONTROL_ACTION = "control_action";

    private static final int REQUEST_PREV = 1;
    private static final int REQUEST_PLAY_PAUSE = 2;
    private static final int REQUEST_NEXT = 3;
    private static final int REQUEST_LIKE = 4;
    private static final int REQUEST_CLOSE = 5;

    private static final long SUPPORTED_ACTIONS =
        PlaybackState.ACTION_PLAY |
        PlaybackState.ACTION_PAUSE |
        PlaybackState.ACTION_PLAY_PAUSE |
        PlaybackState.ACTION_SKIP_TO_PREVIOUS |
        PlaybackState.ACTION_SKIP_TO_NEXT |
        PlaybackState.ACTION_STOP |
        PlaybackState.ACTION_SEEK_TO |
        PlaybackState.ACTION_SET_RATING;

    private Context appContext;
    private NotificationManager notificationManager;
    private MediaSessionManager mediaSessionManager;
    private AudioManager audioManager;
    private Handler mainHandler;
    private android.content.BroadcastReceiver mediaControlReceiver;

    private MediaSession targetMediaSession;
    private MediaController mediaController;
    private MediaController.Callback mediaCallback;
    private MediaSession.Callback originalCallback;

    private String currentTitle = "Netease Cloud Music";
    private String currentArtist = "";
    private Bitmap currentAlbumArt = null;
    private boolean isPlaying = false;
    private boolean isLiked = false;
    private String lastTitle = "";
    private boolean isNotificationClosed = false;
    
    // 音频设备检测
    private boolean hasPrivateAudioDevice = false;
    private boolean audioDeviceCallbackRegistered = false;

    private Icon iconPrev = null;
    private Icon iconPlay = null;
    private Icon iconPause = null;
    private Icon iconNext = null;
    private Icon iconLikeFilled = null;
    private Icon iconLikeBorder = null;
    private Icon iconClose = null;
    private boolean iconsInitialized = false;

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }

        Log.d(TAG, "Module loaded, starting hooks");

        try {
            // Hook Application.attach
            Method attachMethod = Application.class.getDeclaredMethod("attach", Context.class);
            hook(attachMethod).intercept(new ApplicationAttachHooker());

            // Hook Application.onCreate
            Method onCreateMethod = Application.class.getDeclaredMethod("onCreate");
            hook(onCreateMethod).intercept(new ApplicationOnCreateHooker());

            // Hook MediaSession constructors
            hook(MediaSession.class.getDeclaredConstructor(Context.class, String.class))
                .intercept(new MediaSessionCtorHooker1());
            hook(MediaSession.class.getDeclaredConstructor(Context.class, String.class, Bundle.class))
                .intercept(new MediaSessionCtorHooker2());

            // Hook MediaSession.setCallback
            Method setCallbackMethod = MediaSession.class.getDeclaredMethod("setCallback", MediaSession.Callback.class, Handler.class);
            hook(setCallbackMethod).intercept(new MediaSessionSetCallbackHooker());

            // Hook MediaSession.setActive
            Method setActiveMethod = MediaSession.class.getDeclaredMethod("setActive", boolean.class);
            hook(setActiveMethod).intercept(new MediaSessionSetActiveHooker());

            // Hook MediaSession.setMetadata
            Method setMetadataMethod = MediaSession.class.getDeclaredMethod("setMetadata", MediaMetadata.class);
            hook(setMetadataMethod).intercept(new MediaSessionSetMetadataHooker());

            // Hook MediaSession.setPlaybackState
            Method setPlaybackStateMethod = MediaSession.class.getDeclaredMethod("setPlaybackState", PlaybackState.class);
            hook(setPlaybackStateMethod).intercept(new MediaSessionSetPlaybackStateHooker());

        } catch (Exception e) {
            Log.e(TAG, "Hook failed: " + e.getMessage());
        }
    }

    // ===== Hooker Classes =====

    public class ApplicationAttachHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            appContext = (Context) chain.getArg(0);
            Object result = chain.proceed();
            initIcons();
            initNotificationSystem();
            return result;
        }
    }

    public class ApplicationOnCreateHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object result = chain.proceed();
            startMediaSessionMonitor();
            return result;
        }
    }

    public class MediaSessionCtorHooker1 implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            chain.proceed();
            targetMediaSession = (MediaSession) chain.getThisObject();
            return null;
        }
    }

    public class MediaSessionCtorHooker2 implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            chain.proceed();
            targetMediaSession = (MediaSession) chain.getThisObject();
            return null;
        }
    }

    public class MediaSessionSetCallbackHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            MediaSession session = (MediaSession) chain.getThisObject();
            MediaSession.Callback callback = (MediaSession.Callback) chain.getArg(0);
            Handler handler = (Handler) chain.getArg(1);

            if (session == targetMediaSession && callback != null) {
                originalCallback = callback;
                return chain.proceed(new Object[]{new LikeCallbackWrapper(), handler});
            }
            return chain.proceed();
        }
    }

    public class LikeCallbackWrapper extends MediaSession.Callback {
        @Override
        public void onCustomAction(String action, Bundle extras) {
            if (CUSTOM_ACTION_CLOSE.equals(action)) {
                handleCloseAction();
            } else if (CUSTOM_ACTION_LIKE.equals(action)) {
                handleLikeAction();
            } else if (originalCallback != null) {
                originalCallback.onCustomAction(action, extras);
            }
        }

        @Override
        public void onPlay() {
            if (originalCallback != null) originalCallback.onPlay();
        }

        @Override
        public void onPause() {
            if (originalCallback != null) originalCallback.onPause();
        }

        @Override
        public void onSkipToNext() {
            if (originalCallback != null) originalCallback.onSkipToNext();
        }

        @Override
        public void onSkipToPrevious() {
            if (originalCallback != null) originalCallback.onSkipToPrevious();
        }

        @Override
        public void onSeekTo(long pos) {
            if (originalCallback != null) originalCallback.onSeekTo(pos);
        }

        @Override
        public void onStop() {
            if (originalCallback != null) originalCallback.onStop();
        }

        @Override
        public void onSetRating(Rating rating) {
            if (rating != null && rating.getRatingStyle() == Rating.RATING_HEART) {
                isLiked = rating.hasHeart();
                updateNotification();
            }
            if (originalCallback != null) originalCallback.onSetRating(rating);
        }
    }

    public class MediaSessionSetActiveHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            boolean active = (boolean) chain.getArg(0);
            MediaSession session = (MediaSession) chain.getThisObject();

            chain.proceed();

            if (active && session == targetMediaSession) {
                isNotificationClosed = false;
                setupMediaController();
                injectInitialPlaybackState();
            } else if (!active) {
                isNotificationClosed = true;
                cancelNotification();
            }
            return null;
        }
    }

    public class MediaSessionSetMetadataHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            MediaMetadata metadata = (MediaMetadata) chain.getArg(0);
            MediaSession session = (MediaSession) chain.getThisObject();

            chain.proceed();

            if (metadata != null && session == targetMediaSession) {
                isNotificationClosed = false;
                updateMetadata(metadata);
                checkLikeStatusFromMetadata(metadata);
                updateNotification();
            }
            return null;
        }
    }

    public class MediaSessionSetPlaybackStateHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            MediaSession session = (MediaSession) chain.getThisObject();
            PlaybackState originalState = (PlaybackState) chain.getArg(0);

            if (originalState != null && session == targetMediaSession) {
                checkLikeStatusFromPlaybackState(originalState);
                PlaybackState modifiedState = injectPlaybackActions(originalState);
                chain.proceed(new Object[]{modifiedState});
                updatePlaybackState(originalState);
                updateNotification();
            } else {
                chain.proceed();
            }
            return null;
        }
    }

    // ===== Helper Methods =====

    private void initIcons() {
        if (iconsInitialized) return;

        Log.d(TAG, "Loading icons from assets");

        iconPrev = loadIconFromAssets("icons/ic_prev.png");
        iconPlay = loadIconFromAssets("icons/ic_play.png");
        iconPause = loadIconFromAssets("icons/ic_pause.png");
        iconNext = loadIconFromAssets("icons/ic_next.png");
        iconLikeFilled = loadIconFromAssets("icons/ic_like_filled.png");
        iconLikeBorder = loadIconFromAssets("icons/ic_like_border.png");
        iconClose = loadIconFromAssets("icons/ic_close.png");

        Log.d(TAG, "Icons loaded: prev=" + (iconPrev != null) + ", play=" + (iconPlay != null));
        iconsInitialized = true;
    }

    private Icon loadIconFromAssets(String filename) {
        if (appContext == null) return null;
        try {
            Context moduleContext = appContext.createPackageContext(
                "io.github.lostmymind.ncm.car.notify",
                Context.CONTEXT_IGNORE_SECURITY
            );
            Bitmap bitmap = BitmapFactory.decodeStream(moduleContext.getAssets().open(filename));
            if (bitmap != null) {
                return Icon.createWithBitmap(bitmap);
            }
        } catch (Exception e) {
            Log.e(TAG, "loadIconFromAssets error: " + e.getMessage());
        }
        return null;
    }

    private void handleCloseAction() {
        try {
            isNotificationClosed = true;
            cancelNotification();
            if (originalCallback != null) {
                originalCallback.onPause();
            }
        } catch (Exception e) {
            Log.e(TAG, "handleCloseAction error: " + e.getMessage());
        }
    }

    private void handleLikeAction() {
        try {
            if (originalCallback != null) {
                Rating rating = Rating.newHeartRating(!isLiked);
                originalCallback.onSetRating(rating);
            }
            isLiked = !isLiked;
            updatePlaybackStateAndNotification();
        } catch (Exception ignored) {
        }
    }

    private void checkLikeStatusFromMetadata(MediaMetadata metadata) {
        try {
            Rating userRating = metadata.getRating(MediaMetadata.METADATA_KEY_USER_RATING);
            if (userRating != null && userRating.getRatingStyle() == Rating.RATING_HEART) {
                isLiked = userRating.hasHeart();
                return;
            }
            Rating rating = metadata.getRating(MediaMetadata.METADATA_KEY_RATING);
            if (rating != null && rating.getRatingStyle() == Rating.RATING_HEART) {
                isLiked = rating.hasHeart();
            }
        } catch (Exception ignored) {
        }
    }

    private void checkLikeStatusFromPlaybackState(PlaybackState state) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                Bundle extras = state.getExtras();
                if (extras != null) {
                    String[] likeKeys = {"liked", "is_liked", "favorite", "is_favorite"};
                    for (String key : likeKeys) {
                        if (extras.containsKey(key)) {
                            Object val = extras.get(key);
                            if (val instanceof Boolean) {
                                isLiked = (Boolean) val;
                                break;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void injectInitialPlaybackState() {
        try {
            if (targetMediaSession == null) return;
            PlaybackState initialState = buildPlaybackState(PlaybackState.STATE_PAUSED, 0, 1.0f);
            targetMediaSession.setPlaybackState(initialState);
        } catch (Exception ignored) {
        }
    }

    private void updatePlaybackStateAndNotification() {
        try {
            if (targetMediaSession == null || mediaController == null) return;
            PlaybackState currentState = mediaController.getPlaybackState();
            if (currentState != null) {
                PlaybackState newState = buildPlaybackState(
                    currentState.getState(),
                    currentState.getPosition(),
                    currentState.getPlaybackSpeed()
                );
                targetMediaSession.setPlaybackState(newState);
            }
            updateNotification();
        } catch (Exception ignored) {
        }
    }

    private PlaybackState buildPlaybackState(int state, long position, float speed) {
        PlaybackState.Builder builder = new PlaybackState.Builder();
        builder.setState(state, position, speed);
        builder.setActions(SUPPORTED_ACTIONS);

        int likeIconRes = isLiked ? android.R.drawable.star_on : android.R.drawable.star_off;
        PlaybackState.CustomAction likeAction = new PlaybackState.CustomAction.Builder(
            CUSTOM_ACTION_LIKE, isLiked ? "Unlike" : "Like", likeIconRes
        ).build();
        builder.addCustomAction(likeAction);

        PlaybackState.CustomAction closeAction = new PlaybackState.CustomAction.Builder(
            CUSTOM_ACTION_CLOSE, "Close", android.R.drawable.ic_menu_close_clear_cancel
        ).build();
        builder.addCustomAction(closeAction);

        return builder.build();
    }

    private PlaybackState injectPlaybackActions(PlaybackState original) {
        try {
            PlaybackState.Builder builder = new PlaybackState.Builder();

            long position = original.getPosition();
            if (position < 0) position = 0;

            builder.setState(original.getState(), position, original.getPlaybackSpeed());
            builder.setBufferedPosition(original.getBufferedPosition());
            builder.setActions(original.getActions() | SUPPORTED_ACTIONS);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setExtras(original.getExtras());
            }

            int likeIconRes = isLiked ? android.R.drawable.star_on : android.R.drawable.star_off;
            PlaybackState.CustomAction likeAction = new PlaybackState.CustomAction.Builder(
                CUSTOM_ACTION_LIKE, isLiked ? "Unlike" : "Like", likeIconRes
            ).build();
            builder.addCustomAction(likeAction);

            PlaybackState.CustomAction closeAction = new PlaybackState.CustomAction.Builder(
                CUSTOM_ACTION_CLOSE, "Close", android.R.drawable.ic_menu_close_clear_cancel
            ).build();
            builder.addCustomAction(closeAction);

            return builder.build();
        } catch (Exception e) {
            return original;
        }
    }

    private void initNotificationSystem() {
        try {
            notificationManager = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
            mediaSessionManager = (MediaSessionManager) appContext.getSystemService(Context.MEDIA_SESSION_SERVICE);
            audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
            mainHandler = new Handler(Looper.getMainLooper());

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Media Playback", NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription("Music playback control");
                channel.setShowBadge(false);
                channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                notificationManager.createNotificationChannel(channel);
            }

            registerAudioDeviceCallback();
        } catch (Exception ignored) {
        }
    }

    private void startMediaSessionMonitor() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                findActiveMediaSession();
                mainHandler.postDelayed(this, 5000);
            }
        }, 1000);
    }

    private void findActiveMediaSession() {
        try {
            if (mediaSessionManager == null) return;
            for (MediaController controller : mediaSessionManager.getActiveSessions(null)) {
                if (TARGET_PACKAGE.equals(controller.getPackageName())) {
                    if (mediaController == null || mediaController != controller) {
                        mediaController = controller;
                        setupMediaControllerCallback();
                    }
                    return;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void setupMediaController() {
        try {
            if (targetMediaSession == null) return;
            mediaController = targetMediaSession.getController();
            if (mediaController != null) {
                setupMediaControllerCallback();
                MediaMetadata metadata = mediaController.getMetadata();
                PlaybackState state = mediaController.getPlaybackState();
                if (metadata != null) {
                    updateMetadata(metadata);
                    checkLikeStatusFromMetadata(metadata);
                }
                if (state != null) {
                    updatePlaybackState(state);
                    checkLikeStatusFromPlaybackState(state);
                }
                updateNotification();
            }
        } catch (Exception ignored) {
        }
    }

    private void setupMediaControllerCallback() {
        if (mediaController == null) return;
        if (mediaCallback != null) {
            mediaController.unregisterCallback(mediaCallback);
        }
        mediaCallback = new MediaController.Callback() {
            @Override
            public void onMetadataChanged(MediaMetadata metadata) {
                if (metadata != null) {
                    isNotificationClosed = false;
                    updateMetadata(metadata);
                    checkLikeStatusFromMetadata(metadata);
                    updateNotification();
                }
            }

            @Override
            public void onPlaybackStateChanged(PlaybackState state) {
                if (state != null) {
                    updatePlaybackState(state);
                    checkLikeStatusFromPlaybackState(state);
                    updateNotification();
                }
            }

            @Override
            public void onSessionDestroyed() {
                cancelNotification();
            }
        };
        mediaController.registerCallback(mediaCallback, mainHandler);
    }

    private void updateMetadata(MediaMetadata metadata) {
        try {
            String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
            if (title != null && !title.equals(lastTitle)) {
                lastTitle = title;
                isLiked = false;
            }
            if (title != null) currentTitle = title;
            currentArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
            currentAlbumArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        } catch (Exception ignored) {
        }
    }

    private void updatePlaybackState(PlaybackState state) {
        isPlaying = state.getState() == PlaybackState.STATE_PLAYING;
    }

    private void updateNotification() {
        if (isNotificationClosed) return;

        try {
            if (appContext == null || notificationManager == null) return;

            MediaSession.Token token = null;
            if (targetMediaSession != null) {
                token = targetMediaSession.getSessionToken();
            }

            Notification.Builder builder = new Notification.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(currentTitle)
                .setContentText(currentArtist)
                .setOngoing(isPlaying)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setPriority(Notification.PRIORITY_LOW);

            Notification.MediaStyle mediaStyle = new Notification.MediaStyle();
            if (token != null) {
                mediaStyle.setMediaSession(token);
            }
            mediaStyle.setShowActionsInCompactView(0, 1, 2);
            builder.setStyle(mediaStyle);

            addMediaActions(builder);

            if (currentAlbumArt != null) {
                builder.setLargeIcon(currentAlbumArt);
            }

            if (mediaController != null) {
                try {
                    String packageName = mediaController.getPackageName();
                    Intent launchIntent = appContext.getPackageManager().getLaunchIntentForPackage(packageName);
                    if (launchIntent != null) {
                        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            flags |= PendingIntent.FLAG_IMMUTABLE;
                        }
                        PendingIntent contentIntent = PendingIntent.getActivity(appContext, 0, launchIntent, flags);
                        builder.setContentIntent(contentIntent);
                    }
                } catch (Exception ignored) {
                }
            }

            notificationManager.notify(NOTIFICATION_ID, builder.build());
        } catch (Exception e) {
            Log.e(TAG, "updateNotification error: " + e.getMessage());
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void addMediaActions(Notification.Builder builder) {
        try {
            registerMediaControlReceiver();

            PendingIntent prevPI = createControlPendingIntent("prev", REQUEST_PREV);
            if (iconPrev != null) {
                builder.addAction(new Notification.Action.Builder(iconPrev, "Previous", prevPI).build());
            } else {
                builder.addAction(android.R.drawable.ic_media_previous, "Previous", prevPI);
            }

            PendingIntent playPausePI = createControlPendingIntent("playPause", REQUEST_PLAY_PAUSE);
            Icon playPauseIcon = isPlaying ? iconPause : iconPlay;
            if (playPauseIcon != null) {
                builder.addAction(new Notification.Action.Builder(playPauseIcon, isPlaying ? "Pause" : "Play", playPausePI).build());
            } else {
                int res = isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;
                builder.addAction(res, isPlaying ? "Pause" : "Play", playPausePI);
            }

            PendingIntent nextPI = createControlPendingIntent("next", REQUEST_NEXT);
            if (iconNext != null) {
                builder.addAction(new Notification.Action.Builder(iconNext, "Next", nextPI).build());
            } else {
                builder.addAction(android.R.drawable.ic_media_next, "Next", nextPI);
            }

            int likeRequestCode = REQUEST_LIKE + (isLiked ? 1000 : 0);
            PendingIntent likePI = createControlPendingIntent("toggleLike", likeRequestCode);
            Icon likeIcon = isLiked ? iconLikeFilled : iconLikeBorder;
            if (likeIcon != null) {
                builder.addAction(new Notification.Action.Builder(likeIcon, isLiked ? "Unlike" : "Like", likePI).build());
            } else {
                int res = isLiked ? android.R.drawable.star_on : android.R.drawable.star_off;
                builder.addAction(res, isLiked ? "Unlike" : "Like", likePI);
            }

            PendingIntent closePI = createControlPendingIntent("close", REQUEST_CLOSE);
            if (iconClose != null) {
                builder.addAction(new Notification.Action.Builder(iconClose, "Close", closePI).build());
            } else {
                builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Close", closePI);
            }
        } catch (Exception e) {
            Log.e(TAG, "addMediaActions error: " + e.getMessage());
        }
    }

    private PendingIntent createControlPendingIntent(String action, int requestCode) {
        Intent intent = new Intent(ACTION_MEDIA_CONTROL);
        intent.putExtra(EXTRA_CONTROL_ACTION, action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(appContext, requestCode, intent, flags);
    }

    private void registerMediaControlReceiver() {
        if (mediaControlReceiver != null || appContext == null) return;

        mediaControlReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, android.content.Intent intent) {
                String action = intent.getStringExtra(EXTRA_CONTROL_ACTION);
                if (action == null) return;

                switch (action) {
                    case "prev":
                        if (originalCallback != null) originalCallback.onSkipToPrevious();
                        break;
                    case "playPause":
                        if (originalCallback != null) {
                            if (isPlaying) {
                                originalCallback.onPause();
                            } else {
                                originalCallback.onPlay();
                            }
                        }
                        break;
                    case "next":
                        if (originalCallback != null) originalCallback.onSkipToNext();
                        break;
                    case "toggleLike":
                        handleLikeAction();
                        break;
                    case "close":
                        handleCloseAction();
                        break;
                }
            }
        };

        android.content.IntentFilter filter = new android.content.IntentFilter(ACTION_MEDIA_CONTROL);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(mediaControlReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED);
        } else {
            appContext.registerReceiver(mediaControlReceiver, filter);
        }
    }

    private void cancelNotification() {
        if (notificationManager != null) {
            notificationManager.cancel(NOTIFICATION_ID);
        }
    }

    // ===== Audio Device Detection =====

    private void registerAudioDeviceCallback() {
        if (audioDeviceCallbackRegistered || audioManager == null) return;

        checkPrivateAudioDevice();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.registerAudioDeviceCallback(new AudioDeviceCallback() {
                @Override
                public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                    checkPrivateAudioDevice();
                }

                @Override
                public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                    boolean hadPrivate = hasPrivateAudioDevice;
                    checkPrivateAudioDevice();

                    if (hadPrivate && !hasPrivateAudioDevice && isPlaying) {
                        Log.d(TAG, "Private audio device removed, pausing playback");
                        pausePlayback();
                    }
                }
            }, mainHandler);

            audioDeviceCallbackRegistered = true;
            Log.d(TAG, "AudioDeviceCallback registered");
        }
    }

    private void checkPrivateAudioDevice() {
        if (audioManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            hasPrivateAudioDevice = true;
            return;
        }

        AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        hasPrivateAudioDevice = false;

        for (AudioDeviceInfo device : devices) {
            if (isPrivateAudioDevice(device.getType())) {
                hasPrivateAudioDevice = true;
                Log.d(TAG, "Found private audio device: type=" + device.getType());
                break;
            }
        }

        Log.d(TAG, "Private audio device status: " + hasPrivateAudioDevice);
    }

    private boolean isPrivateAudioDevice(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_HEARING_AID:
            case AudioDeviceInfo.TYPE_DOCK:
            case AudioDeviceInfo.TYPE_LINE_ANALOG:
            case AudioDeviceInfo.TYPE_HDMI:
            case AudioDeviceInfo.TYPE_AUX_LINE:
            case AudioDeviceInfo.TYPE_USB_ACCESSORY:
                return true;
            default:
                return false;
        }
    }

    private void pausePlayback() {
        try {
            if (originalCallback != null) {
                originalCallback.onPause();
            }
        } catch (Exception e) {
            Log.e(TAG, "pausePlayback error: " + e.getMessage());
        }
    }
}
