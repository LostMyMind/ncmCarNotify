# 正常版本关键特征备忘录

## 日期
2026-03-27

## 版本状态
**此版本已验证正常工作** - 播放时按钮不消失

---

## 关键代码特征

### 1. Hooker 实现方式
使用独立的内部类，不要用 lambda：
``java
// 正确方式 ✓
hook(attachMethod).intercept(new ApplicationAttachHooker());

public class ApplicationAttachHooker implements XposedInterface.Hooker {
    @Override
    public Object intercept(XposedInterface.Chain chain) throws Throwable {
        // ...
    }
}

// 错误方式 ✗
hook(attachMethod).intercept(chain -> { ... });
``

### 2. MediaSession 构造函数 Hook
必须使用 chain.getThisObject()，不能用返回值：
``java
public class MediaSessionCtorHooker1 implements XposedInterface.Hooker {
    @Override
    public Object intercept(XposedInterface.Chain chain) throws Throwable {
        chain.proceed();
        targetMediaSession = (MediaSession) chain.getThisObject();  // 正确 ✓
        // targetMediaSession = (MediaSession) result;  // 错误 ✗，result 是 null
        return null;
    }
}
``

### 3. 广播处理方式
使用单一 Action + intent extra 传递具体操作：
``java
private static final String ACTION_MEDIA_CONTROL = "com.example.neteasemedianotification.MEDIA_CONTROL";

// BroadcastReceiver 中
String action = intent.getStringExtra(""action"");
switch (action) {
    case ""skipToPrevious"": ...
    case ""play"": ...
    case ""pause"": ...
    case ""playPause"": ...
    case ""skipToNext"": ...
    case ""toggleLike"": ...  // 新增收藏
}

// PendingIntent
Intent intent = new Intent(ACTION_MEDIA_CONTROL);
intent.putExtra(""action"", ""toggleLike"");
PendingIntent pi = PendingIntent.getBroadcast(appContext, requestCode, intent, flags);
``

### 4. 按钮配置
``java
// 4个按钮时
mediaStyle.setShowActionsInCompactView(1, 2, 3);  // 显示后3个（上一首、播放暂停、下一首）

// PendingIntent requestCode: 1, 2, 3, 4（收藏用4）
``

### 5. setPlaybackState hook 中必须调用 updateNotification()
``java
public class MediaSessionSetPlaybackStateHooker implements XposedInterface.Hooker {
    @Override
    public Object intercept(XposedInterface.Chain chain) throws Throwable {
        // ...
        chain.proceed(new Object[]{modifiedState});
        updatePlaybackState(modifiedState);
        updateNotification();  // 必须调用！
        // ...
    }
}
``

### 6. MediaController.Callback 中也要调用 updateNotification()
``java
mediaCallback = new MediaController.Callback() {
    @Override
    public void onMetadataChanged(MediaMetadata metadata) {
        if (metadata != null) {
            updateMetadata(metadata);
            updateNotification();  // 必须调用！
        }
    }

    @Override
    public void onPlaybackStateChanged(PlaybackState state) {
        if (state != null) {
            updatePlaybackState(state);
            updateNotification();  // 必须调用！
        }
    }
};
``

---

## 收藏功能实现要点

### 按钮添加位置
在 ddMediaActions() 方法最前面添加收藏按钮：
``java
private void addMediaActions(Notification.Builder builder) {
    // 收藏按钮（第一个）
    Intent likeIntent = new Intent(ACTION_MEDIA_CONTROL);
    likeIntent.putExtra(""action"", ""toggleLike"");
    PendingIntent likePI = PendingIntent.getBroadcast(
        appContext, 4, likeIntent, 
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
    );
    int likeIcon = isLiked ? android.R.drawable.star_on : android.R.drawable.star_off;
    builder.addAction(likeIcon, isLiked ? ""Unlike"" : ""Like"", likePI);
    
    // 上一首...
    // 播放暂停...
    // 下一首...
}
``

### 收藏逻辑实现
``java
private boolean isLiked = false;

private void toggleLike() {
    if (mediaController != null) {
        MediaController.TransportControls controls = mediaController.getTransportControls();
        if (controls != null) {
            Rating rating = Rating.newHeartRating(!isLiked);
            controls.setRating(rating);
        }
    }
    isLiked = !isLiked;
    updateNotification();
}
``

### executeMediaAction 中添加收藏处理
``java
case ""toggleLike"":
    toggleLike();
    break;
``

### updateMetadata 中重置收藏状态
``java
private void updateMetadata(MediaMetadata metadata) {
    String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
    // ...
    if (title != null && !title.equals(lastTitle)) {
        lastTitle = title;
        isLiked = false;  // 新歌曲重置收藏状态
    }
}
``

---

## 完整差异清单

| 项目 | 正常值 |
|------|--------|
| Hooker | 独立内部类 |
| MediaSession获取 | chain.getThisObject() |
| 广播Action | 单一ACTION_MEDIA_CONTROL |
| 按钮数量 | 4个（收藏+3控制） |
| setShowActionsInCompactView | (1, 2, 3) |
| PendingIntent requestCode | 4, 1, 2, 3 |
| setPlaybackState中 | 调用updateNotification() |
| onPlaybackStateChanged中 | 调用updateNotification() |

---

## 回滚方案
如果新版本出问题，恢复到这个正常版本：
- 文件路径：D:\ZASProduct\NeteaseMediaNotificationBak\app\src\main\java\com\example\neteasemedianotification\ModuleMain.java