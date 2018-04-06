/*
 * Copyright © 2018 Dennis Schulmeister-Zimolong
 * 
 * E-Mail: dhbw@windows3.de
 * Webseite: https://www.wpvs.de/
 * 
 * Dieser Quellcode ist lizenziert unter einer
 * Creative Commons Namensnennung 4.0 International Lizenz.
 */
package dhbwka.wwi.vertsys.pubsub.fahrzeug;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

/**
 * Hauptklasse unseres kleinen Progrämmchens.
 *
 * Mit etwas Google-Maps-Erfahrung lassen sich relativ einfach eigene
 * Wegstrecken definieren. Man muss nur Rechtsklick auf einen Punkt machen und
 * "Was ist hier?" anklicken, um die Koordinaten zu sehen. Allerdings speichert
 * Goolge Maps eine Nachkommastelle mehr, als das ITN-Format erlaubt. :-)
 */
public class Main {

    public static void main(String[] args) throws Exception {
        // Fahrzeug-ID abfragen
        String vehicleId = Utils.askInput("Beliebige Fahrzeug-ID", "postauto");

        // Zu fahrende Strecke abfragen
        File workdir = new File("./waypoints");
        String[] waypointFiles = workdir.list((File dir, String name) -> {
            return name.toLowerCase().endsWith(".itn");
        });

        System.out.println();
        System.out.println("Aktuelles Verzeichnis: " + workdir.getCanonicalPath());
        System.out.println();
        System.out.println("Verfügbare Wegstrecken");
        System.out.println();

        for (int i = 0; i < waypointFiles.length; i++) {
            System.out.println("  [" + i + "] " + waypointFiles[i]);
        }

        System.out.println();
        int index = Integer.parseInt(Utils.askInput("Zu fahrende Strecke", "0"));

        // TODO: Methode parseItnFile() unten ausprogrammieren - erledigt
        List<WGS84> waypoints = parseItnFile(new File(workdir, waypointFiles[index]));

        // Adresse des MQTT-Brokers abfragen
        String mqttAddress = Utils.askInput("MQTT-Broker", Utils.MQTT_BROKER_ADDRESS);

        // TODO: Sicherstellen, dass bei einem Verbindungsabbruch eine sog.
        // LastWill-Nachricht gesendet wird, die auf den Verbindungsabbruch
        // hinweist. Die Nachricht soll eine "StatusMessage" sein, bei der das
        // Feld "type" auf "StatusType.CONNECTION_LOST" gesetzt ist.
        StatusMessage lastWillMessage = new StatusMessage();
        lastWillMessage.type = StatusType.CONNECTION_LOST;

        // Die Nachricht muss dem MqttConnectOptions-Objekt übergeben werden
        // und soll an das Topic Utils.MQTT_TOPIC_NAME gesendet werden.
        // TODO: Verbindung zum MQTT-Broker herstellen.
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        options.setWill(Utils.MQTT_TOPIC_NAME, lastWillMessage.toJson(), index, true);

        String clientId = "Fahrzeug-Aufgabe-" + System.currentTimeMillis();

        System.out.println("Client ID: " + clientId);
        System.out.println("Starte Empfang. Drücke ENTER zum Beenden.");
        System.out.println();

        MqttClient client = new MqttClient(mqttAddress, clientId);
        client.connect(options);

        // TODO: Statusmeldung mit "type" = "StatusType.VEHICLE_READY" senden.
        // Die Nachricht soll soll an das Topic Utils.MQTT_TOPIC_NAME gesendet
        // werden.
        StatusMessage sm = new StatusMessage();
        sm.type = StatusType.VEHICLE_READY;

        if (client != null && client.isConnected()) {
            MqttMessage message = new MqttMessage(sm.toJson());
            message.setQos(2);
            client.publish(Utils.MQTT_TOPIC_NAME, message);
        }

        // TODO: Thread starten, der jede Sekunde die aktuellen Sensorwerte
        // des Fahrzeugs ermittelt und verschickt. Die Sensordaten sollen
        // an das Topic Utils.MQTT_TOPIC_NAME + "/" + vehicleId gesendet werden.7
        //static 
        Vehicle vehicle = new Vehicle(vehicleId, waypoints);
        vehicle.startVehicle();

        Timer timer = new Timer();

        TimerTask tt = new TimerTask(new Runnable() {
            @Override
            public void run() {
                try {
                    while (true) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ex) {
                        }

                        if (client != null && client.isConnected()) {
                            MqttMessage message = new MqttMessage();

                            message.setQos(2);

                            client.publish(Utils.MQTT_TOPIC_NAME + "/" + vehicleId, message);

                        }
                    }
                } catch (MqttException ex) {
                    Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        });

        TimerTask tt = new TimerTask(new Runnable() {
            @Override
            public void run() {
                try {
                    while (true) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ex) {
                        }

                        if (client != null && client.isConnected()) {
                            MqttMessage message = new MqttMessage();

                            message.setQos(2);

                            client.publish(Utils.MQTT_TOPIC_NAME + "/" + vehicleId, message);

                        }
                    }
                } catch (MqttException ex) {
                    Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        });

        // Warten, bis das Programm beendet werden soll
        Utils.fromKeyboard.readLine();

        vehicle.stopVehicle();

        // TODO: Oben vorbereitete LastWill-Nachricht hier manuell versenden,
        // da sie bei einem regulären Verbindungsende nicht automatisch
        // verschickt wird.
        // Anschließend die Verbindung trennen und den oben gestarteten Thread
        // beenden, falls es kein Daemon-Thread ist.
    }

    /**
     * Öffnet die in "filename" übergebene ITN-Datei und extrahiert daraus die
     * Koordinaten für die Wegstrecke des Fahrzeugs. Das Dateiformat ist ganz
     * simpel:
     *
     * <pre>
     * 0845453|4902352|Point 1 |0|
     * 0848501|4900249|Point 2 |0|
     * 0849295|4899460|Point 3 |0|
     * 0849796|4897723|Point 4 |0|
     * </pre>
     *
     * Jede Zeile enthält einen Wegpunkt. Die Datenfelder einer Zeile werden
     * durch | getrennt. Das erste Feld ist die "Longitude", das zweite Feld die
     * "Latitude". Die Zahlen müssen durch 100_000.0 geteilt werden.
     *
     * @param file ITN-Datei
     * @return Liste mit Koordinaten
     * @throws java.io.IOException
     */
    public static List<WGS84> parseItnFile(File file) throws IOException {
        List<WGS84> waypoints = new ArrayList<>();

        // TODO: Übergebene Datei parsen und Liste "waypoints" damit füllen
        BufferedReader fromFile = new BufferedReader(
                new InputStreamReader(
                        new FileInputStream(file)
                )
        );
        String line;

        while ((line = fromFile.readLine()) != null) {
            String[] fields = line.split("\\|");

            try {
                WGS84 wgs84 = new WGS84();
                wgs84.longitude = Integer.parseInt(fields[0]) / 100_000.0;
                wgs84.latitude = Integer.parseInt(fields[1]) / 100_000.0;
                waypoints.add(wgs84);
            } catch (NumberFormatException nfe) {
                Utils.logException(nfe);
            }
        }

        return waypoints;
    }

}
