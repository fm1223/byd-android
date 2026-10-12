# Add narrowly scoped rules only when required by an introduced dependency.

# Paho loads transport factories through META-INF/services.
-keep class * implements org.eclipse.paho.client.mqttv3.spi.NetworkModuleFactory { public <init>(); }
