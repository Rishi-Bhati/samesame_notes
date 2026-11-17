# Glance stores each widget's GlanceAppWidget class by its name (receiver -> provider mapping in its DataStore) and finds the
# widgets to redraw by that name. Keep the names stable across builds and keep R8 from merging these classes, so an app
# update can't make placed widgets unreachable for updateAll().
-keepnames class * extends androidx.glance.appwidget.GlanceAppWidget
-keepnames class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver
