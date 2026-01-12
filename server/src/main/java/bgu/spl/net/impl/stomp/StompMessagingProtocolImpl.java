package bgu.spl.net.impl.stomp;

import bgu.spl.net.api.StompMessagingProtocol;
import bgu.spl.net.srv.Connections;
import java.util.HashMap;
import java.util.Map;

public class StompMessagingProtocolImpl implements StompMessagingProtocol<String> {

    private int connectionId;
    private Connections<String> connections;
    private boolean shouldTerminate = false;

    @Override
    public void start(int connectionId, Connections<String> connections) {
        this.connectionId = connectionId;
        this.connections = connections;
    }

    @Override
    public void process(String message) {
        // --- שלב 1: פירוק ההודעה (Parsing) בתוך הפונקציה ---
        
        // נחלק את ההודעה לשורות
        String[] lines = message.split("\n");
        
        if (lines.length == 0) return; // הגנה ממקרי קצה

        // השורה הראשונה היא הפקודה
        String command = lines[0].trim();

        // חילוץ ה-Headers (כותרות) לתוך מפה
        Map<String, String> headers = new HashMap<>();
        int i = 1;
        // רצים על השורות עד שמגיעים לשורה ריקה (שמפרידה בין כותרות לתוכן)
        while (i < lines.length && !lines[i].trim().isEmpty()) {
            String[] parts = lines[i].split(":", 2);
            if (parts.length == 2) {
                headers.put(parts[0].trim(), parts[1].trim());
            }
            i++;
        }

        // כל מה שנשאר אחרי השורה הריקה הוא ה-Body (תוכן ההודעה)
        String body = "";
        // מדלגים על השורה הריקה
        i++; 
        if (i < lines.length) {
            // מחברים חזרה את שאר השורות לסטרינג אחד
            StringBuilder bodyBuilder = new StringBuilder();
            for (; i < lines.length; i++) {
                bodyBuilder.append(lines[i]).append("\n");
            }
            body = bodyBuilder.toString();
        }

        // --- שלב 2: לוגיקה (מה עושים עם ההודעה) ---

        switch (command) {
            case "CONNECT":
                String user = headers.get("login");
                String pass = headers.get("passcode");
                // TODO: בדיקת משתמש והחזרת תשובה
                System.out.println("Login request from: " + user);
                break;

            case "SUBSCRIBE":
                String dest = headers.get("destination");
                String id = headers.get("id");
                // TODO: הרשמה דרך connections
                System.out.println("Client " + connectionId + " wants to subscribe to " + dest);
                break;

            case "SEND":
                String topic = headers.get("destination");
                // כאן נשתמש ב-body שחילצנו למעלה
                // TODO: שליחה לכל המנויים
                System.out.println("Sending message to " + topic + ": " + body);
                break;
                
            case "DISCONNECT":
                // TODO: ניתוק
                shouldTerminate = true;
                break;
        }
    }

    @Override
    public boolean shouldTerminate() {
        return shouldTerminate;
    }
}