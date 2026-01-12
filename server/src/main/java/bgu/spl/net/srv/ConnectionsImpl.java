package bgu.spl.net.srv;

import java.util.concurrent.ConcurrentHashMap;

public class ConnectionsImpl<T> implements Connections<T> {

    // מיפוי בין מזהה ייחודי (ID) לבין ה-ConnectionHandler של אותו לקוח
    private final ConcurrentHashMap<Integer, ConnectionHandler<T>> activeConnections;

    // מיפוי בין שם ערוץ (Topic) לבין רשימה של מזהי לקוחות שרשומים אליו
    // הערה: נצטרך מבנה נתונים שמחזיק רשימת מנויים לכל ערוץ
    // <ChannelName, ListOfClientIDs>
    private final ConcurrentHashMap<String, ConcurrentHashMap<Integer, Boolean>> channels;

    public ConnectionsImpl() {
        this.activeConnections = new ConcurrentHashMap<>();
        this.channels = new ConcurrentHashMap<>();
    }

    @Override
    public boolean send(int connectionId, T msg) {
        // TODO: לממש שליחה ללקוח ספציפי
        ConnectionHandler<T> handler = activeConnections.get(connectionId);
        if (handler != null) {
            handler.send(msg);
            return true;
        }
        return false;
    }

    @Override
    public void send(String channel, T msg) {
        // TODO: לממש שליחה לכל מי שנרשם לערוץ הזה
        ConcurrentHashMap<Integer, Boolean> subscribers = channels.get(channel);
        if (subscribers != null) {
            for (Integer clientId : subscribers.keySet()) {
                send(clientId, msg);
            }
        }   
    }

    @Override
    public void disconnect(int connectionId) {
        // TODO: לממש ניתוק לקוח והסרה שלו מהמערכת
        activeConnections.remove(connectionId);
        // הסרה מכל הערוצים שהלקוח היה רשום אליהם       
        for (ConcurrentHashMap<Integer, Boolean> subscribers : channels.values()) {
            subscribers.remove(connectionId);
        }

    }

    // פונקציה שנוסיף כדי להוסיף לקוח חדש לרשימה (השרת יקרא לה כשלוקח מתחבר)
    public void addConnection(int connectionId, ConnectionHandler<T> handler) {
        activeConnections.put(connectionId, handler);
    }

    // פונקציה שנוסיף כדי לרשום לקוח לערוץ
    public void subscribeToChannel(String channel, int connectionId) {
        channels.computeIfAbsent(channel, k -> new ConcurrentHashMap<>()).put(connectionId, true);
    }
    
    // פונקציה שנוסיף כדי להסיר לקוח מערוץ
    public void unsubscribeFromChannel(String channel, int connectionId) {
        ConcurrentHashMap<Integer, Boolean> subscribers = channels.get(channel);
        if (subscribers != null) {
            subscribers.remove(connectionId);
        }
    }
}