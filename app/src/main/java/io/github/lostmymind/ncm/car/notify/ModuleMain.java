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
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public class ModuleMain extends XposedModule {

    private static final boolean DEBUG = false;
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
    private static final long SUPPORTED_ACTIONS = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_SET_RATING;
    private static final int DEBOUNCE_DELAY_MS = 100;
    private static final int LIKE_VERIFY_DELAY_MS = 800;
    private static final int LIKE_COOLDOWN_MS = 1500;
    private static final int ALBUM_ART_SIZE = 256;
    private static final long PLAYBACK_STATE_THROTTLE_MS = 200;
    private Context appContext;
    private NotificationManager notificationManager;
    private MediaSessionManager mediaSessionManager;
    private AudioManager audioManager;
    private Handler mainHandler;
    private MediaSession targetMediaSession;
    private MediaController mediaController;
    private MediaController.Callback mediaCallback;
    private MediaSession.Callback originalCallback;
    private String currentMediaId = "";
    private String currentTitle = "Netease Cloud Music";
    private String currentArtist = "";
    private Bitmap currentAlbumArt = null;
    private boolean isPlaying = false;
    private boolean isLiked = false;
    private boolean isNotificationClosed = false;
    private String lastMediaId = "";
    private String lastTitle = "";
    private String lastArtist = "";
    private boolean lastIsPlaying = false;
    private boolean lastIsLiked = false;
    private Runnable debounceRunnable;
    private Runnable likeVerifyRunnable;
    private long lastLikeVerifyTime = 0;
    private long lastPlaybackStateUpdateTime = 0;
    private boolean hasPrivateAudioDevice = false;
    private boolean audioDeviceCallbackRegistered = false;
    private Set<Integer> currentPrivateDeviceIds = new HashSet<>();
    private Icon iconPrev = null;
    private Icon iconPlay = null;
    private Icon iconPause = null;
    private Icon iconNext = null;
    private Icon iconLikeFilled = null;
    private Icon iconLikeBorder = null;
    private Icon iconClose = null;
    private boolean iconsInitialized = false;
    private PendingIntent cachedPrevPI = null;
    private PendingIntent cachedPlayPausePI = null;
    private PendingIntent cachedNextPI = null;
    private PendingIntent cachedLikePI = null;
    private PendingIntent cachedClosePI = null;
    private boolean pendingIntentsInitialized = false;
    private android.content.BroadcastReceiver mediaControlReceiver;

    private void logDebug(String msg) { if (DEBUG) Log.d(TAG, msg); }
    private void logError(String msg) { if (DEBUG) Log.e(TAG, msg); }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) return;
        logDebug("Module loaded, starting hooks");
        try {
            Method attachMethod = Application.class.getDeclaredMethod("attach", Context.class);
            hook(attachMethod).intercept(new ApplicationAttachHooker());
            Method onCreateMethod = Application.class.getDeclaredMethod("onCreate");
            hook(onCreateMethod).intercept(new ApplicationOnCreateHooker());
            hook(MediaSession.class.getDeclaredConstructor(Context.class, String.class)).intercept(new MediaSessionCtorHooker1());
            hook(MediaSession.class.getDeclaredConstructor(Context.class, String.class, Bundle.class)).intercept(new MediaSessionCtorHooker2());
            Method setCallbackMethod = MediaSession.class.getDeclaredMethod("setCallback", MediaSession.Callback.class, Handler.class);
            hook(setCallbackMethod).intercept(new MediaSessionSetCallbackHooker());
            Method setActiveMethod = MediaSession.class.getDeclaredMethod("setActive", boolean.class);
            hook(setActiveMethod).intercept(new MediaSessionSetActiveHooker());
            Method setMetadataMethod = MediaSession.class.getDeclaredMethod("setMetadata", MediaMetadata.class);
            hook(setMetadataMethod).intercept(new MediaSessionSetMetadataHooker());
            Method setPlaybackStateMethod = MediaSession.class.getDeclaredMethod("setPlaybackState", PlaybackState.class);
            hook(setPlaybackStateMethod).intercept(new MediaSessionSetPlaybackStateHooker());
        } catch (Exception e) { logError("Hook failed: " + e.getMessage()); }
    }

    public class ApplicationAttachHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            appContext = (Context) chain.getArg(0);
            Object result = chain.proceed();
            initIcons();
            initNotificationSystem();
            initPendingIntents();
            registerMediaControlReceiver();
            return result;
        }
    }

    public class ApplicationOnCreateHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable { return chain.proceed(); }
    }

    public class MediaSessionCtorHooker1 implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            chain.proceed();
            targetMediaSession = (MediaSession) chain.getThisObject();
            logDebug("MediaSession captured via constructor 1");
            return null;
        }
    }

    public class MediaSessionCtorHooker2 implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            chain.proceed();
            targetMediaSession = (MediaSession) chain.getThisObject();
            logDebug("MediaSession captured via constructor 2");
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
            if (CUSTOM_ACTION_CLOSE.equals(action)) handleCloseAction();
            else if (CUSTOM_ACTION_LIKE.equals(action)) handleLikeAction();
            else if (originalCallback != null) originalCallback.onCustomAction(action, extras);
        }
        @Override
        public void onPlay() { if (originalCallback != null) originalCallback.onPlay(); }
        @Override
        public void onPause() { if (originalCallback != null) originalCallback.onPause(); }
        @Override
        public void onSkipToNext() { if (originalCallback != null) originalCallback.onSkipToNext(); }
        @Override
        public void onSkipToPrevious() { if (originalCallback != null) originalCallback.onSkipToPrevious(); }
        @Override
        public void onSeekTo(long pos) { if (originalCallback != null) originalCallback.onSeekTo(pos); }
        @Override
        public void onStop() { if (originalCallback != null) originalCallback.onStop(); }
        @Override
        public void onSetRating(Rating rating) {
            if (rating != null && rating.getRatingStyle() == Rating.RATING_HEART) {
                long now = System.currentTimeMillis();
                if (now - lastLikeVerifyTime > LIKE_COOLDOWN_MS) {
                    isLiked = rating.hasHeart();
                    scheduleDebouncedUpdate();
                }
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
                updateMetadataInternal(metadata);
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
                long now = System.currentTimeMillis();
                if (now - lastPlaybackStateUpdateTime < PLAYBACK_STATE_THROTTLE_MS) {
                    if (isOnlyPositionChanged(originalState)) {
                        chain.proceed();
                        return null;
                    }
                }
                lastPlaybackStateUpdateTime = now;
                PlaybackState modifiedState = injectPlaybackActionsIfNeeded(originalState);
                chain.proceed(new Object[]{modifiedState});
            } else {
                chain.proceed();
            }
            return null;
        }
        private boolean isOnlyPositionChanged(PlaybackState newState) {
            return newState.getState() == (isPlaying ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED);
        }
    }

    private void initIcons() {
        if (iconsInitialized) return;
        logDebug("Loading icons from assets");
        iconPrev = loadIconFromAssets("icons/ic_prev.png");
        iconPlay = loadIconFromAssets("icons/ic_play.png");
        iconPause = loadIconFromAssets("icons/ic_pause.png");
        iconNext = loadIconFromAssets("icons/ic_next.png");
        iconLikeFilled = loadIconFromAssets("icons/ic_like_filled.png");
        iconLikeBorder = loadIconFromAssets("icons/ic_like_border.png");
        iconClose = loadIconFromAssets("icons/ic_close.png");
        iconsInitialized = true;
    }

    private Icon loadIconFromAssets(String filename) {
        if (appContext == null) return null;
        try {
            Context moduleContext = appContext.createPackageContext("io.github.lostmymind.ncm.car.notify", Context.CONTEXT_IGNORE_SECURITY);
            Bitmap bitmap = BitmapFactory.decodeStream(moduleContext.getAssets().open(filename));
            if (bitmap != null) return Icon.createWithBitmap(bitmap);
        } catch (Exception e) { logError("loadIconFromAssets error: " + e.getMessage()); }
        return null;
    }

    private void initPendingIntents() {
        if (pendingIntentsInitialized || appContext == null) return;
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        Intent prevIntent = new Intent(ACTION_MEDIA_CONTROL).putExtra(EXTRA_CONTROL_ACTION, "prev");
        cachedPrevPI = PendingIntent.getBroadcast(appContext, REQUEST_PREV, prevIntent, flags);
        Intent playPauseIntent = new Intent(ACTION_MEDIA_CONTROL).putExtra(EXTRA_CONTROL_ACTION, "playPause");
        cachedPlayPausePI = PendingIntent.getBroadcast(appContext, REQUEST_PLAY_PAUSE, playPauseIntent, flags);
        Intent nextIntent = new Intent(ACTION_MEDIA_CONTROL).putExtra(EXTRA_CONTROL_ACTION, "next");
        cachedNextPI = PendingIntent.getBroadcast(appContext, REQUEST_NEXT, nextIntent, flags);
        updateLikePendingIntent();
        Intent closeIntent = new Intent(ACTION_MEDIA_CONTROL).putExtra(EXTRA_CONTROL_ACTION, "close");
        cachedClosePI = PendingIntent.getBroadcast(appContext, REQUEST_CLOSE, closeIntent, flags);
        pendingIntentsInitialized = true;
    }

    private void updateLikePendingIntent() {
        if (appContext == null) return;
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        int requestCode = REQUEST_LIKE + (isLiked ? 1000 : 0);
        Intent likeIntent = new Intent(ACTION_MEDIA_CONTROL).putExtra(EXTRA_CONTROL_ACTION, "toggleLike");
        cachedLikePI = PendingIntent.getBroadcast(appContext, requestCode, likeIntent, flags);
    }

    private void initNotificationSystem() {
        try {
            notificationManager = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
            mediaSessionManager = (MediaSessionManager) appContext.getSystemService(Context.MEDIA_SESSION_SERVICE);
            audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
            mainHandler = new Handler(Looper.getMainLooper());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Media Playback", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("Music playback control");
                channel.setShowBadge(false);
                channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                notificationManager.createNotificationChannel(channel);
            }
            registerAudioDeviceCallback();
        } catch (Exception e) { logError("initNotificationSystem error: " + e.getMessage()); }
    }

    private void setupMediaController() {
        try {
            if (targetMediaSession == null) return;
            mediaController = targetMediaSession.getController();
            if (mediaController != null) {
                setupMediaControllerCallback();
                MediaMetadata metadata = mediaController.getMetadata();
                PlaybackState state = mediaController.getPlaybackState();
                if (metadata != null) updateMetadataInternal(metadata);
                if (state != null) isPlaying = state.getState() == PlaybackState.STATE_PLAYING;
                resetLastState();
                scheduleDebouncedUpdate();
            }
        } catch (Exception e) { logError("setupMediaController error: " + e.getMessage()); }
    }

    private void setupMediaControllerCallback() {
        if (mediaController == null) return;
        if (mediaCallback != null) mediaController.unregisterCallback(mediaCallback);
        mediaCallback = new MediaController.Callback() {
            @Override
            public void onMetadataChanged(MediaMetadata metadata) {
                if (metadata != null) {
                    isNotificationClosed = false;
                    updateMetadataInternal(metadata);
                    scheduleDebouncedUpdate();
                }
            }
            @Override
            public void onPlaybackStateChanged(PlaybackState state) {
                if (state != null) {
                    boolean newIsPlaying = (state.getState() == PlaybackState.STATE_PLAYING);
                    if (newIsPlaying != isPlaying) {
                        isPlaying = newIsPlaying;
                        scheduleDebouncedUpdate();
                    }
                }
            }
            @Override
            public void onSessionDestroyed() { cancelNotification(); }
        };
        mediaController.registerCallback(mediaCallback, mainHandler);
    }

    private void updateMetadataInternal(MediaMetadata metadata) {
        try {
            String newMediaId = extractMediaId(metadata);
            String newTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
            String newArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
            Bitmap newAlbumArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            boolean songChanged = false;
            if (newMediaId != null && !newMediaId.isEmpty()) {
                if (!newMediaId.equals(currentMediaId)) {
                    songChanged = true;
                    currentMediaId = newMediaId;
                }
            } else if (newTitle != null && !newTitle.equals(currentTitle)) {
                songChanged = true;
            }
            if (songChanged) {
                isLiked = false;
                lastLikeVerifyTime = 0;
                logDebug("Song changed, reset like status");
            }
            // 从 metadata 检测收藏状态
            checkLikeStatusFromMetadata(metadata);
            if (newTitle != null) currentTitle = newTitle;
            currentArtist = newArtist;
            if (newAlbumArt != null) currentAlbumArt = scaleBitmap(newAlbumArt, ALBUM_ART_SIZE);
            else currentAlbumArt = null;
        } catch (Exception e) { logError("updateMetadataInternal error: " + e.getMessage()); }
    }

    private void checkLikeStatusFromMetadata(MediaMetadata metadata) {
        try {
            // 优先检查 USER_RATING
            Rating userRating = metadata.getRating(MediaMetadata.METADATA_KEY_USER_RATING);
            if (userRating != null && userRating.getRatingStyle() == Rating.RATING_HEART) {
                isLiked = userRating.hasHeart();
                logDebug("Like status from USER_RATING: " + isLiked);
                return;
            }
            // 回退检查 RATING
            Rating rating = metadata.getRating(MediaMetadata.METADATA_KEY_RATING);
            if (rating != null && rating.getRatingStyle() == Rating.RATING_HEART) {
                isLiked = rating.hasHeart();
                logDebug("Like status from RATING: " + isLiked);
            }
        } catch (Exception e) { logError("checkLikeStatusFromMetadata error: " + e.getMessage()); }
    }

    private String extractMediaId(MediaMetadata metadata) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            String mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
            if (mediaId != null && !mediaId.isEmpty()) return mediaId;
        }
        String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        return (title != null ? title : "") + "|" + (artist != null ? artist : "");
    }

    private Bitmap scaleBitmap(Bitmap source, int maxSize) {
        if (source == null) return null;
        int width = source.getWidth();
        int height = source.getHeight();
        if (width <= maxSize && height <= maxSize) return source;
        float scale = Math.min((float) maxSize / width, (float) maxSize / height);
        try { return Bitmap.createScaledBitmap(source, Math.round(width * scale), Math.round(height * scale), true); }
        catch (Exception e) { return source; }
    }

    private void resetLastState() { lastMediaId = ""; lastTitle = ""; lastArtist = ""; lastIsPlaying = false; lastIsLiked = false; }
    private boolean hasStateChanged() { return !currentMediaId.equals(lastMediaId) || !currentTitle.equals(lastTitle) || !currentArtist.equals(lastArtist) || isPlaying != lastIsPlaying || isLiked != lastIsLiked; }
    private void updateLastState() { lastMediaId = currentMediaId; lastTitle = currentTitle; lastArtist = currentArtist; lastIsPlaying = isPlaying; lastIsLiked = isLiked; }

    private void scheduleDebouncedUpdate() {
        if (mainHandler == null) return;
        if (debounceRunnable != null) mainHandler.removeCallbacks(debounceRunnable);
        debounceRunnable = new Runnable() {
            @Override
            public void run() {
                if (!hasStateChanged()) { logDebug("State unchanged, skip notification update"); return; }
                updateLastState();
                updateNotificationInternal();
            }
        };
        mainHandler.postDelayed(debounceRunnable, DEBOUNCE_DELAY_MS);
    }

    private void updateNotificationInternal() {
        if (isNotificationClosed) return;
        try {
            if (appContext == null || notificationManager == null) return;
            MediaSession.Token token = targetMediaSession != null ? targetMediaSession.getSessionToken() : null;
            Notification.Builder builder = new Notification.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(currentTitle)
                .setContentText(currentArtist)
                .setOngoing(isPlaying)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setPriority(Notification.PRIORITY_LOW);
            Notification.MediaStyle mediaStyle = new Notification.MediaStyle();
            if (token != null) mediaStyle.setMediaSession(token);
            mediaStyle.setShowActionsInCompactView(0, 1, 2);
            builder.setStyle(mediaStyle);
            addMediaActions(builder);
            if (currentAlbumArt != null) builder.setLargeIcon(currentAlbumArt);
            if (mediaController != null) {
                try {
                    String packageName = mediaController.getPackageName();
                    Intent launchIntent = appContext.getPackageManager().getLaunchIntentForPackage(packageName);
                    if (launchIntent != null) {
                        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
                        PendingIntent contentIntent = PendingIntent.getActivity(appContext, 0, launchIntent, flags);
                        builder.setContentIntent(contentIntent);
                    }
                } catch (Exception ignored) {}
            }
            notificationManager.notify(NOTIFICATION_ID, builder.build());
        } catch (Exception e) { logError("updateNotificationInternal error: " + e.getMessage()); }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void addMediaActions(Notification.Builder builder) {
        if (iconPrev != null) builder.addAction(new Notification.Action.Builder(iconPrev, "Previous", cachedPrevPI).build());
        else builder.addAction(android.R.drawable.ic_media_previous, "Previous", cachedPrevPI);
        Icon playPauseIcon = isPlaying ? iconPause : iconPlay;
        if (playPauseIcon != null) builder.addAction(new Notification.Action.Builder(playPauseIcon, isPlaying ? "Pause" : "Play", cachedPlayPausePI).build());
        else builder.addAction(isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, isPlaying ? "Pause" : "Play", cachedPlayPausePI);
        if (iconNext != null) builder.addAction(new Notification.Action.Builder(iconNext, "Next", cachedNextPI).build());
        else builder.addAction(android.R.drawable.ic_media_next, "Next", cachedNextPI);
        Icon likeIcon = isLiked ? iconLikeFilled : iconLikeBorder;
        if (likeIcon != null) builder.addAction(new Notification.Action.Builder(likeIcon, isLiked ? "Unlike" : "Like", cachedLikePI).build());
        else builder.addAction(isLiked ? android.R.drawable.star_on : android.R.drawable.star_off, isLiked ? "Unlike" : "Like", cachedLikePI);
        if (iconClose != null) builder.addAction(new Notification.Action.Builder(iconClose, "Close", cachedClosePI).build());
        else builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Close", cachedClosePI);
    }

    private void registerMediaControlReceiver() {
        if (mediaControlReceiver != null || appContext == null) return;
        mediaControlReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, android.content.Intent intent) {
                String action = intent.getStringExtra(EXTRA_CONTROL_ACTION);
                if (action == null) return;
                switch (action) {
                    case "prev": if (originalCallback != null) originalCallback.onSkipToPrevious(); break;
                    case "playPause": if (originalCallback != null) { if (isPlaying) originalCallback.onPause(); else originalCallback.onPlay(); } break;
                    case "next": if (originalCallback != null) originalCallback.onSkipToNext(); break;
                    case "toggleLike": handleLikeAction(); break;
                    case "close": handleCloseAction(); break;
                }
            }
        };
        android.content.IntentFilter filter = new android.content.IntentFilter(ACTION_MEDIA_CONTROL);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) appContext.registerReceiver(mediaControlReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED);
        else appContext.registerReceiver(mediaControlReceiver, filter);
    }

    private void handleLikeAction() {
        try {
            boolean newLikedState = !isLiked;
            isLiked = newLikedState;
            lastLikeVerifyTime = System.currentTimeMillis();
            updateLikePendingIntent();

            logDebug("Optimistic like update: " + newLikedState);

            // 收藏按钮专用刷新通道：主线程立即强制刷新
            forceRefreshNotification();

            if (originalCallback != null) {
                Rating rating = Rating.newHeartRating(newLikedState);
                originalCallback.onSetRating(rating);
            }

            scheduleLikeVerification(newLikedState);
        } catch (Exception e) { logError("handleLikeAction error: " + e.getMessage()); }
    }

    // 收藏按钮专用：强制立即刷新通知，绕过所有防抖和状态检查
    private void forceRefreshNotification() {
        if (mainHandler == null) return;
        // 确保在主线程执行
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (appContext == null || notificationManager == null) return;
                    if (isNotificationClosed) {
                        isNotificationClosed = false;
                    }

                    MediaSession.Token token = targetMediaSession != null ? targetMediaSession.getSessionToken() : null;
                    Notification.Builder builder = new Notification.Builder(appContext, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_media_play)
                        .setContentTitle(currentTitle)
                        .setContentText(currentArtist)
                        .setOngoing(isPlaying)
                        .setShowWhen(false)
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .setPriority(Notification.PRIORITY_LOW);

                    Notification.MediaStyle mediaStyle = new Notification.MediaStyle();
                    if (token != null) mediaStyle.setMediaSession(token);
                    mediaStyle.setShowActionsInCompactView(0, 1, 2);
                    builder.setStyle(mediaStyle);

                    addMediaActions(builder);

                    if (currentAlbumArt != null) builder.setLargeIcon(currentAlbumArt);

                    if (mediaController != null) {
                        try {
                            String packageName = mediaController.getPackageName();
                            Intent launchIntent = appContext.getPackageManager().getLaunchIntentForPackage(packageName);
                            if (launchIntent != null) {
                                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
                                PendingIntent contentIntent = PendingIntent.getActivity(appContext, 0, launchIntent, flags);
                                builder.setContentIntent(contentIntent);
                            }
                        } catch (Exception ignored) {}
                    }

                    // 使用相同ID强制更新
                    notificationManager.notify(NOTIFICATION_ID, builder.build());
                    logDebug("Notification force refreshed for like action");
                } catch (Exception e) { logError("forceRefreshNotification error: " + e.getMessage()); }
            }
        });
    }

    private void scheduleLikeVerification(final boolean expectedState) {
        if (mainHandler == null) return;
        if (likeVerifyRunnable != null) mainHandler.removeCallbacks(likeVerifyRunnable);
        likeVerifyRunnable = new Runnable() {
            @Override
            public void run() {
                if (isLiked != expectedState) {
                    logDebug("Like state mismatch, updating notification");
                    updateLikePendingIntent();
                    scheduleDebouncedUpdate();
                } else { logDebug("Like state verified: " + expectedState); }
            }
        };
        mainHandler.postDelayed(likeVerifyRunnable, LIKE_VERIFY_DELAY_MS);
    }

    private void handleCloseAction() {
        try {
            isNotificationClosed = true;
            cancelNotification();
            if (originalCallback != null) originalCallback.onPause();
        } catch (Exception e) { logError("handleCloseAction error: " + e.getMessage()); }
    }

    private void injectInitialPlaybackState() {
        try { if (targetMediaSession == null) return; targetMediaSession.setPlaybackState(buildPlaybackState(PlaybackState.STATE_PAUSED, 0, 1.0f)); } catch (Exception ignored) {}
    }

    private PlaybackState injectPlaybackActionsIfNeeded(PlaybackState original) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            for (PlaybackState.CustomAction action : original.getCustomActions()) {
                if (CUSTOM_ACTION_LIKE.equals(action.getAction())) return original;
            }
        }
        return buildPlaybackState(original.getState(), Math.max(0, original.getPosition()), original.getPlaybackSpeed());
    }

    private PlaybackState buildPlaybackState(int state, long position, float speed) {
        PlaybackState.Builder builder = new PlaybackState.Builder();
        builder.setState(state, position, speed);
        builder.setActions(SUPPORTED_ACTIONS);
        int likeIconRes = isLiked ? android.R.drawable.star_on : android.R.drawable.star_off;
        builder.addCustomAction(new PlaybackState.CustomAction.Builder(CUSTOM_ACTION_LIKE, isLiked ? "Unlike" : "Like", likeIconRes).build());
        builder.addCustomAction(new PlaybackState.CustomAction.Builder(CUSTOM_ACTION_CLOSE, "Close", android.R.drawable.ic_menu_close_clear_cancel).build());
        return builder.build();
    }

    private void registerAudioDeviceCallback() {
        if (audioDeviceCallbackRegistered || audioManager == null) return;
        checkPrivateAudioDevice();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.registerAudioDeviceCallback(new AudioDeviceCallback() {
                @Override
                public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                    boolean addedPrivate = false;
                    for (AudioDeviceInfo device : addedDevices) {
                        if (isPrivateAudioDevice(device.getType())) { currentPrivateDeviceIds.add(device.getId()); addedPrivate = true; }
                    }
                    if (addedPrivate) { hasPrivateAudioDevice = true; logDebug("Private audio device added"); }
                }
                @Override
                public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                    boolean removedPrivate = false;
                    for (AudioDeviceInfo device : removedDevices) { if (currentPrivateDeviceIds.remove(device.getId())) removedPrivate = true; }
                    if (removedPrivate) {
                        hasPrivateAudioDevice = !currentPrivateDeviceIds.isEmpty();
                        logDebug("Private audio device removed, remaining: " + currentPrivateDeviceIds.size());
                        if (!hasPrivateAudioDevice && isPlaying) { logDebug("No private audio device, pausing playback"); pausePlayback(); }
                    }
                }
            }, mainHandler);
            audioDeviceCallbackRegistered = true;
            logDebug("AudioDeviceCallback registered");
        }
    }

    private void checkPrivateAudioDevice() {
        if (audioManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) { hasPrivateAudioDevice = true; return; }
        AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        currentPrivateDeviceIds.clear();
        hasPrivateAudioDevice = false;
        for (AudioDeviceInfo device : devices) { if (isPrivateAudioDevice(device.getType())) { currentPrivateDeviceIds.add(device.getId()); hasPrivateAudioDevice = true; } }
        logDebug("Initial private audio devices: " + currentPrivateDeviceIds.size());
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
            default: return false;
        }
    }

    private void pausePlayback() { try { if (originalCallback != null) originalCallback.onPause(); } catch (Exception e) { logError("pausePlayback error: " + e.getMessage()); } }
    private void cancelNotification() { if (notificationManager != null) notificationManager.cancel(NOTIFICATION_ID); }
}
