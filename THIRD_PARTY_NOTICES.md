# Third-party protocol references

The China protocol and WBSK algorithm were ported with reference to pybyd_china in lejw0925/byd-China and the local BYD-re wbsk.js/client.js. The byd-China MIT license and copyright notice are retained in docs/third-party/byd-China-LICENSE.

wbsk.properties contains fixed protocol tables and key blobs matching the reference implementation; these are not user credentials or vehicle/session data. The reference states the tables were recovered from libwbsk_crypto_tool.so. Their presence here does not assert a separate MIT grant by the vehicle manufacturer. Review table redistribution provenance before public release.

No official APK, native library, personal account, PIN, VIN or session cache is included.

## Eclipse Paho Java MQTT

org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5 supplies the MQTT 3.1.1 transport. It is dual-licensed under EPL 2.0 / EDL 1.0; this distribution uses EDL 1.0. The original copyright and license are retained in docs/third-party/Paho-EDL-1.0.txt and packaged in app/src/main/assets/licenses/Paho-EDL-1.0.txt. Upstream: https://github.com/eclipse-paho/paho.mqtt.java/tree/v1.2.5 .
