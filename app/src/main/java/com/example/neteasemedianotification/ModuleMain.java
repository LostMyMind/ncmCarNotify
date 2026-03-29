package com.example.neteasemedianotification;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
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

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public class ModuleMain extends XposedModule {

    private static final String TARGET_PACKAGE = "com.netease.cloudmusic.iot";
    private static final String CHANNEL_ID = "netease_media_playback";
    private static final int NOTIFICATION_ID = 10086;
    private static final String CUSTOM_ACTION_LIKE = "com.example.neteasemedianotification.TOGGLE_LIKE";
    private static final String CUSTOM_ACTION_CLOSE = "com.example.neteasemedianotification.CLOSE";

    private static final long SUPPORTED_ACTIONS =
        PlaybackState.ACTION_PLAY |
        PlaybackState.ACTION_PAUSE |
        PlaybackState.ACTION_PLAY_PAUSE |
        PlaybackState.ACTION_SKIP_TO_PREVIOUS |
        PlaybackState.ACTION_SKIP_TO_NEXT |
        PlaybackState.ACTION_STOP |
        PlaybackState.ACTION_SEEK_TO |
        PlaybackState.ACTION_SET_RATING;

    private static ModuleMain instance;
    
    private Context appContext;
    private NotificationManager notificationManager;
    private MediaSessionManager mediaSessionManager;
    private Handler mainHandler;
    
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

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        
        instance = this;

        try {
            Method attachMethod = Application.class.getDeclaredMethod("attach", Context.class);
            hook(attachMethod).intercept(new ApplicationAttachHooker());

            Method onCreateMethod = Application.class.getDeclaredMethod("onCreate");
            hook(onCreateMethod).intercept(new ApplicationOnCreateHooker());

            hook(MediaSession.class.getDeclaredConstructor(Context.class, String.class))
                .intercept(new MediaSessionCtorHooker1());
            hook(MediaSession.class.getDeclaredConstructor(Context.class, String.class, Bundle.class))
                .intercept(new MediaSessionCtorHooker2());

            Method setCallbackMethod = MediaSession.class.getDeclaredMethod("setCallback", MediaSession.Callback.class, Handler.class);
            hook(setCallbackMethod).intercept(new MediaSessionSetCallbackHooker());

            Method setActiveMethod = MediaSession.class.getDeclaredMethod("setActive", boolean.class);
            hook(setActiveMethod).intercept(new MediaSessionSetActiveHooker());

            Method setMetadataMethod = MediaSession.class.getDeclaredMethod("setMetadata", MediaMetadata.class);
            hook(setMetadataMethod).intercept(new MediaSessionSetMetadataHooker());

            Method setPlaybackStateMethod = MediaSession.class.getDeclaredMethod("setPlaybackState", PlaybackState.class);
            hook(setPlaybackStateMethod).intercept(new MediaSessionSetPlaybackStateHooker());
        } catch (Exception ignored) {
        }
    }

    public class ApplicationAttachHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            appContext = (Context) chain.getArg(0);
            Object result = chain.proceed();
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
                updatePlaybackStateAndNotification();
            }
            if (originalCallback != null) originalCallback.onSetRating(rating);
        }
    }

    private void handleCloseAction() {
        try {
            if (originalCallback != null) {
                originalCallback.onPause();
            }
            cancelNotification();
        } catch (Exception ignored) {
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

    public class MediaSessionSetActiveHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            boolean active = (boolean) chain.getArg(0);
            MediaSession session = (MediaSession) chain.getThisObject();
            
            chain.proceed();
            
            if (active && session == targetMediaSession) {
                setupMediaController();
                injectInitialPlaybackState();
            } else if (!active) {
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
                updateMetadata(metadata);
                checkLikeStatusFromMetadata(metadata);
                updateNotification();
            }
            return null;
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

    public class MediaSessionSetPlaybackStateHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            MediaSession session = (MediaSession) chain.getThisObject();
            PlaybackState originalState = (PlaybackState) chain.getArg(0);
            
            if (originalState != null && session == targetMediaSession) {
                checkLikeStatusFromPlaybackState(originalState);
                
                PlaybackState modifiedState = injectPlaybackActions(originalState);
                chain.proceed(new Object[]{modifiedState});
                updatePlaybackState(modifiedState);
                updateNotification();
            } else {
                chain.proceed();
                if (originalState != null && session == targetMediaSession) {
                    updatePlaybackState(originalState);
                    updateNotification();
                }
            }
            return null;
        }
    }

    private void checkLikeStatusFromPlaybackState(PlaybackState state) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                Bundle extras = state.getExtras();
                if (extras != null) {
                    String[] likeKeys = {"liked", "is_liked", "favorite", "is_favorite", "like", "love"};
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
        
        int likeIcon = isLiked ? android.R.drawable.star_on : android.R.drawable.star_off;
        String likeLabel = isLiked ? "Unlike" : "Like";
        PlaybackState.CustomAction likeAction = new PlaybackState.CustomAction.Builder(
            CUSTOM_ACTION_LIKE, likeLabel, likeIcon
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
            
            int likeIcon = isLiked ? android.R.drawable.star_on : android.R.drawable.star_off;
            String likeLabel = isLiked ? "Unlike" : "Like";
            PlaybackState.CustomAction likeAction = new PlaybackState.CustomAction.Builder(
                CUSTOM_ACTION_LIKE, likeLabel, likeIcon
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
            
            String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
            Bitmap art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            
            if (title != null) currentTitle = title;
            if (artist != null) currentArtist = artist;
            if (art != null) currentAlbumArt = art;
        } catch (Exception ignored) {
        }
    }

    private void updatePlaybackState(PlaybackState state) {
        isPlaying = state.getState() == PlaybackState.STATE_PLAYING;
    }

    private void updateNotification() {
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
                .setOngoing(true)
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
                        PendingIntent contentIntent = PendingIntent.getActivity(
                            appContext, 0, launchIntent, 
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        );
                        builder.setContentIntent(contentIntent);
                    }
                } catch (Exception ignored) {
                }
            }

            Notification notification = builder.build();
            notificationManager.notify(NOTIFICATION_ID, notification);

        } catch (Exception ignored) {
        }
    }

    private void addMediaActions(Notification.Builder builder) {
        try {
            builder.addAction(android.R.drawable.ic_media_previous, "Previous", createEmptyPendingIntent());
            int playPauseIcon = isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;
            builder.addAction(playPauseIcon, isPlaying ? "Pause" : "Play", createEmptyPendingIntent());
            builder.addAction(android.R.drawable.ic_media_next, "Next", createEmptyPendingIntent());
            int likeIcon = isLiked ? android.R.drawable.star_on : android.R.drawable.star_off;
            builder.addAction(likeIcon, isLiked ? "Unlike" : "Like", createEmptyPendingIntent());
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Close", createEmptyPendingIntent());
        } catch (Exception ignored) {
        }
    }

    private PendingIntent createEmptyPendingIntent() {
        Intent intent = new Intent();
        return PendingIntent.getActivity(appContext, 0, intent, PendingIntent.FLAG_IMMUTABLE);
    }

    private void cancelNotification() {
        try {
            if (notificationManager != null) {
                notificationManager.cancel(NOTIFICATION_ID);
            }
        } catch (Exception ignored) {
        }
    }
}


