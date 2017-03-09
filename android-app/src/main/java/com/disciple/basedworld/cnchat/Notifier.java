package com.disciple.basedworld.cnchat;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.support.v4.app.NotificationCompat;

public class Notifier extends BroadcastReceiver {

    private static NotificationCompat.Builder builder = null;
    static final int ID = 2;

    static void prepareNotification(Context ctx, PendingIntent clickAction, String title, String text) {
        if(clickAction == null) clickAction = PendingIntent.getActivity(ctx, 0, new Intent(ctx, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);
        if(title == null) title = "CNChat";
        if(text == null) text = "By the time you hear this message, it will already be far too late.";;
        builder = new NotificationCompat.Builder(ctx).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title).setContentText(text)
                .setContentIntent(clickAction);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if(builder == null) {
            prepareNotification(context, null, null, null);
        }
        manager.notify(ID, builder.build());
        builder = null;
    }
}
