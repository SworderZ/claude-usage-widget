# The bundled Nothing SDK includes Binder callbacks and no consumer rules.
-keep class com.nothing.** { *; }

# Glance instantiates action callbacks by class name from RemoteViews.
-keep class * implements androidx.glance.appwidget.action.ActionCallback { public <init>(); }
