package bgu.spl.net.impl.stomp;

import bgu.spl.net.api.StompMessagingProtocol;
import bgu.spl.net.srv.Connections;
import bgu.spl.net.srv.ConnectionsImpl;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class StompMessagingProtocolImpl implements StompMessagingProtocol<String> {

    private int connectionId;
    private Connections<String> connections;
    private boolean shouldTerminate = false;
    private boolean isLoggedIn = false;

    
    // מפתח: subscription-id, ערך: channel-name
    private Map<String, String> subscriberIdToChannel = new ConcurrentHashMap<>();

    @Override
    public void start(int connectionId, Connections<String> connections) {
        this.connectionId = connectionId;
        this.connections = connections;
    }

    @Override
    public void process(String message) {
        // שימוש במחלקת העזר Frame לפירוק ההודעה
        Frame frame = new Frame(message);
        String command = frame.getCommand();

        // --- בדיקת אבטחה 1: חסימת פולשים ---
        // אם המשתמש לא מחובר, הוא רשאי לשלוח אך ורק פקודת CONNECT
        if (!isLoggedIn && !command.equals("CONNECT")) {
            sendError("Not connected", "You must log in first using the CONNECT command.");
            return;
        }

        switch (command) {
            case "CONNECT":
                handleConnect(frame);
                break;
            case "SUBSCRIBE":
                handleSubscribe(frame);
                break;
            case "UNSUBSCRIBE":
                handleUnsubscribe(frame);
                break;
            case "SEND":
                handleSend(frame);
                break;
            case "DISCONNECT":
                handleDisconnect(frame);
                break;
            default:
                sendError("Unknown Command", "The command " + command + " is not recognized");
        }
    }

    @Override
    public boolean shouldTerminate() {
        return shouldTerminate;
    }

    // --- פונקציות הטיפול (Handlers) ---

    private void handleConnect(Frame frame) {
        // --- בדיקת אבטחה 2: מניעת התחברות כפולה ---
        if (isLoggedIn) {
            sendError("Already Connected", "You are already logged in. Disconnect first.");
            return;
        }

        String login = frame.getHeader("login");
        String passcode = frame.getHeader("passcode");
        String acceptVersion = frame.getHeader("accept-version");

        // בדיקת גרסה (אופציונלי אך מומלץ)
        if (acceptVersion != null && !acceptVersion.contains("1.2")) {
            sendError("Version not supported", "Supported version is 1.2");
            return;
        }

        // בדיקת אימות משתמש (כרגע תמיד מצליח אם השדות קיימים)
        if (login != null && passcode != null) {
            isLoggedIn = true; // המשתמש מחובר כעת

            String response = "CONNECTED\n" +
                              "version:1.2\n" +
                              "\n" +
                              "\u0000";
            connections.send(connectionId, response);
        } else {
            sendError("Authentication Failed", "Missing login or passcode header.");
        }
    }

    private void handleSubscribe(Frame frame) {
        String destination = frame.getHeader("destination");
        String id = frame.getHeader("id");

        if (destination == null || id == null) {
            sendError("Malformed Frame", "SUBSCRIBE must contain 'destination' and 'id'.");
            return;
        }

        // --- בדיקת אבטחה 3: מניעת כפילות מנויים (Zombie Subscription) ---
        if (subscriberIdToChannel.containsKey(id)) {
            sendError("Subscriber Error", "Subscription ID " + id + " already exists. Unsubscribe first.");
            return;
        }

        // 1. שמירה במפה המקומית
        subscriberIdToChannel.put(id, destination);

        // 2. שמירה ב-Connections (דורש Casting לפי ההנחיות)
        ((ConnectionsImpl<String>) connections).subscribeToChannel(destination, connectionId);

        // 3. שליחת אישור אם נדרש
        sendReceiptIfNeeded(frame);
    }

    private void handleUnsubscribe(Frame frame) {
        String id = frame.getHeader("id");

        if (id == null) {
            sendError("Malformed Frame", "UNSUBSCRIBE must contain 'id'.");
            return;
        }

        // --- בדיקת אבטחה 4: האם המנוי בכלל קיים? ---
        String channel = subscriberIdToChannel.remove(id);

        if (channel != null) {
            // רק אם המנוי היה קיים - מסירים מה-Connections
            ((ConnectionsImpl<String>) connections).unsubscribeFromChannel(channel, connectionId);
            sendReceiptIfNeeded(frame);
        } else {
            sendError("Subscription Error", "No subscription found with id: " + id);
        }
    }

    private void handleSend(Frame frame) {
        String destination = frame.getHeader("destination");
        String body = frame.getBody();

        if (destination == null) {
            sendError("Malformed Frame", "SEND must contain 'destination'.");
            return;
        }

        // יצירת הודעת MESSAGE שתשלח לכל המנויים בערוץ
        String messageFrame = "MESSAGE\n" +
                              "destination:" + destination + "\n" +
                              "message-id:" + System.currentTimeMillis() + "\n" +
                              "subscription:0\n" + // שדה חובה לפי הפרוטוקול, גם אם לא יודעים את ה-ID של המקבל
                              "\n" +
                              body + "\n" +
                              "\u0000";

        connections.send(destination, messageFrame);
        sendReceiptIfNeeded(frame);
    }

    private void handleDisconnect(Frame frame) {
        sendReceiptIfNeeded(frame);
        shouldTerminate = true; // סימון לשרת לסגור את הסוקט
        connections.disconnect(connectionId); // מחיקה מרשימת הלקוחות הפעילים
    }

    // --- פונקציות עזר (Utils) ---

    private void sendReceiptIfNeeded(Frame frame) {
        String receiptId = frame.getHeader("receipt");
        if (receiptId != null) {
            String receiptFrame = "RECEIPT\n" +
                                  "receipt-id:" + receiptId + "\n" +
                                  "\n" +
                                  "\u0000";
            connections.send(connectionId, receiptFrame);
        }
    }

    private void sendError(String message, String description) {
        String errorFrame = "ERROR\n" +
                            "message:" + message + "\n" +
                            "\n" +
                            description + "\n" +
                            "\u0000";
        connections.send(connectionId, errorFrame);
        
        // שגיאה גוררת ניתוק מיידי
        shouldTerminate = true;
        connections.disconnect(connectionId);
    }
}