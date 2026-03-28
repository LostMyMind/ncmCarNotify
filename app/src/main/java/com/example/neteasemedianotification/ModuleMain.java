package com.example.neteasemedianotification;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public class ModuleMain extends XposedModule {

    private static final String TARGET_PACKAGE = "com.netease.cloudmusic.iot";
    private static final String TAG = "[MediaNotifyHook]";
    private static final String CHANNEL_ID = "netease_media_playback";
    private static final int NOTIFICATION_ID = 10086;
    private static final String CUSTOM_ACTION_LIKE = "com.example.neteasemedianotification.TOGGLE_LIKE";

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
        
        log(Log.INFO, TAG, "========== Hook Started ==========");
        log(Log.INFO, TAG, "Package: " + param.getPackageName());

        try {
            Method attachMethod = Application.class.getDeclaredMethod("attach", Context.class);
            hook(attachMethod).intercept(new ApplicationAttachHooker());

            Method onCreateMethod = Application.class.getDeclaredMethod("onCreate");
            hook(onCreateMethod).intercept(new ApplicationOnCreateHooker());

            hook(MediaSession.class.getDeclaredConstructor(Context.class, String.class))
                .intercept(new MediaSessionCtorHooker1());
            hook(MediaSession.class.getDeclaredConstructor(Context.class, String.class, android.os.Bundle.class))
                .intercept(new MediaSessionCtorHooker2());

            // Hook setCallback to intercept custom actions
            Method setCallbackMethod = MediaSession.class.getDeclaredMethod("setCallback", MediaSession.Callback.class, Handler.class);
            hook(setCallbackMethod).intercept(new MediaSessionSetCallbackHooker());

            Method setActiveMethod = MediaSession.class.getDeclaredMethod("setActive", boolean.class);
            hook(setActiveMethod).intercept(new MediaSessionSetActiveHooker());

            Method setMetadataMethod = MediaSession.class.getDeclaredMethod("setMetadata", MediaMetadata.class);
            hook(setMetadataMethod).intercept(new MediaSessionSetMetadataHooker());

            Method setPlaybackStateMethod = MediaSession.class.getDeclaredMethod("setPlaybackState", PlaybackState.class);
            hook(setPlaybackStateMethod).intercept(new MediaSessionSetPlaybackStateHooker());

            log(Log.INFO, TAG, "All hooks installed successfully");
        } catch (Exception e) {
            log(Log.INFO, TAG, "Hook installation failed: " + e.getMessage());
        }
    }

    public class ApplicationAttachHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            appContext = (Context) chain.getArg(0);
            Object result = chain.proceed();
            initNotificationSystem();
            log(Log.INFO, TAG, "Application.attach hooked");
            return result;
        }
    }

    public class ApplicationOnCreateHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object result = chain.proceed();
            startMediaSessionMonitor();
            log(Log.INFO, TAG, "Application.onCreate hooked");
            return result;
        }
    }

    public class MediaSessionCtorHooker1 implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            chain.proceed();
            targetMediaSession = (MediaSession) chain.getThisObject();
            log(Log.INFO, TAG, "MediaSession(1) created");
            return null;
        }
    }

    public class MediaSessionCtorHooker2 implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            chain.proceed();
            targetMediaSession = (MediaSession) chain.getThisObject();
            log(Log.INFO, TAG, "MediaSession(2) created");
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
                log(Log.INFO, TAG, "MediaSession.setCallback hooked, wrapping callback");
                // Replace with our wrapper
                return chain.proceed(new Object[]{new LikeCallbackWrapper(), handler});
            }
            return chain.proceed();
        }
    }

    public class LikeCallbackWrapper extends MediaSession.Callback {
        @Override
        public void onCustomAction(String action, Bundle extras) {
            log(Log.INFO, TAG, "LikeCallbackWrapper.onCustomAction: " + action);
            if (CUSTOM_ACTION_LIKE.equals(action)) {
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
            log(Log.INFO, TAG, "LikeCallbackWrapper.onSetRating");
            if (originalCallback != null) originalCallback.onSetRating(rating);
        }
    }

    private void handleLikeAction() {
        try {
            log(Log.INFO, TAG, "handleLikeAction called, isLiked=" + isLiked);
            
            if (originalCallback != null) {
                Rating rating = Rating.newHeartRating(!isLiked);
                originalCallback.onSetRating(rating);
            }
            
            isLiked = !isLiked;
            updatePlaybackStateAndNotification();
            log(Log.INFO, TAG, "handleLikeAction completed: isLiked=" + isLiked);
        } catch (Exception e) {
            log(Log.INFO, TAG, "handleLikeAction error: " + e.getMessage());
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
                log(Log.INFO, TAG, "MediaSession activated");
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

    private void injectInitialPlaybackState() {
        try {
            if (targetMediaSession == null) return;
            
            PlaybackState initialState = buildPlaybackState(PlaybackState.STATE_PAUSED, 0, 1.0f);
            targetMediaSession.setPlaybackState(initialState);
            log(Log.INFO, TAG, "Injected initial PlaybackState");
        } catch (Exception e) {
            log(Log.INFO, TAG, "injectInitialPlaybackState error: " + e.getMessage());
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
        } catch (Exception e) {
            log(Log.INFO, TAG, "updatePlaybackStateAndNotification error: " + e.getMessage());
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
        } catch (Exception e) {
            log(Log.INFO, TAG, "initNotificationSystem error: " + e.getMessage());
        }
    }

    private void startMediaSessionMonitor() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                findActiveMediaSession();
                mainHandler.postDelayed(this, 2000);
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
        } catch (Exception e) {
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
                
                if (metadata != null) updateMetadata(metadata);
                if (state != null) updatePlaybackState(state);
                
                updateNotification();
            }
        } catch (Exception e) {
            log(Log.INFO, TAG, "setupMediaController error: " + e.getMessage());
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
                    updateNotification();
                }
            }

            @Override
            public void onPlaybackStateChanged(PlaybackState state) {
                if (state != null) {
                    updatePlaybackState(state);
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
        } catch (Exception e) {
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
            builder.setStyle(mediaStyle);

            // Add actions (only for expanded view fallback)
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
                } catch (Exception e) {
                }
            }

            Notification notification = builder.build();
            notificationManager.notify(NOTIFICATION_ID, notification);

        } catch (Exception e) {
            log(Log.INFO, TAG, "updateNotification error: " + e.getMessage());
        }
    }

    private void addMediaActions(Notification.Builder builder) {
        try {
            int likeIcon = isLiked ? android.R.drawable.star_on : android.R.drawable.star_off;
            builder.addAction(likeIcon, isLiked ? "Unlike" : "Like", createEmptyPendingIntent());
            builder.addAction(android.R.drawable.ic_media_previous, "Previous", createEmptyPendingIntent());
            int playPauseIcon = isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;
            builder.addAction(playPauseIcon, isPlaying ? "Pause" : "Play", createEmptyPendingIntent());
            builder.addAction(android.R.drawable.ic_media_next, "Next", createEmptyPendingIntent());
        } catch (Exception e) {
            log(Log.INFO, TAG, "addMediaActions error: " + e.getMessage());
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
        } catch (Exception e) {
        }
    }
}