package com.andre.airpodscompanion;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

public class AirPodsBatteryWidgetProvider extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            AirPodsBatteryStore.updateWidget(context, appWidgetManager, appWidgetId);
        }
    }
}
