package io.github.lostmymind.ncm.car.notify;

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

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class ModuleMain implements IXposedHookLoadPackage {

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

    private static ModuleMain instance;
    
    private Context appContext;
    private NotificationManager notificationManager;
    private MediaSessionManager mediaSessionManager;
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
    
    private Icon iconPrev = null;
    private Icon iconPlay = null;
    private Icon iconPause = null;
    private Icon iconNext = null;
    private Icon iconLikeFilled = null;
    private Icon iconLikeBorder = null;
    private Icon iconClose = null;
    private boolean iconsInitialized = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }
        
        instance = this;
        Log.d(TAG, "模块已加载，开始Hook");

        try {
            // Hook Application.attach
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    appContext = (Context) param.args[0];
                    Log.d(TAG, "Application.attach 被调用");
                    initIcons();
                    initNotificationSystem();
                }
            });

            // Hook Application.onCreate
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    startMediaSessionMonitor();
                }
            });

            // Hook MediaSession 构造函数
            XposedHelpers.findAndHookConstructor(MediaSession.class, Context.class, String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    targetMediaSession = (MediaSession) param.thisObject;
                }
            });

            XposedHelpers.findAndHookConstructor(MediaSession.class, Context.class, String.class, Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    targetMediaSession = (MediaSession) param.thisObject;
                }
            });

            // Hook MediaSession.setCallback
            XposedHelpers.findAndHookMethod(MediaSession.class, "setCallback", MediaSession.Callback.class, Handler.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    MediaSession session = (MediaSession) param.thisObject;
                    MediaSession.Callback callback = (MediaSession.Callback) param.args[0];
                    Handler handler = (Handler) param.args[1];
                    
                    if (session == targetMediaSession && callback != null) {
                        originalCallback = callback;
                        param.args[0] = new LikeCallbackWrapper();
                    }
                }
            });

            // Hook MediaSession.setActive
            XposedHelpers.findAndHookMethod(MediaSession.class, "setActive", boolean.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    boolean active = (boolean) param.args[0];
                    MediaSession session = (MediaSession) param.thisObject;
                    
                    if (active && session == targetMediaSession) {
                        isNotificationClosed = false;
                        setupMediaController();
                        injectInitialPlaybackState();
                    } else if (!active) {
                        isNotificationClosed = true;
                        cancelNotification();
                    }
                }
            });

            // Hook MediaSession.setMetadata
            XposedHelpers.findAndHookMethod(MediaSession.class, "setMetadata", MediaMetadata.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    MediaMetadata metadata = (MediaMetadata) param.args[0];
                    MediaSession session = (MediaSession) param.thisObject;
                    
                    if (metadata != null && session == targetMediaSession) {
                        updateMetadata(metadata);
                        checkLikeStatusFromMetadata(metadata);
                        updateNotification();
                    }
                }
            });

            // Hook MediaSession.setPlaybackState
            XposedHelpers.findAndHookMethod(MediaSession.class, "setPlaybackState", PlaybackState.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    MediaSession session = (MediaSession) param.thisObject;
                    PlaybackState originalState = (PlaybackState) param.args[0];
                    
                    if (originalState != null && session == targetMediaSession) {
                        checkLikeStatusFromPlaybackState(originalState);
                        PlaybackState modifiedState = injectPlaybackActions(originalState);
                        param.args[0] = modifiedState;
                    }
                }
                
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    MediaSession session = (MediaSession) param.thisObject;
                    PlaybackState state = (PlaybackState) param.args[0];
                    
                    if (state != null && session == targetMediaSession) {
                        updatePlaybackState(state);
                        updateNotification();
                    }
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Hook失败: " + e.getMessage());
        }
    }

    private void initIcons() {
        if (iconsInitialized) {
            return;
        }
        
        Log.d(TAG, "开始从 assets 加载图标");
        
        iconPrev = loadIconFromAssets("icons/ic_prev.png");
        iconPlay = loadIconFromAssets("icons/ic_play.png");
        iconPause = loadIconFromAssets("icons/ic_pause.png");
        iconNext = loadIconFromAssets("icons/ic_next.png");
        iconLikeFilled = loadIconFromAssets("icons/ic_like_filled.png");
        iconLikeBorder = loadIconFromAssets("icons/ic_like_border.png");
        iconClose = loadIconFromAssets("icons/ic_close.png");
        
        Log.d(TAG, "图标加载结果: prev=" + (iconPrev != null) + ", play=" + (iconPlay != null) + ", close=" + (iconClose != null));
        
        iconsInitialized = true;
    }
    
    private Icon loadIconFromAssets(String filename) {
        if (appContext == null) return null;
        try {
            Context moduleContext = appContext.createPackageContext(
                "io.github.lostmymind.ncm.car.notify", 
                Context.CONTEXT_IGNORE_SECURITY
            );
            android.content.res.AssetManager assets = moduleContext.getAssets();
            Bitmap bitmap = BitmapFactory.decodeStream(assets.open(filename));
            if (bitmap != null) {
                return Icon.createWithBitmap(bitmap);
            }
        } catch (Exception e) {
            Log.e(TAG, "loadIconFromAssets error: " + e.getMessage());
        }
        return null;
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
        
        int likeIconRes = isLiked ? android.R.drawable.star_on : android.R.drawable.star_off;
        String likeLabel = isLiked ? "Unlike" : "Like";
        PlaybackState.CustomAction likeAction = new PlaybackState.CustomAction.Builder(
            CUSTOM_ACTION_LIKE, likeLabel, likeIconRes
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
            String likeLabel = isLiked ? "Unlike" : "Like";
            PlaybackState.CustomAction likeAction = new PlaybackState.CustomAction.Builder(
                CUSTOM_ACTION_LIKE, likeLabel, likeIconRes
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
        if (isNotificationClosed) {
            return;
        }
        
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
                        PendingIntent contentIntent = PendingIntent.getActivity(
                            appContext, 0, launchIntent, flags
                        );
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

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void addMediaActions(Notification.Builder builder) {
        try {
            registerMediaControlReceiver();
            
            // Previous
            PendingIntent prevPI = createControlPendingIntent("prev", REQUEST_PREV);
            if (iconPrev != null) {
                builder.addAction(new Notification.Action.Builder(iconPrev, "Previous", prevPI).build());
            } else {
                builder.addAction(android.R.drawable.ic_media_previous, "Previous", prevPI);
            }
            
            // Play/Pause
            PendingIntent playPausePI = createControlPendingIntent("playPause", REQUEST_PLAY_PAUSE);
            Icon playPauseIcon = isPlaying ? iconPause : iconPlay;
            if (playPauseIcon != null) {
                builder.addAction(new Notification.Action.Builder(playPauseIcon, isPlaying ? "Pause" : "Play", playPausePI).build());
            } else {
                int res = isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;
                builder.addAction(res, isPlaying ? "Pause" : "Play", playPausePI);
            }
            
            // Next
            PendingIntent nextPI = createControlPendingIntent("next", REQUEST_NEXT);
            if (iconNext != null) {
                builder.addAction(new Notification.Action.Builder(iconNext, "Next", nextPI).build());
            } else {
                builder.addAction(android.R.drawable.ic_media_next, "Next", nextPI);
            }
            
            // Like
            int likeRequestCode = REQUEST_LIKE + (isLiked ? 1000 : 0);
            PendingIntent likePI = createControlPendingIntent("toggleLike", likeRequestCode);
            Icon likeIcon = isLiked ? iconLikeFilled : iconLikeBorder;
            if (likeIcon != null) {
                builder.addAction(new Notification.Action.Builder(likeIcon, isLiked ? "Unlike" : "Like", likePI).build());
            } else {
                int res = isLiked ? android.R.drawable.star_on : android.R.drawable.star_off;
                builder.addAction(res, isLiked ? "Unlike" : "Like", likePI);
            }
            
            // Close
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
}
